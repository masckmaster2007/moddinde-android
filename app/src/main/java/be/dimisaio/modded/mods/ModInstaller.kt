package be.dimisaio.modded.mods

import android.content.Context
import android.util.Log
import com.geode.launcher.utils.DownloadUtils
import com.geode.launcher.utils.LaunchUtils
import com.geode.launcher.utils.PreferenceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

typealias ModInstallProgressCallback = (completed: Long, outOf: Long) -> Unit

/**
 * Ports install-mods.sh into the app: downloads the DindeGDPS mod pack and
 * default configs into the active profile's Geode directories.
 */
object ModInstaller {
    private const val TAG = "ModInstaller"

    // Bump this whenever the configs.zip layout changes or you want to
    // force a one-time reinstall.
    private const val MOD_PACK_VERSION = 2

    private const val CONFIGS_URL = "https://cdn-dinde.141412.xyz/configs.zip"
    private const val MOD_LIST_URL = "https://cdn-dinde.141412.xyz/mods.json"

    private val modIdRegex = Regex("/mods/([^/]+)/")

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private fun filenameFor(url: String): String {
        val match = modIdRegex.find(url)
        return if (match != null) {
            "${match.groupValues[1]}.geode"
        } else {
            url.substringAfterLast('/')
        }
    }

    /**
     * Fetches the current mod list from [MOD_LIST_URL].
     *
     * The endpoint returns a plain JSON array of download URLs.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchModUrls(): List<String> {
        val request = Request.Builder()
            .url(MOD_LIST_URL)
            .addHeader("Accept", "application/json")
            .build()

        val call = httpClient.newCall(request)

        return call.executeAsync().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    "unexpected response ${response.code} fetching $MOD_LIST_URL"
                )
            }

            val body = response.body

            Json {
                ignoreUnknownKeys = true
            }.decodeFromBufferedSource<List<String>>(body.source())
        }
    }

    fun isInstalled(context: Context): Boolean {
        return PreferenceUtils.get(context)
            .getInt(PreferenceUtils.Key.MOD_PACK_VERSION) >= MOD_PACK_VERSION
    }

    /**
     * Force the next [install] call to run again.
     */
    fun markForReinstall(context: Context) {
        PreferenceUtils.get(context)
            .setInt(PreferenceUtils.Key.MOD_PACK_VERSION, 0)
    }

    /**
     * Extracts a ZIP stream into [output].
     *
     * This intentionally does not use DownloadUtils' ZIP extractor.
     *
     * Important:
     * - Uses ZipEntry.isDirectory rather than File.isDirectory.
     * - Protects against Zip Slip/path traversal.
     * - Creates parent directories for files.
     * - Detects file/directory collisions.
     */
    private suspend fun extractZip(
        inputStream: InputStream,
        output: File
    ) = runInterruptible {
        if (output.exists()) {
            output.deleteRecursively()
        }

        if (!output.mkdirs() && !output.isDirectory) {
            throw IOException("failed to create ZIP output directory: $output")
        }

        val canonicalOutput = output.canonicalFile
        val outputPrefix = canonicalOutput.path + File.separator

        ZipInputStream(BufferedInputStream(inputStream)).use { zipStream ->
            while (true) {
                val entry = zipStream.nextEntry ?: break

                try {
                    val entryName = entry.name

                    if (entryName.isEmpty()) {
                        continue
                    }

                    val destination = File(output, entryName)
                    val canonicalDestination = destination.canonicalFile

                    // Zip Slip protection.
                    if (canonicalDestination.path != canonicalOutput.path &&
                        !canonicalDestination.path.startsWith(outputPrefix)
                    ) {
                        throw IOException(
                            "attempted copy outside output directory: $entryName"
                        )
                    }

                    if (entry.isDirectory) {
                        // A ZIP directory entry must be handled according to
                        // ZipEntry.isDirectory. File.isDirectory() would return
                        // false because the path does not exist yet.
                        if (destination.exists() && !destination.isDirectory) {
                            throw IOException(
                                "ZIP file/directory collision: " +
                                    "$entryName already exists as a file"
                            )
                        }

                        if (!destination.exists() &&
                            !destination.mkdirs() &&
                            !destination.isDirectory
                        ) {
                            throw IOException(
                                "failed to create ZIP directory: $destination"
                            )
                        }
                    } else {
                        // If something with this name already exists as a
                        // directory, the ZIP is malformed.
                        if (destination.exists() && destination.isDirectory) {
                            throw IOException(
                                "ZIP file/directory collision: " +
                                    "$entryName already exists as a directory"
                            )
                        }

                        val parent = destination.parentFile
                            ?: throw IOException(
                                "ZIP entry has no parent directory: $entryName"
                            )

                        if (!parent.exists() &&
                            !parent.mkdirs() &&
                            !parent.isDirectory
                        ) {
                            throw IOException(
                                "failed to create parent directory: $parent"
                            )
                        }

                        destination.outputStream().use { destinationStream ->
                            zipStream.copyTo(destinationStream)
                        }
                    }
                } finally {
                    zipStream.closeEntry()
                }
            }
        }
    }

