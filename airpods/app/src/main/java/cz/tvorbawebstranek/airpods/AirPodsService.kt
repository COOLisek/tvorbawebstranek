package cz.tvorbawebstranek.airpods

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Služba, která drží stav baterie v oznámení, dokud jsou sluchátka připojená.
 *
 * Startuje ji [BluetoothReceiver] při připojení audio zařízení (a [MainActivity],
 * pokud už připojené je) a zastavuje se při odpojení. Běží jako foreground
 * service, protože jinak by Android BLE skenování na pozadí utnul.
 *
 * Skenuje v režimu LOW_POWER – na oznámení nikdo nečeká v řádu milisekund
 * a nepřetržité skenování na plný výkon by znatelně ubíralo baterii telefonu.
 */
class AirPodsService : Service() {

    private var scanner: AirPodsScanner? = null

    /** Poslední známý stav si držíme i po tom, co sluchátka přestanou vysílat. */
    private var lastStatus: AirPodsStatus? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // startForeground musí přijít do pár sekund od startu, jinak systém službu zabije.
        // Když chybí oprávnění, foreground service typu connectedDevice na Androidu 14
        // vyhodí SecurityException – proto to hlídáme.
        try {
            startInForeground(buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Službu nejde spustit na popředí", e)
            stopSelf()
            return
        }

        val scanner = AirPodsScanner(
            context = this,
            scanMode = ScanSettings.SCAN_MODE_LOW_POWER
        ) { status ->
            // null znamená jen ticho v éteru – poslední známé hodnoty držíme dál
            // a v oznámení k nim dopíšeme, jak jsou staré.
            if (status != null) {
                lastStatus = status
                updateNotification()
            }
        }
        this.scanner = scanner

        if (!scanner.start()) {
            Log.w(TAG, "Skenování se nepodařilo spustit, službu ukončuji")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scanner?.stop()
        scanner = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val status = lastStatus

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)

        if (status == null) {
            return builder
                .setContentTitle(getString(R.string.notification_waiting_title))
                .setContentText(getString(R.string.notification_waiting_text))
                .build()
        }

        val text = batteryLine(status)
        return builder
            .setContentTitle(status.model)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + ageSuffix(status)))
            .build()
    }

    /** Například „L 40 % · P 40 % · Pouzdro 90 %“, nedostupné části vynecháme. */
    private fun batteryLine(status: AirPodsStatus): String {
        if (status.singleBattery) {
            val single = if (status.left.isKnown) status.left else status.right
            return part(getString(R.string.short_headphones), single)
                ?: getString(R.string.notification_no_data)
        }

        val parts = listOfNotNull(
            part(getString(R.string.short_left), status.left),
            part(getString(R.string.short_right), status.right),
            part(getString(R.string.short_case), status.case)
        )
        return if (parts.isEmpty()) getString(R.string.notification_no_data)
        else parts.joinToString(" · ")
    }

    private fun part(label: String, battery: PodBattery): String? {
        val percent = battery.percent ?: return null
        val bolt = if (battery.charging) " ⚡" else ""
        return "$label $percent %$bolt"
    }

    /** Když sluchátka chvíli mlčí, ať je z oznámení poznat, že čísla nejsou aktuální. */
    private fun ageSuffix(status: AirPodsStatus): String {
        val ageMinutes = (SystemClock.elapsedRealtime() - status.timestampMs) / 60_000L
        return if (ageMinutes < 1) "" else "\n" + getString(R.string.notification_age, ageMinutes)
    }

    companion object {
        private const val TAG = "AirPodsService"
        private const val CHANNEL_ID = "airpods_battery"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "cz.tvorbawebstranek.airpods.STOP"

        /**
         * Spustí službu, pokud jsou k tomu oprávnění. Bez nich by foreground
         * service na Androidu 14 spadl, takže radši nespustíme nic.
         */
        fun start(context: Context) {
            if (!Prefs.isNotificationEnabled(context)) return
            if (!hasRequiredPermissions(context)) return
            val intent = Intent(context, AirPodsService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Službu nejde nastartovat", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPodsService::class.java))
        }

        private fun hasRequiredPermissions(context: Context): Boolean =
            AirPodsScanner.hasPermissions(context) && BluetoothAudio.canQueryDevices(context)
    }
}
