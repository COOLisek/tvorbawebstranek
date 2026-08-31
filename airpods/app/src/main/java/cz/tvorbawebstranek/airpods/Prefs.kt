package cz.tvorbawebstranek.airpods

import android.content.Context

/**
 * Jedno jediné nastavení: má aplikace držet baterii v oznámení, dokud jsou
 * sluchátka připojená? Ukládá se do SharedPreferences, ať to přežije restart.
 */
object Prefs {

    private const val FILE = "airpods_prefs"
    private const val KEY_NOTIFICATION = "notification_enabled"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Zapnuto je to ve výchozím stavu – právě kvůli tomu si aplikaci člověk instaluje. */
    fun isNotificationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION, true)

    fun setNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION, enabled).apply()
    }
}
