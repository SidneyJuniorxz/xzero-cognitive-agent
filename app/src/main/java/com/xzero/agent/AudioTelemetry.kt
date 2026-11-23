package com.xzero.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10

/**
 * Telemetria do pipeline de áudio (acordar + aceitar a ação).
 *
 * Centraliza em um só lugar tudo que antes só existia espalhado em logs soltos:
 *  - nível instantâneo do microfone (dBFS), piso de ruído adaptativo e limiar efetivo do VAD;
 *  - cada disparo do VAD com a relação sinal/ruído (SNR) real daquele instante;
 *  - latência de "acordar" (VAD -> primeira parcial -> transcrição final);
 *  - cada confirmação falada pré-execução e seu desfecho (aceita, cancelada, ajustada, sem resposta);
 *  - quantas falas foram descartadas pelo filtro anti-eco (principal causa de confirmação perdida).
 *
 * Exposto em `GET /api/agent/audio` e pelo `tools/monitor_audio.sh`.
 */
object AudioTelemetry {

    private const val TAG = "AudioTelemetry"
    private const val MAX_WAKE_EVENTS = 40
    private const val MAX_CONFIRM_EVENTS = 20

    enum class MicState { IDLE, VAD_LISTENING, RECOGNIZING, SPEAKING_TTS, YIELDED_TO_USER, DISABLED }

    data class WakeEvent(
        val timestamp: Long,
        val rmsDbfs: Double,
        val floorDbfs: Double,
        val thresholdDbfs: Double,
        val snrDb: Double,
        val consecutiveFrames: Int,
        var latencyToRecognizerMs: Long = -1,
        var latencyToPartialMs: Long = -1,
        var latencyToFinalMs: Long = -1,
        var transcript: String = "",
        var outcome: String = "pending",
    )

    data class ConfirmEvent(
        val timestamp: Long,
        val question: String,
        var reply: String = "",
        var outcome: String = "pending",
        var latencyMs: Long = -1,
        var attempts: Int = 0,
    )

    // ---- Estado instantâneo do microfone ----
    @Volatile var micState: MicState = MicState.IDLE
        private set
    @Volatile var lastFrameAtMs: Long = 0L
        private set
    @Volatile var rmsDbfs: Double = -100.0
        private set
    @Volatile var noiseFloorDbfs: Double = -60.0
        private set
    @Volatile var thresholdDbfs: Double = -36.0
        private set
    @Volatile var peakDbfsLast10s: Double = -100.0
        private set
    private var peakWindowStartMs: Long = 0L

    // ---- Contadores acumulados ----
    @Volatile var vadTriggers: Int = 0; private set
    @Volatile var vadTriggersWithoutTranscript: Int = 0; private set
    @Volatile var recognizerStarts: Int = 0; private set
    @Volatile var finalTranscripts: Int = 0; private set
    @Volatile var noMatchErrors: Int = 0; private set
    @Volatile var speechTimeouts: Int = 0; private set
    @Volatile var audioErrors: Int = 0; private set
    @Volatile var micYields: Int = 0; private set
    @Volatile var echoDrops: Int = 0; private set
    @Volatile var echoDropsDuringConfirmation: Int = 0; private set
    @Volatile var vadFrames: Long = 0L; private set
    @Volatile var startedAtMs: Long = System.currentTimeMillis(); private set

    private val wakeEvents = ArrayDeque<WakeEvent>()
    private val confirmEvents = ArrayDeque<ConfirmEvent>()

    @Volatile private var openWake: WakeEvent? = null
    @Volatile private var openConfirm: ConfirmEvent? = null
    @Volatile private var recognizerStartedAtMs: Long = 0L

    fun rmsToDbfs(rms: Double): Double =
        if (rms <= 1.0) -100.0 else 20.0 * log10(rms / 32768.0)

    fun setMicState(state: MicState) {
        micState = state
    }

    /** Chamado a cada frame do VAD (40 ms) — mantém o nível ao vivo sem poluir o logcat. */
    fun onVadFrame(rmsDb: Double, floorDb: Double, thresholdDb: Double) {
        val now = System.currentTimeMillis()
        vadFrames++
        lastFrameAtMs = now
        rmsDbfs = rmsDb
        noiseFloorDbfs = floorDb
        thresholdDbfs = thresholdDb
        if (now - peakWindowStartMs > 10_000L) {
            peakWindowStartMs = now
            peakDbfsLast10s = rmsDb
        } else if (rmsDb > peakDbfsLast10s) {
            peakDbfsLast10s = rmsDb
        }
    }

