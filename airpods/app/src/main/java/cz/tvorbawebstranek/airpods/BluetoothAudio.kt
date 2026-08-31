package cz.tvorbawebstranek.airpods

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Pomocné funkce ke klasickému Bluetooth spojení (ne BLE).
 *
 * BLE inzerát, ze kterého čteme baterii, nemá s párováním nic společného –
 * jenže uživatel chce vidět baterii "když jsou sluchátka připojená".
 * Proto potřebujeme vědět, jestli je zrovna připojené nějaké audio zařízení.
 */
object BluetoothAudio {

    /**
     * Oprávnění potřebné k dotazu na připojená zařízení.
     * Na Androidu 12+ je to BLUETOOTH_CONNECT, dřív stačilo BLUETOOTH z manifestu.
     */
    fun canQueryDevices(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Je zařízení sluchátka nebo headset? Bereme širokou třídu zařízení,
     * ne jméno – uživatel si AirPods může přejmenovat na cokoliv.
     */
    @SuppressLint("MissingPermission")
    fun isAudioDevice(device: BluetoothDevice?): Boolean {
        val deviceClass = device?.bluetoothClass ?: return false
        return when (deviceClass.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> true
            else -> deviceClass.hasService(BluetoothClass.Service.AUDIO)
        }
    }

    /**
     * Zjistí přes profil A2DP, jestli je právě připojené nějaké audio zařízení.
     * Dotaz na profil je asynchronní, výsledek proto přijde do [callback].
     *
     * Používá se při spuštění aplikace – pokud jsou AirPods připojené už teď,
     * broadcast o připojení už dávno proběhl a nikdo ho nezachytil.
     */
    @SuppressLint("MissingPermission")
    fun isAudioDeviceConnected(context: Context, callback: (Boolean) -> Unit) {
        val adapter = adapter(context)
        if (adapter == null || !adapter.isEnabled || !canQueryDevices(context)) {
            callback(false)
            return
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = try {
                    proxy.connectedDevices.any { isAudioDevice(it) }
                } catch (e: SecurityException) {
                    false
                }
                adapter.closeProfileProxy(profile, proxy)
                callback(connected)
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        if (!adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)) {
            callback(false)
        }
    }
}
