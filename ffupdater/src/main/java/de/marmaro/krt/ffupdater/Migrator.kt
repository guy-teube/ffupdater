package de.marmaro.krt.ffupdater

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.Keep
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy.*
import de.marmaro.krt.ffupdater.installer.entity.Installer

@Keep
object Migrator {

    @SuppressLint("ApplySharedPref")
    fun migrate(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val lastVersionCode = preferences.getInt(FFUPDATER_VERSION_CODE, 0)

        if (lastVersionCode < 148) { // 78.0.6
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .edit()
                .remove("lastBackgroundCheckTimestamp")
                .commit()
        }

        if (lastVersionCode < 137) { // 77.7.11
            val editor = PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit()

            preferences.all
                .map { it.key }
                .filter { it.startsWith("cached_update_check_result__") }
                .forEach { editor.remove(it) }

            editor.commit()
        }

        if (lastVersionCode < 121) { // 77.5.0
            val editor = PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit()

            if (preferences.getBoolean("installer__root", false)) {
                editor.putString("installer__method", Installer.ROOT_INSTALLER.name)
            } else if (preferences.getBoolean("installer__native", false)) {
                editor.putString("installer__method", Installer.NATIVE_INSTALLER.name)
            } else if (preferences.getBoolean("installer__session", false)) {
                editor.putString("installer__method", Installer.SESSION_INSTALLER.name)
            }

            when (preferences.getString("network__dns_provider", null)?.toIntOrNull()) {
                0 -> editor.putString("network__dns_provider", "SYSTEM")
                1 -> editor.putString("network__dns_provider", "DIGITAL_SOCIETY_SWITZERLAND_DOH")
                2 -> editor.putString("network__dns_provider", "QUAD9_DOH")
                3 -> editor.putString("network__dns_provider", "CLOUDFLARE_DOH")
                4 -> editor.putString("network__dns_provider", "GOOGLE_DOH")
                5 -> editor.putString("network__dns_provider", "CUSTOM_SERVER")
                6 -> editor.putString("network__dns_provider", "NO")
                else -> {}
            }

            editor.commit()
        }

        preferences.edit()
            .putInt(FFUPDATER_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    private const val FFUPDATER_VERSION_CODE = "migrator_ffupdater_version_code"
}