    fun onVadTrigger(rmsDb: Double, floorDb: Double, thresholdDb: Double, frames: Int) {
        vadTriggers++
        val ev = WakeEvent(
            timestamp = System.currentTimeMillis(),
            rmsDbfs = rmsDb,
            floorDbfs = floorDb,
            thresholdDbfs = thresholdDb,
            snrDb = rmsDb - floorDb,
            consecutiveFrames = frames,
        )
        openWake = ev
        synchronized(wakeEvents) {
            wakeEvents.addLast(ev)
            while (wakeEvents.size > MAX_WAKE_EVENTS) wakeEvents.removeFirst()
        }
    }

    fun onRecognizerStart(mode: String) {
        recognizerStarts++
        recognizerStartedAtMs = System.currentTimeMillis()
        openWake?.let {
            if (it.latencyToRecognizerMs < 0) it.latencyToRecognizerMs = recognizerStartedAtMs - it.timestamp
        }
        micState = MicState.RECOGNIZING
        Log.d(TAG, "🎙️ recognizer start ($mode)")
    }

    fun onPartialTranscript(text: String) {
        val ev = openWake ?: return
        if (ev.latencyToPartialMs < 0) ev.latencyToPartialMs = System.currentTimeMillis() - ev.timestamp
        ev.transcript = text
    }

    fun onFinalTranscript(text: String, outcome: String) {
        finalTranscripts++
        val ev = openWake
        if (ev != null) {
            ev.latencyToFinalMs = System.currentTimeMillis() - ev.timestamp
            ev.transcript = text
            ev.outcome = outcome
            openWake = null
        }
        openConfirm?.let { c ->
            if (c.outcome == "pending") {
                c.reply = text
                c.latencyMs = System.currentTimeMillis() - c.timestamp
            }
        }
    }

    fun onRecognizerError(code: Int, isManual: Boolean) {
        when (code) {
            android.speech.SpeechRecognizer.ERROR_NO_MATCH -> noMatchErrors++
            android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> speechTimeouts++
            android.speech.SpeechRecognizer.ERROR_AUDIO,
            android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> audioErrors++
        }
        val ev = openWake ?: return
        if (ev.transcript.isBlank()) {
            vadTriggersWithoutTranscript++
            // Microfone abriu e não saiu nada: acionamento desnecessário -> penaliza o despertar.
            VadAutoTuner.registerOutcome(VadAutoTuner.Outcome.WASTED, "erro=$code")
            ev.outcome = "sem_fala_reconhecida(erro=$code)"
            ev.latencyToFinalMs = System.currentTimeMillis() - ev.timestamp
            openWake = null
        }
    }

    /**
     * Desfecho de um despertar que PRODUZIU fala reconhecida.
     * `productive` = virou comando/ação; caso contrário foi só conversa guardada na memória curta.
     */
    fun onWakeOutcome(productive: Boolean, detail: String) {
        VadAutoTuner.registerOutcome(
            if (productive) VadAutoTuner.Outcome.PRODUCTIVE else VadAutoTuner.Outcome.NEUTRAL,
            detail,
        )
    }

    fun onEchoDrop(text: String, duringConfirmation: Boolean) {
        echoDrops++
        if (!duringConfirmation) {
            // X0 ouviu a própria voz: abriu microfone sem necessidade.
            VadAutoTuner.registerOutcome(VadAutoTuner.Outcome.WASTED, "eco_do_proprio_tts")
        }
        if (duringConfirmation) {
            echoDropsDuringConfirmation++
            openConfirm?.let { it.outcome = "descartada_como_eco" }
        }
        Log.w(TAG, "🔇 anti-eco descartou '${text.take(60)}' (confirmação=$duringConfirmation)")
    }

    @Volatile var voicePrintRejects: Int = 0; private set

    /** Disparo barrado pelo gate de voz: não abriu o microfone (economia real de sessão). */
    fun onVoicePrintReject(similarity: Double) {
        voicePrintRejects++
        Log.i(TAG, "🚫 disparo barrado por voz desconhecida (sim=${Math.round(similarity * 100) / 100.0})")
    }

    fun onMicYield(reason: String) {
        micYields++
        micState = MicState.YIELDED_TO_USER
        Log.d(TAG, "🤝 mic cedido: $reason")
    }

    fun onConfirmationAsked(question: String) {
        val ev = ConfirmEvent(timestamp = System.currentTimeMillis(), question = question.take(220))
        openConfirm = ev
        synchronized(confirmEvents) {
            confirmEvents.addLast(ev)
            while (confirmEvents.size > MAX_CONFIRM_EVENTS) confirmEvents.removeFirst()
        }
    }

    fun onConfirmationRetry() {
        openConfirm?.let { it.attempts++ }
    }

