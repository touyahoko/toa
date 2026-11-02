package org.mhxxtools.mhxxrngtool.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

/**
 * Android BluetoothHidDevice による **汎用ゲームパッド HID**（実験）。
 *
 * 重要:
 * - サードパーティアプリは端末の Bluetooth Class of Device (COD) を
 *   公式APIで自由に変更できません。HID として SDP 登録する方式です。
 * - Nintendo Switch がこの汎用HIDを Proコンとして認識する保証はありません。
 * - オンのときだけ registerApp し、オフで unregister します。
 */
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidGamepad(private val context: Context) {

    companion object {
        private const val TAG = "BtHidGamepad"
        private const val ID_GAMEPAD: Byte = 1

        /** シンプルな Gamepad HID レポート記述子 (Button x16 + X/Y/Z/Rz) */
        private val DESCRIPTOR: ByteArray = byteArrayOf(
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x05,        // Usage (Game Pad)
            0xA1.toByte(), 0x01, // Collection (Application)
            0xA1.toByte(), 0x00, //   Collection (Physical)
            0x05, 0x09,        //     Usage Page (Button)
            0x19, 0x01,        //     Usage Minimum (Button 1)
            0x29, 0x10,        //     Usage Maximum (Button 16)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x95.toByte(), 0x10, //     Report Count (16)
            0x75, 0x01,        //     Report Size (1)
            0x81.toByte(), 0x02, //     Input (Data,Var,Abs)
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x09, 0x30,        //     Usage (X)
            0x09, 0x31,        //     Usage (Y)
            0x09, 0x32,        //     Usage (Z)
            0x09, 0x35,        //     Usage (Rz)
            0x15, 0x00,        //     Logical Minimum (0)
            0x26, 0xFF.toByte(), 0x00, // Logical Maximum (255)
            0x75, 0x08,        //     Report Size (8)
            0x95.toByte(), 0x04, //     Report Count (4)
            0x81.toByte(), 0x02, //     Input (Data,Var,Abs)
            0xC0.toByte(),     //   End Collection
            0xC0.toByte()      // End Collection
        )
    }

    interface Listener {
        fun onState(msg: String)
        fun onConnected(deviceName: String?)
        fun onDisconnected()
    }

    var listener: Listener? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var hostDevice: BluetoothDevice? = null
    private var registered = false
    private val executor = Executors.newSingleThreadExecutor()

    // ボタンビット (16) + 軸 4バイト
    private var buttons: Int = 0
    private var axisX: Int = 128
    private var axisY: Int = 128
    private var axisZ: Int = 128
    private var axisRz: Int = 128

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            hidDevice = proxy
            listener?.onState("HID サービス接続 → アプリ登録中…")
            registerAppInternal(proxy)
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                registered = false
                hostDevice = null
                listener?.onState("HID サービス切断")
                listener?.onDisconnected()
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothHidGamepad.registered = registered
            listener?.onState(if (registered) "HID登録ON（検出待ち）" else "HID登録OFF")
            if (registered && pluggedDevice != null) {
                hostDevice = pluggedDevice
                listener?.onConnected(pluggedDevice.name ?: pluggedDevice.address)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    listener?.onConnected(device?.name ?: device?.address)
                    listener?.onState("ホスト接続: ${device?.name ?: device?.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (hostDevice?.address == device?.address) hostDevice = null
                    listener?.onDisconnected()
                    listener?.onState("ホスト切断")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                sendReport(device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            listener?.onState("Android 9 以上が必要です")
            return
        }
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener?.onState("Bluetooth をオンにしてください")
            return
        }
        val ok = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        if (!ok) listener?.onState("HID_DEVICE プロキシ取得失敗")
        else listener?.onState("HID プロキシ要求中…")
    }

    @SuppressLint("MissingPermission")
    private fun registerAppInternal(hid: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "MHXX Gamepad",
            "MHXX RNG experimental HID gamepad",
            "MHXXTools",
            BluetoothHidDevice.SUBCLASS1_GAMEPAD,
            DESCRIPTOR
        )
        val inQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )
        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )
        val ok = hid.registerApp(sdp, null, inQos, executor, callback)
        listener?.onState(if (ok) "registerApp 要求OK" else "registerApp 失敗")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregister: ${e.message}")
        }
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            hidDevice?.let { bm.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {}
        hidDevice = null
        hostDevice = null
        registered = false
        listener?.onState("HID オフ")
        listener?.onDisconnected()
    }

    fun setButton(bit: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
        sendReport()
    }

    fun setLeftStick(x: Int, y: Int) {
        axisX = x.coerceIn(0, 255)
        axisY = y.coerceIn(0, 255)
        sendReport()
    }

    fun setRightStick(x: Int, y: Int) {
        axisZ = x.coerceIn(0, 255)
        axisRz = y.coerceIn(0, 255)
        sendReport()
    }

    @SuppressLint("MissingPermission")
    private fun sendReport(device: BluetoothDevice? = hostDevice) {
        val hid = hidDevice ?: return
        val dev = device ?: return
        val report = byteArrayOf(
            (buttons and 0xFF).toByte(),
            ((buttons shr 8) and 0xFF).toByte(),
            axisX.toByte(),
            axisY.toByte(),
            axisZ.toByte(),
            axisRz.toByte()
        )
        try {
            hid.sendReport(dev, ID_GAMEPAD.toInt(), report)
        } catch (e: Exception) {
            Log.w(TAG, "sendReport: ${e.message}")
        }
    }

    val isRegistered: Boolean get() = registered
    val connectedName: String? get() = hostDevice?.name ?: hostDevice?.address
}
