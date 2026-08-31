package cz.tvorbawebstranek.airpods

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Poslouchá připojení a odpojení Bluetooth zařízení a podle toho zapíná
 * a vypíná [AirPodsService].
 *
 * Broadcasty ACL_CONNECTED a ACL_DISCONNECTED jsou jedny z mála implicitních
 * broadcastů, které smí přijímat i receiver zapsaný v manifestu, takže
 * aplikace nemusí kvůli tomu nic držet běžící. Zároveň platí, že Bluetooth
 * broadcast vyžadující BLUETOOTH_CONNECT je výjimka z omezení Androidu 12+
 * na start foreground service z pozadí – jinak by tohle nešlo.
 */
class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = deviceFrom(intent)

        // Zajímají nás jen sluchátka a headsety, ne třeba připojené hodinky
        // nebo klávesnice. Rozhodujeme podle třídy zařízení, ne podle jména –
        // AirPods si může uživatel přejmenovat na cokoliv.
        if (!BluetoothAudio.isAudioDevice(device)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.i(TAG, "Připojeno audio zařízení, spouštím službu")
                AirPodsService.start(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "Audio zařízení odpojeno, zastavuji službu")
                AirPodsService.stop(context)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "BluetoothReceiver"
    }
}
