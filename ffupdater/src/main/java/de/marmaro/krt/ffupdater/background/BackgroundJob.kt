package de.marmaro.krt.ffupdater.background

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.ExistingPeriodicWorkPolicy.REPLACE
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.App
import de.marmaro.krt.ffupdater.app.impl.exceptions.ApiNetworkException
import de.marmaro.krt.ffupdater.download.ApkCache
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter.DownloadStatus.Status.FAILED
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter.DownloadStatus.Status.SUCCESSFUL
import de.marmaro.krt.ffupdater.download.NetworkUtil
import de.marmaro.krt.ffupdater.download.StorageUtil
import de.marmaro.krt.ffupdater.settings.PreferencesHelper
import de.marmaro.krt.ffupdater.settings.SettingsHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit.MINUTES

/**
 * This class will call the [WorkManager] to check regularly for app updates in the background.
 * When an app update is available, a notification will be displayed.
 *
 * doWork can be interrupted at any time and cause a CancellationException.
 */
class BackgroundJob(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    /**
     * If:
     * - airplane mode is enabled
     * - internet is not available
     * - the system download manager is currently downloading a file
     * then delay the background job execution by 30s, 1m, 2m, 4m, ...
     * <a>https://developer.android.com/reference/androidx/work/BackoffPolicy?hl=en#EXPONENTIAL</a>
     * <a>https://developer.android.com/reference/androidx/work/WorkRequest#DEFAULT_BACKOFF_DELAY_MILLIS</a>
     *
     * Result.failure will remove the scheduled job (and that's unwanted).
     */
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (NetworkUtil.isAirplaneModeOn(context)) {
            Log.i(LOG_TAG, "delay BackgroundJob due to enabled airplane mode")
            return Result.retry()
        }
        if (!NetworkUtil.isInternetAvailable(context)) {
            Log.i(LOG_TAG, "delay BackgroundJob because internet is not available")
            return Result.retry()
        }
        if (DownloadManagerAdapter.create(context).isDownloadingAFileNow()) {
            Log.i(LOG_TAG, "delay BackgroundJob because other downloads are running")
            return Result.retry()
        }

        Log.i(LOG_TAG, "execute BackgroundJob")
        try {
            val appsWithUpdates = findAppsWithUpdates()
            if (NetworkUtil.isActiveNetworkUnmetered(context)) {
                downloadUpdatesInBackground(appsWithUpdates)
            }
            showUpdateNotification(appsWithUpdates)
            updateLastBackgroundCheckTimestamp()
        } catch (e: CancellationException) {
            // when the network is disabled, this exception will be thrown -> ignore it
        } catch (e: ApiNetworkException) {
            // GithubRateLimitExceededException will be caught too
            val message = context.getString(R.string.background_network_issue_notification__text)
            ErrorNotificationBuilder.showNotification(context, e, message)
        } catch (e: Exception) {
            val message = context.getString(R.string.background_unknown_bug_notification__text)
            ErrorNotificationBuilder.showNotification(context, e, message)
        }
        //don't Result.retry() on exceptions to avoid error spamming
        return Result.success()
    }

    /**
     * Returns apps which:
     *  - are installed
     *  - are not disabled (in the settings "excluded applications")
     *  - have an available update
     * @throws InvalidApiResponseException
     * @throws ApiNetworkException
     * @throws CancellationException
     */
    private suspend fun findAppsWithUpdates(): List<App> {
        val disabledApps = SettingsHelper(applicationContext).disabledApps
        return App.values()
            .filter { it !in disabledApps }
            .filter { it.detail.isInstalled(applicationContext) }
            // nice side effect: check for updates by calling updateCheck()
            .filter { it.detail.updateCheck(applicationContext).isUpdateAvailable }
    }

    /**
     * If the current network is unmetered, then download the update for the given apps
     * with the DownloadManager in the background.
     */
    private suspend fun downloadUpdatesInBackground(appsWithUpdates: List<App>) {
        val downloadManager = DownloadManagerAdapter.create(applicationContext)
        appsWithUpdates.forEach { downloadUpdateInBackground(it, downloadManager) }
    }

    /**
     * Start the download of an app update and wait until the download is finished.
     * Preconditions for the background download:
     *  - enough memory
     *  - update must not be already downloaded
     */
    private suspend fun downloadUpdateInBackground(
        app: App,
        downloadManager: DownloadManagerAdapter
    ) {
        val apkCache = ApkCache(app, applicationContext)
        val cachedUpdateChecker = app.detail.updateCheck(applicationContext)
        val availableResult = cachedUpdateChecker.availableResult

        if (apkCache.isCacheAvailable(availableResult)) {
            Log.i(LOG_TAG, "skip $app download because it's already cached")
            return
        }
        if (!StorageUtil.isEnoughStorageAvailable(applicationContext)) {
            Log.i(LOG_TAG, "skip $app download because we don't have enough free storage")
            return
        }

        Log.i(LOG_TAG, "download $app in the background")
        val downloadId = downloadManager.enqueue(applicationContext, app, availableResult)
        repeat(5 * 60) {
            when (downloadManager.getStatusAndProgress(downloadId).status) {
                SUCCESSFUL -> {
                    val downloadedFile = downloadManager.openDownloadedFile(downloadId)
                    apkCache.copyToCache(downloadedFile)
                    return
                }
                FAILED -> {
                    downloadManager.remove(downloadId)
                    return
                }
                else -> delay(1000)
            }
        }
        downloadManager.remove(downloadId)
    }

    private fun updateLastBackgroundCheckTimestamp() {
        PreferencesHelper(applicationContext).lastBackgroundCheck = LocalDateTime.now()
    }

    private fun showUpdateNotification(appsWithUpdates: List<App>) {
        UpdateNotificationBuilder.showNotifications(appsWithUpdates, applicationContext)
    }

    companion object {
        private const val WORK_MANAGER_KEY = "update_checker"
        private const val LOG_TAG = "BackgroundJob"

        fun startOrStopBackgroundUpdateCheck(context: Context) {
            if (SettingsHelper(context).automaticCheck) {
                startBackgroundUpdateCheck(context)
            } else {
                stopBackgroundUpdateCheck(context)
            }
        }

        private fun startBackgroundUpdateCheck(context: Context) {
            val settingsHelper = SettingsHelper(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
            if (settingsHelper.onlyUnmeteredNetwork) {
                constraints.setRequiredNetworkType(NetworkType.UNMETERED)
            }

            val workRequest = PeriodicWorkRequest.Builder(
                BackgroundJob::class.java,
                settingsHelper.checkInterval.toMinutes(),
                MINUTES
            )
                .setConstraints(constraints.build())
                .build()

            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(WORK_MANAGER_KEY, REPLACE, workRequest)
        }

        private fun stopBackgroundUpdateCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_MANAGER_KEY)
        }
    }
}