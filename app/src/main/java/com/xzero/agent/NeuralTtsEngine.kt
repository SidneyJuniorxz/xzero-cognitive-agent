package com.xzero.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Motor TTS neural 100% offline via Sherpa-ONNX (Piper VITS pt_BR: Faber / Cadu).
 *
 * Os modelos ficam em `filesDir/neural_tts/<voiceId>/` (fora do APK para manter builds
 * rápidos) com `espeak-ng-data` compartilhado em `filesDir/neural_tts/espeak-ng-data/`.
 * Se o modelo não existir ou falhar, retorna `false` para fallback automático ao Google TTS.
 */
object NeuralTtsEngine {

    private const val TAG = "NeuralTTS"

    private var activeVoiceId: String = ""
    private var activeTts: OfflineTts? = null
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var cancelled: Boolean = false

    fun isAvailable(ctx: Context, voiceId: String): Boolean {
        if (voiceId != "faber" && voiceId != "cadu") return false
        val base = File(ctx.filesDir, "neural_tts")
        val model = File(base, "$voiceId/pt_BR-$voiceId-medium.onnx")
        val tokens = File(base, "$voiceId/tokens.txt")
        val dataDir = File(base, "espeak-ng-data")
        return model.exists() && tokens.exists() && dataDir.isDirectory
    }

    @Synchronized
    private fun ensureLoaded(ctx: Context, voiceId: String): OfflineTts? {
        if (activeTts != null && activeVoiceId == voiceId) return activeTts
        if (!isAvailable(ctx, voiceId)) return null
        return runCatching {
            activeTts?.release()
            activeTts = null
            val base = File(ctx.filesDir, "neural_tts")
            val vits = OfflineTtsVitsModelConfig(
                model = File(base, "$voiceId/pt_BR-$voiceId-medium.onnx").absolutePath,
                lexicon = "",
                tokens = File(base, "$voiceId/tokens.txt").absolutePath,
                dataDir = File(base, "espeak-ng-data").absolutePath,
                dictDir = "",
                noiseScale = 0.667f,
                noiseScaleW = 0.8f,
                lengthScale = 1.0f,
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            val cfg = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )
            val tts = OfflineTts(null, cfg)
            activeTts = tts
            activeVoiceId = voiceId
            Log.i(TAG, "✅ Voz neural [$voiceId] carregada (${tts.sampleRate()} Hz)")
            tts
        }.onFailure {
            Log.w(TAG, "⚠️ Falha ao carregar voz neural [$voiceId]: ${it.message}")
        }.getOrNull()
    }

    fun speak(
        ctx: Context,
        text: String,
        voiceId: String,
        speed: Float = 1.04f,
        onStart: () -> Unit,
        onDone: () -> Unit,
    ): Boolean {
        if (!isAvailable(ctx, voiceId) || text.isBlank()) return false
        cancelled = false
        Thread {
            var track: AudioTrack? = null
            try {
                val tts = ensureLoaded(ctx, voiceId)
                if (tts == null) {
                    onDone()
                    return@Thread
                }
                val audio = tts.generate(text, 0, speed)
                val samples = audio.samples
                val sr = audio.sampleRate
                if (samples.isEmpty() || cancelled) {
                    onDone()
                    return@Thread
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    sr,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                ).coerceAtLeast(samples.size * 4)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                activeTrack = track
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                if (!cancelled) {
                    onStart()
                    track.play()
                    val durationMs = ((samples.size.toDouble() / sr.toDouble()) * 1000.0).toLong() + 120L
                    val endAt = System.currentTimeMillis() + durationMs
                    while (!cancelled && System.currentTimeMillis() < endAt) {
                        Thread.sleep(40L)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Erro ao falar com voz neural [$voiceId]: ${t.message}")
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                if (activeTrack === track) activeTrack = null
                onDone()
            }
        }.start()
        return true
    }

    fun stop() {
        cancelled = true
        runCatching { activeTrack?.stop() }
        runCatching { activeTrack?.release() }
        activeTrack = null
    }
}
