package com.wangzan101.mika.driving

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

/** 全屏免提界面:常亮、竖屏,只显示 [DrivingScreen]。进来时确保 [DrivingService] 在跑。 */
class DrivingActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) DrivingService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DrivingService.driving = true
        setContent {
            DrivingScreen(
                onSpeak = { DrivingService.send(this, DrivingService.ACTION_START_LISTENING) },
                onInterrupt = { DrivingService.send(this, DrivingService.ACTION_INTERRUPT) },
                onExit = {
                    DrivingService.stop(this)
                    finish()
                },
            )
        }
        if (!DrivingService.isRunning) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                DrivingService.start(this)
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onDestroy() {
        // 换方向等重建不会走到这里(竖屏锁定);真的离开了才不算在开车
        if (isFinishing) DrivingService.driving = false
        super.onDestroy()
    }
}
