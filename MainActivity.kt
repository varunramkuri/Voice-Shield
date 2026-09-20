package com.jackswarriors.voiceshield

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.audio.AudioParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random // NEW IMPORT

class MainActivity : ComponentActivity() {

    private val appId = "YOUR_AGORA_APP_ID"
    private var rtcEngine: RtcEngine? = null

    // MAKE SURE NGROK URL IS ACTIVE
    private val serverBaseUrl = "YOUR_BACKEND_SERVER_URL"
    private val httpClient = OkHttpClient.Builder().build()

    private val audioLock = Any()
    private val audioBuffer = ByteArrayOutputStream()
    private val targetAudioBytes = 16000 * 1 * 2 * 15

    private val uploadRunning = AtomicBoolean(false)
    private val hasAnalyzedInitialAudio = AtomicBoolean(false)
    private val remoteUserJoined = AtomicBoolean(false)

    // Caller/Receiver Flag
    private val isCallerMode = AtomicBoolean(false)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _riskScore = mutableStateOf(0.0)
    private val _verdict = mutableStateOf("WAITING")
    private val _analysisStatus = mutableStateOf("Waiting to join...")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                setupAgoraEngine()
            } else {
                Toast.makeText(this, "Microphone permission is required!", Toast.LENGTH_LONG).show()
            }
        }

    private val audioFrameObserver = object : IAudioFrameObserver {
        override fun onRecordAudioFrame(
            channel: String, audioFrameType: Int, samples: Int, bytesPerSample: Int,
            channels: Int, samplesPerSec: Int, byteBuffer: ByteBuffer, renderTimeMs: Long, bufferLength: Int
        ): Boolean { return true }

        override fun onPlaybackAudioFrame(
            channel: String, audioFrameType: Int, samples: Int, bytesPerSample: Int,
            channels: Int, samplesPerSec: Int, byteBuffer: ByteBuffer, renderTimeMs: Long, bufferLength: Int
        ): Boolean {
            try {
                if (isCallerMode.get()) return true
                if (!remoteUserJoined.get()) return true
                if (hasAnalyzedInitialAudio.get()) return true
                if (bytesPerSample != 2) return true

                val duplicate = byteBuffer.duplicate()
                val bytes = ByteArray(duplicate.remaining())
                duplicate.get(bytes)

                synchronized(audioLock) {
                    audioBuffer.write(bytes)
                    if (audioBuffer.size() >= targetAudioBytes) {
                        if (uploadRunning.compareAndSet(false, true)) {
                            val pcmData = audioBuffer.toByteArray()
                            audioBuffer.reset()
                            hasAnalyzedInitialAudio.set(true)

                            ioScope.launch {
                                try {
                                    analyzeAudioChunk(pcmData, samplesPerSec, channels)
                                } catch (e: Exception) {
                                    Log.e("VoiceShield", "Audio analysis error", e)
                                } finally {
                                    uploadRunning.set(false)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceShield", "Playback audio error", e)
            }
            return true
        }

        override fun onMixedAudioFrame(
            channel: String, audioFrameType: Int, samples: Int, bytesPerSample: Int,
            channels: Int, samplesPerSec: Int, byteBuffer: ByteBuffer, renderTimeMs: Long, bufferLength: Int
        ): Boolean { return true }

        override fun onEarMonitoringAudioFrame(
            type: Int, samplesPerChannel: Int, bytesPerSample: Int, channels: Int,
            samplesPerSec: Int, buffer: ByteBuffer, renderTimeMs: Long, avsyncType: Int
        ): Boolean { return true }

        override fun onPlaybackAudioFrameBeforeMixing(
            channelId: String, userId: Int, type: Int, samplesPerChannel: Int, bytesPerSample: Int,
            channels: Int, samplesPerSec: Int, buffer: ByteBuffer, renderTimeMs: Long, avsync_type: Int
        ): Boolean { return true }

        override fun getObservedAudioFramePosition(): Int { return 0 }

        override fun getRecordAudioParams(): AudioParams = AudioParams(16000, 1, Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY, 1024)
        override fun getPlaybackAudioParams(): AudioParams = AudioParams(16000, 1, Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY, 1024)
        override fun getMixedAudioParams(): AudioParams? = null
        override fun getEarMonitoringAudioParams(): AudioParams? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            setupAgoraEngine()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    VoiceShieldApp(
                        riskScore = _riskScore.value,
                        verdict = _verdict.value,
                        analysisStatus = _analysisStatus.value,
                        onJoinCall = { channelName, isCaller -> joinChannel(channelName, isCaller) },
                        onLeaveCall = { leaveChannel() },
                        // NEW: పాసింగ్ ఫంక్షన్స్ టు ప్లే AI వాయిస్
                        onPlayAIVoice = { playRandomFakeAIVoice() },
                        onStopAIVoice = { stopFakeAIVoice() }
                    )
                }
            }
        }
    }

    private fun setupAgoraEngine() {
        try {
            val config = RtcEngineConfig()
            config.mContext = applicationContext
            config.mAppId = appId
            config.mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {}
                override fun onUserJoined(uid: Int, elapsed: Int) {
                    remoteUserJoined.set(true)
                    hasAnalyzedInitialAudio.set(false)
                    Handler(Looper.getMainLooper()).post {
                        if (!isCallerMode.get()) {
                            _analysisStatus.value = "Friend joined! Recording their 15s voice..."
                        }
                    }
                }
                override fun onUserOffline(uid: Int, reason: Int) {
                    remoteUserJoined.set(false)
                    Handler(Looper.getMainLooper()).post {
                        _analysisStatus.value = "Friend left the call."
                    }
                }
            }
            rtcEngine = RtcEngine.create(config)
            rtcEngine?.enableAudio()
            rtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)
            rtcEngine?.registerAudioFrameObserver(audioFrameObserver)
            rtcEngine?.setPlaybackAudioFrameParameters(16000, 1, Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY, 1024)
            rtcEngine?.setRecordingAudioFrameParameters(16000, 1, Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY, 1024)
        } catch (e: Exception) {}
    }

    private fun joinChannel(channelName: String, caller: Boolean) {
        val token = "YOUR_AGORA_TOKEN"

        synchronized(audioLock) { audioBuffer.reset() }
        uploadRunning.set(false)
        hasAnalyzedInitialAudio.set(false)
        remoteUserJoined.set(false)
        isCallerMode.set(caller)

        _riskScore.value = 0.0
        _verdict.value = "WAITING"
        _analysisStatus.value = if (caller) {
            "Waiting for friend to join room..."
        } else {
            "Waiting for friend's voice..."
        }

        val options = ChannelMediaOptions()
        options.channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
        options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
        options.autoSubscribeAudio = true
        rtcEngine?.joinChannel(token, channelName, 0, options)
    }

    private suspend fun analyzeAudioChunk(pcmData: ByteArray, sampleRate: Int, channels: Int) {
        try {
            withContext(Dispatchers.Main) { _analysisStatus.value = "Analyzing remote audio chunk..." }
            val wavData = pcmToWav(pcmData, sampleRate, channels)
            val tempFile = File(cacheDir, "voice_chunk_${System.currentTimeMillis()}.wav")
            FileOutputStream(tempFile).use { it.write(wavData) }

            val requestBody = tempFile.asRequestBody("audio/wav".toMediaType())
            val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("audio_chunk", tempFile.name, requestBody).build()
            val request = Request.Builder().url("${serverBaseUrl}analyze_live_audio").post(multipartBody).build()

            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string()
            response.close()
            tempFile.delete()

            if (responseText.isNullOrBlank()) return
            val json = JSONObject(responseText)

            if (json.optString("status").equals("success", true)) {
                val score = json.optDouble("risk_score", 0.0)
                val serverVerdict = json.optString("verdict", "UNKNOWN")
                withContext(Dispatchers.Main) {
                    _riskScore.value = score
                    _verdict.value = serverVerdict
                    _analysisStatus.value = when {
                        serverVerdict.equals("FAKE", true) -> "⚠ Suspicious / AI voice detected"
                        serverVerdict.equals("SILENCE", true) -> "Audio volume too low. Speak louder."
                        else -> "Voice verified as normal human"
                    }
                }
            } else {
                withContext(Dispatchers.Main) { _analysisStatus.value = "Analysis error: ${json.optString("message")}" }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { _analysisStatus.value = "Server connection failed" }
        }
    }

    // ==========================================
    // NEW: PLAY AI VOICE LOGIC (INTEGRATED)
    // ==========================================
    private fun playRandomFakeAIVoice() {
        try {
            // మీ raw ఫోల్డర్ లో ఉన్న 10 ఫైల్స్ పేర్లు ఇక్కడ ఇవ్వండి.
            val aiVoiceFiles = arrayOf(
                R.raw.voice_1, R.raw.voice_2, R.raw.voice_3,
                R.raw.voice_4, R.raw.voice_5, R.raw.voice_6,
                R.raw.voice_7, R.raw.voice_8, R.raw.voice_9,
                R.raw.voice_10
            )

            val randomFileId = aiVoiceFiles[Random.nextInt(aiVoiceFiles.size)]
            val inputStream = resources.openRawResource(randomFileId)

            val tempFile = File(cacheDir, "temp_ai_voice.wav")
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)

            inputStream.close()
            outputStream.close()

            // ప్లే చేయడం (Replace = true)
            rtcEngine?.startAudioMixing(tempFile.absolutePath, false, 1)
            Toast.makeText(this, "🤖 AI Voice Playing!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error playing voice (Check raw folder)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopFakeAIVoice() {
        rtcEngine?.stopAudioMixing()
        Toast.makeText(this, "🛑 AI Voice Stopped", Toast.LENGTH_SHORT).show()
    }
    // ==========================================

    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val totalDataLength = pcmData.size + 36
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.writeBytes("RIFF")
        writeIntLE(data, totalDataLength)
        data.writeBytes("WAVE")
        data.writeBytes("fmt ")
        writeIntLE(data, 16)
        writeShortLE(data, 1)
        writeShortLE(data, channels)
        writeIntLE(data, sampleRate)
        writeIntLE(data, byteRate)
        writeShortLE(data, channels * 2)
        writeShortLE(data, 16)
        data.writeBytes("data")
        writeIntLE(data, pcmData.size)
        data.write(pcmData)
        data.flush()
        return output.toByteArray()
    }

    private fun writeIntLE(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value shr 8) and 0xFF)
        out.writeByte((value shr 16) and 0xFF)
        out.writeByte((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value shr 8) and 0xFF)
    }

    private fun leaveChannel() {
        rtcEngine?.leaveChannel()
        rtcEngine?.stopAudioMixing() // ఆడియో ప్లే అవుతుంటే ఆపేయడానికి
        synchronized(audioLock) { audioBuffer.reset() }
        uploadRunning.set(false)
        hasAnalyzedInitialAudio.set(false)
        remoteUserJoined.set(false)
        _riskScore.value = 0.0
        _verdict.value = "WAITING"
        _analysisStatus.value = "Call ended"
    }

    override fun onDestroy() {
        rtcEngine?.stopAudioMixing()
        rtcEngine?.leaveChannel()
        RtcEngine.destroy()
        rtcEngine = null
        ioScope.cancel()
        super.onDestroy()
    }
}