    /** outcome: aceita | cancelada | ajustada | sem_resposta | descartada_como_eco */
    fun onConfirmationResolved(reply: String, outcome: String) {
        val ev = openConfirm ?: return
        ev.reply = reply.take(220)
        ev.outcome = outcome
        if (ev.latencyMs < 0) ev.latencyMs = System.currentTimeMillis() - ev.timestamp
        openConfirm = null
        Log.i(TAG, "✅ confirmação '$outcome' em ${ev.latencyMs}ms (resposta='${reply.take(40)}')")
    }

    fun reset() {
        vadTriggers = 0; vadTriggersWithoutTranscript = 0; recognizerStarts = 0
        finalTranscripts = 0; noMatchErrors = 0; speechTimeouts = 0; audioErrors = 0
        micYields = 0; echoDrops = 0; echoDropsDuringConfirmation = 0; vadFrames = 0L
        startedAtMs = System.currentTimeMillis()
        synchronized(wakeEvents) { wakeEvents.clear() }
        synchronized(confirmEvents) { confirmEvents.clear() }
    }

    private fun WakeEvent.toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("rms_dbfs", round1(rmsDbfs))
        .put("floor_dbfs", round1(floorDbfs))
        .put("threshold_dbfs", round1(thresholdDbfs))
        .put("snr_db", round1(snrDb))
        .put("frames", consecutiveFrames)
        .put("latency_recognizer_ms", latencyToRecognizerMs)
        .put("latency_partial_ms", latencyToPartialMs)
        .put("latency_final_ms", latencyToFinalMs)
        .put("transcript", transcript)
        .put("outcome", outcome)

    private fun ConfirmEvent.toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("question", question)
        .put("reply", reply)
        .put("outcome", outcome)
        .put("latency_ms", latencyMs)
        .put("attempts", attempts)

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    fun getJson(): JSONObject {
        val wakes = synchronized(wakeEvents) { wakeEvents.toList() }
        val confirms = synchronized(confirmEvents) { confirmEvents.toList() }
        val withFinal = wakes.filter { it.latencyToFinalMs > 0 }
        val accepted = confirms.count { it.outcome == "aceita" }
        val resolved = confirms.count { it.outcome != "pending" }

        return JSONObject()
            .put("mic_state", micState.name)
            .put("last_frame_at", lastFrameAtMs)
            .put("live", JSONObject()
                .put("rms_dbfs", round1(rmsDbfs))
                .put("noise_floor_dbfs", round1(noiseFloorDbfs))
                .put("threshold_dbfs", round1(thresholdDbfs))
                .put("snr_db", round1(rmsDbfs - noiseFloorDbfs))
                .put("headroom_db", round1(thresholdDbfs - noiseFloorDbfs))
                .put("peak_dbfs_10s", round1(peakDbfsLast10s)))
            .put("counters", JSONObject()
                .put("uptime_ms", System.currentTimeMillis() - startedAtMs)
                .put("vad_frames", vadFrames)
                .put("vad_triggers", vadTriggers)
                .put("vad_triggers_sem_transcricao", vadTriggersWithoutTranscript)
                .put("recognizer_starts", recognizerStarts)
                .put("transcricoes_finais", finalTranscripts)
                .put("erros_no_match", noMatchErrors)
                .put("erros_speech_timeout", speechTimeouts)
                .put("erros_audio_ocupado", audioErrors)
                .put("mic_cedido", micYields)
                .put("descartes_anti_eco", echoDrops)
                .put("descartes_anti_eco_em_confirmacao", echoDropsDuringConfirmation)
                .put("barrados_por_voz_desconhecida", voicePrintRejects))
            .put("quality", JSONObject()
                .put("taxa_falso_disparo", if (vadTriggers == 0) 0.0 else
                    round1(vadTriggersWithoutTranscript * 100.0 / vadTriggers))
                .put("latencia_media_final_ms", if (withFinal.isEmpty()) 0 else
                    withFinal.sumOf { it.latencyToFinalMs } / withFinal.size)
                .put("snr_medio_db", if (wakes.isEmpty()) 0.0 else
                    round1(wakes.sumOf { it.snrDb } / wakes.size))
                .put("confirmacoes_total", confirms.size)
                .put("confirmacoes_aceitas", accepted)
                .put("taxa_aceite_confirmacao", if (resolved == 0) 0.0 else
                    round1(accepted * 100.0 / resolved)))
            .put("autotune", VadAutoTuner.toJson())
            .put("voiceprint", VoicePrint.toJson())
            .put("emocao", EmotionEngine.toJson())
            .put("portao_desbloqueio", UnlockGate.toJson())
            .put("semantica", VoiceSemantics.toJson())
            .put("wake_events", JSONArray(wakes.reversed().map { it.toJson() }))
            .put("confirm_events", JSONArray(confirms.reversed().map { it.toJson() }))
    }
}
