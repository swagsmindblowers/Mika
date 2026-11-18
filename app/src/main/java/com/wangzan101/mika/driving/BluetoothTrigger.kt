package com.wangzan101.mika.driving

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 连上车载蓝牙(ACL_CONNECTED)时进入驾驶模式。
 *
 * 判断「是车」:设备类别是音频/视频,或名字(不分大小写)带车厂/车机关键词。
 * 注意系统对后台限制很严:安卓 10+ 不让后台直接开界面,14+ 不让后台起麦克风前台服务。
 * 所以只有用户给了「显示在其他应用上层」时才直接弹出界面,否则留一条点一下就进的通知(见 [DrivingNotifications])。
 */
class BluetoothTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val prefs = DrivingPrefs.get(context)
        if (!prefs.bluetoothTriggerEnabled) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        if (!looksLikeCar(context, device, prefs.bluetoothAnyAudioDevice)) return
        fire(context)
    }

    companion object {
        private const val TAG = "JarvisBluetooth"

        internal val NAME_KEYWORDS = listOf(
            "car", "auto", "bmw", "audi", "mercedes", "toyota", "honda", "ford", "hyundai",
            "kia", "volvo", "mazda", "subaru", "head unit", "stereo", "radio",
        )

        internal fun nameLooksLikeCar(name: String?): Boolean {
            val n = name?.lowercase() ?: return false
            return NAME_KEYWORDS.any { it in n }
        }

        /** 读设备类别和名字都要 BLUETOOTH_CONNECT(安卓 12+);没有就当作不是,不抛异常。 */
        fun looksLikeCar(context: Context, device: BluetoothDevice, anyAudioDevice: Boolean = true): Boolean {
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted; cannot inspect device")
                return false
            }
            return try {
                (anyAudioDevice && device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO) ||
                    nameLooksLikeCar(device.name)
            } catch (e: SecurityException) {
                false
            }
        }

        /** 进入驾驶模式(真连上车机和设置页的「模拟」按钮共用)。 */
        fun fire(context: Context) {
            val app = context.applicationContext
            DrivingService.driving = true
            DrivingService.start(app)
            if (Settings.canDrawOverlays(app)) {
                try {
                    app.startActivity(
                        Intent(app, DrivingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "cannot open driving screen from background", e)
                }
            }
            DrivingNotifications.promptOpenDriving(app, "Car connected. Tap to start Jarvis driving mode")
        }
    }
}