// =================================================================
// VOICESHIELD APP (Compose Components Below)
// =================================================================

@Composable
fun VoiceShieldApp(
    riskScore: Double,
    verdict: String,
    analysisStatus: String,
    onJoinCall: (String, Boolean) -> Unit,
    onLeaveCall: () -> Unit,
    onPlayAIVoice: () -> Unit, // NEW
    onStopAIVoice: () -> Unit  // NEW
) {
    var currentScreen by remember { mutableStateOf("DIALER") }
    var phoneNumber by remember { mutableStateOf("") }
    var isCaller by remember { mutableStateOf(false) }

    when (currentScreen) {
        "DIALER" -> {
            DialerScreen(
                phoneNumber = phoneNumber,
                onNumberChange = { phoneNumber = it },
                onCallClick = {
                    if (phoneNumber.isNotEmpty()) {
                        isCaller = true
                        currentScreen = "OUTGOING"
                    }
                },
                onTestIncomingClick = {
                    if (phoneNumber.isNotEmpty()) {
                        isCaller = false
                        currentScreen = "INCOMING"
                    }
                }
            )
        }
        "OUTGOING" -> {
            OutgoingCallScreen(
                dialedNumber = phoneNumber,
                onCancelCall = { currentScreen = "DIALER" },
                onCallConnected = {
                    onJoinCall(phoneNumber, isCaller)
                    currentScreen = "ACTIVE"
                }
            )
        }
        "INCOMING" -> {
            IncomingCallScreen(
                callerNumber = phoneNumber,
                onAccept = {
                    onJoinCall(phoneNumber, isCaller)
                    currentScreen = "ACTIVE"
                },
                onDecline = { currentScreen = "DIALER" }
            )
        }
        "ACTIVE" -> {
            ActiveCallScreen(
                callerNumber = phoneNumber,
                riskScore = riskScore,
                verdict = verdict,
                analysisStatus = analysisStatus,
                isCaller = isCaller,
                onEndCall = {
                    onLeaveCall()
                    currentScreen = "DIALER"
                    phoneNumber = ""
                },
                onPlayAIVoice = onPlayAIVoice, // NEW
                onStopAIVoice = onStopAIVoice  // NEW
            )
        }
    }
}

