package com.sinilink.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * 欣易连功放的 BLE 连接管理。
 * 订阅全部通知/指示特征, 等 CCCD 写完后再发状态查询, 自动重试。
 */
object SinilinkBle {
    private const val TAG = "SinilinkBle"
    private const val STATUS_RETRY_MS = 3000L
    private const val STATUS_ATTEMPTS_PER_CHANNEL = 2
    private const val CONNECT_TIMEOUT_MS = 12000L
    private const val DESCRIPTOR_WATCHDOG_MS = 5000L
    private const val BOND_TIMEOUT_MS = 15000L
    private const val DISCOVERY_TIMEOUT_MS = 10000L

    private data class StatusChannel(val uuid: String, val withResponse: Boolean)

    private val statusChannels = listOf(
        StatusChannel(Protocol.CHAR_TX, true), // ae10: 与 Gadgetbridge 一致, 带响应
        StatusChannel("0000ae03-0000-1000-8000-00805f9b34fb", false), // ae03: 无响应写
        StatusChannel("0000ae01-0000-1000-8000-00805f9b34fb", false), // ae01: 无响应写
    )

    private val NOTIFY_UUIDS = listOf(
        Protocol.CHAR_RX, // 0000ae04
        "0000ae02-0000-1000-8000-00805f9b34fb",
        "0000ae05-0000-1000-8000-00805f9b34fb",
    )
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var subscribedUuids = mutableSetOf<String>()
    private var descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var descriptorWriteInFlight = false
    private var channelIndex = 0
    private var channelAttempts = 0
    private var controlReady = false
    private var ampResponds = false
    private var servicesDiscovered = false
    var onStatus: ((String) -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private var appContext: Context? = null
    private var targetDevice: BluetoothDevice? = null
    private var bondReceiver: BroadcastReceiver? = null

    private val bondAttemptedMacs = mutableSetOf<String>()

    private val bondTimeout = Runnable {
        Log.i(TAG, "bond timeout, connecting anyway")
        unregisterBondReceiver()
        startGattConnect()
    }

    private val discoveryTimeout = Runnable {
        Log.w(TAG, "services discovery timeout, retrying")
        if (!servicesDiscovered) {
            onError?.invoke("服务发现超时，正在重试")
            disconnect()
        }
    }

    private val main = Handler(Looper.getMainLooper())

    private val connectTimeout = Runnable {
        Log.w(TAG, "connect timeout, retrying")
        if (!controlReady) {
            onError?.invoke("连接超时，正在重试")
            disconnect()
        }
    }

    private val descriptorWatchdog = Runnable {
        if (descriptorWriteInFlight) {
            Log.w(TAG, "descriptor write stuck, proceeding without it")
            descriptorWriteInFlight = false
            descriptorQueue.clear()
            gatt?.let { onSubscriptionsReady(it) }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, device: BluetoothDevice, onReady: () -> Unit, onError: (String) -> Unit) {
        disconnect()
        this.onReady = onReady
        this.onError = onError
        controlReady = false
        ampResponds = false
        servicesDiscovered = false
        appContext = context.applicationContext
        targetDevice = device

        // 部分功放固件只向已配对的客户端发送通知, 所以先尝试配对
        if (device.bondState != BluetoothDevice.BOND_BONDED
            && device.address !in bondAttemptedMacs
        ) {
            bondAttemptedMacs.add(device.address)
            Log.i(TAG, "device not bonded, requesting bond")
            onStatus?.invoke("正在与功放配对，请在屏幕上确认配对请求…")
            registerBondReceiver()
            try {
                val ok = device.createBond()
                Log.i(TAG, "createBond result=$ok")
            } catch (e: Exception) {
                Log.w(TAG, "createBond failed", e)
            }
            main.postDelayed(bondTimeout, BOND_TIMEOUT_MS)
            return
        }

        startGattConnect()
    }

    @SuppressLint("MissingPermission")
    private fun startGattConnect() {
        val context = appContext ?: return
        val device = targetDevice ?: return
        Log.i(TAG, "connecting to ${device.address} ${device.name}")
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        gatt = device.connectGatt(
            context,
            false,
            object : BluetoothGattCallback() {
            // 显式指定 LE 传输, 避免部分盒子默认走错传输方式
            // (connectGatt 4 参数版本在 API 23+ 可用, minSdk 26 满足)
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                main.removeCallbacks(connectTimeout)
                Log.i(TAG, "connection state: status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "connected, requesting mtu")
                        main.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS)
                        g.requestMtu(512)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!controlReady) {
                            main.post { onError?.invoke("与功放的连接已断开") }
                        }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                servicesDiscovered = true
                main.removeCallbacks(discoveryTimeout)
                Log.i(TAG, "services discovered status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    main.post { onError?.invoke("服务发现失败($status)") }
                    return
                }
                val service = g.getService(UUID.fromString(Protocol.SERVICE_UUID))
                if (service == null) {
                    Log.w(TAG, "service 0xAE00 not found")
                    main.post { onError?.invoke("未找到控制服务 0xAE00") }
                    return
                }
                txChar = service.getCharacteristic(UUID.fromString(Protocol.CHAR_TX))
                if (txChar == null) {
                    Log.w(TAG, "TX characteristic not found")
                    main.post { onError?.invoke("未找到控制特征") }
                    return
                }

                subscribedUuids.clear()
                descriptorQueue.clear()
                descriptorWriteInFlight = false
                for (uuid in NOTIFY_UUIDS) {
                    val c = service.getCharacteristic(UUID.fromString(uuid)) ?: continue
                    Log.i(TAG, "subscribing to $uuid")
                    subscribedUuids.add(uuid.lowercase())
                    g.setCharacteristicNotification(c, true)
                    val cccd = c.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        // 指示特征(ae05)用 0x0002, 通知特征用 0x0001
                        cccd.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                            byteArrayOf(0x02, 0x00)
                        } else {
                            byteArrayOf(0x01, 0x00)
                        }
                        descriptorQueue.addLast(cccd)
                    }
                }
                if (descriptorQueue.isEmpty()) {
                    onSubscriptionsReady(g)
                } else {
                    main.postDelayed(descriptorWatchdog, DESCRIPTOR_WATCHDOG_MS)
                    writeNextDescriptor(g)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "mtu changed mtu=$mtu status=$status")
                // MTU 协商完成后才开始服务发现, 避免两个操作同时进行导致卡死
                g.discoverServices()
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                Log.i(TAG, "descriptor write ${descriptor.characteristic.uuid} status=$status")
                descriptorWriteInFlight = false
                writeNextDescriptor(g)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                Log.i(TAG, "notify ${characteristic.uuid}: ${value.toHex()}")
                if (characteristic.uuid.toString().lowercase() in subscribedUuids
                    && Protocol.isStatusFrame(value)
                ) {
                    ampResponds = true
                    Log.i(TAG, "amp responded via notification")
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                Log.i(TAG, "read ${characteristic.uuid} status=$status: ${value.toHex()}")
                if (Protocol.isStatusFrame(value)) {
                    ampResponds = true
                    Log.i(TAG, "amp responded via read")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.i(TAG, "write ${characteristic.uuid} status=$status")
            }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun sendSource(source: Int): Boolean {
        val g = gatt ?: return false
        val c = txChar ?: return false
        return try {
            Log.i(TAG, "sending source 0x${source.toString(16)}")
            write(g, c, Protocol.sourceCommand(source))
            true
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun onSubscriptionsReady(g: BluetoothGatt) {
        main.removeCallbacks(descriptorWatchdog)
        if (!controlReady) {
            controlReady = true
            Log.i(TAG, "ready to control")
            main.post { onReady?.invoke() }
        }
        // 后台探测功放是否响应(部分安卓设备收不到通知, 但写入仍然有效)
        if (!ampResponds) {
            channelIndex = 0
            channelAttempts = 0
            sendStatusOnChannel(g)
        }
    }

    /** 依次尝试 ae10/ae03/ae01 三个写入通道, 探测功放是否响应 */
    @SuppressLint("MissingPermission")
    private fun sendStatusOnChannel(g: BluetoothGatt) {
        if (ampResponds) return
        if (channelIndex >= statusChannels.size) {
            Log.i(TAG, "probe done, ampResponds=$ampResponds")
            return
        }
        if (channelAttempts >= STATUS_ATTEMPTS_PER_CHANNEL) {
            channelIndex++
            channelAttempts = 0
            sendStatusOnChannel(g)
            return
        }
        val ch = statusChannels[channelIndex]
        val c = g.getService(UUID.fromString(Protocol.SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(ch.uuid))
        if (c == null) {
            channelIndex++
            channelAttempts = 0
            sendStatusOnChannel(g)
            return
        }
        channelAttempts++
        Log.i(TAG, "status request via ${ch.uuid} attempt $channelAttempts (withResponse=${ch.withResponse})")
        try {
            c.value = Protocol.statusRequest()
            c.writeType = if (ch.withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            g.writeCharacteristic(c)
            if (ch.uuid == Protocol.CHAR_TX) {
                g.readCharacteristic(c)
            }
        } catch (e: Exception) {
            Log.w(TAG, "status write failed", e)
        }
        main.postDelayed({ if (!ampResponds) sendStatusOnChannel(g) }, STATUS_RETRY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextDescriptor(g: BluetoothGatt) {
        if (descriptorWriteInFlight) return
        val d = descriptorQueue.removeFirstOrNull()
        if (d == null) {
            onSubscriptionsReady(g)
            return
        }
        descriptorWriteInFlight = true
        Log.i(TAG, "writing CCCD for ${d.characteristic.uuid} value=${d.value.toHex()}")
        g.writeDescriptor(d)
    }

    @SuppressLint("MissingPermission")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
        c.value = data
        g.writeCharacteristic(c)
    }

    fun disconnect() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(descriptorWatchdog)
        main.removeCallbacks(bondTimeout)
        main.removeCallbacks(discoveryTimeout)
        unregisterBondReceiver()
        onStatus = null
        onReady = null
        onError = null
        controlReady = false
        ampResponds = false
        servicesDiscovered = false
        subscribedUuids.clear()
        descriptorQueue.clear()
        descriptorWriteInFlight = false
        appContext = null
        targetDevice = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        txChar = null
    }

    private fun registerBondReceiver() {
        unregisterBondReceiver()
        val context = appContext ?: return
        bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val dev = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                if (dev.address == targetDevice?.address
                    && intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    == BluetoothDevice.BOND_BONDED
                ) {
                    Log.i(TAG, "bonded, connecting")
                    main.removeCallbacks(bondTimeout)
                    unregisterBondReceiver()
                    startGattConnect()
                }
            }
        }
        try {
            context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        } catch (e: Exception) {
            Log.w(TAG, "register bond receiver failed", e)
        }
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        try {
            appContext?.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
