package be.dimisaio.modded.mods

import android.content.Context
import android.util.Log
import com.geode.launcher.utils.DownloadUtils
import com.geode.launcher.utils.LaunchUtils
import com.geode.launcher.utils.PreferenceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    // bump this whenever MOD_URLS changes to force every device to reinstall
    private const val MOD_PACK_VERSION = 1

    private const val CONFIGS_URL = "https://cdn-dinde.141412.xyz/configs.zip"

    private val MOD_URLS = listOf(
        "https://cdn-dinde.141412.xyz/cgytrus.menu-shaders-mod.geode",
        "https://github.com/masckmaster2007/feurdll/releases/latest/download/jeantasoeur.feurdll.geode",
        "https://github.com/masckmaster2007/MoreSocials-Dinde/releases/latest/download/jarvisdevil.moredindesocials.geode",
        "https://github.com/masckmaster2007/TheMap-Dinde/releases/latest/download/jarvisdevil.the_dindemap.geode",
        "https://api.geode-sdk.org/v1/mods/weebify.coins_in_pause_menu/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/weebify.level_info_in_pause_menu/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/mishpro.comments_in_pause_menu/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/alphalaneous.improved_song_browser/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/timestepyt.secretlayer6/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/prevter.comment_emojis/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/raydeeux.pausemenuloop/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/dankmeme.globed2/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/cdc.level_thumbnails/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/raydeeux.loadingscreentweaks/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/geode.node-ids/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/jecket.gauntlets_position_fix/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/dasshu.better-gauntlets/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/adyagd.godlikefaces/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/alphalaneous.alphas-ui-pack/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/alphalaneous.editortab_api/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/beat.afk_pause/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/bluetoadmaker.messagenotification/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/capeling.startpos_switcher/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/cdc.level_thumbnailsd/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/cvolton.betterinfo/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/cvolton.level-id-api/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/cvolton.misc_bugfixes/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/dasshu.badgified/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/geode.custom-keybinds/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/geode.texture-loader/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/hiimjustin000.better_safe/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/hjfod.backups/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/hjfod.betteredit/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/hjfod.gdshare/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/hjfod.gmd-api/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/hjfod.trashcan/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/km7dev.quests_in_pause_menu/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/ninxout.prntscrn/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/prevter.imageplus/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/raydeeux.viewsfxlist/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/raydeeux_limegradient.warbledcompletions/versions/latest/download",
        "https://api.geode-sdk.org/v1/mods/techstudent10.settings_plus/versions/latest/download"
    )

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

    fun isInstalled(context: Context): Boolean {
        return PreferenceUtils.get(context).getInt(PreferenceUtils.Key.MOD_PACK_VERSION) >= MOD_PACK_VERSION
    }

    /** Force the next [install] call to run again, e.g. from a "reinstall mods" settings action. */
    fun markForReinstall(context: Context) {
        PreferenceUtils.get(context).setInt(PreferenceUtils.Key.MOD_PACK_VERSION, 0)
    }

    /**
     * Downloads every mod in [MOD_URLS] plus the default config pack, mirroring
     * install-mods.sh. Best-effort: a single mod failing to download is logged
     * and skipped rather than aborting the whole pass, matching the old script's
     * behavior (curl failures there weren't checked either).
     */
    suspend fun install(
        context: Context,
        onProgress: ModInstallProgressCallback? = null
    ) = withContext(Dispatchers.IO) {
        val modsDir = File(LaunchUtils.getBaseDirectory(context), "game/geode/mods")
        val savedModsDir = File(LaunchUtils.getSaveDirectory(context), "geode/mods")
        val configDir = File(LaunchUtils.getBaseDirectory(context), "game/geode/config/raydeeux.loadingscreentweaks")

        modsDir.mkdirs()
        savedModsDir.mkdirs()
        configDir.mkdirs()

        val totalSteps = (MOD_URLS.size + 1).toLong()
        var completedSteps = 0L
        onProgress?.invoke(completedSteps, totalSteps)

        for (url in MOD_URLS) {
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
            DownloadUtils.downloadFile(httpClient, CONFIGS_URL, configZip)
            DownloadUtils.copyZipStreamToDirectory(configZip.inputStream(), savedModsDir)
            configZip.delete()

            // same special-case as the bash script: loadingscreentweaks' custom.txt
            // lives in the config dir, not alongside the mod's saved data
            val loadingScreenConfig = File(savedModsDir, "raydeeux.loadingscreentweaks/custom.txt")
            if (loadingScreenConfig.exists()) {
                loadingScreenConfig.copyTo(File(configDir, "custom.txt"), overwrite = true)
                loadingScreenConfig.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "failed to install default configs", e)
        } finally {
            completedSteps++
            onProgress?.invoke(completedSteps, totalSteps)
        }

        PreferenceUtils.get(context).setInt(PreferenceUtils.Key.MOD_PACK_VERSION, MOD_PACK_VERSION)
    }
}
