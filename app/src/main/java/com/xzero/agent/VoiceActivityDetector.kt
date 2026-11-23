package com.xzero.agent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detector de Atividade de Voz (VAD) local via AudioRecord contínuo e silencioso.
 *
 * Evita o loop infinito de createSpeechRecognizer/startListening/destroy no silêncio
 * (que causava bips constantes e pisca-pisca do ícone de microfone no Android 14).
 *
 * NORMALIZAÇÃO (dBFS + SNR):
 * Antes o limiar era calculado em RMS linear (`floor * 2.55`), então o ponto de disparo
 * variava de 520 a 1500+ RMS conforme o ruído da sala — às vezes acordava com um sussurro,
 * às vezes não acordava nem com fala normal. Agora todo o cálculo é em dBFS:
 *   limiar = max(piso_de_ruído + SNR_TRIGGER_DB, ABS_MIN_TRIGGER_DBFS)
 * ou seja, a fala precisa estar sempre os MESMOS ~9 dB acima do ruído da sala,
 * com o piso de ruído subindo devagar e descendo rápido (nunca inflado pela própria fala).
 */
class VoiceActivityDetector(
    private val scope: CoroutineScope,
    private val onVoiceDetected: () -> Unit,
    private val shouldPause: () -> Boolean,
    private val gateEnabledProvider: () -> Boolean = { false },
    private val onBeforeHandoff: () -> Unit = {},
    private val isDevicePlaybackActiveProvider: () -> Boolean = { false },
) {
    companion object {
        @Volatile
        var onPcmFrameListener: ((ShortArray, Int) -> Unit)? = null

        private const val TAG = "AgentePhoneVAD"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 640 // 40 ms @ 16 kHz

        /** Quantos dB acima do ruído ambiente a fala precisa estar para contar como voz. */
        private const val SNR_TRIGGER_DB = 10.0
        /** Margem extra de SNR quando o próprio celular está reproduzindo música/vídeo no alto-falante. */
        private const val PLAYBACK_EXTRA_SNR_DB = 6.5
        private const val PLAYBACK_MIN_TRIGGER_DBFS = -30.5
        /**
         * Piso absoluto: abaixo disso é ruído de fundo/TV distante mesmo em sala silenciosa.
         * Sons distantes (-40 a -42 dBFS) não são reconhecidos pelo SODA e geram erro=7;
         * a fala real do usuário chega entre -36 e -18 dBFS.
         */
        private const val ABS_MIN_TRIGGER_DBFS = -38.5
        /**
         * Limites do piso de ruído adaptativo (sala silenciosa .. sala barulhenta).
         * No celular Android, o noise gate neural Qualcomm Fluence (`fnn usecase state 1`)
         * derruba artificialmente o silêncio para -66..-69 dBFS; limitar o piso em -56.0 dBFS
         * mantém a referência no piso acústico real da sala (-53 a -56 dBFS) sem deixar cliques
         * de -39 dBFS parecerem +23 dB de SNR.
         */
        private const val MIN_NOISE_FLOOR_DBFS = -56.0
        private const val MAX_NOISE_FLOOR_DBFS = -27.0
        /** Sobe devagar (ruído novo) e desce suave (sem cair no gate digital do DSP). */
        private const val FLOOR_RISE_ALPHA = 0.015
        private const val FLOOR_FALL_ALPHA = 0.035
        /** Margem de guarda: frames perto do limiar não entram na média do piso. */
        private const val FLOOR_ADAPT_GUARD_DB = 3.0

        private const val WARMUP_FRAMES = 8 // ~320 ms apenas na 1ª calibração pós-boot
        private const val POST_START_SETTLE_FRAMES = 3 // ~120 ms pós-startRecording para descartar pop/cauda de áudio
        private const val FRAMES_NORMAL = 2 // ~80 ms de fala contínua
        private const val FRAMES_FAST = 1 // ~40 ms quando a fala é clara e próxima (SNR >= 13.5 dB e >= -34.5 dBFS)
        private const val FAST_PATH_SNR_DB = 13.5
        private const val TELEMETRY_LOG_INTERVAL_MS = 2_000L
        private const val PRINT_WINDOW_SAMPLES = 16_000 // 1 s @ 16 kHz

        /** Converte a janela circular em um array linear na ordem cronológica correta. */
        private fun linearizeWindow(ring: ShortArray, writePos: Int, filled: Int): ShortArray {
            if (filled < ring.size) return ring.copyOfRange(0, filled)
            val out = ShortArray(ring.size)
            val tail = ring.size - writePos
            System.arraycopy(ring, writePos, out, 0, tail)
            System.arraycopy(ring, 0, out, tail, writePos)
            return out
        }
    }

    /** Assinatura de voz do último disparo (usada para matricular o perfil em acerto). */
    @Volatile
    var lastTriggerFeatures: DoubleArray? = null
        private set

    private fun voicePrintGateEnabled(): Boolean = gateEnabledProvider()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastStartMs: Long = 0L
        private set

    @Volatile
    var lastVoiceTriggerMs: Long = 0L
        private set

    @Volatile
    var lastOfflineKeyword: String = ""
        private set

    @Volatile
    var lastTriggeredByKws: Boolean = false
        private set

    private var job: Job? = null

    /** Piso de ruído adaptativo em dBFS, preservado entre reinícios do VAD. */
    @Volatile
    var noiseFloorDbfs: Double = -52.0
        private set

    @Volatile
    private var calibratedOnce: Boolean = false

    /**
     * Limiar efetivo = (piso + SNR exigido) com piso absoluto, somado ao ajuste APRENDIDO
     * pelo [VadAutoTuner] (sobe quando o X0 acorda à toa, desce quando acerta e vira ação).
     */
    val thresholdDbfs: Double
        get() {
            val base = max(noiseFloorDbfs + SNR_TRIGGER_DB, ABS_MIN_TRIGGER_DBFS) + VadAutoTuner.snrOffsetDb
            return if (isDevicePlaybackActiveProvider()) {
                max(base + PLAYBACK_EXTRA_SNR_DB, PLAYBACK_MIN_TRIGGER_DBFS)
            } else {
                base
            }
        }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (shouldPause()) return

        lastStartMs = System.currentTimeMillis()
        isRunning = true
        job = scope.launch(Dispatchers.IO) {
            runLoop()
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
        if (AudioTelemetry.micState == AudioTelemetry.MicState.VAD_LISTENING) {
            AudioTelemetry.setMicState(AudioTelemetry.MicState.IDLE)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_SAMPLES * 4)

        var recorder: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            // Quando o celular está reproduzindo música/vídeo no alto-falante, usa VOICE_COMMUNICATION
            // para ativar o caminho de referência de cancelamento de eco (EC Reference) do DSP Qualcomm!
            val primarySource = if (isDevicePlaybackActiveProvider()) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            val rec = try {
                AudioRecord(
                    primarySource,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (_: Throwable) {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            }
            recorder = rec

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord não inicializou (state=${rec.state})")
                return
            }

            // Ativa cancelamento de eco acústico (AEC) e supressão de ruído (NS) em hardware no DSP
            // para anular o som que o próprio celular está reproduzindo no alto-falante!
            val sessionId = rec.audioSessionId
            runCatching {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }
            }
            runCatching {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
            }
            runCatching {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
                }
            }

            rec.startRecording()
            AudioTelemetry.setMicState(AudioTelemetry.MicState.VAD_LISTENING)
            Log.i(
                TAG,
                "🎙️ VAD silencioso iniciado (16kHz | piso=${fmt(noiseFloorDbfs)}dBFS | limiar=${fmt(thresholdDbfs)}dBFS | SNR exigido=${SNR_TRIGGER_DB}dB)",
            )

            val buffer = ShortArray(FRAME_SAMPLES)
            // Janela circular com ~1 s de PCM para extrair a assinatura de voz no instante do disparo
            val printWindow = ShortArray(PRINT_WINDOW_SAMPLES)
            var printWritePos = 0
            var printFilled = 0
            var speechFrames = 0
            var warmupLeft = if (calibratedOnce) 0 else WARMUP_FRAMES
            var settleLeft = POST_START_SETTLE_FRAMES
            var warmupMinDb = 0.0
            var lastTelemetryLogMs = 0L

            while (currentCoroutineContext().isActive && isRunning) {
                if (shouldPause()) {
                    break
                }

                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read < 0) {
                        Log.w(TAG, "AudioRecord.read retornou erro=$read; pausando VAD")
                        break
                    }
                    delay(20L)
                    continue
                }
                onPcmFrameListener?.invoke(buffer, read)

                var sumSq = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    sumSq += s * s
                    printWindow[printWritePos] = buffer[i]
                    printWritePos = (printWritePos + 1) % PRINT_WINDOW_SAMPLES
                    if (printFilled < PRINT_WINDOW_SAMPLES) printFilled++
                }
                val rmsDb = AudioTelemetry.rmsToDbfs(sqrt(sumSq / read.toDouble()))

                // Descarta frames digitais zerados (-100 dBFS) emitidos pelo HAL de áudio
                // no primeiro frame após `startRecording()`, impedindo que corrompam o piso para -62 dBFS!
                if (rmsDb <= -88.0) {
                    continue
                }

                // Descarta os primeiros ~120 ms pós-startRecording para nunca disparar com pop do ADC ou cauda de beep
                if (settleLeft > 0) {
                    settleLeft--
                    continue
                }

                // Aquecimento (apenas na 1ª calibração): usa o MENOR nível acústico real observado.
                // Nos reinícios seguintes, preserva `noiseFloorDbfs` aprendido e fica pronto imediatamente!
                if (warmupLeft > 0) {
                    val clampedRms = rmsDb.coerceAtLeast(MIN_NOISE_FLOOR_DBFS)
                    warmupMinDb = if (warmupLeft == WARMUP_FRAMES) clampedRms else min(warmupMinDb, clampedRms)
                    warmupLeft--
                    if (warmupLeft == 0) {
                        noiseFloorDbfs = warmupMinDb.coerceIn(MIN_NOISE_FLOOR_DBFS, MAX_NOISE_FLOOR_DBFS)
                        calibratedOnce = true
                    }
                    AudioTelemetry.onVadFrame(rmsDb, noiseFloorDbfs, thresholdDbfs)
                    continue
                }

                val threshold = thresholdDbfs
                AudioTelemetry.onVadFrame(rmsDb, noiseFloorDbfs, threshold)

                // KWS offline (Sherpa-ONNX): alimenta somente quando há voz/energia acima do piso (+1.5s de cauda)
                // para manter 0% de CPU extra no silêncio e detectar "X-Zero / X0 / Xenon / Ada" com 0 ms perdidos!
                val now = System.currentTimeMillis()
                val kwsActiveWindow = (rmsDb >= noiseFloorDbfs + 4.0) || (now - lastVoiceTriggerMs < 1500L)
                if (OfflineWakeWordSpotter.isReady && kwsActiveWindow) {
                    val kw = OfflineWakeWordSpotter.feedPcm16(buffer, read)
                    if (kw != null) {
                        lastOfflineKeyword = kw
                        lastTriggeredByKws = true
                        lastVoiceTriggerMs = now
                        runCatching { onBeforeHandoff() }
                        AudioTelemetry.onVadTrigger(rmsDb, noiseFloorDbfs, threshold, 1)
                        Log.i(TAG, "🎯 [KWS Offline] Palavra '$kw' detectada direto no AudioRecord -> acordando!")
                        isRunning = false
                        runCatching { aec?.release(); aec = null }
                        runCatching { ns?.release(); ns = null }
                        runCatching { agc?.release(); agc = null }
                        runCatching { rec.stop() }
                        runCatching { rec.release() }
                        recorder = null
                        scope.launch(Dispatchers.Main) { onVoiceDetected() }
                        return
                    }
                }
                lastTriggeredByKws = false

                if (now - lastTelemetryLogMs >= TELEMETRY_LOG_INTERVAL_MS) {
                    lastTelemetryLogMs = now
                    Log.d(
                        TAG,
                        "📊 nivel=${fmt(rmsDb)}dBFS piso=${fmt(noiseFloorDbfs)}dBFS limiar=${fmt(threshold)}dBFS snr=${fmt(rmsDb - noiseFloorDbfs)}dB",
                    )
                }

                if (rmsDb >= threshold) {
                    speechFrames++
                    val snr = rmsDb - noiseFloorDbfs
                    val required = if (snr >= FAST_PATH_SNR_DB && rmsDb >= -34.5) {
                        if (OfflineWakeWordSpotter.isReady) 3 else FRAMES_FAST
                    } else {
                        if (OfflineWakeWordSpotter.isReady) 4 else FRAMES_NORMAL
                    }
                    if (speechFrames >= required) {
                        // Assinatura de voz do trecho que disparou (timbre, não volume):
                        // separa "o usuário falou" de "a TV/outra pessoa falou na sala".
                        val feats = VoicePrint.featuresFrom(
                            linearizeWindow(printWindow, printWritePos, printFilled),
                            printFilled,
                        )
                        lastTriggerFeatures = feats
                        if (!VoicePrint.evaluateTrigger(feats, voicePrintGateEnabled())) {
                            // Voz desconhecida com perfil maduro e gate ligado: não abre o microfone.
                            speechFrames = 0
                            AudioTelemetry.onVoicePrintReject(VoicePrint.lastSimilarity)
                            continue
                        }
                        lastVoiceTriggerMs = now
                        // Sinaliza handoff SINCRONAMENTE antes de parar o AudioRecord, impedindo que
                        // o AudioRecordingCallback (thread Binder) interprete a transição de 20ms como app externo!
                        runCatching { onBeforeHandoff() }
                        AudioTelemetry.onVadTrigger(rmsDb, noiseFloorDbfs, threshold, speechFrames)
                        Log.i(
                            TAG,
                            "🗣️ VAD detectou fala (nivel=${fmt(rmsDb)}dBFS, limiar=${fmt(threshold)}dBFS, piso=${fmt(noiseFloorDbfs)}dBFS, snr=${fmt(snr)}dB, frames=$speechFrames) -> acionando SpeechRecognizer",
                        )
                        isRunning = false
                        // Libera efeitos DSP e o AudioRecord antes de disparar o SpeechRecognizer na Main thread
                        runCatching { aec?.release(); aec = null }
                        runCatching { ns?.release(); ns = null }
                        runCatching { agc?.release(); agc = null }
                        runCatching { rec.stop() }
                        runCatching { rec.release() }
                        recorder = null
                        scope.launch(Dispatchers.Main) {
                            onVoiceDetected()
                        }
                        return
                    }
                } else {
                    speechFrames = 0
                    // Só adapta o piso com frames claramente de fundo (e acima do gate digital de -58 dBFS do Fluence):
                    // assim nem o início/fim de frase empurra o piso para cima, nem o noise gate puxa para -62 dBFS.
                    if (rmsDb < threshold - FLOOR_ADAPT_GUARD_DB && rmsDb >= -58.0) {
                        val alpha = if (rmsDb < noiseFloorDbfs) FLOOR_FALL_ALPHA else FLOOR_RISE_ALPHA
                        noiseFloorDbfs = (noiseFloorDbfs + (rmsDb - noiseFloorDbfs) * alpha)
                            .coerceIn(MIN_NOISE_FLOOR_DBFS, MAX_NOISE_FLOOR_DBFS)
                    }
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Sem permissão RECORD_AUDIO para VAD: ${se.message}")
        } catch (t: Throwable) {
            if (currentCoroutineContext().isActive) {
                Log.w(TAG, "Erro no loop VAD: ${t.message}")
            }
        } finally {
            isRunning = false
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            if (AudioTelemetry.micState == AudioTelemetry.MicState.VAD_LISTENING) {
                AudioTelemetry.setMicState(AudioTelemetry.MicState.IDLE)
            }
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}
