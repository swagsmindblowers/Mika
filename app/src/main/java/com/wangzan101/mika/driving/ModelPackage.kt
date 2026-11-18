package com.wangzan101.mika.driving

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.wangzan101.mika.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一个模型包(下载、解压、就绪)的状态。 */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    /** [percent] 为 -1 表示总大小未知。 */
    data class Downloading(val percent: Int) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val reason: String) : ModelStatus
    /** 要求只在 Wi-Fi 下载,而当前是计费网络。 */
    data object WaitingForWifi : ModelStatus
}

/**
 * 一个 `.tar.bz2` 模型包:下载到 `filesDir/<dirName>/`(去掉最外层目录 [topDir])。
 * [ensure] 幂等,已经就绪或正在下载都直接返回;解压先进临时目录、全部成功才改名,中途断了不会留下半个模型。
 * [keep] 决定包里哪些文件要留(默认全留)。
 */
internal class ModelPackage(
    private val url: String,
    private val topDir: String,
    private val dirName: String,
    /** 就绪时必须存在的相对路径(文件或目录)。 */
    private val required: List<String>,
    private val minBytes: Long,
    private val notificationId: Int,
    private val title: String,
    private val keep: (relativePath: String) -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Missing)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    fun dir(context: Context) = File(context.filesDir, dirName)

    fun isReady(context: Context): Boolean {
        val d = dir(context)
        return File(d, READY_MARK).isFile && required.all { File(d, it).exists() }
    }

    @Synchronized
    fun ensure(context: Context) {
        val app = context.applicationContext
        if (isReady(app)) {
            _status.value = ModelStatus.Ready
            return
        }
        if (job?.isActive == true) return
        if (DrivingPrefs.get(app).wifiOnlyDownload && isMetered(app)) {
            _status.value = ModelStatus.WaitingForWifi
            return
        }
        job = scope.launch {
            _status.value = ModelStatus.Downloading(0)
            try {
                download(app)
                _status.value = ModelStatus.Ready
                notifyDone(app, "$title ready", "Downloaded.")
            } catch (e: Exception) {
                if (!isActive) throw e
                Log.w(TAG, "$title download failed", e)
                _status.value = ModelStatus.Failed(e.message ?: e.javaClass.simpleName)
                notifyDone(app, "$title download failed", "Will retry next time driving mode starts.")
            }
        }
    }

    private fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return true) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun download(context: Context) {
        val root = dir(context)
        val parent = root.parentFile!!
        val archive = File(parent, "$dirName-download.tar.bz2")
        val staging = File(parent, "$dirName-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            val total = body.contentLength()
            var done = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPercent) {
                                lastPercent = pct
                                _status.value = ModelStatus.Downloading(pct)
                                notifyProgress(context, pct)
                            }
                        }
                    }
                }
            }
            if (archive.length() < minBytes) throw IOException("download too small (${archive.length()} bytes)")
        }
        notifyProgress(context, -1)
        try {
            extract(archive, staging)
            val missing = required.filterNot { File(staging, it).exists() }
            if (missing.isNotEmpty()) throw IOException("model archive is missing: $missing")
            File(staging, READY_MARK).writeText("1")
            root.deleteRecursively()
            if (!staging.renameTo(root)) throw IOException("cannot move model into place")
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    /** 解 tar.bz2 到 [into],去掉最外层目录;拒绝跳出目标目录的条目。 */
    private fun extract(archive: File, into: File) {
        val base = into.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").removePrefix(topDir)
                if (name.isEmpty() || !entry.isDirectory && !entry.isFile) continue
                val target = File(base, name).canonicalFile
                if (target != base && !target.path.startsWith(base.path + File.separator)) throw IOException("unsafe path in archive: ${entry.name}")
                if (!keep(name.trimEnd('/'))) continue
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { tar.copyTo(it) }
                }
            }
        }
    }

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis model downloads", NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun notifyProgress(context: Context, percent: Int) {
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading $title")
            .setContentText(if (percent >= 0) "$percent%" else "Unpacking…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
        runCatching { manager(context).notify(notificationId, n) }
    }

    private fun notifyDone(context: Context, heading: String, text: String) {
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { manager(context).notify(notificationId, n) }
    }

    private companion object {
        const val TAG = "JarvisModel"
        const val CHANNEL = "jarvis_model"
        const val READY_MARK = ".ready"
    }
}
