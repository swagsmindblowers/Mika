package com.wangzan101.mika.driving

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Kokoro-82M 的 int8 版(sherpa-onnx `kokoro-int8-multi-lang-v1_0`,Apache-2.0):比 Piper 自然得多,
 * 但算得慢(桌面 CPU 上也只是接近实时,手机上更慢),所以 [JarvisTTS] 会在连续两句跑不过播放速度时自动退回 Piper。
 * 发布包约 132 MB;只留英文用得上的文件(约 170 MB):模型、音色表、tokens、英式词典和 espeak-ng-data。
 */
object KokoroModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
    const val MODEL = "model.int8.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val LEXICON = "lexicon-gb-en.txt"
    const val ESPEAK_DIR = "espeak-ng-data"

    /** 音色表里的 bm_george(英式男声)。其余英式男声:24 bm_daniel、25 bm_fable、27 bm_lewis。 */
    const val SPEAKER_ID = 26

    private val pkg = ModelPackage(
        url = URL,
        topDir = "kokoro-int8-multi-lang-v1_0/",
        dirName = "kokoro",
        required = listOf(MODEL, VOICES, TOKENS, LEXICON, ESPEAK_DIR),
        minBytes = 50L * 1024 * 1024,
        notificationId = 0x4A43,
        title = "Jarvis natural voice",
        keep = { it == MODEL || it == VOICES || it == TOKENS || it == LEXICON || it.startsWith("$ESPEAK_DIR/") || it == ESPEAK_DIR },
    )

    val status: StateFlow<ModelStatus> get() = pkg.status
    fun dir(context: Context) = pkg.dir(context)
    fun isReady(context: Context) = pkg.isReady(context)
    fun ensure(context: Context) = pkg.ensure(context)
}