    /**
     * Replaces [destination] with the contents of [source].
     *
     * The destination is only removed after the source has been completely
     * downloaded and extracted.
     */
    private fun replaceDirectory(
        source: File,
        destination: File
    ) {
        if (!source.isDirectory) {
            throw IOException("source is not a directory: $source")
        }

        val parent = destination.parentFile
            ?: throw IOException("destination has no parent: $destination")

        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("failed to create destination parent: $parent")
        }

        val backup = File(
            parent,
            "${destination.name}.backup-${System.nanoTime()}"
        )

        try {
            // Move the current directory out of the way first.
            if (destination.exists()) {
                if (!destination.renameTo(backup)) {
                    // renameTo can fail across filesystems. In that case,
                    // remove the old directory only after extraction succeeded.
                    destination.deleteRecursively()
                }
            }

            if (!source.renameTo(destination)) {
                // Fall back to copying if renameTo isn't possible.
                source.copyRecursively(destination, overwrite = true)
                source.deleteRecursively()
            }

            backup.deleteRecursively()
        } catch (e: Exception) {
            // Attempt to restore the old directory if replacement failed.
            if (!destination.exists() && backup.exists()) {
                backup.renameTo(destination)
            }

            throw e
        }
    }

    /**
     * Downloads every mod listed in the manifest plus the default config pack.
     *
     * A single mod download failure is logged and skipped.
     * A failure fetching the manifest aborts this installation pass.
     */
    suspend fun install(
        context: Context,
        onProgress: ModInstallProgressCallback? = null
    ) = withContext(Dispatchers.IO) {
        val modsDir = File(
            LaunchUtils.getBaseDirectory(context),
            "game/geode/mods"
        )

        val savedModsDir = File(
            LaunchUtils.getSaveDirectory(context),
            "geode/mods"
        )

        val configDir = File(
            LaunchUtils.getBaseDirectory(context),
            "game/geode/config"
        )

        modsDir.mkdirs()
        savedModsDir.mkdirs()
        configDir.mkdirs()

        val modUrls = try {
            fetchModUrls()
        } catch (e: IOException) {
            Log.w(
                TAG,
                "failed to fetch mod list from $MOD_LIST_URL, " +
                    "skipping mod install this launch",
                e
            )

            return@withContext
        }

        val totalSteps = (modUrls.size + 1).toLong()
        var completedSteps = 0L

        onProgress?.invoke(completedSteps, totalSteps)

        /*
         * Install mods.
         */
        for (url in modUrls) {
            val outputFile = File(
                modsDir,
                filenameFor(url)
            )

            val tempFile = File(
                modsDir,
                "${outputFile.name}.tmp"
            )

            try {
                // Remove stale temporary file first.
                if (tempFile.exists()) {
                    tempFile.deleteRecursively()
                }

                DownloadUtils.downloadFile(
                    httpClient,
                    url,
                    tempFile
                )

                if (!tempFile.isFile) {
                    throw IOException(
                        "download did not produce a regular file: $tempFile"
                    )
                }

                if (outputFile.exists()) {
                    if (!outputFile.deleteRecursively()) {
                        throw IOException(
                            "failed to remove existing mod: $outputFile"
                        )
                    }
                }

                if (!tempFile.renameTo(outputFile)) {
                    // renameTo may fail depending on the filesystem.
                    tempFile.copyTo(
                        outputFile,
                        overwrite = true
                    )

                    if (!tempFile.delete()) {
                        Log.w(
                            TAG,
                            "failed to delete temporary mod file: $tempFile"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "failed to download mod from $url",
                    e
                )

                tempFile.deleteRecursively()
            } finally {
                completedSteps++
                onProgress?.invoke(
                    completedSteps,
                    totalSteps
                )
            }
        }

        /*
         * Install configs and saves.
         */
        try {
            val configZip = File(
                context.cacheDir,
                "dinde-configs.zip"
            )

            val stagingDir = File(
                context.cacheDir,
                "dinde-configs-staging"
            )

            // Always start with a clean staging area.
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }

            if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                throw IOException(
                    "failed to create staging directory: $stagingDir"
                )
            }

            try {
                // Remove a stale ZIP from an interrupted previous install.
                if (configZip.exists()) {
                    configZip.deleteRecursively()
                }

                /*
                 * Download the ZIP.
                 */
                DownloadUtils.downloadFile(
                    httpClient,
                    CONFIGS_URL,
                    configZip
                )

                if (!configZip.isFile) {
                    throw IOException(
                        "config download did not produce a regular file: $configZip"
                    )
                }

                /*
                 * Extract using our own extractor.
                 */
                configZip.inputStream().use { inputStream ->
                    extractZip(
                        inputStream,
                        stagingDir
                    )
                }

                /*
                 * The ZIP is no longer needed.
                 */
                configZip.deleteRecursively()

                /*
                 * Validate the extracted layout before touching the live
                 * directories.
                 */
                val stagedConfig = File(
                    stagingDir,
                    "config"
                )

                val stagedSave = File(
                    stagingDir,
                    "save"
                )

                if (!stagedConfig.exists() && !stagedSave.exists()) {
                    throw IOException(
                        "configs ZIP contains neither config/ nor save/"
                    )
                }

                if (stagedConfig.exists() && !stagedConfig.isDirectory) {
                    throw IOException(
                        "configs ZIP contains config as a file, expected directory"
                    )
                }

                if (stagedSave.exists() && !stagedSave.isDirectory) {
                    throw IOException(
                        "configs ZIP contains save as a file, expected directory"
                    )
                }

                /*
                 * Replace config directory.
                 */
                if (stagedConfig.isDirectory) {
                    replaceDirectory(
                        stagedConfig,
                        configDir
                    )
                }

                /*
                 * Replace save directory.
                 *
                 * Note that the ZIP's save/ directory maps to:
                 *
                 *   <save directory>/geode/mods
                 *
                 * matching the original installer.
                 */
                if (stagedSave.isDirectory) {
                    replaceDirectory(
                        stagedSave,
                        savedModsDir
                    )
                }

                Log.i(
                    TAG,
                    "Default configs and saves successfully overridden"
                )
            } finally {
                configZip.deleteRecursively()
                stagingDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "failed to install default configs",
                e
            )
        } finally {
            completedSteps++
            onProgress?.invoke(
                completedSteps,
                totalSteps
            )
        }

        /*
         * Only mark the pack as installed after the entire pass.
         *
         * Individual mod download failures remain best-effort, matching the
         * behavior of the previous implementation.
         */
        PreferenceUtils.get(context).setInt(
            PreferenceUtils.Key.MOD_PACK_VERSION,
            MOD_PACK_VERSION
        )
    }
}
