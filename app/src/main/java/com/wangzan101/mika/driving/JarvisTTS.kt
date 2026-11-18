package com.wangzan101.mika.driving

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * 离线的 Jarvis 声音:sherpa-onnx,Kokoro 或 Piper 英式男声(en_GB-alan-medium),模型见 [KokoroModel]、[JarvisModel]。
 *
 * 一句话一次 `generate()`,按先后进队列:第一句在播的时候,下一句已经在后台合成(流水线),缩短句间停顿。
 * 模型还没下好、或引擎起不来时,退回安卓自带的 TextToSpeech,朗读不会因此哑掉。
 *
 * 声音:默认 Kokoro(bm_george,自然但算得慢),手机跑不过时自动退回 Piper(见 [watchKokoroSpeed]);语速在「Jarvis Driving」页里调。
 */
class JarvisTTS(
    context: Context,
    /** 一轮回答的最后一句([speak] 的 `isFinal = true`)播完时调。 */
    private val onSpeakingDone: () -> Unit = {},
) {
    private val app = context.applicationContext

    /** 语速。每次开口前按设置里的值刷新(见 [DrivingPrefs.voiceSpeed]);也可以直接改。 */
    @Volatile
    var speed: Float = DrivingPrefs.get(context).voiceSpeed

    private class Item(val text: String, val isFinal: Boolean, val onStart: (() -> Unit)?) {
        val done = CompletableDeferred<Boolean>()
    }

    private sealed interface Clip {
        val item: Item
        class Audio(override val item: Item, val samples: FloatArray, val sampleRate: Int) : Clip
        class Fallback(override val item: Item) : Clip
    }

    private inner class Session {
        val requests = Channel<Item>(Channel.UNLIMITED)
        /** 容量 1:正在播一句、最多再备好一句、再多一句在合成,不无限往前算。 */
        val ready = Channel<Clip>(1)
        val pending = ConcurrentLinkedQueue<Item>()
        lateinit var job: Job
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var session: Session? = null

    private val engineLock = Any()
    private var piper: OfflineTts? = null
    private var piperFailed = false
    private var kokoro: OfflineTts? = null
    private var kokoroFailed = false
    /** Kokoro 已经出过几句(第一句含加载后的热身,不计入速度判断)和连续几句跑不过播放。 */
    private var kokoroClips = 0
    private var slowStreak = 0

    @Volatile
    private var track: AudioTrack? = null
    private var fallback: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private val audioManager get() = app.getSystemService(AudioManager::class.java)

    /** 排进朗读队列并立刻返回;结果为 true = 这一句播完了,false = 被 [stop] 打断。[onStart] 在这一句开始播时调。 */
    fun enqueue(text: String, isFinal: Boolean = false, onStart: (() -> Unit)? = null): Deferred<Boolean> {
        speed = DrivingPrefs.get(app).speedFor(VoiceEngine.Piper) // 只给系统朗读的退路用;sherpa 的声音按各自的语速
        val item = Item(SentenceSplitter.clean(text), isFinal, onStart)
        if (item.text.isEmpty()) {
            if (isFinal) onSpeakingDone()
            return item.done.also { it.complete(true) }
        }
        synchronized(lock) {
            val s = session ?: newSession().also { session = it }
            s.pending += item
            s.requests.trySend(item)
        }
        return item.done
    }

    /** 朗读 [text],挂起到播完。返回 false 表示被 [stop] 打断。 */
    suspend fun speak(text: String, isFinal: Boolean): Boolean = enqueue(text, isFinal).await()

    /** 马上停声、丢掉还没播的,取消正在合成的。 */
    fun stop() {
        val s = synchronized(lock) { session.also { session = null } } ?: return
        s.job.cancel()
        // AudioTrack.write 阻塞时协程取消不了它,从这里把轨道停住让写调用返回
        track?.let { runCatching { it.pause(); it.flush() } }
        fallback?.stop()
        while (true) s.requests.tryReceive().getOrNull() ?: break
        s.pending.forEach { it.done.complete(false) }
        s.pending.clear()
    }

    fun shutdown() {
        stop()
        scope.cancel()
        // 合成可能还在原生层:等它放开锁再释放
        Thread {
            synchronized(engineLock) {
                piper?.release()
                piper = null
                kokoro?.release()
                kokoro = null
            }
        }.start()
        fallback?.shutdown()
        fallback = null
    }

    private fun newSession(): Session {
        val s = Session()
        s.job = scope.launch {
            launch {
                for (item in s.requests) s.ready.send(generate(item))
            }
            launch(Dispatchers.IO) {
                for (clip in s.ready) play(s, clip)
            }
        }
        return s
    }

    // ---- 合成 ----

    private fun generate(item: Item): Clip {
        val prefs = DrivingPrefs.get(app)
        val audio = runCatching {
            synchronized(engineLock) {
                val (kind, tts) = pickEngine(prefs) ?: return@runCatching null
                val started = SystemClock.elapsedRealtime()
                val out = tts.generate(
                    text = item.text,
                    sid = if (kind == VoiceEngine.Kokoro) KokoroModel.SPEAKER_ID else 0,
                    speed = prefs.speedFor(kind),
                )
                if (kind == VoiceEngine.Kokoro) watchKokoroSpeed(prefs, out.samples.size.toFloat() / out.sampleRate, SystemClock.elapsedRealtime() - started)
                out
            }
        }.onFailure { Log.w(TAG, "generate failed, using fallback voice", it) }.getOrNull()
        return if (audio != null && audio.samples.isNotEmpty()) Clip.Audio(item, audio.samples, audio.sampleRate) else Clip.Fallback(item)
    }

    /**
     * Kokoro 算得比播放慢,句子之间就会断断续续。第一句不算(刚加载完),之后连续两句(音频至少 1.5 秒)
     * 合成用时超过 [SLOW_RTF] 倍音频时长,就记下「太慢」,往后用 Piper;设置里重新选 Kokoro 会再试。
     */
    private fun watchKokoroSpeed(prefs: DrivingPrefs, audioSeconds: Float, tookMs: Long) {
        if (++kokoroClips == 1 || audioSeconds < 1.5f) return
        val rtf = tookMs / 1000f / audioSeconds
        slowStreak = if (rtf > SLOW_RTF) slowStreak + 1 else 0
        Log.d(TAG, "kokoro rtf=%.2f".format(rtf))
        if (slowStreak >= 2) {
            Log.w(TAG, "Kokoro is too slow on this phone (rtf=%.2f); switching to Piper".format(rtf))
            prefs.kokoroTooSlow = true
        }
    }

    /** 调用方持有 [engineLock]。选中且可用的 Kokoro 优先;其次 Piper;都没有返回 null(退回系统朗读)。 */
    private fun pickEngine(prefs: DrivingPrefs): Pair<VoiceEngine, OfflineTts>? {
        if (prefs.voiceEngine == VoiceEngine.Kokoro && !prefs.kokoroTooSlow) kokoro()?.let { return VoiceEngine.Kokoro to it }
        return piper()?.let { VoiceEngine.Piper to it }
    }

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun piper(): OfflineTts? {
        piper?.let { return it }
        if (piperFailed || !JarvisModel.isReady(app)) return null
        val dir = JarvisModel.dir(app)
        val vits = OfflineTtsVitsModelConfig(
            model = File(dir, JarvisModel.ONNX).path,
            lexicon = "",
            tokens = File(dir, JarvisModel.TOKENS).path,
            dataDir = File(dir, JarvisModel.ESPEAK_DIR).path,
            dictDir = "",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f,
        )
        return create("Piper", OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"))) {
            piperFailed = true
        }?.also { piper = it }
    }

    private fun kokoro(): OfflineTts? {
        kokoro?.let { return it }
        if (kokoroFailed || !KokoroModel.isReady(app)) return null
        val dir = KokoroModel.dir(app)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, KokoroModel.MODEL).path,
                    voices = File(dir, KokoroModel.VOICES).path,
                    tokens = File(dir, KokoroModel.TOKENS).path,
                    dataDir = File(dir, KokoroModel.ESPEAK_DIR).path,
                    lexicon = File(dir, KokoroModel.LEXICON).path,
                ),
                numThreads = threads(), debug = false, provider = "cpu",
            ),
        )
        return create("Kokoro", config) { kokoroFailed = true }?.also { kokoro = it }
    }

    private fun create(name: String, config: OfflineTtsConfig, onFail: () -> Unit): OfflineTts? = try {
        OfflineTts(config = config)
    } catch (e: Throwable) {
        Log.w(TAG, "$name init failed", e)
        onFail()
        null
    }

    // ---- 播放 ----

    private suspend fun play(s: Session, clip: Clip) {
        val item = clip.item
        var completed = false
        try {
            requestFocus()
            item.onStart?.invoke()
            when (clip) {
                is Clip.Audio -> playAudio(clip)
                is Clip.Fallback -> speakFallback(clip.item.text)
            }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "playback failed", e)
        } finally {
            s.pending.remove(item)
            item.done.complete(completed)
            if (item.isFinal || s.pending.isEmpty()) abandonFocus()
        }
        if (item.isFinal && completed) onSpeakingDone()
    }

    private suspend fun playAudio(clip: Clip.Audio) {
        val rate = clip.sampleRate
        val samples = clip.samples
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, rate * 4 / 2)) // 至少半秒
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            var offset = 0
            while (offset < samples.size) {
                coroutineContext.ensureActive()
                val n = t.write(samples, offset, minOf(CHUNK, samples.size - offset), AudioTrack.WRITE_BLOCKING)
                if (n < 0) throw IOException("AudioTrack.write failed: $n")
                offset += n
            }
            // 等缓冲里的声音真正放完;设个上限,轨道卡住时不会永远挂着
            var waitedMs = 0L
            val limitMs = samples.size * 1000L / rate + 2_000
            while (t.playbackHeadPosition < samples.size && waitedMs < limitMs) {
                delay(15)
                waitedMs += 15
            }
        } finally {
            if (track === t) track = null
            runCatching { t.pause(); t.flush() }
            t.release()
        }
    }

    private suspend fun speakFallback(text: String) {
        val tts = fallbackEngine() ?: throw IOException("no text-to-speech engine")
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { tts.stop() }
            tts.setSpeechRate(speed)
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
    }

    private suspend fun fallbackEngine(): TextToSpeech? {
        fallback?.let { return it }
        return suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(app) { status ->
                val engine = created
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    engine.language = Locale.UK
                    engine.setAudioAttributes(ATTRIBUTES)
                    fallback = engine
                    if (cont.isActive) cont.resume(engine)
                } else {
                    engine?.shutdown()
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(ATTRIBUTES)
            .build()
        if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }

    private fun abandonFocus() {
        focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focus = null
    }

    companion object {
        private const val TAG = "JarvisTTS"

        /** 默认语速。这个声音原速偏慢,稍微加快听着更自然;设置页里可以调。 */
        const val DEFAULT_SPEED = 1.15f
        /** Kokoro 原速就自然,不用加快。 */
        const val DEFAULT_KOKORO_SPEED = 1.0f
        /** 合成用时 / 音频时长超过它就算跑不过播放。 */
        private const val SLOW_RTF = 1.1f
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.6f

        private const val CHUNK = 4096

        private val ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
