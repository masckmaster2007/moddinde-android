package be.dimisaio.modded.mods

import android.content.Context
import android.util.Log
import com.geode.launcher.utils.DownloadUtils
import com.geode.launcher.utils.LaunchUtils
import com.geode.launcher.utils.PreferenceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

typealias ModInstallProgressCallback = (completed: Long, outOf: Long) -> Unit

/**
 * Ports install-mods.sh into the app: downloads the DindeGDPS mod pack and
 * default configs into the active profile's Geode directories.
 */
object ModInstaller {
    private const val TAG = "ModInstaller"

    // bump this whenever the configs.zip layout changes (or you just want to
    // force a one-time reinstall) — the mod list itself is server-side now
    // (see MOD_LIST_URL) and doesn't need a version bump to pick up changes
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
        return if (match != null) "${match.groupValues[1]}.geode" else url.substringAfterLast('/')
    }

    /**
     * Fetches the current mod list from [MOD_LIST_URL] — a plain JSON array of
     * download URLs, e.g. `["https://cdn-dinde.141412.xyz/some.geode", ...]`.
     * Kept server-side so the pack can be updated without an app release.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun fetchModUrls(): List<String> {
        val request = Request.Builder()
            .url(MOD_LIST_URL)
            .addHeader("Accept", "application/json")
            .build()

        val call = httpClient.newCall(request)
        return call.executeAsync().use { response ->
            if (response.code != 200) {
                throw IOException("unexpected response ${response.code} fetching $MOD_LIST_URL")
            }

            val format = Json { ignoreUnknownKeys = true }
            format.decodeFromBufferedSource<List<String>>(response.body.source())
        }
    }

    fun isInstalled(context: Context): Boolean {
        return PreferenceUtils.get(context).getInt(PreferenceUtils.Key.MOD_PACK_VERSION) >= MOD_PACK_VERSION
    }

    /** Force the next [install] call to run again, e.g. from a "reinstall mods" settings action. */
    fun markForReinstall(context: Context) {
        PreferenceUtils.get(context).setInt(PreferenceUtils.Key.MOD_PACK_VERSION, 0)
    }

    /**
     * Downloads every mod listed in the manifest at [MOD_LIST_URL] plus the
     * default config pack, mirroring install-mods.sh. Best-effort: a single
     * mod failing to download is logged and skipped rather than aborting the
     * whole pass, matching the old script's behavior (curl failures there
     * weren't checked either). If the manifest itself can't be fetched, the
     * whole pass is skipped and [MOD_PACK_VERSION] is left unset, so it's
     * retried on the next launch rather than being permanently skipped.
     */
    suspend fun install(
        context: Context,
        onProgress: ModInstallProgressCallback? = null
    ) = withContext(Dispatchers.IO) {
        val modsDir = File(LaunchUtils.getBaseDirectory(context), "game/geode/mods")
        val savedModsDir = File(LaunchUtils.getSaveDirectory(context), "geode/mods")
        val configDir = File(LaunchUtils.getBaseDirectory(context), "game/geode/config")

        modsDir.mkdirs()
        savedModsDir.mkdirs()
        configDir.mkdirs()

        val modUrls = try {
            fetchModUrls()
        } catch (e: IOException) {
            Log.w(TAG, "failed to fetch mod list from $MOD_LIST_URL, skipping mod install this launch", e)
            return@withContext
        }

        val totalSteps = (modUrls.size + 1).toLong()
        var completedSteps = 0L
        onProgress?.invoke(completedSteps, totalSteps)

        for (url in modUrls) {
            val outputFile = File(modsDir, filenameFor(url))
            val tempFile = File(modsDir, "${outputFile.name}.tmp")

            try {
                DownloadUtils.downloadFile(httpClient, url, tempFile)
                if (outputFile.exists()) outputFile.delete()
                tempFile.renameTo(outputFile)
            } catch (e: IOException) {
                Log.w(TAG, "failed to download mod from $url", e)
                tempFile.delete()
            } finally {
                completedSteps++
                onProgress?.invoke(completedSteps, totalSteps)
            }
        }

        try {
            val configZip = File(context.cacheDir, "dinde-configs.zip")
            val stagingDir = File(context.cacheDir, "dinde-configs-staging")

            if (stagingDir.exists()) stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            DownloadUtils.downloadFile(httpClient, CONFIGS_URL, configZip)
            
            configZip.inputStream().use { inputStream ->
                DownloadUtils.copyZipStreamToDirectory(inputStream, stagingDir)
            }
            configZip.delete()

            // 1. Force override config directory
            val stagedConfig = File(stagingDir, "config")
            if (stagedConfig.exists()) {
                if (configDir.exists()) configDir.deleteRecursively()
                configDir.mkdirs()
                stagedConfig.copyRecursively(configDir, overwrite = true)
            }

            // 2. Force override save directory
            val stagedSave = File(stagingDir, "save")
            if (stagedSave.exists()) {
                if (savedModsDir.exists()) savedModsDir.deleteRecursively()
                savedModsDir.mkdirs()
                stagedSave.copyRecursively(savedModsDir, overwrite = true)
            }

            stagingDir.deleteRecursively()
            Log.i(TAG, "Default configs and saves successfully overridden")
        } catch (e: Exception) {
            Log.w(TAG, "failed to install default configs", e)
        } finally {
            completedSteps++
            onProgress?.invoke(completedSteps, totalSteps)
        }

        PreferenceUtils.get(context).setInt(PreferenceUtils.Key.MOD_PACK_VERSION, MOD_PACK_VERSION)
    }
}
