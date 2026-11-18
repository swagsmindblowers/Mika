package com.wangzan101.mika.driving

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * 开机后:安卓不允许后台直接开麦克风前台服务,所以「常驻」打开时只发一条通知,点一下就让 Jarvis 恢复听唤醒词。
 * (打开 Mika 本身也会自动恢复,见 [DrivingService.startIfAlwaysOn]。)
 */
class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!DrivingPrefs.get(context).alwaysOn) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        DrivingNotifications.promptResume(context)
    }
}

/** 没有界面的跳板:从通知点进来,在前台起服务(这时系统允许),马上关掉自己。 */
class JarvisStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DrivingService.start(this)
        finish()
    }
}
