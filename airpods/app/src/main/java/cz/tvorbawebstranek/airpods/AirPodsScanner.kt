package cz.tvorbawebstranek.airpods

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Pasivně poslouchá BLE inzeráty v okolí a hlásí stav nejbližších AirPods.
 *
 * Nikam se nepřipojuje – jen odposlouchává broadcast, který sluchátka vysílají
 * do okolí. Kvůli tomu nepotřebuje párování ani navázané spojení, ale funguje
 * jen když sluchátka zrovna vysílají (viz README).
 */
class AirPodsScanner(
    private val context: Context,
    private val listener: (AirPodsStatus?) -> Unit
) {

    /** Chyby, na které uživatel musí zareagovat (zapnout Bluetooth, dát oprávnění). */
    enum class Error { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, SCAN_FAILED }

    var onError: ((Error) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    var isScanning = false
        private set

    /** Nejsilnější inzerát v aktuálním okně, aby výsledek neposkakoval mezi více zařízeními. */
    private var bestInWindow: AirPodsStatus? = null
    private var windowStartMs = 0L

    private val staleRunnable = Runnable {
        bestInWindow = null
        listener(null)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE skenování selhalo, kód $errorCode")
            isScanning = false
            onError?.invoke(Error.SCAN_FAILED)
        }
    }

    /**
     * Vrátí oprávnění, která je potřeba si vyžádat na aktuální verzi Androidu.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Spustí skenování. Když něco chybí, ohlásí to přes [onError] a vrátí false.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isScanning) return true

        val adapter = bluetoothAdapter
        if (adapter == null) {
            onError?.invoke(Error.NO_BLUETOOTH)
            return false
        }
        // Oprávnění řešíme jako první – na Androidu 12+ se bez BLUETOOTH_CONNECT
        // nedá ani vyvolat systémový dialog na zapnutí Bluetooth.
        if (!hasPermissions()) {
            onError?.invoke(Error.NO_PERMISSION)
            return false
        }
        if (!adapter.isEnabled) {
            onError?.invoke(Error.BLUETOOTH_OFF)
            return false
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onError?.invoke(Error.BLUETOOTH_OFF)
            return false
        }

        // Filtr propustí jen Apple manufacturer data začínající typem 0x07 (proximity pairing).
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AirPodsParser.APPLE_COMPANY_ID,
                byteArrayOf(AirPodsParser.PROXIMITY_PAIRING_TYPE),
                byteArrayOf(0xFF.toByte())
            )
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()

        return try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            scheduleStaleTimeout()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Chybí oprávnění pro skenování", e)
            onError?.invoke(Error.NO_PERMISSION)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(staleRunnable)
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Skenování se nepodařilo zastavit", e)
        }
    }

    private fun handleResult(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(AirPodsParser.APPLE_COMPANY_ID)
            ?: return
        if (result.rssi < MIN_RSSI) return

        val status = AirPodsParser.parse(data, result.rssi, SystemClock.elapsedRealtime()) ?: return

        val now = SystemClock.elapsedRealtime()
        val current = bestInWindow
        if (current == null || now - windowStartMs > WINDOW_MS || status.rssi >= current.rssi) {
            if (current == null || now - windowStartMs > WINDOW_MS) windowStartMs = now
            bestInWindow = status
            listener(status)
        }
        scheduleStaleTimeout()
    }

    /** Když nic nepřijde do [STALE_MS], nahlásíme, že se nic neozývá. */
    private fun scheduleStaleTimeout() {
        handler.removeCallbacks(staleRunnable)
        handler.postDelayed(staleRunnable, STALE_MS)
    }

    private companion object {
        const val TAG = "AirPodsScanner"

        /** Slabší signál než tohle je nejspíš cizí zařízení o pár místností dál. */
        const val MIN_RSSI = -80

        /** Okno, ve kterém vybíráme nejsilnější inzerát. */
        const val WINDOW_MS = 1_000L

        /** Po jak dlouhém tichu považujeme údaje za neplatné. */
        const val STALE_MS = 10_000L
    }
}
