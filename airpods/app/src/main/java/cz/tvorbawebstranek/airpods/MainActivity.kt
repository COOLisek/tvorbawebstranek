package cz.tvorbawebstranek.airpods

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cz.tvorbawebstranek.airpods.databinding.ActivityMainBinding
import cz.tvorbawebstranek.airpods.databinding.ItemBatteryBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: AirPodsScanner

    private val handler = Handler(Looper.getMainLooper())
    private var lastStatus: AirPodsStatus? = null

    /** Uživatel skenování zapnul – po návratu do aplikace ho obnovíme. */
    private var scanRequested = false

    /** Při prvním zobrazení začneme hledat sami, ať uživatel nemusí nic mačkat. */
    private var firstStart = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            startScanning()
        } else {
            scanRequested = false
            updateButton()
            binding.statusText.setText(R.string.error_permission)
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (scanner.isBluetoothEnabled()) startScanning() else updateButton()
    }

    /** Jednou za sekundu přepíšeme "aktualizováno před N s". */
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatusLine()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = AirPodsScanner(this) { status -> onStatus(status) }
        scanner.onError = { error -> onError(error) }

        binding.leftPod.batteryName.setText(R.string.left_pod)
        binding.rightPod.batteryName.setText(R.string.right_pod)
        binding.casePod.batteryName.setText(R.string.case_pod)

        binding.scanButton.setOnClickListener {
            if (scanner.isScanning) stopScanning() else startScanning()
        }

        updateButton()
    }

    override fun onStart() {
        super.onStart()
        if (!scanner.isScanning && (firstStart || scanRequested)) startScanning()
        firstStart = false
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        // BLE skenování na pozadí zbytečně žere baterii, tak ho vypneme.
        scanner.stop()
        handler.removeCallbacks(ticker)
        updateButton()
    }

    private fun startScanning() {
        scanRequested = true
        if (scanner.start()) {
            binding.statusText.setText(R.string.status_scanning)
        }
        updateButton()
    }

    private fun stopScanning() {
        scanRequested = false
        scanner.stop()
        binding.statusText.setText(R.string.status_stopped)
        updateButton()
    }

    private fun onStatus(status: AirPodsStatus?) {
        lastStatus = status
        if (status == null) {
            binding.modelText.setText(R.string.no_device)
            clearPod(binding.leftPod)
            clearPod(binding.rightPod)
            clearPod(binding.casePod)
            binding.rightPod.root.visibility = View.VISIBLE
            binding.casePod.root.visibility = View.VISIBLE
            binding.statusText.setText(
                if (scanner.isScanning) R.string.status_lost else R.string.status_stopped
            )
            return
        }

        binding.modelText.text = status.model

        if (status.singleBattery) {
            binding.leftPod.batteryName.setText(R.string.headphones)
            binding.rightPod.root.visibility = View.GONE
            binding.casePod.root.visibility = View.GONE
            // U jedné baterie posílá zařízení hodnotu jen v jednom z půlbajtů.
            val single = if (status.left.isKnown) status.left else status.right
            renderPod(binding.leftPod, single)
        } else {
            binding.leftPod.batteryName.setText(R.string.left_pod)
            binding.rightPod.root.visibility = View.VISIBLE
            binding.casePod.root.visibility = View.VISIBLE
            renderPod(binding.leftPod, status.left)
            renderPod(binding.rightPod, status.right)
            renderPod(binding.casePod, status.case)
        }

        refreshStatusLine()
    }

    private fun renderPod(pod: ItemBatteryBinding, battery: PodBattery) {
        val percent = battery.percent
        if (percent == null) {
            pod.batteryValue.setText(R.string.unknown_value)
            pod.batteryBar.progress = 0
            pod.batteryNote.setText(R.string.not_available)
            return
        }

        pod.batteryValue.text = getString(R.string.percent_format, percent)
        pod.batteryBar.progress = percent

        val notes = buildList {
            if (battery.charging) add(getString(R.string.charging))
            if (battery.inEar) add(getString(R.string.in_ear))
        }
        pod.batteryNote.text = if (notes.isEmpty()) "" else notes.joinToString(" · ")
    }

    private fun clearPod(pod: ItemBatteryBinding) {
        pod.batteryValue.setText(R.string.unknown_value)
        pod.batteryBar.progress = 0
        pod.batteryNote.setText(R.string.waiting_short)
    }

    private fun refreshStatusLine() {
        val status = lastStatus
        if (status == null) {
            binding.statusText.setText(
                if (scanner.isScanning) R.string.status_scanning else R.string.status_stopped
            )
            return
        }
        val ageSeconds = ((SystemClock.elapsedRealtime() - status.timestampMs) / 1000L).toInt()
        binding.statusText.text = getString(R.string.status_found, status.rssi, ageSeconds)
    }

    private fun onError(error: AirPodsScanner.Error) {
        when (error) {
            AirPodsScanner.Error.NO_BLUETOOTH -> {
                binding.statusText.setText(R.string.error_no_bluetooth)
                binding.scanButton.isEnabled = false
            }
            AirPodsScanner.Error.BLUETOOTH_OFF -> {
                binding.statusText.setText(R.string.error_bluetooth_off)
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            AirPodsScanner.Error.NO_PERMISSION -> {
                binding.statusText.setText(R.string.error_permission)
                permissionLauncher.launch(scanner.requiredPermissions())
            }
            AirPodsScanner.Error.SCAN_FAILED -> {
                binding.statusText.setText(R.string.error_scan_failed)
                Toast.makeText(this, R.string.error_scan_failed, Toast.LENGTH_LONG).show()
                updateButton()
            }
        }
    }

    private fun updateButton() {
        binding.scanButton.setText(
            if (scanner.isScanning) R.string.stop_scan else R.string.start_scan
        )
    }
}
