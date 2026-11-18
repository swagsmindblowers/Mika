package com.wangzan101.mika.driving

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 驾驶模式的入口页(独立的桌面图标「Jarvis Driving」):授权、开关、声音模型状态,
 * 以及不用真的连车机就能试一遍蓝牙触发的按钮。Mika 原有的设置页不动。
 */
class DrivingSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsContent() }
    }

    @Composable
    private fun SettingsContent() {
        val prefs = remember { DrivingPrefs.get(this) }
        var refresh by remember { mutableIntStateOf(0) }
        var wakeWord by remember { mutableStateOf(prefs.wakeWordEnabled) }
        var btTrigger by remember { mutableStateOf(prefs.bluetoothTriggerEnabled) }
        var btAny by remember { mutableStateOf(prefs.bluetoothAnyAudioDevice) }
        var wifiOnly by remember { mutableStateOf(prefs.wifiOnlyDownload) }
        var alwaysOn by remember { mutableStateOf(prefs.alwaysOn) }
        var engine by remember { mutableStateOf(prefs.voiceEngine) }
        var speed by remember { mutableFloatStateOf(prefs.voiceSpeed) }
        val kokoroModel by KokoroModel.status.collectAsState()
        val serviceRunning by DrivingService.runningFlow.collectAsState()
        val wakeError by DrivingService.wakeError.collectAsState()
        val model by JarvisModel.status.collectAsState()
        val wakeModel by WakeWordModel.status.collectAsState()
        val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter { refresh >= 0 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val modelReady = refresh >= 0 && JarvisModel.isReady(this)
        val kokoroReady = refresh >= 0 && KokoroModel.isReady(this)
        val tooSlow = refresh >= 0 && prefs.kokoroTooSlow
        val wakeModelReady = refresh >= 0 && WakeWordModel.isReady(this)

        Column(
            Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Jarvis Driving", color = Color(0xFF00E5FF), fontSize = 28.sp, fontWeight = FontWeight.Bold)

            Section("Permissions")
            if (missing.isEmpty()) {
                Note("Microphone, Bluetooth and notifications are all granted.")
            } else {
                Note("Missing: " + missing.joinToString { it.substringAfterLast('.') })
                Button(onClick = { requestPermissions.launch(missing.toTypedArray()) }) { Text("Grant permissions") }
            }

            Section("Always listening")
            ToggleRow("Listen for \"Hey Jarvis\" in the background", alwaysOn) {
                alwaysOn = it
                prefs.alwaysOn = it
                if (it) DrivingService.start(this@DrivingSettingsActivity) else DrivingService.stop(this@DrivingSettingsActivity)
            }
            Note(
                when {
                    serviceRunning && wakeError != null -> "Running, but the wake word is off: $wakeError"
                    serviceRunning && wakeWord -> "Listening now. The microphone stays on and uses battery."
                    serviceRunning -> "Running (wake word turned off below; use the SPEAK button)."
                    alwaysOn -> "Not running right now. It starts when Mika opens; after a reboot tap the notification."
                    else -> "Off."
                },
            )
            if (!serviceRunning) {
                Button(onClick = { DrivingService.start(this@DrivingSettingsActivity) }) { Text("Start listening now") }
            }

            Section("Wake word")
            ToggleRow("Listen for \"Jarvis\"", wakeWord) { wakeWord = it; prefs.wakeWordEnabled = it }
            Note(
                when {
                    wakeModelReady -> "Wake word model installed. Say \"Hey Jarvis\" or \"Jarvis\"."
                    wakeModel is ModelStatus.Downloading -> "Downloading wake word model… ${(wakeModel as ModelStatus.Downloading).percent}%"
                    wakeModel is ModelStatus.WaitingForWifi -> "Waiting for Wi-Fi (about 17 MB)."
                    wakeModel is ModelStatus.Failed -> "Wake word download failed: ${(wakeModel as ModelStatus.Failed).reason}"
                    else -> "Wake word model not installed (about 17 MB). Use the SPEAK button until it is."
                },
            )
            if (!wakeModelReady) Button(onClick = { WakeWordModel.ensure(this@DrivingSettingsActivity); refresh++ }) { Text("Download wake word model") }

            Section("Bluetooth trigger")
            ToggleRow("Start when a car connects", btTrigger) { btTrigger = it; prefs.bluetoothTriggerEnabled = it }
            ToggleRow("Any Bluetooth audio device counts (includes headphones)", btAny) { btAny = it; prefs.bluetoothAnyAudioDevice = it }
            OutlinedButton(onClick = { BluetoothTrigger.fire(this@DrivingSettingsActivity) }) { Text("Simulate car connecting") }

            Section("Jarvis voice")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in VoiceEngine.entries) {
                    val label = if (e == VoiceEngine.Kokoro) "Natural (Kokoro)" else "Fast (Piper)"
                    if (engine == e) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = {
                        prefs.voiceEngine = e
                        engine = e
                        speed = prefs.voiceSpeed
                        if (e == VoiceEngine.Kokoro) KokoroModel.ensure(this@DrivingSettingsActivity)
                        refresh++
                    }) { Text(label) }
                }
            }
            Note(
                when {
                    engine == VoiceEngine.Kokoro && tooSlow -> "Kokoro was too slow on this phone, so Jarvis switched to the fast voice. Tap Natural again to retry."
                    engine == VoiceEngine.Kokoro && kokoroReady -> "Natural voice installed. If it can't keep up with playback on this phone, Jarvis falls back to the fast voice automatically."
                    engine == VoiceEngine.Kokoro && kokoroModel is ModelStatus.Downloading -> "Downloading natural voice… ${(kokoroModel as ModelStatus.Downloading).percent}% (about 132 MB). The fast voice is used meanwhile."
                    engine == VoiceEngine.Kokoro && kokoroModel is ModelStatus.WaitingForWifi -> "Waiting for Wi-Fi (about 132 MB). The fast voice is used meanwhile."
                    engine == VoiceEngine.Kokoro && kokoroModel is ModelStatus.Failed -> "Natural voice download failed: ${(kokoroModel as ModelStatus.Failed).reason}"
                    engine == VoiceEngine.Kokoro -> "Natural voice not installed (about 132 MB)."
                    else -> "Using the fast voice."
                },
            )
            if (engine == VoiceEngine.Kokoro && !kokoroReady) {
                Button(onClick = { KokoroModel.ensure(this@DrivingSettingsActivity); refresh++ }) { Text("Download natural voice") }
            }
            Note(
                when {
                    modelReady -> "Fast voice installed."
                    model is ModelStatus.Downloading -> "Downloading fast voice… ${(model as ModelStatus.Downloading).percent}%"
                    model is ModelStatus.WaitingForWifi -> "Fast voice: waiting for Wi-Fi (about 67 MB)."
                    model is ModelStatus.Failed -> "Fast voice download failed: ${(model as ModelStatus.Failed).reason}"
                    else -> "Fast voice not installed (about 67 MB). The phone's built-in voice is used until a voice is installed."
                },
            )
            Note("Speaking speed: ${"%.2f".format(speed)}x")
            Slider(
                value = speed, onValueChange = { speed = it },
                onValueChangeFinished = { prefs.voiceSpeed = speed },
                valueRange = JarvisTTS.MIN_SPEED..JarvisTTS.MAX_SPEED,
            )
            OutlinedButton(onClick = { testVoice(speed) }) { Text("Test voice") }
            ToggleRow("Download on Wi-Fi only", wifiOnly) { wifiOnly = it; prefs.wifiOnlyDownload = it }
            if (!modelReady) Button(onClick = { JarvisModel.ensure(this@DrivingSettingsActivity); refresh++ }) { Text("Download fast voice") }

            Section("Driving mode")
            Button(onClick = { startActivity(Intent(this@DrivingSettingsActivity, DrivingActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start driving mode")
            }
        }
    }

    private var testTts: JarvisTTS? = null

    /** 按此刻滑块上的速度念一句,听听效果。 */
    private fun testVoice(speed: Float) {
        DrivingPrefs.get(this).voiceSpeed = speed
        val tts = testTts ?: JarvisTTS(this).also { testTts = it }
        tts.stop()
        tts.enqueue("Good evening. Your next meeting is at three p.m., and the traffic on your route looks clear.", isFinal = true)
    }

    override fun onDestroy() {
        testTts?.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun Section(title: String) = Text(title.uppercase(), color = Color(0xFF8A8A8A), fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 12.dp))

    @Composable
    private fun Note(text: String) = Text(text, color = Color.White, fontSize = 15.sp)

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