@Composable
fun DialerScreen(phoneNumber: String, onNumberChange: (String) -> Unit, onCallClick: () -> Unit, onTestIncomingClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        TextButton(onClick = onTestIncomingClick, modifier = Modifier.padding(bottom = 50.dp)) { Text("Simulate Incoming Call 📞", color = Color.Cyan) }
        Text(text = phoneNumber, fontSize = 42.sp, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 30.dp).height(60.dp), maxLines = 1)
        Spacer(modifier = Modifier.height(20.dp))
        val padNumbers = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
        padNumbers.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { num ->
                    Button(onClick = { onNumberChange(phoneNumber + num) }, shape = CircleShape, modifier = Modifier.size(75.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))) { Text(text = num, fontSize = 28.sp, color = Color.White) }
                }
            }
            Spacer(modifier = Modifier.height(15.dp))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.size(75.dp))
            Button(onClick = onCallClick, shape = CircleShape, modifier = Modifier.size(85.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))) { Text("📞", fontSize = 32.sp) }
            IconButton(onClick = { if (phoneNumber.isNotEmpty()) onNumberChange(phoneNumber.dropLast(1)) }, modifier = Modifier.size(75.dp)) { Text("⌫", fontSize = 28.sp, color = Color.Gray) }
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun OutgoingCallScreen(dialedNumber: String, onCancelCall: () -> Unit, onCallConnected: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 60.dp)) {
            Text("Calling...", color = Color.Gray, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(dialedNumber, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(40.dp))
            TextButton(onClick = onCallConnected) { Text("(Tap here when friend answers)", color = Color.Gray) }
        }
        Button(onClick = onCancelCall, shape = CircleShape, modifier = Modifier.size(80.dp).padding(bottom = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("☎", fontSize = 30.sp, color = Color.White) }
    }
}

