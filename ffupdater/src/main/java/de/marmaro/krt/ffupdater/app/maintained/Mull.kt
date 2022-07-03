package de.marmaro.krt.ffupdater.app.maintained

import android.os.Build
import android.util.Log
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.entity.LatestUpdate
import de.marmaro.krt.ffupdater.device.ABI
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.network.ApiConsumer
import de.marmaro.krt.ffupdater.network.fdroid.FdroidConsumer

/**
 * https://f-droid.org/en/packages/us.spotco.fennec_dos/
 */
class Mull(
    private val apiConsumer: ApiConsumer = ApiConsumer.INSTANCE,
    private val deviceAbiExtractor: DeviceAbiExtractor = DeviceAbiExtractor.INSTANCE,
) : AppBase() {
    override val packageName = "us.spotco.fennec_dos"
    override val displayTitle = R.string.mull__title
    override val displayDescription = R.string.mull__description
    override val displayWarning = R.string.mull__warning
    override val displayDownloadSource = R.string.download_source__fdroid
    override val displayIcon = R.mipmap.ic_logo_mull
    override val minApiLevel = Build.VERSION_CODES.LOLLIPOP
    override val supportedAbis = listOf(ABI.ARM64_V8A, ABI.ARMEABI_V7A)
    override val normalInstallation = true

    @Suppress("SpellCheckingInspection")
    override val signatureHash = "ff81f5be56396594eee70fef2832256e15214122e2ba9cedd26005ffd4bcaaa8"

    override suspend fun checkForUpdate(): LatestUpdate {
        Log.i(LOG_TAG, "check for latest version")
        val abi = deviceAbiExtractor.supportedAbis.first { abi -> abi in supportedAbis }

        val fdroidConsumer = FdroidConsumer(packageName, apiConsumer)
        val result = fdroidConsumer.updateCheck()

        check(result.versionCodesAndDownloadUrls.size == 2)
        val versionCodeAndDownloadUrl = if (abi == ABI.ARM64_V8A) {
            result.versionCodesAndDownloadUrls.maxByOrNull { it.versionCode }!!
        } else {
            result.versionCodesAndDownloadUrls.minByOrNull { it.versionCode }!!
        }

        Log.i(LOG_TAG, "found latest version ${result.versionName}")
        return LatestUpdate(
            downloadUrl = versionCodeAndDownloadUrl.downloadUrl,
            version = result.versionName,
            publishDate = null,
            fileSizeBytes = null,
            fileHash = null,
            firstReleaseHasAssets = true
        )
    }

    companion object {
        private const val LOG_TAG = "Mull"
    }
}