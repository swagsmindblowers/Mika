package com.wangzan101.mika.driving

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.voice.SpeechFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Jarvis 驾驶模式的前台服务(麦克风类型):唤醒词 → 听 → 交给 Mika 的任务 → 逐句朗读。
 *
 * 听:沿用 Mika 现有的语音输入([MikaApp.voice],用户在设置里选的引擎),但那是按住说话的会话,
 * 这里用音量判断「说完了」(停顿约 1.5 秒)后替用户松开。
 * 想:和对话页同一条路——[TaskService.start] 开任务,从 [MikaApp.taskState] 读结局。
 * 说:[JarvisTTS]。
 */
class DrivingService : LifecycleService() {
    private lateinit var app: MikaApp
    private var wake: WakeWordManager? = null
    private lateinit var tts: JarvisTTS
    private var turn: Job? = null
    private var conversationId: String? = null
    private var lastTurnAt = 0L

    override fun onCreate() {
        super.onCreate()
        app = application as MikaApp
        val stop = PendingIntent.getService(
            this, 0, Intent(this, DrivingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            ServiceCompat.startForeground(
                this, DrivingNotifications.ID_SERVICE,
                DrivingNotifications.service(this, "Listening for \"Hey Jarvis\"", stop),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // 没有录音权限或系统不允许此刻起麦克风前台服务
            Log.w(TAG, "cannot enter foreground", e)
            DrivingNotifications.promptOpenDriving(this)
            stopSelf()
            return
        }
        running = true
        tts = JarvisTTS(this)
        JarvisModel.ensure(this) // Piper:既是选项,也是 Kokoro 太慢时的退路
        if (DrivingPrefs.get(this).voiceEngine == VoiceEngine.Kokoro) KokoroModel.ensure(this)
        if (DrivingPrefs.get(this).wakeWordEnabled) {
            WakeWordModel.ensure(this)
            val manager = WakeWordManager(this).also { wake = it }
            lifecycleScope.launch {
                // 模型还在下载时先不开;下好后自动开始听
                WakeWordModel.status.first { WakeWordModel.isReady(this@DrivingService) }
                if (!manager.start()) Log.w(TAG, "wake word off: ${manager.error.value}")
            }
            lifecycleScope.launch { manager.error.collect { _wakeError.value = it } }
            lifecycleScope.launch {
                manager.detections.collect {
                    if (_state.value == DrivingState.IDLE) startTurn() else manager.resume()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_LISTENING -> if (_state.value == DrivingState.IDLE) startTurn()
            ACTION_INTERRUPT -> interrupt()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        running = false
        driving = false
        _wakeError.value = null
        turn?.cancel()
        wake?.stop()
        if (::tts.isInitialized) tts.shutdown()
        _state.value = DrivingState.IDLE
        super.onDestroy()
    }

    // ---- 状态机 ----

    private fun interrupt() {
        if (_state.value == DrivingState.PROCESSING) TaskService.cancel(app)
        tts.stop()
        turn?.cancel() // 回 IDLE 在 runTurn 的 finally 里做
    }

    private fun startTurn() {
        val previous = turn
        turn = lifecycleScope.launch {
            previous?.cancelAndJoin()
            runTurn()
        }
    }

    private suspend fun runTurn() {
        try {
            wake?.pause()
            _transcript.value = ""
            _response.value = ""
            setState(DrivingState.LISTENING)
            val heard = listen()
            if (heard !is Heard.Text) {
                speakPlain(heard.failureMessage())
                return
            }
            _transcript.value = heard.text
            setState(DrivingState.PROCESSING)
            val answer = ask(heard.text)
            setState(DrivingState.SPEAKING)
            speakAnswer(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "turn failed", e)
            runCatching { speakPlain("Sorry, something went wrong.") }
        } finally {
            lastTurnAt = System.currentTimeMillis()
            setState(DrivingState.IDLE)
            wake?.resume()
        }
    }

    private fun setState(s: DrivingState) {
        _state.value = s
    }

    // ---- 听 ----

    private sealed interface Heard {
        data class Text(val text: String) : Heard
        data object Silence : Heard
        data class Failed(val why: SpeechFailure) : Heard

        fun failureMessage(): String = when (this) {
            is Failed -> when (why) {
                is SpeechFailure.NoPermission -> "I need microphone permission. Please open Mika when you can."
                is SpeechFailure.Unavailable -> "Speech recognition isn't available. Check Mika's voice settings."
                is SpeechFailure.Network -> "I can't reach the speech service right now."
                else -> "Sorry, I couldn't hear you."
            }
            else -> "Sorry, I didn't catch that."
        }
    }

    /**
     * 开一次 Mika 的语音会话(要在主线程,lifecycleScope 就是),用音量/文字停顿判断说完。
     * 开口前最多等 [NO_SPEECH_MS],开口后停顿 [END_SILENCE_MS] 收尾,整段不超过 [MAX_UTTERANCE_MS]。
     */
    private suspend fun listen(): Heard = coroutineScope {
        val session = app.voice.start()
        val startedAt = System.currentTimeMillis()
        var lastActivity = startedAt
        var spoke = false
        var text = ""
        var finalText: String? = null
        val ticker = launch {
            while (true) {
                delay(150)
                val now = System.currentTimeMillis()
                val quiet = now - lastActivity
                if ((spoke && quiet > END_SILENCE_MS) || (!spoke && now - startedAt > NO_SPEECH_MS) || now - startedAt > MAX_UTTERANCE_MS) {
                    session.release()
                    return@launch
                }
            }
        }
        try {
            var failure: SpeechFailure? = null
            session.events.collect { event ->
                when (event) {
                    is SpeechEvent.Level -> if (event.value > VOICE_LEVEL) { spoke = true; lastActivity = System.currentTimeMillis() }
                    is SpeechEvent.Partial -> {
                        val t = (event.stable + event.tail).trim()
                        if (t.isNotEmpty()) {
                            if (t != text) lastActivity = System.currentTimeMillis()
                            spoke = true
                            text = t
                            _transcript.value = t
                        }
                    }
                    is SpeechEvent.Final -> finalText = event.text.trim()
                    is SpeechEvent.Failed -> failure = event.why
                    SpeechEvent.Converting -> lastActivity = System.currentTimeMillis()
                }
            }
            val result = finalText ?: text
            when {
                result.isNotEmpty() -> Heard.Text(result)
                failure != null -> Heard.Failed(failure!!)
                else -> Heard.Silence
            }
        } finally {
            ticker.cancel()
            session.cancel()
        }
    }

    // ---- 想 ----

    /** 把话交给 Mika 的任务系统,等它给出回答。需要屏幕、确认或接管时不在车里硬做:停下并说明。 */
    private suspend fun ask(spoken: String): String {
        val id = conversationId?.takeIf { System.currentTimeMillis() - lastTurnAt < CONVERSATION_IDLE_MS }
            ?: app.conversations.newConversation().also { conversationId = it }
        val tasks = app.taskState
        var seen: TaskState = tasks.state.value
        TaskService.start(app, "${if (driving) DRIVING_PREAMBLE else VOICE_PREAMBLE}\n\n$spoken", id)
        while (true) {
            val state = withTimeoutOrNull(TASK_TIMEOUT_MS) {
                combine(tasks.state, tasks.conversationId) { s, c -> s to c }
                    .filter { (s, c) -> c == id && s !== seen && s.needsAttention() }
                    .first().first
            } ?: run {
                TaskService.cancel(app)
                return "That is taking too long, so I've stopped."
            }
            seen = state
            when (state) {
                is TaskState.Succeeded -> return state.answer.ifBlank { "Done." }
                is TaskState.Failed -> return "I couldn't do that. ${state.reason}"
                TaskState.Cancelled -> return "Cancelled."
                is TaskState.WaitingInput -> {
                    // 模型在提问:念出来,再听一次回答转给任务
                    setState(DrivingState.SPEAKING)
                    speakPlain(state.prompt)
                    setState(DrivingState.LISTENING)
                    when (val reply = listen()) {
                        is Heard.Text -> {
                            _transcript.value = reply.text
                            setState(DrivingState.PROCESSING)
                            TaskService.answer(app, reply.text)
                        }
                        else -> {
                            TaskService.cancel(app)
                            return reply.failureMessage()
                        }
                    }
                }
                else -> {
                    // 确认花钱、接管、被卡住:行车中不自动同意任何东西
                    TaskService.cancel(app)
                    return "That needs you at the phone, so I've stopped. Please pull over and open Mika."
                }
            }
        }
    }

    private fun TaskState.needsAttention() = this is TaskState.Terminal || this is TaskState.WaitingInput ||
        this is TaskState.WaitingTakeover || this is TaskState.WaitingConfirmation || (this is TaskState.Held && reason.needsYou)

    // ---- 说 ----

    /** 一句话照常朗读(错误提示、追问);同时作为 [response] 显示。 */
    private suspend fun speakPlain(text: String) {
        setState(DrivingState.SPEAKING)
        _response.value = text
        tts.speak(text, isFinal = true)
    }

    /** 回答逐句送进 [JarvisTTS]:第一句播的时候后面几句已经在合成;[response] 随每句开始播而增长。 */
    private suspend fun speakAnswer(text: String) {
        val sentences = SentenceSplitter.split(text)
        if (sentences.isEmpty()) {
            speakPlain("Done.")
            return
        }
        _response.value = ""
        val last = sentences.mapIndexed { i, sentence ->
            tts.enqueue(sentence, isFinal = i == sentences.lastIndex) {
                _response.update { if (it.isEmpty()) sentence else "$it $sentence" }
            }
        }.last()
        last.await()
        _response.value = sentences.joinToString(" ")
    }

    companion object {
        private const val TAG = "JarvisDriving"
        const val ACTION_START_LISTENING = "com.wangzan101.mika.DRIVING_LISTEN"
        const val ACTION_INTERRUPT = "com.wangzan101.mika.DRIVING_INTERRUPT"
        private const val ACTION_STOP = "com.wangzan101.mika.DRIVING_STOP"

        private const val VOICE_LEVEL = 0.25f
        private const val END_SILENCE_MS = 1_500L
        private const val NO_SPEECH_MS = 8_000L
        private const val MAX_UTTERANCE_MS = 30_000L
        private const val TASK_TIMEOUT_MS = 3 * 60_000L
        private const val CONVERSATION_IDLE_MS = 10 * 60_000L

        /** 常驻听唤醒词、不是在开车时写在话前面的提示:回答要说出来,所以要短、纯文字。 */
        private const val VOICE_PREAMBLE =
            "[Voice assistant: the user spoke this request and hears your reply read aloud. " +
                "Answer in one to three short spoken sentences, plain text only, no lists or markdown.]"

        /** 开车时写在话前面,让 Mika 知道用户在开车、回答是用耳朵听的。 */
        private const val DRIVING_PREAMBLE =
            "[Driving mode: the user is driving and hears your reply through the car speakers. " +
                "Answer in one to three short spoken sentences, plain text only, no lists or markdown. " +
                "Do not start anything that needs the user to look at or touch the screen; " +
                "if the request needs that, say so briefly and offer to do it when they have stopped.]"

        private val _state = MutableStateFlow(DrivingState.IDLE)
        val state: StateFlow<DrivingState> = _state.asStateFlow()

        private val _transcript = MutableStateFlow("")
        /** 听到的用户原话。 */
        val transcript: StateFlow<String> = _transcript.asStateFlow()

        private val _response = MutableStateFlow("")
        /** Jarvis 的回答;朗读时随每一句开始播而增长。 */
        val response: StateFlow<String> = _response.asStateFlow()

        private val _running = MutableStateFlow(false)
        private var running: Boolean
            get() = _running.value
            set(v) { _running.value = v }

        /** 服务是否在运行(界面据此决定要不要先起服务)。 */
        val isRunning: Boolean get() = running

        /** 同上,给界面观察。 */
        val runningFlow: StateFlow<Boolean> = _running.asStateFlow()

        private val _wakeError = MutableStateFlow<String?>(null)
        /** 唤醒词起不来的原因(模型没下好、没权限……);正常或没开唤醒词时为 null。 */
        val wakeError: StateFlow<String?> = _wakeError.asStateFlow()

        /**
         * 起服务。没有录音权限时不起(麦克风类型的前台服务没权限会直接抛异常),改发通知请用户去授权;
         * 系统不让后台起时改发「点一下开始」的通知。
         */
        fun start(context: Context, action: String? = null, prompt: Boolean = true) {
            val app = context.applicationContext
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                if (prompt) DrivingNotifications.promptSettings(app, "Jarvis needs microphone permission. Tap to grant it.")
                return
            }
            try {
                ContextCompat.startForegroundService(app, Intent(app, DrivingService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start from background", e)
                if (prompt) DrivingNotifications.promptOpenDriving(app)
            }
        }

        /**
         * 「常驻」打开时,Mika 进程一起来就让 Jarvis 在后台听(MikaApp.onCreate 调)。
         * 进程可能是被开机广播等后台事件拉起来的,那时安卓不让起麦克风前台服务:静默失败,不发通知(开机另有提示)。
         */
        fun startIfAlwaysOn(context: Context) {
            val app = context.applicationContext
            if (running || !DrivingPrefs.get(app).alwaysOn) return
            start(app, prompt = false)
        }

        /**
         * 此刻是不是真的在开车(打开了驾驶界面,或车载蓝牙触发):只影响写给 Mika 的提示语,
         * 开车时要求回答简短、不做要看屏幕的事;平时常驻听唤醒词时不加这个限制。
         */
        @Volatile
        var driving: Boolean = false

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, DrivingService::class.java))
        }

        /** 给 DrivingScreen、车机屏发动作:服务没在跑就先起。 */
        fun send(context: Context, action: String) {
            val app = context.applicationContext
            if (!running) {
                if (action == ACTION_START_LISTENING) start(app, action)
                return
            }
            app.startService(Intent(app, DrivingService::class.java).setAction(action))
        }
    }
}