@Composable
fun IncomingCallScreen(callerNumber: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Incoming Call...", color = Color.White, fontSize = 22.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(callerNumber, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onDecline, shape = CircleShape, modifier = Modifier.size(80.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("☎", fontSize = 30.sp, color = Color.White) }
            Button(onClick = onAccept, shape = CircleShape, modifier = Modifier.size(80.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))) { Text("📞", fontSize = 30.sp, color = Color.White) }
        }
    }
}

@Composable
fun ActiveCallScreen(
    callerNumber: String,
    riskScore: Double,
    verdict: String,
    analysisStatus: String,
    isCaller: Boolean,
    onEndCall: () -> Unit,
    onPlayAIVoice: () -> Unit, // NEW
    onStopAIVoice: () -> Unit  // NEW
) {
    var secondsElapsed by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            secondsElapsed++
        }
    }

    val minutes = (secondsElapsed / 60).toString().padStart(2, '0')
    val seconds = (secondsElapsed % 60).toString().padStart(2, '0')
    val timeString = "$minutes:$seconds"

    val displayVerdict = when {
        verdict.equals("FAKE", ignoreCase = true) -> "⚠ HIGH RISK"
        verdict.equals("REAL", ignoreCase = true) -> "✓ LOW RISK"
        verdict.equals("SILENCE", ignoreCase = true) -> "🔇 No Voice Detected"
        else -> "ANALYZING"
    }
    val scoreText = if (verdict.equals("SILENCE", true)) "--" else String.format("%.2f%%", riskScore)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(50.dp))
            Text(text = callerNumber, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(text = timeString, color = Color.Gray, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(40.dp))

            if (!isCaller) {
                Surface(color = Color(0xFF1E1E1E), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "AI Voice Risk Score", color = Color.White, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = scoreText,
                            color = when {
                                verdict.equals("FAKE", ignoreCase = true) -> Color.Red
                                verdict.equals("REAL", ignoreCase = true) -> Color(0xFF4CAF50)
                                verdict.equals("SILENCE", ignoreCase = true) -> Color.Gray
                                else -> Color.Yellow
                            },
                            fontSize = 30.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = displayVerdict,
                            color = if (verdict.equals("SILENCE", true)) Color.Gray else Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(text = analysisStatus, color = Color.Gray, fontSize = 13.sp)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(20.dp))
                Text(text = "Secure Call in Progress", color = Color.Gray, fontSize = 16.sp)

                // ===============================================
                // NEW: AI PLAY / STOP BUTTONS (Added to Caller UI)
                // ===============================================
                Spacer(modifier = Modifier.height(40.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onPlayAIVoice,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
                    ) {
                        Text("🤖 Play AI Voice", color = Color.White)
                    }
                    Button(
                        onClick = onStopAIVoice,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("🛑 Stop", color = Color.White)
                    }
                }
                // ===============================================
            }
        }

        Button(onClick = onEndCall, shape = CircleShape, modifier = Modifier.size(80.dp).padding(bottom = 20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
            Text(text = "☎", fontSize = 30.sp, color = Color.White)
        }
    }
}