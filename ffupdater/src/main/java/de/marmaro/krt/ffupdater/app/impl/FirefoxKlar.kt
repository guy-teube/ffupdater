package de.marmaro.krt.ffupdater.app.impl

import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.MainThread
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.app.entity.DisplayCategory.FROM_MOZILLA
import de.marmaro.krt.ffupdater.app.entity.LatestVersion
import de.marmaro.krt.ffupdater.device.ABI
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.network.file.CacheBehaviour
import de.marmaro.krt.ffupdater.network.github.GithubConsumer
import de.marmaro.krt.ffupdater.settings.DeviceSettingsHelper

/**
 * https://github.com/mozilla-mobile/focus-android
 * https://api.github.com/repos/mozilla-mobile/focus-android/releases
 * https://www.apkmirror.com/apk/mozilla/firefox-klar-the-privacy-browser-2/
 */
@Keep
object FirefoxKlar : AppBase() {
    override val app = App.FIREFOX_KLAR
    override val packageName = "org.mozilla.klar"
    override val title = R.string.firefox_klar__title
    override val description = R.string.firefox_klar__description
    override val downloadSource = "GitHub"
    override val icon = R.drawable.ic_logo_firefox_focus_klar
    override val minApiLevel = Build.VERSION_CODES.LOLLIPOP
    override val supportedAbis = ARM32_ARM64_X86_X64

    @Suppress("SpellCheckingInspection")
    override val signatureHash = "6203a473be36d64ee37f87fa500edbc79eab930610ab9b9fa4ca7d5c1f1b4ffc"
    override val projectPage = "https://github.com/mozilla-mobile/firefox-android"
    override val displayCategory = listOf(FROM_MOZILLA)

    @MainThread
    @Throws(NetworkException::class)
    override suspend fun fetchLatestUpdate(context: Context, cacheBehaviour: CacheBehaviour): LatestVersion {
        val fileSuffix = findfileSuffix()
        val result = GithubConsumer.findLatestRelease(
            repository = GithubConsumer.REPOSITORY__MOZILLA_MOBILE__FIREFOX_ANDROID,
            resultsPerApiCall = GithubConsumer.RESULTS_PER_API_CALL__FIREFOX_ANDROID,
            isValidRelease = { !it.isPreRelease && """^Klar \d""".toRegex().containsMatchIn(it.name) },
            isSuitableAsset = { it.nameStartsAndEndsWith("klar-", fileSuffix) },
            dontUseApiForLatestRelease = true,
            cacheBehaviour = cacheBehaviour,
            requireReleaseDescription = false,
        )
        val version = result.tagName
            .removePrefix("klar-v") //convert v108.1.1 or focus-v108.1.1 to 108.1.1
            .removePrefix("v") //fallback if the tag naming schema changed
        return LatestVersion(
            downloadUrl = result.url,
            version = version,
            publishDate = result.releaseDate,
            exactFileSizeBytesOfDownload = result.fileSizeBytes,
            fileHash = null,
        )
    }

    private fun findfileSuffix(): String {
        val fileSuffix =
            when (DeviceAbiExtractor.findBestAbi(supportedAbis, DeviceSettingsHelper.prefer32BitApks)) {
                ABI.ARMEABI_V7A -> "-armeabi-v7a.apk"
                ABI.ARM64_V8A -> "-arm64-v8a.apk"
                ABI.X86 -> "-x86.apk"
                ABI.X86_64 -> "-x86_64.apk"
                else -> throw IllegalArgumentException("ABI is not supported")
            }
        return fileSuffix
    }
}