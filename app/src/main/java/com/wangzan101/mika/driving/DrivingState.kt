package com.wangzan101.mika.driving

import android.content.Context
import android.content.SharedPreferences

/** 驾驶模式的状态机:IDLE → LISTENING → PROCESSING → SPEAKING → IDLE;出错或打断从任何状态回 IDLE。 */
enum class DrivingState { IDLE, LISTENING, PROCESSING, SPEAKING }

/** Jarvis 的声音:Kokoro 自然但重,Piper 快但平。 */
enum class VoiceEngine { Kokoro, Piper }

/** 驾驶模式自己的开关,和 Mika 原有设置分开存([DrivingSettingsActivity] 里改)。 */
class DrivingPrefs private constructor(private val prefs: SharedPreferences) {
    /** 常驻监听「Jarvis」/「Hey Jarvis」(离线、不需要 Key,首次要下载约 17 MB 的模型);关掉后只能点屏幕上的 SPEAK。 */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(WAKE_WORD, true)
        set(v) { prefs.edit().putBoolean(WAKE_WORD, v).apply() }

    /**
     * 常驻:Mika 一启动就让 Jarvis 在后台听唤醒词(不用先点「驾驶模式」)。常驻通知可见,麦克风一直开着,会耗电。
     * 重启手机后安卓不允许后台直接开麦克风,所以开机后是一条点一下就恢复的通知。
     */
    var alwaysOn: Boolean
        get() = prefs.getBoolean(ALWAYS_ON, true)
        set(v) { prefs.edit().putBoolean(ALWAYS_ON, v).apply() }

    /** 用哪个声音。默认 Kokoro;设成 Kokoro 时清掉「太慢」的记号,让它重新试一次。 */
    var voiceEngine: VoiceEngine
        get() = runCatching { VoiceEngine.valueOf(prefs.getString(VOICE_ENGINE, null).orEmpty()) }.getOrDefault(VoiceEngine.Kokoro)
        set(v) {
            prefs.edit().putString(VOICE_ENGINE, v.name).apply()
            if (v == VoiceEngine.Kokoro) kokoroTooSlow = false
        }

    /** Kokoro 在这台手机上连续跑不过播放速度,已自动退回 Piper。 */
    var kokoroTooSlow: Boolean
        get() = prefs.getBoolean(KOKORO_SLOW, false)
        set(v) { prefs.edit().putBoolean(KOKORO_SLOW, v).apply() }

    /** [engine] 的语速,1.0 是模型原速,大于 1 更快。Piper 原速偏慢所以默认快一点。 */
    fun speedFor(engine: VoiceEngine): Float = when (engine) {
        VoiceEngine.Kokoro -> prefs.getFloat(SPEED_KOKORO, JarvisTTS.DEFAULT_KOKORO_SPEED)
        // 沿用旧的键:之前只有 Piper
        VoiceEngine.Piper -> prefs.getFloat(VOICE_SPEED, JarvisTTS.DEFAULT_SPEED)
    }.coerceIn(JarvisTTS.MIN_SPEED, JarvisTTS.MAX_SPEED)

    fun setSpeedFor(engine: VoiceEngine, v: Float) {
        val key = if (engine == VoiceEngine.Kokoro) SPEED_KOKORO else VOICE_SPEED
        prefs.edit().putFloat(key, v.coerceIn(JarvisTTS.MIN_SPEED, JarvisTTS.MAX_SPEED)).apply()
    }

    /** 当前选中的声音的语速(设置页的滑块)。 */
    var voiceSpeed: Float
        get() = speedFor(voiceEngine)
        set(v) = setSpeedFor(voiceEngine, v)

    /** 连上车载蓝牙时自动进入驾驶模式。 */
    var bluetoothTriggerEnabled: Boolean
        get() = prefs.getBoolean(BT_TRIGGER, true)
        set(v) { prefs.edit().putBoolean(BT_TRIGGER, v).apply() }

    /** true:任何蓝牙音频设备(包括耳机)都算;false:只认名字里带车厂/车机关键词的。 */
    var bluetoothAnyAudioDevice: Boolean
        get() = prefs.getBoolean(BT_ANY_AUDIO, true)
        set(v) { prefs.edit().putBoolean(BT_ANY_AUDIO, v).apply() }

    /** 声音模型(约 67 MB)和唤醒词模型(约 17 MB)只在不计流量的网络(Wi-Fi)下下载。 */
    var wifiOnlyDownload: Boolean
        get() = prefs.getBoolean(WIFI_ONLY, true)
        set(v) { prefs.edit().putBoolean(WIFI_ONLY, v).apply() }

    companion object {
        private const val WAKE_WORD = "wake_word"
        private const val ALWAYS_ON = "always_on"
        private const val VOICE_SPEED = "voice_speed"
        private const val SPEED_KOKORO = "speed_kokoro"
        private const val VOICE_ENGINE = "voice_engine"
        private const val KOKORO_SLOW = "kokoro_too_slow"
        private const val BT_TRIGGER = "bt_trigger"
        private const val BT_ANY_AUDIO = "bt_any_audio"
        private const val WIFI_ONLY = "wifi_only"

        fun get(context: Context) = DrivingPrefs(
            context.applicationContext.getSharedPreferences("jarvis_driving", Context.MODE_PRIVATE),
        )
    }
}
