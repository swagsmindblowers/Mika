package com.wangzan101.mika.driving

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.R

/** 驾驶模式的通知:前台服务常驻通知,以及后台起不来时让用户点一下的提示。 */
internal object DrivingNotifications {
    const val CHANNEL_SERVICE = "jarvis_driving"
    const val CHANNEL_PROMPT = "jarvis_driving_prompt"
    const val ID_SERVICE = 0x4A31
    private const val ID_PROMPT = 0x4A32

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Jarvis driving mode", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROMPT, "Jarvis driving prompts", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openDriving(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, DrivingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun openSettings(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, DrivingSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(context: Context, text: String, stop: PendingIntent): Notification {
        ensureChannels(context)
        return Notification.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis driving mode")
            .setContentText(text)
            .setContentIntent(openDriving(context))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** 系统不让后台直接起驾驶模式(安卓 10+ 限制后台启动界面、麦克风前台服务)时,留一条点一下就进的通知。 */
    fun promptOpenDriving(context: Context, text: String = "Tap to start Jarvis driving mode") {
        post(context, text, openDriving(context))
    }

    /** 开机后恢复常驻的提示:点一下经 [JarvisStartActivity] 起服务,不打开驾驶界面。 */
    fun promptResume(context: Context) {
        post(
            context, "Tap to resume listening for \"Hey Jarvis\"",
            PendingIntent.getActivity(
                context, 2, Intent(context, JarvisStartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    fun promptSettings(context: Context, text: String) {
        post(context, text, openSettings(context))
    }

    private fun post(context: Context, text: String, intent: PendingIntent) {
        ensureChannels(context)
        val n = Notification.Builder(context, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setTimeoutAfter(2 * 60_000L)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_PROMPT, n) }
    }
}
