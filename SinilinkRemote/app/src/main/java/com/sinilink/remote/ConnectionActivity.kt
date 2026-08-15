package com.sinilink.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 连接页: 进入后自动扫描并循环重试, 无需手动点击。
 */
class ConnectionActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRetry: Button
    private lateinit var lvDevices: ListView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanning = false
    private var connecting = false
    private var stopped = false
    private val foundDevices = mutableListOf<Pair<BluetoothDevice, Int>>()
    private var lastMac: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                startAutoScan()
            } else {
                tvStatus.text = getString(R.string.need_permission)
                btnRetry.visibility = View.VISIBLE
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (stopped) return@registerForActivityResult
            if (bluetoothAdapter?.isEnabled == true) {
                beginScan()
            } else {
                tvStatus.text = getString(R.string.bt_off)
                scheduleBtPrompt()
            }
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name
            Log.d(TAG, "scan result: name=$name addr=${result.device.address} rssi=${result.rssi}")
            if (isTargetName(name)) {
                if (foundDevices.none { it.first.address == result.device.address }) {
                    foundDevices.add(result.device to result.rssi)
                    tvStatus.text = getString(R.string.found_device, name, result.device.address)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            tvStatus.text = getString(R.string.scan_failed, errorCode)
            tvStatus.postDelayed({ if (!stopped) beginScan() }, RETRY_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)

        tvStatus = findViewById(R.id.tv_status)
        btnRetry = findViewById(R.id.btn_retry)
        lvDevices = findViewById(R.id.lv_devices)
        lastMac = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_MAC, null)

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position < foundDevices.size) {
                connectTo(foundDevices[position].first)
            }
        }

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            tvStatus.text = getString(R.string.no_bluetooth)
            return
        }

        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            ensurePermissionsAndStart()
        }

        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                return
            }
        }
        startAutoScan()
    }

    private fun startAutoScan() {
        if (stopped) return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            tvStatus.text = getString(R.string.no_bluetooth)
            return
        }
        if (!adapter.isEnabled) {
            tvStatus.text = getString(R.string.bt_off)
            scheduleBtPrompt()
            return
        }
        beginScan()
    }

    private fun scheduleBtPrompt() {
        tvStatus.postDelayed({
            if (stopped) return@postDelayed
            val adapter = bluetoothAdapter ?: return@postDelayed
            if (adapter.isEnabled) {
                beginScan()
            } else {
                try {
                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: Exception) {
                    tvStatus.text = getString(R.string.bt_off)
                }
            }
        }, BT_PROMPT_DELAY_MS)
    }

    private fun beginScan() {
        if (stopped || connecting) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            scheduleBtPrompt()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            tvStatus.text = getString(R.string.scan_unavailable)
            tvStatus.postDelayed({ if (!stopped) beginScan() }, RETRY_DELAY_MS)
            return
        }

        foundDevices.clear()
        scanning = true
        tvStatus.text = getString(R.string.scanning)
        scanner.startScan(scanCallback)

        tvStatus.postDelayed({
            if (scanning && !stopped) {
                scanner.stopScan(scanCallback)
                scanning = false
                onScanFinished()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun onScanFinished() {
        if (stopped) return
        if (foundDevices.isEmpty()) {
            tvStatus.text = getString(R.string.scan_retry)
            tvStatus.postDelayed({ if (!stopped) beginScan() }, RETRY_DELAY_MS)
        } else if (foundDevices.size == 1) {
            connectTo(foundDevices[0].first)
        } else {
            showDevicePicker()
        }
    }

    private fun showDevicePicker() {
        tvStatus.text = getString(R.string.multiple_found)
        val names = foundDevices.map {
            "${it.first.name ?: getString(R.string.unknown_device)}（${it.first.address}）"
        }
        lvDevices.adapter = ArrayAdapter(this, R.layout.list_item_device, names)
        lvDevices.visibility = View.VISIBLE
    }

    private fun isTargetName(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase()
        return n.endsWith("-app") || n.contains("sinilink") || n.contains("xinyi")
            || n.contains("djg") || n.contains("xy-")
    }

    private fun connectTo(device: BluetoothDevice) {
        if (stopped) return
        connecting = true
        tvStatus.text = getString(R.string.connecting, device.name)

        SinilinkBle.onStatus = { msg -> tvStatus.text = msg }
        SinilinkBle.connect(
            this,
            device,
            onReady = {
                connecting = false
                SinilinkBle.onStatus = null
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_MAC, device.address).apply()
                startActivity(Intent(this, ControlActivity::class.java))
                finish()
            },
            onError = { msg ->
                connecting = false
                SinilinkBle.onStatus = null
                tvStatus.text = getString(R.string.connect_failed, msg)
                tvStatus.postDelayed({ if (!stopped) beginScan() }, RETRY_DELAY_MS)
            }
        )
    }

    override fun onDestroy() {
        stopped = true
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SinilinkApp"
        private const val PREFS_NAME = "sinilink"
        private const val KEY_MAC = "mac"
        private const val SCAN_TIMEOUT_MS = 8000L
        private const val RETRY_DELAY_MS = 2000L
        private const val BT_PROMPT_DELAY_MS = 4000L
    }
}
