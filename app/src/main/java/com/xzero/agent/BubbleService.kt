package com.xzero.agent

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Bolha flutuante + painel de chat com voz e escuta contínua de wake word ("Ok agente"). */
class BubbleService : Service(), AgentUi {

    companion object {
        @Volatile var instance: BubbleService? = null
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BubbleService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var bubble: FrameLayout
    private lateinit var xenonSprite: XenonSpriteView
    private lateinit var haloRing: View
    private lateinit var statusBadge: TextView
    private lateinit var speechView: TextView
    private lateinit var speechLp: WindowManager.LayoutParams
    private var speechShown = false
    private var speechHideJob: Job? = null
    private var floatAnimator: ObjectAnimator? = null
    private var runAnimator: ValueAnimator? = null
    private var movementSequence = 0L
    private var lastWalkingAction = XenonSpriteView.Action.RUN_RIGHT
    private lateinit var panel: LinearLayout
    private lateinit var logView: TextView
    private lateinit var input: EditText
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams
    private var panelShown = false
    private var tts: TextToSpeech? = null
    /** Dono da voz de saída (perfil, prosódia emocional, anti-eco) — extraído deste arquivo. */
    private val voice by lazy { TtsVoiceController(this) }
    private var recognizer: SpeechRecognizer? = null
    private lateinit var agent: Agent
    private var job: Job? = null
    private var pending: CompletableDeferred<String>? = null
    private var wakeActive = false
    private var manualListen = false
    private var manualListenAttempts = 0
    private val maxManualListenAttempts = 3
    private var lastWakeEventMs = 0L
    private var watchdogJob: Job? = null
    private var micHealthJob: Job? = null
    private var partialCommitJob: Job? = null
    private var speechConsolidationJob: Job? = null
    private val accumulatedConsolidationSegments = mutableListOf<String>()
    private var glowView: GlowOverlayView? = null
    private var audioRecordingCallback: android.media.AudioManager.AudioRecordingCallback? = null
    private var activeAudioFocusRequest: android.media.AudioFocusRequest? = null
    private var a11yServiceObserver: AccessibilityServiceObserver? = null
    @Volatile private var recognizerOwnSessionActive = false
    @Volatile private var recognizerSessionInFlight = false
    private var recognizerStartupWatchdogJob: Job? = null
    @Volatile private var recognizerStartMs = 0L
    @Volatile private var lastRecognizerEndMs = 0L
    @Volatile private var lastVadHandoffMs = 0L
    @Volatile private var vadHandoffPending = false
    @Volatile private var userMicYieldUntilMs = 0L
    @Volatile private var currentPartialTranscript = ""
    @Volatile private var currentAgentPhase: AgentPhase = AgentPhase.IDLE_RELAXED
    /** Resultado vindo do GoalVerifier; nunca é inferido a partir do texto da resposta. */
    @Volatile private var lastTaskGoalVerified: Boolean? = null
    @Volatile private var wakeScreenContext: AmbientContextEngine.ScreenContextSnapshot? = null
    @Volatile var latestLiveSpeechText: String = "⚡ X0 / X-Zero: Pronto! Pode falar 'X0', 'X-Zero', agitar o celular ou conversar em modo Live."
    private var lastPartialPreviewMs = 0L
    private val vad: VoiceActivityDetector by lazy {
        VoiceActivityDetector(
            scope = scope,
            onVoiceDetected = {
                if (wakeActive && !manualListen && !isSpeakingTts && !isUserUsingMicInOtherApp() && !recognizerSessionInFlight && !recognizerOwnSessionActive) {
                    vadHandoffPending = true
                    lastVadHandoffMs = System.currentTimeMillis()
                    lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
                    if (vad.lastTriggeredByKws) {
                        // Wake word ("X-Zero" / "X0" / "Xenon" / "Ada") já confirmada offline pelo Sherpa-ONNX!
                        // Acorda em escuta ativa com brilho roxo imediato para capturar o comando inteiro.
                        showGlow()
                        onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "kws_offline:${vad.lastOfflineKeyword}")
                        showSpeechBubble("👂 Tô aqui…", 3600L)
                        startRecognizer(isManual = true)
                    } else {
                        // CRÍTICO: dispara o SpeechRecognizer IMEDIATAMENTE na Main Thread
                        startRecognizer(isManual = false)
                    }
                    captureWakeScreenContext()
                } else {
                    vadHandoffPending = false
                }
            },
            shouldPause = {
                !wakeActive || manualListen || isSpeakingTts || recognizerSessionInFlight || recognizerOwnSessionActive || vadHandoffPending || isUserUsingMicInOtherApp()
            },
            gateEnabledProvider = { Prefs(this@BubbleService).voicePrintGate },
            onBeforeHandoff = {
                vadHandoffPending = true
                lastVadHandoffMs = System.currentTimeMillis()
            },
            isDevicePlaybackActiveProvider = {
                val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                am?.isMusicActive == true
            },
        )
    }

    private fun captureWakeScreenContext() {
        scope.launch(Dispatchers.Default) {
            runCatching {
                val dump = AgentAccessibilityService.instance?.dumpScreen()
                val snap = AmbientContextEngine.ScreenContextSnapshot.fromRawDump(dump)
                if (snap != null) {
                    wakeScreenContext = snap
                    android.util.Log.i("AgentePhone", "👁️ [Visão ao Acordar] App=${snap.packageName} | ${snap.summary.take(120)}")
                }
            }
        }
    }

    private fun cancelRecognizerSession(destroyInstance: Boolean = false) {
        recognizerStartupWatchdogJob?.cancel()
        recognizerStartupWatchdogJob = null
        partialCommitJob?.cancel()
        recognizerSessionInFlight = false
        recognizerOwnSessionActive = false
        if (destroyInstance) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        } else {
            runCatching { recognizer?.cancel() }
        }
        releaseRecognizerBeepMuteAfter(750L)
    }

    private fun syncActiveJobBubbleStatus() {
        if (!isJobActive()) return
        if (agent.ambient.isBtwArmed()) {
            setBubbleStatus("📌🎙️")
        } else if (currentAgentPhase != AgentPhase.ACTING) {
            setBubbleStatus("⏳🎙️")
        }
    }

    private var confirmingSuccessUntilMs: Long = 0L
    private var recoveringErrorUntilMs: Long = 0L

    /**
     * Prioridade determinística dos estados do Xenon:
     * ERRO > CONFIRMAÇÃO > AJUDA > ESCUTA > EXECUÇÃO > PENSAMENTO > OCIOSIDADE.
     */
    private fun phasePriority(phase: AgentPhase): Int = when (phase) {
        AgentPhase.RECOVERING_ERROR -> 60
        AgentPhase.CONFIRMING_SUCCESS -> 50
        AgentPhase.ASKING_HELP -> 45
        AgentPhase.ATTENTIVE_LISTENING -> 40
        AgentPhase.ACTING, AgentPhase.RESEARCHING, AgentPhase.LEARNING -> 30
        AgentPhase.THINKING -> 20
        AgentPhase.IDLE_RELAXED -> 10
    }

    override fun onAgentPhaseChanged(phase: AgentPhase, detail: String) {
        // "Ouvindo" só é mostrado após SpeechRecognizer.onReadyForSpeech. Wake word
        // habilitada, microfone ocupado por outro app ou permissão negada não são escuta.
        val actuallyListening = recognizerOwnSessionActive && recognizer != null
        val requested = if (phase == AgentPhase.ATTENTIVE_LISTENING && !actuallyListening) {
            if (isJobActive()) AgentPhase.THINKING else AgentPhase.IDLE_RELAXED
        } else phase
        val now = System.currentTimeMillis()
        // Protege janelas mínimas de ERRO e CONFIRMAÇÃO contra rebaixamentos prematuros
        if (currentAgentPhase == AgentPhase.CONFIRMING_SUCCESS &&
            now < confirmingSuccessUntilMs &&
            phasePriority(requested) < phasePriority(AgentPhase.CONFIRMING_SUCCESS)
        ) {
            return
        }
        if (currentAgentPhase == AgentPhase.RECOVERING_ERROR &&
            now < recoveringErrorUntilMs &&
            phasePriority(requested) < phasePriority(AgentPhase.ATTENTIVE_LISTENING)
        ) {
            return
        }
        if (requested == AgentPhase.IDLE_RELAXED && isJobActive() && detail != "standby") {
            return
        }

        currentAgentPhase = requested
        if (requested == AgentPhase.CONFIRMING_SUCCESS) {
            confirmingSuccessUntilMs = now + 1850L
        } else if (requested == AgentPhase.RECOVERING_ERROR) {
            recoveringErrorUntilMs = now + 1200L
        }

        val visual = visualForPhase(currentAgentPhase)
        runCatching {
            when (currentAgentPhase) {
                AgentPhase.ATTENTIVE_LISTENING -> EmotionEngine.feel(EmotionEngine.Emotion.ATENTO, "escutando: $detail", bump = 0.25)
                AgentPhase.THINKING, AgentPhase.RESEARCHING, AgentPhase.ACTING, AgentPhase.LEARNING ->
                    EmotionEngine.feel(EmotionEngine.Emotion.FOCADO, "${currentAgentPhase.name.lowercase()}: $detail", bump = 0.25)
                AgentPhase.ASKING_HELP -> EmotionEngine.feel(EmotionEngine.Emotion.CAUTELOSO, "pedindo ajuda: $detail", bump = 0.25)
                AgentPhase.RECOVERING_ERROR -> EmotionEngine.feel(EmotionEngine.Emotion.PREOCUPADO, "recuperando erro: $detail", bump = 0.35)
                AgentPhase.CONFIRMING_SUCCESS -> EmotionEngine.feel(EmotionEngine.Emotion.SATISFEITO, "sucesso verificado: $detail", bump = 0.6)
                AgentPhase.IDLE_RELAXED -> EmotionEngine.relax("ocioso")
            }
            showFunctionalAction(
                visual,
                force = currentAgentPhase == AgentPhase.RECOVERING_ERROR ||
                    currentAgentPhase == AgentPhase.CONFIRMING_SUCCESS ||
                    currentAgentPhase == AgentPhase.ATTENTIVE_LISTENING
            )
        }
        when (currentAgentPhase) {
            AgentPhase.IDLE_RELAXED -> setBubbleStatus("🤖")
            AgentPhase.ATTENTIVE_LISTENING -> setBubbleStatus(if (actuallyListening) "👂" else if (isJobActive()) "⏳" else "🤖")
            AgentPhase.THINKING -> setBubbleStatus(if (wakeActive) "⏳🎙️" else "⏳")
            AgentPhase.RESEARCHING -> setBubbleStatus("🔎 $detail")
            AgentPhase.ACTING -> setBubbleStatus("⚡ $detail")
            AgentPhase.ASKING_HELP -> setBubbleStatus("❓")
            AgentPhase.RECOVERING_ERROR -> setBubbleStatus("🛠️")
            AgentPhase.LEARNING -> setBubbleStatus("📚")
            AgentPhase.CONFIRMING_SUCCESS -> setBubbleStatus("✅")
        }
    }

    private fun visualForPhase(phase: AgentPhase): XenonSpriteView.Action = when (phase) {
            AgentPhase.IDLE_RELAXED -> XenonSpriteView.Action.IDLE
            AgentPhase.ATTENTIVE_LISTENING -> XenonSpriteView.Action.LISTENING_ATTENTIVELY
            AgentPhase.THINKING -> XenonSpriteView.Action.DEEP_THINKING
            AgentPhase.RESEARCHING -> XenonSpriteView.Action.RESEARCHING_WEB
            AgentPhase.ACTING -> XenonSpriteView.Action.EXECUTING_TASK
            AgentPhase.ASKING_HELP -> XenonSpriteView.Action.ASKING_HELP
            AgentPhase.RECOVERING_ERROR -> XenonSpriteView.Action.RECOVERING_ERROR
            AgentPhase.LEARNING -> XenonSpriteView.Action.LEARNING_NEW
            AgentPhase.CONFIRMING_SUCCESS -> XenonSpriteView.Action.SUCCESS_CONFIRMED
        }

    /** A única troca de pose para estados funcionais; preserva enter/exit de pensamento. */
    private fun showFunctionalAction(action: XenonSpriteView.Action, force: Boolean = false) {
        if (action == XenonSpriteView.Action.DEEP_THINKING || action == XenonSpriteView.Action.THINK) {
            xenonSprite.enterThinking()
        } else if (xenonSprite.getCurrentAction() == XenonSpriteView.Action.DEEP_THINKING ||
            xenonSprite.getCurrentAction() == XenonSpriteView.Action.THINK
        ) {
            xenonSprite.exitThinking(action)
        } else {
            xenonSprite.setAction(action, force = force)
        }
    }

    override fun onTaskCompletion(verified: Boolean, detail: String) {
        lastTaskGoalVerified = verified
        android.util.Log.i("AgentePhone", "🏁 GoalVerifier: verified=$verified ($detail)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Quando promovido a partir da MainActivity (foreground), atualiza o ForegroundServiceType para incluir MICROPHONE!
        runCatching { startFg(ProactiveWatcher.isRecordingAudio) }
        syncWakeWord()
        ensureWakeRecognizerHealthy()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        HealthMonitor.installCrashHandler(this)
        VadAutoTuner.init(this)
        VoicePrint.init(this)
        VoiceSemantics.init(this)
        PersonaStore.init(this)
        TaskCheckpoint.init(this)
        InitiativeEngine.init(this)
        AdaStateMachine.transition(AdaStateMachine.AdaState.STANDBY_VAD, "onCreate")
        avisarSeHouverTarefaInterrompida()
        // Emoção -> animação do Xenon + cor da aura (a forma visível da ADA indica estado real)
        EmotionEngine.renderer = { emotion, intensity ->
            scope.launch {
                runCatching {
                    xenonSprite.setAuraColor(EmotionEngine.auraColorInt())
                    // Uma emoção nunca substitui a atividade real do agente. Durante
                    // pesquisa, execução, escuta ou confirmação, AgentPhase é a fonte
                    // de verdade do sprite; a emoção só colore a aura e a voz.
                    val canDecorateIdle = currentAgentPhase == AgentPhase.IDLE_RELAXED &&
                        !isJobActive() && !manualListen && !recognizerOwnSessionActive
                    if (canDecorateIdle && (intensity >= 0.35 || emotion == EmotionEngine.Emotion.CALMO)) {
                        xenonSprite.setAction(emotion.xenon) {
                            if (!emotion.xenon.loops && currentAgentPhase == AgentPhase.IDLE_RELAXED && !isJobActive()) {
                                xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                            }
                        }
                    }
                }
            }
        }
        // Garante escuta contínua (Wake Word + Live Mode) ativa por padrão
        val prefs = Prefs(this)
        if (!prefs.wakeWord) prefs.wakeWord = true
        startFg()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        agent = Agent(this, this)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            OfflineWakeWordSpotter.init(this@BubbleService)
            runCatching {
                val syncRes = agent.mcp.syncAllServers()
                android.util.Log.i("AgentePhone", "🔌 [MCP Startup Sync]: $syncRes")
            }
        }
        // Registra observer para detecção passiva de desabilitação de AccessibilityService
        a11yServiceObserver = AccessibilityServiceObserver.register(this)
        setupUserMicPriorityMonitor()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { applyTtsVoiceProfile(it) }
                announceStartupSystemsReady()
            }
        }
        buildBubble()
        buildSpeechBubble()
        buildPanel()
        ProactiveWatcher.init(this)
        syncWakeWord()
        startWatchdogLoop()
        startMicHealthLoop()
        // Observador único da FSM: garante que as bordas iluminadas sigam 100% o estado LISTENING_USER
        scope.launch {
            AdaStateMachine.state.collect { snap ->
                if (snap.shouldShowGlowBorder) showGlow() else hideGlow()
            }
        }
        if (Prefs(this).monitoringServerEnabled) {
            runCatching { RealtimeMonitoringAPI.startServer(this) }
        }
        val ur = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    ProactiveWatcher.onDeviceUnlocked()
                }
            }
        }
        unlockReceiver = ur
        runCatching {
            registerReceiver(ur, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
        }
    }

    private var unlockReceiver: android.content.BroadcastReceiver? = null

    fun reloadVoiceProfile() {
        tts?.let { applyTtsVoiceProfile(it) }
    }

    /** Vozes pt-BR do aparelho, ranqueadas — delega ao TtsVoiceController. */
    fun listVoicesJson(): org.json.JSONObject = voice.listVoicesJson(tts)

    /** Fala um texto de teste com o perfil e a emoção atuais. */
    fun speakTest(text: String) {
        scope.launch { say(text) }
    }

    @Volatile private var bootAnnouncementSpoken = false

    private fun announceStartupSystemsReady() {
        if (bootAnnouncementSpoken) return
        bootAnnouncementSpoken = true
        scope.launch {
            delay(850)
            val a11yOk = AgentAccessibilityService.instance != null
            val sensorsOk = ProactiveWatcher.enableLight || ProactiveWatcher.enableAccel || ProactiveWatcher.enableProximity
            val kwsOk = OfflineWakeWordSpotter.isReady
            val mcpArr = runCatching { agent.mcp.toJson().optJSONArray("servers") }.getOrNull()
            val mcpOnline = if (mcpArr != null) {
                (0 until mcpArr.length()).count { idx ->
                    mcpArr.optJSONObject(idx)?.optString("last_status", "")?.startsWith("online") == true
                }
            } else 0

            val missing = mutableListOf<String>()
            if (!a11yOk) missing += "acessibilidade"
            if (!sensorsOk) missing += "sensores"
            if (!kwsOk) missing += "escuta offline"
            if (mcpOnline == 0) missing += "MCP"

            val msg = if (missing.isEmpty()) {
                "Iniciando cognição. Tudo ativo, X-Zero pronto!"
            } else {
                "Iniciando cognição. Atenção: ${missing.joinToString(", ")} não subiu. X-Zero pronto!"
            }
            log("🚀 $msg")
            xenonSprite.setAuraColor(Color.parseColor("#00F5D4"))
            xenonSprite.setAction(XenonSpriteView.Action.WAVE) {
                if (currentAgentPhase == AgentPhase.IDLE_RELAXED && !isJobActive()) {
                    xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                }
            }
            showSpeechBubble(msg, 4800L)
            say(msg)
        }
    }

    private fun applyTtsVoiceProfile(t: TextToSpeech) = voice.applyProfile(t)

    override fun onDestroy() {
        instance = null
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        a11yServiceObserver?.let { AccessibilityServiceObserver.unregister(this, it) }
        wakeActive = false
        vad.stop()
        runCatching { RealtimeMonitoringAPI.stopServer() }
        partialCommitJob?.cancel()
        micHealthJob?.cancel()
        hideGlow()
        hideSpeechBubble()
        ProactiveWatcher.shutdown()
        floatAnimator?.cancel()
        scope.coroutineContext[Job]?.cancel()
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioRecordingCallback?.let { am?.unregisterAudioRecordingCallback(it) }
            abandonTransientAudioFocus()
        }
        runCatching { wm.removeView(bubble) }
        runCatching { if (panelShown) wm.removeView(panel) }
        tts?.shutdown(); recognizer?.destroy()
        super.onDestroy()
    }

    private fun showGlow() {
        if (glowView != null) return
        try {
            val v = GlowOverlayView(this)
            wm.addView(v, GlowOverlayView.makeLayoutParams())
            glowView = v
        } catch (e: Exception) {
            log("Erro ao mostrar glow: ${e.message}")
        }
    }

    private fun hideGlow() {
        glowView?.let {
            it.stop()
            runCatching { wm.removeView(it) }
        }
        glowView = null
    }

    fun ensureCameraForegroundService() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { startFg(ProactiveWatcher.isRecordingAudio) }
        }
    }

    private fun startFg(recordingAudio: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agente", "X0 / X-Zero", NotificationManager.IMPORTANCE_LOW))
        val title = if (recordingAudio) "🔴 X0 / X-Zero: Gravação de Áudio Ativa" else "X0 / X-Zero • Modo Live Ativo"
        val sub = if (recordingAudio) "Microfone captando ambiente (.m4a) com consentimento" else "Ouvindo 'X0' / 'X-Zero', Shake ou Modo Live ✨"
        val n = Notification.Builder(this, "agente")
            .setContentTitle(title)
            .setContentText(sub)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        val hasCam = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fgTypes = if (hasCam && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        try {
            startForeground(1, n, fgTypes)
        } catch (e: Exception) {
            android.util.Log.w("AgentePhone", "startFg MICROPHONE/CAMERA fallback para SPECIAL_USE: ${e.message}")
            runCatching {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        }
    }

    fun updateRecordingIndicator(recording: Boolean) {
        scope.launch {
            startFg(recording)
            if (recording) {
                statusBadge.text = "🔴 REC"
                statusBadge.visibility = View.VISIBLE
                xenonSprite.setAuraColor(Color.parseColor("#FF1744"))
                showSpeechBubble("🔴 Gravação de áudio ambiente iniciada (.m4a)", 4500L)
            } else {
                setBubbleStatus("idle")
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float) = v * resources.displayMetrics.density

    private var apparentOffsetXpx = 0f
    private var apparentOffsetYpx = 0f
    private var floatOffsetYpx = 0f
    private var aliveLoopJob: Job? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val slotW = dp(112)
        val slotH = dp(122)

        bubble = FrameLayout(this).apply {
            alpha = 0.98f
            elevation = 22f
            clipChildren = false
            clipToPadding = false
        }

        // Mantém haloRing invisível apenas para compatibilidade interna, pois a aura neon agora é desenhada sob os pés do Xenon!
        haloRing = View(this).apply { visibility = View.GONE }
        bubble.addView(haloRing, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        xenonSprite = XenonSpriteView(this).apply {
            onApparentMotionListener = { dxDp, dyDp, _, _ ->
                apparentOffsetXpx = dpF(dxDp)
                apparentOffsetYpx = dpF(dyDp)
                syncSpeechWithXenonMotion()
            }
        }
        bubble.addView(xenonSprite, FrameLayout.LayoutParams(slotW, slotH, Gravity.CENTER))

        statusBadge = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EE0B132B"))
                setStroke(dp(1), Color.parseColor("#35D7FF"))
            }
            visibility = View.GONE
        }
        bubble.addView(
            statusBadge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )

        bubbleLp = WindowManager.LayoutParams(
            dp(116), dp(126),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(6)
            y = dp(280)
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        var longPressed = false
        var longPressJob: Job? = null
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubble.alpha = 1f
                    if (isSpeakingTts) {
                        interruptTtsBargeIn("Toque no Xenon")
                    }
                    sx = bubbleLp.x; sy = bubbleLp.y; tx = e.rawX; ty = e.rawY; moved = false
                    longPressed = false
                    longPressJob?.cancel()
                    longPressJob = scope.launch {
                        delay(520L)
                        if (!moved) {
                            longPressed = true
                            activateFromAssistantTrigger()
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) {
                        moved = true
                        longPressJob?.cancel()
                    }
                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    bubbleLp.x = (sx + dx).coerceIn(0, screenW - dp(116))
                    bubbleLp.y = (sy + dy).coerceIn(dp(36), screenH - dp(160))
                    wm.updateViewLayout(bubble, bubbleLp)
                    updateSpeechBubblePosition()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                    bubble.alpha = 0.98f
                    if (longPressed) {
                        // Escuta manual já ativada pelo toque longo
                    } else if (!moved) {
                        togglePanel()
                    } else {
                        // Soltar após arrastar mantém exatamente a posição escolhida;
                        // não há mais "snap" automático para uma borda.
                        if (!isJobActive() && currentAgentPhase == AgentPhase.IDLE_RELAXED) {
                            showFunctionalAction(XenonSpriteView.Action.IDLE)
                        }
                    }
                }
            }
            true
        }
        bubble.setOnLongClickListener { activateFromAssistantTrigger(); true }
        wm.addView(bubble, bubbleLp)
        startFloatingAnimation()
        startAliveLoop()
    }

    /**
     * Constrói o Label/Balão de fala ampliado que acompanha o movimento aparente do Xenon.
     */
    private fun buildSpeechBubble() {
        speechView = TextView(this).apply {
            setTextColor(Color.parseColor("#F4FCFF"))
            textSize = 14.5f
            setLineSpacing(dpF(2.5f), 1.1f)
            maxWidth = dp(274)
            minWidth = dp(148)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            elevation = 28f
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FC061126")) // Alta opacidade para leitura limpa sobre qualquer app
                setStroke(dp(2), Color.parseColor("#EE35D7FF"))
            }
        }
        speechLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(118)
            y = dp(268)
        }
    }

    private fun computeSpeechBaseX(): Int {
        val screenW = resources.displayMetrics.widthPixels
        val estW = if (speechView.width > 0) speechView.width else dp(260)
        val rightX = bubbleLp.x + dp(106)
        return if (rightX + estW <= screenW - dp(8)) {
            rightX
        } else {
            (bubbleLp.x - estW + dp(6)).coerceAtLeast(dp(8))
        }
    }

    private fun computeSpeechBaseY(): Int {
        return (bubbleLp.y - dp(8)).coerceAtLeast(dp(36))
    }

    private fun syncSpeechWithXenonMotion() {
        if (!speechShown) return
        speechLp.x = computeSpeechBaseX() + apparentOffsetXpx.toInt()
        speechLp.y = (computeSpeechBaseY() + apparentOffsetYpx.toInt() + floatOffsetYpx.toInt()).coerceAtLeast(dp(28))
        runCatching { wm.updateViewLayout(speechView, speechLp) }
    }

    private fun updateSpeechBubblePosition() {
        if (!speechShown) return
        syncSpeechWithXenonMotion()
    }

    fun showSpeechBubble(text: String, durationMs: Long = 5400L) {
        if (text.isBlank()) return
        scope.launch {
            val clean = text.trim().take(240)
            val formatted = if (clean.startsWith("⚡") || clean.startsWith("🔴") || clean.startsWith("👀") || clean.startsWith("👋") || clean.startsWith("🎙️") || clean.startsWith("⏳") || clean.startsWith("📌")) {
                clean
            } else {
                "⚡ XZERO: $clean"
            }
            speechView.text = formatted
            latestLiveSpeechText = formatted
            speechLp.x = computeSpeechBaseX()
            speechLp.y = computeSpeechBaseY()

            if (!speechShown) {
                runCatching {
                    speechView.alpha = 0f
                    wm.addView(speechView, speechLp)
                    speechShown = true
                    syncSpeechWithXenonMotion()
                    speechView.animate().alpha(1f).setDuration(180).start()
                }
            } else {
                syncSpeechWithXenonMotion()
                runCatching { wm.updateViewLayout(speechView, speechLp) }
            }

            speechHideJob?.cancel()
            speechHideJob = scope.launch {
                delay(durationMs)
                hideSpeechBubble()
            }
        }
    }

    fun hideSpeechBubble() {
        speechHideJob?.cancel()
        if (speechShown) {
            runCatching { wm.removeView(speechView) }
            speechShown = false
        }
    }

    /**
     * Acionado quando o usuário sacode o celular (shake gesture no acelerômetro).
     * - Se o XZERO já estiver PENSANDO/EXECUTANDO (isJobActive == true), ativa o `/btw` por voz em paralelo
     *   e mostra na tela "📌🎙️ PENSANDO + OUVINDO /BTW" para o usuário saber que ativou!
     * - Se estiver ocioso, abre a escuta imediata de comando.
     */
    fun onShakeDetected() {
        scope.launch {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isInteractive != true) return@launch
            if (isSpeakingTts) {
                interruptTtsBargeIn("Shake Barge-In")
            }
            agent.ambient.notifySensorPickupOrAttention(3.0, 22_000L)
            if (isJobActive()) {
                agent.ambient.armBtwMode(22_000L)
                agent.notifyUserStartedSpeakingBtw()
                setBubbleStatus("📌🎙️")
                showSpeechBubble("⏸️📌🎙️ /btw Ativo (Shake)! Pausei o passo atual e estou ouvindo: fale para incluir contexto, recalcular rota ou cancelar…", 5200L)
                log("📳📌 Shake durante execução -> Fluxo primário pausado + Modo /btw ativado!")
                if (Prefs(this@BubbleService).subtleSound) SoundFeedback.playListening()
                startRecognizer(isManual = false)
            } else {
                xenonSprite.setAction(XenonSpriteView.Action.JUMP)
                showSpeechBubble("👋 Opa, hã? Tô aqui…", 4200L)
                log("📳 Shake detectado! Escuta rápida ativada.")
                if (Prefs(this@BubbleService).subtleSound) SoundFeedback.playListening()
                listenManual(resetAttempts = true)
            }
        }
    }

    /**
     * Acionado pela rotina de sensores em background quando o usuário pega o celular da mesa (Pick-Up Gesture).
     * Zero consumo de tokens LLM: apenas aumenta a sensibilidade do script de intenção e garante o mic pronto!
     */
    fun onPhonePickedUp() {
        scope.launch {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isInteractive != true) return@launch
            agent.ambient.notifySensorPickupOrAttention(2.2, 18_000L)
            if (wakeActive && !manualListen && !isSpeakingTts) {
                ensureWakeRecognizerHealthy()
            }
        }
    }

    /**
     * Dispara alerta proativo quando uma vigia (WhatsApp, E-mail, Claude, Codex) é acionada.
     */
    fun triggerProactiveWatchAlert(
        rule: ProactiveWatcher.WatchRule,
        notifPkg: String,
        notifTitle: String,
        notifText: String
    ) {
        if (isJobActive()) return
        scope.launch {
            xenonSprite.setAction(XenonSpriteView.Action.JUMP)
            val shortAlert = "👀 Vigia [${rule.source}]: $notifTitle — ${notifText.take(80)}"
            showSpeechBubble(shortAlert, 6800L)
            log("🔔 $shortAlert")
            val proactiveTask = "EVENTO DE VIGIA ATIVA (${rule.source}): Notificação de '$notifTitle' dizendo '$notifText'. " +
                "Instrução combinada com o usuário: '${rule.instruction}'. " +
                "Analise rapidamente, se precisar pesquise com web_search, avise o usuário em 1 frase direta com sua sugestão e pergunte com ask_user se deve executar."
            handleText(proactiveTask, fromPanel = false)
        }
    }

    private fun startFloatingAnimation() {
        floatAnimator?.cancel()
        floatAnimator = ObjectAnimator.ofFloat(xenonSprite, View.TRANSLATION_Y, 0f, -dpF(4.0f), 0f).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                floatOffsetYpx = (anim.animatedValue as? Float) ?: 0f
                syncSpeechWithXenonMotion()
            }
            start()
        }
    }

    /**
     * Comportamento vivo autônomo:
     * Quando ocioso, o Xenon pisca, pensa com calma, mexe no notebook holográfico
     * e faz pequenos deslocamentos sutis (subindo, descendo e andando) pela tela.
     */
    private fun startAliveLoop() {
        aliveLoopJob?.cancel()
        aliveLoopJob = scope.launch {
            while (true) {
                delay((7500L..12000L).random())
                if (!isJobActive() && !manualListen && !isSpeakingTts && !recognizerOwnSessionActive &&
                    pending == null && currentAgentPhase == AgentPhase.IDLE_RELAXED &&
                    xenonSprite.getCurrentAction() == XenonSpriteView.Action.IDLE
                ) {
                    // Verifica primeiro se há algo importante para chamar a atenção do usuário (notificações acumuladas, bateria, armazenamento, erro recorrente)
                    val pendingSug = InitiativeEngine.sugestaoPendente()
                    if (pendingSug != null) {
                        callAttentionWithSuggestion(pendingSug.texto, pendingSug.id)
                        continue
                    }

                    val roll = (1..14).random()
                    when (roll) {
                        1, 2 -> xenonSprite.setAction(XenonSpriteView.Action.LOOKING_AROUND)
                        3 -> xenonSprite.setAction(XenonSpriteView.Action.PLAYING_HOLOGRAM)
                        4 -> xenonSprite.setAction(XenonSpriteView.Action.WAVING_SPONTANEOUSLY)
                        5 -> xenonSprite.setAction(XenonSpriteView.Action.FOOT_TAPPING)
                        6 -> xenonSprite.setAction(XenonSpriteView.Action.CURIOUS)
                        7 -> xenonSprite.setAction(XenonSpriteView.Action.DISCOVERY_REACTION)
                        8 -> xenonSprite.setAction(XenonSpriteView.Action.RESTING)
                        13 -> {
                            // Vai até a borda mais próxima e executa a descida do muro com aterrissagem
                            val dm = resources.displayMetrics
                            val edgeX = if (bubbleLp.x + dp(58) < dm.widthPixels / 2) dp(8) else (dm.widthPixels - dp(126)).coerceAtLeast(dp(8))
                            if (kotlin.math.abs(bubbleLp.x - edgeX) > dp(24)) {
                                moveSmoothlyTo(edgeX, bubbleLp.y)
                                delay(1600L)
                            }
                            xenonSprite.setAction(XenonSpriteView.Action.WALL_DESCENT) {
                                xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                            }
                        }
                        9, 10 -> {
                            // Pensa com calma por alguns segundos e volta ao repouso
                            xenonSprite.setAction(XenonSpriteView.Action.DEEP_THINKING)
                            delay(3400L)
                            if (!isJobActive() && currentAgentPhase == AgentPhase.IDLE_RELAXED) {
                                xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                            }
                        }
                        11 -> {
                            // Notebook holográfico: consolida aprendizado, escreve o diário e pode gerar sugestão importante
                            xenonSprite.setAction(XenonSpriteView.Action.LAPTOP_WORK)
                            scope.launch(Dispatchers.IO) {
                                val sugestao = runCatching {
                                    InitiativeEngine.tick(agent.getGraphForInitiative())
                                }.getOrNull()
                                if (sugestao != null) {
                                    withContext(Dispatchers.Main) {
                                        val id = InitiativeEngine.sugestaoPendente()?.id
                                        callAttentionWithSuggestion(sugestao, id)
                                    }
                                }
                            }
                            delay(4200L)
                            if (!isJobActive() && currentAgentPhase == AgentPhase.IDLE_RELAXED) {
                                xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                            }
                        }
                        12, 14 -> {
                            // Mantém o Xenon no lugar escolhido pelo usuário (sem sair andando sozinho pela tela);
                            // apenas olha ao redor ou descansa discretamente.
                            xenonSprite.setAction(
                                if (roll == 14) XenonSpriteView.Action.LOOKING_AROUND
                                else XenonSpriteView.Action.RESTING
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Chama a atenção do usuário de forma visível e contextual quando o Xenon percebe algo importante
     * (ex.: acúmulo de notificações, bateria/armazenamento, padrão de falha ou sugestão de pesquisa no GPT).
     */
    private fun callAttentionWithSuggestion(sugestao: String, sugestaoId: String?) {
        scope.launch {
            if (isJobActive() || manualListen) return@launch
            val dm = resources.displayMetrics
            val visibleX = if (bubbleLp.x < dm.widthPixels / 2) dp(12) else (dm.widthPixels - dp(126)).coerceAtLeast(dp(12))
            val visibleY = (dm.heightPixels * 0.28f).toInt().coerceIn(dp(72), dm.heightPixels - dp(220))
            moveSmoothlyTo(visibleX, visibleY, durationOverrideMs = 1100L)
            delay(350L)
            xenonSprite.setAction(XenonSpriteView.Action.JUMP, force = true) {
                xenonSprite.setAction(XenonSpriteView.Action.POINT, force = true) {
                    xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                }
            }
            log("💡 $sugestao")
            showSpeechBubble("💡 $sugestao", 9500L)
            latestLiveSpeechText = "💡 $sugestao"
            agent.ambient.openFollowUpWindow(20_000L)
            if (sugestaoId != null) {
                InitiativeEngine.marcarMostrada(sugestaoId)
            }
        }
    }

    /**
     * Chamado em tempo real pelo Co-Piloto Proativo (InitiativeEngine) quando detecta continuação
     * de tarefa (ex: usuário começou a limpar armazenamento), resultado de pesquisa trazida para a tela
     * ou alerta de log/armazenamento crítico.
     */
    fun mostrarSugestaoProativaImediata(sug: InitiativeEngine.Sugestao) {
        callAttentionWithSuggestion(sug.texto, sug.id)
    }

    private fun triggerTouchAnimation() {
        val rand = (1..5).random()
        when (rand) {
            1 -> xenonSprite.setAction(XenonSpriteView.Action.WAVE)
            2 -> xenonSprite.setAction(XenonSpriteView.Action.JUMP)
            3 -> xenonSprite.setAction(XenonSpriteView.Action.HOLOGRAM)
            4 -> xenonSprite.setAction(XenonSpriteView.Action.LAPTOP_WORK)
            else -> xenonSprite.setAction(XenonSpriteView.Action.EXPLAIN)
        }
    }

    /** Escolhe corrida lateral quando há deslocamento em X, ou caminhada frontal/de costas em Y. */
    private fun walkingActionFor(dx: Int, dy: Int = 0): XenonSpriteView.Action {
        if (kotlin.math.abs(dx) > dp(12) && kotlin.math.abs(dx) >= kotlin.math.abs(dy) / 2) {
            lastWalkingAction = if (dx > 0) XenonSpriteView.Action.RUN_RIGHT else XenonSpriteView.Action.RUN_LEFT
            return lastWalkingAction
        }
        if (dy > dp(16)) return XenonSpriteView.Action.WALK_FORWARD
        if (dy < -dp(16)) return XenonSpriteView.Action.WALK_BACKWARD
        return lastWalkingAction
    }

    /**
     * Move o Xenon livremente em 2D. A descida do muro fica reservada ao gesto
     * explícito de muro, e não é mais uma falsa animação de "andar para baixo".
     */
    fun moveSmoothlyTo(targetX: Int, targetY: Int = bubbleLp.y, durationOverrideMs: Long? = null) {
        val dm = resources.displayMetrics
        val minX = dp(8)
        val maxX = (dm.widthPixels - dp(126)).coerceAtLeast(minX)
        val minY = dp(64)
        val maxY = (dm.heightPixels - dp(210)).coerceAtLeast(minY)
        val clampedX = targetX.coerceIn(minX, maxX)
        val clampedY = targetY.coerceIn(minY, maxY)

        val startX = bubbleLp.x
        val startY = bubbleLp.y
        val dx = clampedX - startX
        val dy = clampedY - startY
        if (dx == 0 && dy == 0) return

        runAnimator?.cancel()
        val moveId = ++movementSequence
        xenonSprite.setAction(walkingActionFor(dx, dy), force = true)

        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val dur = durationOverrideMs ?: (dist * 3.8f).toLong().coerceIn(1450L, 3000L)

        runAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                bubbleLp.x = (startX + dx * f).toInt()
                bubbleLp.y = (startY + dy * f).toInt()
                runCatching { wm.updateViewLayout(bubble, bubbleLp) }
                updateSpeechBubblePosition()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (moveId != movementSequence) return
                    if (!isJobActive() && !manualListen && currentAgentPhase == AgentPhase.IDLE_RELAXED) {
                        showFunctionalAction(XenonSpriteView.Action.IDLE)
                    } else {
                        showFunctionalAction(visualForPhase(currentAgentPhase))
                    }
                    updateSpeechBubblePosition()
                }
            })
            start()
        }
    }

    /**
     * Move o Xenon horizontalmente na tela executando os sprites de corrida (RUN_RIGHT / RUN_LEFT),
     * levando o balão de fala sincronizado!
     */
    fun runToEdge(targetX: Int) {
        moveSmoothlyTo(targetX, bubbleLp.y)
    }

    /**
     * Tenta interpretar comandos diretos de movimentação/pose do Ada ("vai pra direita", "sobe", "desce", "notebook").
     * Retorna true se tratou localmente com animação fluida.
     */
    fun moveAvatarByCommand(command: String): Boolean = tryHandleAvatarMotionCommand(command)

    /**
     * Interpreta pedidos curtos e claros voltados ao movimento/pose do próprio Xenon:
     * direita, esquerda, cima, baixo, centro, cantos, notebook e muro.
     */
    private fun tryHandleAvatarMotionCommand(raw: String): Boolean =
        AvatarMotionController.tryHandleCommand(
            raw = raw,
            currentX = bubbleLp.x,
            currentY = bubbleLp.y,
            dm = resources.displayMetrics,
            dp = ::dp,
            onMoveTo = { tx, ty, speech ->
                moveSmoothlyTo(tx, ty)
                showSpeechBubble(speech, 2200L)
            },
            onAction = { act, speech, returnToIdle ->
                if (returnToIdle) {
                    xenonSprite.setAction(act, force = true) {
                        xenonSprite.setAction(XenonSpriteView.Action.IDLE)
                    }
                } else {
                    xenonSprite.setAction(act, force = true)
                }
                showSpeechBubble(speech, 2800L)
            }
        )

    fun triggerDemoAnimation(animName: String, speech: String?, targetX: Int) {
        scope.launch {
            if (!speech.isNullOrBlank()) {
                showSpeechBubble(speech, 6500L)
            }
            if (targetX >= 0) {
                runToEdge(targetX)
                return@launch
            }
            val act = runCatching { XenonSpriteView.Action.valueOf(animName) }.getOrDefault(XenonSpriteView.Action.WAVE)
            showFunctionalAction(act)
        }
    }

    fun setBubbleStatus(status: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { setBubbleStatus(status) }
            return
        }
        val strokeColor: Int
        val badgeText: String
        val showBadge: Boolean

        if (ProactiveWatcher.isRecordingAudio) {
            xenonSprite.setAuraColor(Color.parseColor("#FF1744"))
            statusBadge.text = "🔴 REC"
            statusBadge.visibility = View.VISIBLE
            return
        }

        when {
            status.startsWith("⚡") -> {
                val actName = status.removePrefix("⚡").trim().ifBlank { "executando" }
                strokeColor = Color.parseColor("#00E676") // Verde elétrico - em ação
                badgeText = "⚡ EM AÇÃO: $actName"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.EXECUTING_TASK)
            }
            status.startsWith("🔎") -> {
                strokeColor = Color.parseColor("#8B5CF6")
                badgeText = "🔎 PESQUISANDO"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.RESEARCHING_WEB)
            }
            status.startsWith("📚") -> {
                strokeColor = Color.parseColor("#A855F7")
                badgeText = "📚 APRENDENDO"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.LEARNING_NEW)
            }
            status.startsWith("🛠️") -> {
                strokeColor = Color.parseColor("#EF4444")
                badgeText = "🛠️ RECUPERANDO"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.RECOVERING_ERROR, force = true)
            }
            status.startsWith("✅") -> {
                strokeColor = Color.parseColor("#22C55E")
                badgeText = "✅ SUCESSO VERIFICADO"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.SUCCESS_CONFIRMED, force = true)
            }
            status.contains("📌🎙️") -> {
                strokeColor = Color.parseColor("#00F5D4") // Ciano/Neon - /btw ativo enquanto pensa
                badgeText = "📌🎙️ PENSANDO + OUVINDO /BTW"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.DEEP_THINKING)
            }
            status.contains("⏳🎙️") || (status.contains("⏳") && wakeActive) -> {
                strokeColor = Color.parseColor("#F72585") // Rosa/Magenta + escuta ativa em paralelo
                badgeText = "🧠🎙️ PENSANDO + OUVINDO"
                showBadge = true
                showFunctionalAction(if (currentAgentPhase == AgentPhase.ACTING) XenonSpriteView.Action.EXECUTING_TASK else XenonSpriteView.Action.DEEP_THINKING)
            }
            status.contains("⏳") -> {
                strokeColor = Color.parseColor("#F72585") // Rosa/Magenta - pensando
                badgeText = "🧠 PENSANDO"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.DEEP_THINKING)
            }
            status.contains("👂❓") -> {
                strokeColor = Color.parseColor("#FF9100") // Laranja - dúvida / correção de rota
                badgeText = "❓ DÚVIDA"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.ASKING_HELP)
            }
            status.contains("👂") -> {
                strokeColor = Color.parseColor("#00F5D4") // Ciano neon - ouvindo atento + vendo tela
                val actuallyListening = recognizerOwnSessionActive && recognizer != null
                badgeText = if (actuallyListening) {
                    if (isJobActive()) "🧠🎙️ PENSANDO + OUVINDO" else "👂 ATENTO"
                } else if (isJobActive()) "🧠 PENSANDO" else "🤖 PRONTO"
                showBadge = true
                showFunctionalAction(
                    if (actuallyListening) XenonSpriteView.Action.LISTENING_ATTENTIVELY
                    else if (isJobActive()) XenonSpriteView.Action.DEEP_THINKING
                    else XenonSpriteView.Action.IDLE
                )
            }
            status.contains("💬🎙️") -> {
                strokeColor = Color.parseColor("#00F5D4") // Ciano Live Follow-up
                val actuallyListening = recognizerOwnSessionActive && recognizer != null
                badgeText = if (actuallyListening) "💬🎙️ LIVE ATENTO" else "💬 LIVE PRONTO"
                showBadge = true
                showFunctionalAction(if (actuallyListening) XenonSpriteView.Action.LISTENING_ATTENTIVELY else XenonSpriteView.Action.IDLE)
            }
            status.contains("❓") -> {
                strokeColor = Color.parseColor("#FFB703") // Âmbar - pergunta
                badgeText = "💬 PERGUNTA"
                showBadge = true
                showFunctionalAction(XenonSpriteView.Action.ASKING_HELP)
            }
            else -> {
                strokeColor = Color.parseColor("#35D7FF") // Azul ciano calmo - parado/despreocupado
                val inLive = wakeActive && agent.ambient.isInFollowUpWindow()
                badgeText = if (inLive) "💬🎙️ LIVE" else ""
                showBadge = inLive
                showFunctionalAction(XenonSpriteView.Action.IDLE)
            }
        }

        xenonSprite.setAuraColor(strokeColor)

        if (showBadge) {
            statusBadge.text = badgeText
            statusBadge.visibility = View.VISIBLE
        } else {
            statusBadge.visibility = View.GONE
        }
    }

    private fun buildPanel() {
        logView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val scroll = ScrollView(this).apply { addView(logView) }
        input = EditText(this).apply {
            hint = "Peça algo… (ex: abre o WhatsApp)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine()
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        val mic = Button(this).apply { text = "🎤"; setOnClickListener { listenManual() } }
        val send = Button(this).apply { text = "Enviar"; setOnClickListener { submit() } }
        val stop = Button(this).apply { text = "■"; setOnClickListener { job?.cancel(); log("⏹ interrompido") } }
        val close = Button(this).apply { text = "✕"; setOnClickListener { togglePanel() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f)); addView(mic); addView(send); addView(stop); addView(close)
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#EE1E1E2E")) }
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); addView(row)
        }
        panelLp = WindowManager.LayoutParams(-1, dp(340),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        log("Olá! Chame 'X0' / 'X-Zero' (ou 'X0 <comando>') ou toque 🎤.")
    }

    private fun togglePanel() {
        if (panelShown) wm.removeView(panel) else wm.addView(panel, panelLp)
        panelShown = !panelShown
    }

    private fun hidePanel() { if (panelShown) togglePanel() }

    private fun submit() {
        val t = input.text.toString().trim(); if (t.isEmpty()) return
        input.setText(""); handleText(t, fromPanel = true)
    }

    /**
     * Garante que o usuário (e apps como WhatsApp, Ligações, Gravador) tenha PRIORIDADE TOTAL
     * sobre o uso do microfone. Se outro app iniciar gravação ou chamada, o XZERO libera o mic na hora!
     * IMPORTANTE: Usa janela de proteção (`recognizerStartMs`) para não confundir o próprio SpeechRecognizer
     * do XZERO no momento em que o Android abre o AudioRecord antes de chamar `onReadyForSpeech`!
     */
    private fun setupUserMicPriorityMonitor() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            val cb = object : android.media.AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                    val list = configs ?: emptyList()
                    val now = System.currentTimeMillis()
                    val inCallOrComm = am.mode == android.media.AudioManager.MODE_IN_CALL ||
                        am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                        am.mode == android.media.AudioManager.MODE_RINGTONE

                    val vadGrace = vad.isRunning || vadHandoffPending ||
                        (now - vad.lastStartMs) < 3500L ||
                        (now - lastVadHandoffMs) < 5000L ||
                        (now - vad.lastVoiceTriggerMs) < 5000L
                    val ownStartupGrace = (now - recognizerStartMs) < 15_000L || vadGrace
                    val ownTeardownGrace = (now - lastRecognizerEndMs) < 3500L
                    val ownActive = manualListen ||
                        pending != null ||
                        recognizerSessionInFlight ||
                        recognizerOwnSessionActive ||
                        ownStartupGrace ||
                        ownTeardownGrace ||
                        vad.isRunning

                    // Nota técnica (celular Android / Android 14): o Google SpeechRecognizer abre
                    // 2 sessões AudioRecordingConfiguration simultâneas (NetworkSpeechRecognizer + SodaSpeechRecognizer).
                    // Portanto, nunca usamos `list.size > 1` para cortar nossa própria sessão ativa!
                    val externalAppUsingMic = inCallOrComm ||
                        (list.isNotEmpty() && !ownActive && !ProactiveWatcher.isRecordingAudio)

                    if (externalAppUsingMic) {
                        yieldMicForUserPriority(8000L, "App externo/chamada usando o microfone")
                    } else if (agent.ambient.userMicPriorityYielded && list.isEmpty() && !inCallOrComm) {
                        agent.ambient.userMicPriorityYielded = false
                        userMicYieldUntilMs = 0L
                        if (wakeActive && !manualListen) {
                            scheduleWakeRestart(900)
                        }
                    }
                }
            }
            audioRecordingCallback = cb
            am.registerAudioRecordingCallback(cb, null)
        }
    }

    fun yieldMicForUserPriority(durationMs: Long = 10_000L, reason: String = "Prioridade de microfone do usuário") {
        AudioTelemetry.onMicYield(reason)
        userMicYieldUntilMs = System.currentTimeMillis() + durationMs
        agent.ambient.userMicPriorityYielded = true
        if (!manualListen && pending == null) {
            vad.stop()
            scope.launch {
                cancelRecognizerSession(destroyInstance = true)
            }
            android.util.Log.i("AgentePhone", "🎙️ Prioridade de Mic cedida ao usuário ($reason)")
            scheduleWakeRestart(durationMs + 500L)
        }
    }

    private fun isUserUsingMicInOtherApp(): Boolean {
        val now = System.currentTimeMillis()
        if (now < userMicYieldUntilMs) return true
        val am = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        if (am.mode == android.media.AudioManager.MODE_IN_CALL ||
            am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == android.media.AudioManager.MODE_RINGTONE
        ) {
            agent.ambient.userMicPriorityYielded = true
            return true
        }
        agent.ambient.userMicPriorityYielded = false
        return false
    }

    /**
     * O usuário pediu para continuar a tarefa que ficou pela metade?
     * Só conta como retomada se existir um checkpoint órfão de verdade.
     */
    private fun pedidoDeRetomada(t: String): Boolean {
        if (TaskCheckpoint.orphan() == null) return false
        val n = SemanticMatcher.normalize(t)
        if (n.split(" ").size > 6) return false
        val gatilhos = listOf(
            "continua", "continue", "continuar", "retoma", "retomar", "retome",
            "termina aquilo", "termina isso", "acaba aquilo", "volta naquilo",
            "segue", "segue aquilo", "pode continuar", "continua de onde parou",
        )
        return gatilhos.any { " $n ".contains(" $it ") }
    }

    /** Ao subir, avisa (sem falar alto) se ficou tarefa pela metade. */
    private fun avisarSeHouverTarefaInterrompida() {
        val s = TaskCheckpoint.orphan() ?: return
        scope.launch {
            delay(2500)
            val resumo = "↩️ Tarefa interrompida no passo ${s.step}: \"${s.goal.take(60)}\" — diga \"continua\" para retomar"
            log(resumo)
            showSpeechBubble(resumo, 7000L)
            latestLiveSpeechText = resumo
        }
    }

    /** Ponto de entrada de texto (painel, voz ou broadcast adb). */
    fun handleText(t: String, fromPanel: Boolean = false) {
        InitiativeEngine.marcarAtividade()
        if (isSpeakingTts) interruptTtsBargeIn("Nova entrada do usuário")
        speechConsolidationJob?.cancel()
        accumulatedConsolidationSegments.clear()
        manualListen = false
        manualListenAttempts = 0
        cancelRecognizerSession(destroyInstance = false)
        log("🧑 $t")
        pending?.let { pending = null; it.complete(t); return }
        if (tryHandleAvatarMotionCommand(t)) {
            if (wakeActive) scheduleWakeRestart(250)
            return
        }

        // Verifica se é uma injeção de contexto em paralelo estilo `/btw` do Claude ou fala durante execução ativa
        val btwArmedBefore = agent.ambient.isBtwArmed()
        val btwDirective = agent.ambient.detectBtwTrigger(t, isJobActive())
        if (btwDirective != null && (isJobActive() || btwArmedBefore || AmbientContextEngine.BTW_PREFIXES.any { t.lowercase(Locale("pt", "BR")).startsWith(it) })) {
            if (btwDirective.type == AmbientContextEngine.BtwDirectiveType.CANCEL_CURRENT_TASK) {
                VoiceSemantics.repudiateLastSemanticAccept("cancelado_por_voz_durante_tarefa")
                job?.cancel()
                agent.ambient.clearShortTermMemory()
                setBubbleStatus("🤖")
                hideGlow()
                val msg = "Tarefa cancelada como pediu."
                log("🛑 $msg"); say(msg)
                if (wakeActive) scheduleWakeRestart(1800)
                return
            }
            agent.ambient.enqueueBtwDirective(btwDirective, isJobActive())
            if (isJobActive()) {
                val modeDesc = when (btwDirective.type) {
                    AmbientContextEngine.BtwDirectiveType.RECALCULATE_ROUTE -> "recalculando rota em paralelo"
                    AmbientContextEngine.BtwDirectiveType.SWITCH_MAIN_TASK -> "trocando objetivo principal"
                    else -> "contexto /btw incluído em paralelo"
                }
                setBubbleStatus("⏳🎙️")
                log("📌 [/btw ativo]: ${btwDirective.cleanText} ($modeDesc)")
                showSpeechBubble("📌🎙️ /btw absorvido ($modeDesc): ${btwDirective.cleanText.take(65)}", 3800L)
                if (wakeActive) scheduleWakeRestart(450)
                return
            } else {
                val msg = "📌 Contexto /btw guardado em paralelo: '${btwDirective.cleanText}'. Pode falar o objetivo principal!"
                log(msg)
                showSpeechBubble(msg, 4200L)
                if (wakeActive) scheduleWakeRestart(600)
                return
            }
        }
        // Se falou apenas "xzero" ou "x0" enquanto já estava pensando, arma o /btw e avisa visualmente!
        if (isJobActive() && agent.ambient.isBtwArmed() && !btwArmedBefore) {
            setBubbleStatus("📌🎙️")
            log("📌🎙️ [Wake durante execução]: /btw armado! Ouvindo instrução paralela...")
            showSpeechBubble("📌🎙️ Estou pensando e ouvindo seu /btw! Pode falar o ajuste…", 4800L)
            if (wakeActive) scheduleWakeRestart(350)
            return
        }

        // Retomada: "continua" recupera o objetivo original do checkpoint em vez de
        // tratar a palavra solta como uma tarefa nova.
        val retomando = pedidoDeRetomada(t)
        val tarefaEfetiva = if (retomando) {
            val cp = TaskCheckpoint.orphan()!!
            log("↩️ Retomando: \"${cp.goal.take(70)}\" (parou no passo ${cp.step})")
            cp.goal
        } else {
            t
        }

        job = scope.launch {
            lastTaskGoalVerified = null
            setBubbleStatus(if (wakeActive) "⏳🎙️" else "⏳")
            hideGlow()
            hidePanel() // libera 100% da tela para o agente ou uso manual
            // Mantém o microfone escutando EM PARALELO enquanto pensa (para /btw, Shake ou "X0" novamente!)
            if (wakeActive) {
                showSpeechBubble("⏳🎙️ Pensando... (Ouvindo /btw, 'X0' ou agite)", 2600L)
                scheduleWakeRestart(180)
            }
            val ans = try {
                agent.run(tarefaEfetiva, resumingFromCheckpoint = retomando)
            } finally {
                HealthMonitor.markTaskEnd()
                hideGlow()
            }
            // Abre janela de 60s estilo ChatGPT Live para conversa contínua fluida sem precisar repetir "X0"
            agent.ambient.openFollowUpWindow(60_000L)
            val goalVerified = lastTaskGoalVerified == true
            if (goalVerified) {
                // A única porta de entrada para comemoração: callback do GoalVerifier.
                onAgentPhaseChanged(AgentPhase.CONFIRMING_SUCCESS, "objetivo validado")
                scope.launch {
                    delay(1900L)
                    if (!isJobActive()) {
                        onAgentPhaseChanged(if (wakeActive) AgentPhase.ATTENTIVE_LISTENING else AgentPhase.IDLE_RELAXED, "pós-validação")
                    }
                }
            } else {
                currentAgentPhase = if (wakeActive && recognizerOwnSessionActive) AgentPhase.ATTENTIVE_LISTENING else AgentPhase.IDLE_RELAXED
                setBubbleStatus(if (currentAgentPhase == AgentPhase.ATTENTIVE_LISTENING) "💬🎙️" else "🤖")
            }
            log("🤖 $ans"); say(ans)
            // Só reabre o painel se o usuário enviou digitando pelo painel; por voz deixa a tela livre para uso manual
            if (fromPanel && !panelShown) togglePanel()
            if (wakeActive) scheduleWakeRestart(250)
        }
    }

    fun isJobActive(): Boolean = job?.isActive == true

    fun getCurrentVisualStateSummary(): org.json.JSONObject = org.json.JSONObject()
        .put("phase", currentAgentPhase.name)
        .put("sprite_action", if (::xenonSprite.isInitialized) xenonSprite.getCurrentAction().name else "UNKNOWN")
        .put("job_active", isJobActive())
        .put("manual_listen", manualListen)
        .put("speaking_tts", isSpeakingTts)
        .put("follow_up_window", agent.ambient.isInFollowUpWindow())

    fun activateFromAssistantTrigger() {
        scope.launch {
            if (panelShown) hidePanel()
            listenManual(resetAttempts = true, playChime = true)
        }
    }

    fun abortStuckJob(reason: String) {
        scope.launch {
            job?.cancel()
            pending?.cancel()
            pending = null
            HealthMonitor.markTaskEnd()
            HealthMonitor.recordError(reason)
            setBubbleStatus("🤖")
            log("🛠 Auto-recuperação: $reason")
            say("Tive um travamento, mas já me recuperei.")
        }
    }

    fun ensureWakeRecognizerHealthy(): Boolean {
        if (!wakeActive || manualListen || isSpeakingTts || vadHandoffPending || isUserUsingMicInOtherApp()) return false
        val now = android.os.SystemClock.elapsedRealtime()
        val idleMs = if (lastWakeEventMs > 0) now - lastWakeEventMs else 99_999L
        // No modo VAD silencioso, apenas garante que o AudioRecord VAD esteja rodando sem reiniciar SpeechRecognizer no silêncio
        if (!recognizerSessionInFlight && !recognizerOwnSessionActive && !vad.isRunning) {
            vad.start()
            return true
        }
        // Se um SpeechRecognizer ativo travar por muito tempo sem eventos, destrói e volta para o VAD silencioso
        if ((recognizerSessionInFlight || recognizerOwnSessionActive) &&
            ((!recognizerOwnSessionActive && idleMs > 4_000L) || idleMs > 18_000L)
        ) {
            scope.launch {
                cancelRecognizerSession(destroyInstance = true)
                vad.start()
            }
            return true
        }
        return false
    }

    private fun startMicHealthLoop() {
        micHealthJob?.cancel()
        micHealthJob = scope.launch {
            while (true) {
                delay(4_000L)
                runCatching {
                    VadAutoTuner.decayTick()
                    if (wakeActive && !manualListen && !isSpeakingTts && !isUserUsingMicInOtherApp()) {
                        ensureWakeRecognizerHealthy()
                    }
                }
            }
        }
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(15_000)
                runCatching {
                    val report = HealthMonitor.checkAndHeal(this@BubbleService, heal = true)
                    val healed = report.optString("healed_actions")
                        .split(",")
                        .filter { it.isNotBlank() && it != "wake_recognizer_restarted" }
                        .joinToString(",")
                    if (healed.isNotEmpty()) {
                        log("🩺 Healthcheck auto-recuperou: $healed")
                    }
                }
            }
        }
    }

    fun syncWakeWord() {
        val want = Prefs(this).wakeWord
        if (want && !wakeActive) {
            wakeActive = true
            log("👂 escuta silenciosa VAD 'X0 / X-Zero' (Modo Live) ativada")
            vad.start()
        } else if (!want && wakeActive) {
            wakeActive = false
            vad.stop()
            cancelRecognizerSession(destroyInstance = true)
            setBubbleStatus("🤖")
            log("🔇 escuta 'X0 / X-Zero' desativada")
        }
    }

    private fun listenManual(resetAttempts: Boolean = true, playChime: Boolean = false) {
        if (resetAttempts) manualListenAttempts = 0
        manualListen = true
        vadHandoffPending = false
        vad.stop()
        val hadActiveRecognizer = recognizerSessionInFlight || recognizerOwnSessionActive
        cancelRecognizerSession(destroyInstance = false)
        requestTransientAudioFocus()
        showGlow()
        setBubbleStatus("👂")
        if (!isJobActive()) {
            onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "escuta_manual")
        }
        scope.launch {
            if (playChime && Prefs(this@BubbleService).subtleSound) {
                SoundFeedback.playListening()
                delay(45L)
            } else if (hadActiveRecognizer) {
                // Dá tempo ao SodaSpeechRecognizer para fechar a sessão anterior sem colidir (evita erro=5/erro=7)
                delay(110L)
            }
            if (manualListen || pending != null) {
                startRecognizer(isManual = true)
            }
        }
    }

    private fun scheduleWakeRestart(ms: Long = 380) {
        if (!wakeActive) return
        scope.launch {
            delay(ms)
            // Aguarda o TTS terminar completamente antes de reabrir o microfone para nunca transcrever a própria voz!
            var waitedTts = 0
            while (isSpeakingTts && waitedTts < 36) {
                delay(500)
                waitedTts++
            }
            if (waitedTts > 0) delay(550) // margem acústica pós-fala
            if (isUserUsingMicInOtherApp()) {
                scheduleWakeRestart(2500)
                return@launch
            }
            agent.ambient.applyDecayAndPrune()
            if (wakeActive && !manualListen && !isSpeakingTts && !recognizerSessionInFlight && !recognizerOwnSessionActive) {
                // Só pula o VAD durante tarefa ativa (/btw) ou consolidação em andamento;
                // em silêncio (mesmo na janela de follow-up), volta ao VAD para não gerar loop de erro=7 a cada 4s.
                if (isJobActive() || accumulatedConsolidationSegments.isNotEmpty()) {
                    startRecognizer(isManual = false)
                } else {
                    vad.start()
                }
            }
        }
    }

    /**
     * Detecta a wake word "XZERO" / "ECS ZERO" / "X0" (mantido para compatibilidade direta)
     */
    fun extractWakeCommand(raw: String): String? {
        return agent.ambient.detectWakeName(raw)?.second
    }

    /**
     * Agenda o envio consolidado de múltiplos trechos de fala ("speech consolidation debounce"):
     * - Quando o usuário faz uma pausa no meio de um pensamento ou fala em 2-3 frases seguidas,
     *   acumula todos os trechos e aguarda `waitMs` (2.3s a 3.6s) com o microfone reaberto.
     * - Se `onBeginningOfSpeech` ou `onPartialResults` detectar que o usuário voltou a falar durante essa pausa,
     *   o timer é estendido automaticamente para não cortar o raciocínio!
     */
    private fun enqueueConsolidatedVoicePrompt(segmentPrompt: String, originTag: String) {
        if (segmentPrompt.isBlank()) return
        accumulatedConsolidationSegments.add(segmentPrompt.trim())
        val isIncomplete = agent.ambient.isIncompleteOrHesitationPhrase(segmentPrompt)
        val waitMs = if (isIncomplete) 2500L else 1200L
        setBubbleStatus("👂")
        showSpeechBubble("🎙️ Ouvindo continuação (${waitMs / 1000.0}s): \"${accumulatedConsolidationSegments.joinToString(" ").takeLast(85)}\"", waitMs + 600L)
        scheduleConsolidationDispatch(waitMs, originTag)
        // Reabre o microfone imediatamente para captar a continuação caso o usuário continue falando após a pausa!
        if (wakeActive && !isSpeakingTts) {
            scheduleWakeRestart(180)
        }
    }

    private fun scheduleConsolidationDispatch(waitMs: Long, originTag: String) {
        speechConsolidationJob?.cancel()
        speechConsolidationJob = scope.launch {
            delay(waitMs)
            // Se o usuário estiver falando neste exato momento (parcial ativa), aguarda terminar!
            // IMPORTANTE: não bloqueia por `recognizerOwnSessionActive` vazio para que o wake_vad em silêncio não atrase 5.4s!
            var extraWait = 0
            while (currentPartialTranscript.isNotBlank() && extraWait < 8) {
                delay(350L)
                extraWait++
            }
            if (accumulatedConsolidationSegments.isNotEmpty() && pending == null) {
                val mergedPrompt = accumulatedConsolidationSegments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                accumulatedConsolidationSegments.clear()
                android.util.Log.i("AgentePhone", "🎙️🧩 [Consolidação de Fala Completa ($originTag)] -> '$mergedPrompt'")
                setBubbleStatus("🤖")
                handleText(mergedPrompt, fromPanel = false)
            }
        }
    }

    /**
     * Processa uma frase ouvida no modo de escuta contínua inteligente (AmbientContextEngine):
     * - Guarda na memória curta deslizante (120s base, prolongada até 360s, até 24 trechos).
     * - Se ouvir "XZERO", "ECS ZERO", "X0", recupera todo o contexto curto falado antes e consolida pausas antes de ativar o LLM!
     * - Se a pontuação de intenção aprendida atingir o limiar com volume suficiente de dados, consolida e ativa o LLM!
     * - Se for `/btw`, injeta em paralelo ao pensamento atual sem interromper!
     */
    fun processWakeTranscript(spoken: String): String {
        // Se já havia um comando consolidando (usuário fez uma pausa de 1-2s e continuou falando a segunda parte da frase),
        // anexa este novo trecho diretamente ao comando em consolidação!
        if (accumulatedConsolidationSegments.isNotEmpty() && !isJobActive() && pending == null) {
            val cleanContinuation = agent.ambient.detectWakeName(spoken)?.second?.ifBlank { spoken } ?: spoken
            android.util.Log.i("AgentePhone", "🎙️➕ [Continuação pós-pausa anexada]: '$cleanContinuation'")
            enqueueConsolidatedVoicePrompt(cleanContinuation, "continuação")
            return "consolidating:${accumulatedConsolidationSegments.size}"
        }

        val snap = wakeScreenContext?.takeIf { System.currentTimeMillis() - it.capturedAtMs <= 8_000L }
        val isPlaybackActive = (getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
        val hasExplicitWake = agent.ambient.detectWakeName(spoken) != null
        // Quando o próprio celular está tocando música/vídeo no alto-falante, nunca dispara ações por
        // intenção implícita (auto_intent) — só aceita se o usuário chamou pelo nome ("X0", "Ada", "Xenon")!
        if (isPlaybackActive && !hasExplicitWake && !agent.ambient.isInFollowUpWindow()) {
            android.util.Log.i("AgentePhone", "🔇 [Playback Guard] Ignorando áudio de mídia sem wake word: '${spoken.take(60)}'")
            return "buffered:playback_guard"
        }
        val eval = agent.ambient.onTranscriptSegment(spoken, isJobActive(), snap)
        return when (eval.decision) {
            AmbientContextEngine.TriggerDecisionType.ACTIVATE_BY_WAKE_NAME -> {
                if (eval.effectiveTaskPrompt.isNotEmpty()) {
                    log("🎙️⚡ [${eval.matchedTrigger}${if (eval.screenMatched) "+👁️tela" else ""}] Contexto capturado -> ${eval.effectiveTaskPrompt.take(110)}")
                    enqueueConsolidatedVoicePrompt(eval.effectiveTaskPrompt, eval.matchedTrigger)
                    "command:${eval.effectiveTaskPrompt}"
                } else {
                    onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "wake_sem_comando")
                    log("👂 X0 / X-Zero ativado! Ouvindo comando…")
                    val wakeBubble = listOf("👂 Tô ouvindo…", "👂 Pode falar…", "👂 Oi, fala…").random()
                    showSpeechBubble(wakeBubble, 4200L)
                    scope.launch {
                        if (Prefs(this@BubbleService).subtleSound) SoundFeedback.playListening()
                        listenManual(resetAttempts = true)
                    }
                    "listening"
                }
            }
            AmbientContextEngine.TriggerDecisionType.ACTIVATE_BY_INTENT_SCORE -> {
                log("🧠⚡ [Modo Live score=${String.format(Locale.US, "%.1f", eval.rollingScore)}${if (eval.screenMatched) "+👁️tela" else ""}] Consolidando fala -> ${eval.effectiveTaskPrompt.take(110)}")
                enqueueConsolidatedVoicePrompt(eval.effectiveTaskPrompt, "auto_intent")
                "auto_intent:${eval.effectiveTaskPrompt}"
            }
            AmbientContextEngine.TriggerDecisionType.PARALLEL_BTW_INJECTION -> {
                setBubbleStatus("📌🎙️")
                log("📌 [/btw voz em paralelo]: ${eval.effectiveTaskPrompt}")
                showSpeechBubble("📌🎙️ /btw em paralelo: ${eval.effectiveTaskPrompt.take(65)}", 3800L)
                "btw:${eval.effectiveTaskPrompt}"
            }
            AmbientContextEngine.TriggerDecisionType.KEEP_LISTENING -> {
                if (eval.matchedTrigger == "btw_armed_by_wake") {
                    setBubbleStatus("📌🎙️")
                    log("📌🎙️ [X0 durante pensamento] /btw armado por voz! Ouvindo seu complemento...")
                    showSpeechBubble("📌🎙️ Estou pensando e ouvindo seu /btw! Pode falar…", 4800L)
                    return "btw_armed"
                }
                if (eval.matchedTrigger.startsWith("wake_waiting_completion") || eval.matchedTrigger == "aguardando_conclusao_pausa") {
                    onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "aguardando_conclusao")
                    showSpeechBubble("🎙️ Aguardando você concluir o pensamento… (memória: ${eval.bufferedChunksCount} trechos)", 3800L)
                } else if (!isJobActive() && accumulatedConsolidationSegments.isEmpty()) {
                    // Observação passiva enquanto o usuário trabalha + aprendizado por feedback de sugestão recente
                    val feedbackAck = InitiativeEngine.registrarFeedbackPorVoz(spoken, agent.getGraphForInitiative())
                    if (feedbackAck != null) {
                        log("🎓 $feedbackAck")
                        showSpeechBubble("🎓 $feedbackAck", 4200L)
                    } else {
                        InitiativeEngine.observarFalaPassiva(spoken, snap?.packageName)
                    }
                    onAgentPhaseChanged(AgentPhase.IDLE_RELAXED, "standby")
                }
                android.util.Log.i(
                    "AgentePhone",
                    "🧠 [Memória Curta Live] Ouvido='${spoken.take(60)}' | score=${String.format(Locale.US, "%.1f", eval.rollingScore)}/${agent.ambient.autoIntentThreshold} (${eval.bufferedChunksCount} trechos, trigger=${eval.matchedTrigger})"
                )
                "buffered:score=${String.format(Locale.US, "%.1f", eval.rollingScore)},window=${eval.memoryWindowSec}s,chunks=${eval.bufferedChunksCount}"
            }
        }
    }

    fun getAmbientAndMcpStatusJson(): org.json.JSONObject {
        return org.json.JSONObject()
            .put("ambient", agent.ambient.getStatusJson())
            .put("mcp", agent.mcp.toJson())
            .put("rules", agent.rules.toJsonArray())
    }

    fun getAgentInstance(): Agent = agent

    private var beepUnmuteJob: Job? = null
    @Volatile private var recognizerBeepsMuted = false

    /**
     * Pede AudioFocus transitório com ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) quando o usuário
     * ativa o microfone manualmente, responde a uma confirmação ou fala a wake word sobre música/vídeo.
     * Assim o Spotify/YouTube reduz o volume suavemente enquanto o X0 escuta, e restaura ao terminar!
     */
    private fun requestTransientAudioFocus() {
        runCatching {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            if (activeAudioFocusRequest != null) return
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { }
                .build()
            if (am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                activeAudioFocusRequest = req
            }
        }
    }

    private fun abandonTransientAudioFocus() {
        runCatching {
            val req = activeAudioFocusRequest ?: return
            activeAudioFocusRequest = null
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
            am.abandonAudioFocusRequest(req)
        }
    }

    /**
     * Mantém os bips nativos do Google SpeechRecognizer (`com.google.android.tts:raw/open`,
     * `raw/failure` e `raw/success` no `STREAM_NOTIFICATION` / `STREAM_SYSTEM`) silenciados
     * durante TODA a sessão do reconhecedor e por 750ms após o fechamento.
     * Isso impede que o bip de falha (`raw/failure`, 550ms) toque no alto-falante em volume 7/7
     * e re-acione o VAD em loop infinito a cada 3,8s!
     */
    private fun holdRecognizerBeepMute() {
        beepUnmuteJob?.cancel()
        beepUnmuteJob = null
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        recognizerBeepsMuted = true
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.ADJUST_MUTE, 0) }
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.ADJUST_MUTE, 0) }
    }

    private fun releaseRecognizerBeepMuteAfter(durationMs: Long = 750L) {
        beepUnmuteJob?.cancel()
        beepUnmuteJob = scope.launch {
            delay(durationMs)
            if (!recognizerSessionInFlight && !recognizerOwnSessionActive && recognizerBeepsMuted) {
                recognizerBeepsMuted = false
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return@launch
                runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, android.media.AudioManager.ADJUST_UNMUTE, 0) }
                runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.ADJUST_UNMUTE, 0) }
            }
        }
    }

    /**
     * Barge-In (Corte de Fala Natural): interrompe imediatamente o TTS do X0 quando o usuário
     * começa a falar ("X0", "para", "espera", etc.), sacode o celular ou toca no Xenon!
     */
    fun interruptTtsBargeIn(reason: String) {
        if (!isSpeakingTts) return
        isSpeakingTts = false
        lastTtsEndedAtMs = System.currentTimeMillis()
        voice.estimateSpokenPrefixOnInterrupt()?.let { heard ->
            agent.truncateLastAssistantMemory(heard)
        }
        NeuralTtsEngine.stop()
        runCatching { tts?.stop() }
        android.util.Log.i("AgentePhone", "🗣️🛑 [Barge-In] Fala do X0 interrompida ($reason)")
        setBubbleStatus("👂")
    }

    private fun isBargeInVoiceTrigger(candidate: String): Boolean {
        if (isEchoFromOwnTts(candidate)) return false
        val c = normalizeEchoText(candidate)
        if (c.isEmpty()) return false
        if (agent.ambient.detectWakeName(candidate) != null) return true
        val bargeKeywords = listOf(
            "para", "pare", "espera", "aguarda", "calma", "silencio", "silêncio",
            "cancela", "cancelar", "ouve", "escuta", "btw", "alias", "aliás",
            "sim", "nao", "não", "volta", "muda", "troca", "ok"
        )
        if (bargeKeywords.any { kw -> c == kw || c.startsWith("$kw ") || c.contains(" $kw") }) return true
        // Barge-in natural (estilo conversa ao vivo): qualquer fala real >= 2 palavras ou >= 6 letras que não seja eco do próprio TTS corta a fala e passa a ouvir!
        val words = c.split(" ").filter { it.isNotBlank() }
        return words.size >= 2 || c.length >= 6
    }

    /**
     * Quando o usuário abre o XZERO Control Center no celular (MainActivity), o X0 passa a viver
     * dentro do card "Espelho Ao Vivo do X0" da própria tela, ocultando a bolha flutuante externa
     * para não cobrir os cards de telemetria; ao sair do app (onPause), a bolha volta instantaneamente!
     */
    fun setOverlayVisible(visible: Boolean) {
        scope.launch {
            runCatching {
                bubble.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (!visible && speechShown) hideSpeechBubble()
            }
        }
    }

    private fun startRecognizer(isManual: Boolean, allowDuringTtsBargeIn: Boolean = false) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch { startRecognizer(isManual, allowDuringTtsBargeIn) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (isManual) {
                hideGlow()
                log("Reconhecimento de voz indisponível")
            }
            return
        }
        // Prioridade total do microfone para o usuário: se ele estiver gravando áudio em outro app ou em ligação, cede na hora!
        if (!isManual && isUserUsingMicInOtherApp()) {
            scheduleWakeRestart(2500)
            return
        }
        // Se for escuta passiva comum (sem modo Barge-In) e o XZERO estiver falando, aguarda
        if (!isManual && isSpeakingTts && !allowDuringTtsBargeIn) {
            scheduleWakeRestart(900)
            return
        }
        // Só interrompe o TTS se o usuário clicou manualmente no microfone sem pergunta pendente!
        // Quando `pending != null` (X0 acabou de fazer uma pergunta de confirmação por voz), NUNCA corta a própria fala do X0!
        if (isManual && pending == null) {
            interruptTtsBargeIn("Escuta manual acionada")
        } else if (isManual && pending != null && isSpeakingTts) {
            scope.launch {
                var waited = 0
                while (isSpeakingTts && waited < 30) {
                    delay(250L)
                    waited++
                }
                delay(350L)
                if (pending != null) startRecognizer(isManual = true)
            }
            return
        }
        if (isManual || pending != null) {
            requestTransientAudioFocus()
        }
        cancelRecognizerSession(destroyInstance = false)
        var sessionCommitted = false
        lastVadHandoffMs = System.currentTimeMillis()
        recognizerStartMs = lastVadHandoffMs
        recognizerSessionInFlight = true
        recognizerOwnSessionActive = false
        vadHandoffPending = false
        vad.stop()
        lastWakeEventMs = android.os.SystemClock.elapsedRealtime()

        fun commitSessionTranscript(candidates: List<String>, origin: String) {
            if (sessionCommitted) return
            sessionCommitted = true
            holdRecognizerBeepMute()
            recognizerStartupWatchdogJob?.cancel()
            recognizerStartupWatchdogJob = null
            partialCommitJob?.cancel()
            currentPartialTranscript = ""
            recognizerSessionInFlight = false
            recognizerOwnSessionActive = false
            releaseRecognizerBeepMuteAfter(750L)
            lastRecognizerEndMs = System.currentTimeMillis()
            agent.notifyUserFinishedSpeakingBtw()
            lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
            val spoken = agent.ambient.selectBestTranscriptCandidate(candidates, isJobActive())
            if (spoken.isNotBlank() && isEchoFromOwnTts(spoken)) {
                AudioTelemetry.onEchoDrop(spoken, pending != null)
                android.util.Log.i("AgentePhone", "🎙️🔇 [Anti-Eco TTS] Ignorando eco da própria fala: '$spoken'")
                if (isManual || pending != null) {
                    scope.launch {
                        delay(350L)
                        if (manualListen || pending != null) startRecognizer(isManual = true)
                    }
                } else if (wakeActive && !manualListen) {
                    abandonTransientAudioFocus()
                    scheduleWakeRestart(450)
                }
                return
            }
            if (isSpeakingTts && spoken.isNotBlank() && isBargeInVoiceTrigger(spoken)) {
                interruptTtsBargeIn("Voz Final: $spoken")
            } else if (isSpeakingTts) {
                abandonTransientAudioFocus()
                if (wakeActive && !manualListen) scheduleWakeRestart(500)
                return
            }
            AudioTelemetry.onFinalTranscript(spoken, if (pending != null) "resposta_confirmacao" else origin)
            android.util.Log.i("AgentePhone", "🎙️ [$origin] Transcrição: '$spoken' (opções=${candidates.joinToString(" | ")})")
            if (spoken.isNotBlank()) {
                latestLiveSpeechText = "🎙️ Você disse: \"$spoken\""
            }
            if (pending != null) {
                // Resposta a uma confirmação/pergunta do X0 -> entrega imediatamente sem delay de consolidação!
                if (spoken.isNotEmpty()) {
                    manualListenAttempts = 0
                    manualListen = false
                    abandonTransientAudioFocus()
                    setBubbleStatus("⏳🎙️")
                    handleText(spoken)
                } else {
                    handleManualListenFailure("Não ouvi sua confirmação")
                }
            } else if (isManual) {
                if (spoken.isNotEmpty()) {
                    manualListenAttempts = 0
                    manualListen = false
                    abandonTransientAudioFocus()
                    // Se falou via escuta manual mas fez uma pausa ou frase incompleta, usa a consolidação para não cortar!
                    if (agent.ambient.isIncompleteOrHesitationPhrase(spoken)) {
                        enqueueConsolidatedVoicePrompt(spoken, "manual_incomplete")
                    } else if (accumulatedConsolidationSegments.isNotEmpty()) {
                        enqueueConsolidatedVoicePrompt(spoken, "manual_continuation")
                    } else {
                        setBubbleStatus(if (isJobActive()) "⏳🎙️" else "🤖")
                        handleText(spoken)
                    }
                } else {
                    handleManualListenFailure("Não ouvi nenhuma fala")
                }
            } else {
                abandonTransientAudioFocus()
                val res = processWakeTranscript(spoken)
                // Pontuação do despertar: virou comando/ação = acerto; só conversa guardada = neutro.
                val wasProductive = !res.startsWith("buffered:")
                AudioTelemetry.onWakeOutcome(
                    productive = wasProductive,
                    detail = res.substringBefore(":").take(24),
                )
                // Despertar produtivo = era a voz do usuário: matricula no perfil de voz.
                if (wasProductive) VoicePrint.enroll(vad.lastTriggerFeatures)
                if (wakeActive && !manualListen && !isSpeakingTts) {
                    val restartDelay = if (res.startsWith("buffered:") || res.startsWith("btw:") || res == "btw_armed" || res.startsWith("consolidating:")) 500L else 600L
                    scheduleWakeRestart(restartDelay)
                }
            }
        }

        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        recognizerStartupWatchdogJob = scope.launch {
            delay(3_200L)
            if (recognizerSessionInFlight && !recognizerOwnSessionActive && !sessionCommitted) {
                android.util.Log.w("AgentePhone", "🎙️⚠️ [Watchdog Startup] SpeechRecognizer não abriu onReadyForSpeech em 3,2s — recriando instância")
                AudioTelemetry.onRecognizerError(SpeechRecognizer.ERROR_CLIENT, isManual)
                cancelRecognizerSession(destroyInstance = true)
                abandonTransientAudioFocus()
                if (isManual || pending != null) {
                    handleManualListenFailure("Serviço de voz demorou a responder")
                } else {
                    scheduleWakeRestart(650L)
                }
            }
        }

        rec.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle) {
                    val rawCandidates = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList().orEmpty()
                    val effectiveCandidates = if (rawCandidates.isEmpty() && currentPartialTranscript.isNotBlank()) {
                        listOf(currentPartialTranscript)
                    } else {
                        rawCandidates
                    }
                    commitSessionTranscript(effectiveCandidates, "Final")
                }

                override fun onError(error: Int) {
                    holdRecognizerBeepMute()
                    AudioTelemetry.onRecognizerError(error, isManual)
                    recognizerStartupWatchdogJob?.cancel()
                    recognizerStartupWatchdogJob = null
                    partialCommitJob?.cancel()
                    recognizerSessionInFlight = false
                    recognizerOwnSessionActive = false
                    releaseRecognizerBeepMuteAfter(750L)
                    lastRecognizerEndMs = System.currentTimeMillis()
                    agent.notifyUserFinishedSpeakingBtw()
                    lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
                    if (sessionCommitted) return

                    // RESGATE SODA: Se o SodaSpeechRecognizer cortou com NO_SPEECH_DETECTED (-1 / 7 / 6 / 5) mas já tinha capturado fala parcial!
                    if (currentPartialTranscript.isNotBlank() &&
                        (error == SpeechRecognizer.ERROR_NO_MATCH ||
                         error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                         error == SpeechRecognizer.ERROR_CLIENT ||
                         error == -1)
                    ) {
                        val rescued = currentPartialTranscript
                        android.util.Log.i("AgentePhone", "🎙️⚡ [Resgate SODA erro=$error] Processando fala parcial capturada: '$rescued'")
                        commitSessionTranscript(listOf(rescued), "Resgate-SODA")
                        return
                    }

                    // Só destrói a instância do SpeechRecognizer em erros estruturais do serviço/cliente (5, 8, 3, 11)
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_AUDIO ||
                        error == 11
                    ) {
                        cancelRecognizerSession(destroyInstance = true)
                    }

                    // Se deu erro de áudio/ocupado na escuta contínua, outro app do usuário pegou o microfone -> cede prioridade!
                    if (!isManual && (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) {
                        yieldMicForUserPriority(4500L, "Conflito de microfone detectado (erro $error)")
                        return
                    }
                    val desc = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi o que você disse"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi nenhum som"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecedor ocupado"
                        SpeechRecognizer.ERROR_AUDIO -> "Falha de áudio"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout de rede"
                        SpeechRecognizer.ERROR_NETWORK -> "Falha de rede"
                        SpeechRecognizer.ERROR_CLIENT -> "Erro no serviço de fala"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sem permissão de microfone"
                        else -> "Erro no reconhecimento ($error)"
                    }
                    if (isManual || pending != null) {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            finishManualListen("Sem permissão para microfone.")
                        } else {
                            handleManualListenFailure(desc)
                        }
                    } else {
                        abandonTransientAudioFocus()
                        android.util.Log.i("AgentePhone", "🎙️ [Wake encerrado sem comando: erro=$error ($desc)]")
                        agent.ambient.applyDecayAndPrune()
                        if (isJobActive()) {
                            syncActiveJobBubbleStatus()
                        } else if (agent.ambient.isInFollowUpWindow()) {
                            currentAgentPhase = AgentPhase.ATTENTIVE_LISTENING
                            setBubbleStatus("💬🎙️")
                            hideGlow()
                        } else {
                            onAgentPhaseChanged(AgentPhase.IDLE_RELAXED, "standby")
                            hideGlow()
                        }
                        val isMusicPlaying = (getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
                        val restartDelay = if (isMusicPlaying && !agent.ambient.isInFollowUpWindow() &&
                            (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        ) {
                            3200L
                        } else {
                            700L
                        }
                        scheduleWakeRestart(restartDelay)
                    }
                }

                override fun onReadyForSpeech(p: Bundle?) {
                    recognizerStartupWatchdogJob?.cancel()
                    recognizerStartupWatchdogJob = null
                    recognizerOwnSessionActive = true
                    recognizerStartMs = System.currentTimeMillis()
                    lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
                    val activeInteractiveListen = isManual || pending != null
                    if (!isSpeakingTts && activeInteractiveListen) {
                        AdaStateMachine.transition(AdaStateMachine.AdaState.LISTENING_USER, "onReadyForSpeech")
                        showGlow()
                    } else {
                        hideGlow()
                    }
                    if (isJobActive()) {
                        syncActiveJobBubbleStatus()
                    } else if (isManual || pending != null) {
                        onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "escuta_manual")
                    } else if (agent.ambient.isInFollowUpWindow()) {
                        currentAgentPhase = AgentPhase.ATTENTIVE_LISTENING
                        setBubbleStatus("💬🎙️")
                    }
                    // No modo wake_vad passivo (e mesmo na janela de follow-up antes da fala),
                    // não acende a borda roxa até confirmar fala real em onBeginningOfSpeech/onPartialResults!
                }

                override fun onBeginningOfSpeech() {
                    recognizerOwnSessionActive = true
                    lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
                    if (!isSpeakingTts && (isManual || pending != null || agent.ambient.isInFollowUpWindow())) {
                        AdaStateMachine.transition(AdaStateMachine.AdaState.LISTENING_USER, "onBeginningOfSpeech")
                        showGlow()
                    }
                    // Se havia um comando aguardando janela de consolidação pós-pausa e o usuário voltou a falar,
                    // estende a janela para juntar tudo num único comando completo!
                    if (accumulatedConsolidationSegments.isNotEmpty() && speechConsolidationJob?.isActive == true) {
                        scheduleConsolidationDispatch(4200L, "estendido_por_voz")
                    }
                    if (isJobActive()) {
                        agent.notifyUserStartedSpeakingBtw()
                        setBubbleStatus("📌🎙️")
                    }
                }

                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {
                    holdRecognizerBeepMute()
                    recognizerOwnSessionActive = false
                    lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
                    if (!isJobActive() && pending == null && !manualListen) {
                        AdaStateMachine.transition(AdaStateMachine.AdaState.UNDERSTANDING, "onEndOfSpeech")
                    }
                    if (pending == null && !manualListen) {
                        hideGlow()
                    }
                }

                override fun onPartialResults(p: Bundle?) {
                    lastWakeEventMs = android.os.SystemClock.elapsedRealtime()
                    val partials = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList().orEmpty().filter { it.isNotBlank() }
                    if (partials.isEmpty()) return

                    val bestPartial = agent.ambient.selectBestTranscriptCandidate(partials, isJobActive())
                    if (bestPartial.isNotBlank()) {
                        if (isEchoFromOwnTts(bestPartial)) return
                        // Barge-In Acústico: se o X0 estava falando e o usuário falou "X0", "para", "espera", corta a voz do X0 na hora!
                        if (isSpeakingTts) {
                            if (isBargeInVoiceTrigger(bestPartial)) {
                                interruptTtsBargeIn("Voz parcial: '$bestPartial'")
                            } else {
                                return
                            }
                        }
                        if (isJobActive()) {
                            agent.notifyUserStartedSpeakingBtw()
                        }
                        if (accumulatedConsolidationSegments.isNotEmpty() && speechConsolidationJob?.isActive == true) {
                            scheduleConsolidationDispatch(4200L, "estendido_por_parcial")
                        }
                        val changed = bestPartial != currentPartialTranscript
                        AudioTelemetry.onPartialTranscript(bestPartial)
                        currentPartialTranscript = bestPartial
                        if (changed) {
                            android.util.Log.i("AgentePhone", "🎙️ [Parcial]: '$bestPartial'")
                            latestLiveSpeechText = "🎙️ Ouvindo ao vivo: \"$bestPartial\""
                            // Timer de commit por pausa natural ajustável:
                            // - Se for resposta rápida de confirmação (`pending != null`), 1.8s;
                            // - Se a frase parcial terminar em conectivo aberto ("e", "para", "que", "espera"), 4.6s;
                            // - Caso normal de fala contínua, 3.3s para nunca cortar o usuário no meio do raciocínio!
                            val pauseTimeoutMs = when {
                                pending != null -> 1150L
                                agent.ambient.isIncompleteOrHesitationPhrase(bestPartial) -> 2800L
                                else -> 1900L
                            }
                            partialCommitJob?.cancel()
                            partialCommitJob = scope.launch {
                                delay(pauseTimeoutMs)
                                if (!sessionCommitted && currentPartialTranscript.isNotBlank() && !isSpeakingTts) {
                                    val snapshot = currentPartialTranscript
                                    android.util.Log.i("AgentePhone", "🎙️⚡ [Auto-Commit Pausa ${pauseTimeoutMs}ms]: '$snapshot'")
                                    runCatching { recognizer?.stopListening() }
                                    delay(300L)
                                    if (!sessionCommitted && snapshot.isNotBlank()) {
                                        commitSessionTranscript(listOf(snapshot), "AutoCommit-Pausa")
                                    }
                                }
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastPartialPreviewMs < 280L) return
                    val (shouldShow, previewText) = agent.ambient.previewPartialTranscript(partials, isJobActive())
                    if (shouldShow && previewText.isNotBlank()) {
                        lastPartialPreviewMs = now
                        requestTransientAudioFocus()
                        if (!isSpeakingTts && !isJobActive()) {
                            showGlow()
                            onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "escutando_vad")
                        }
                        if (isJobActive()) setBubbleStatus("📌🎙️") else setBubbleStatus("👂")
                        showSpeechBubble(previewText, 2600L)
                    }
                }

                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // IMPORTANTE: No GoogleTTSRecognitionService (RecognitionServiceImpl),
                // EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS define a janela mínima que o microfone
                // permanece aberto aguardando início de fala antes de chamar onMicrophoneCloseRequested!
                // (Valores baixos como 300ms ou 1200ms fechavam o microfone 300ms após onMicrophoneOpened).
                val confirming = pending != null
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    if (confirming) 1500 else 2600,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    if (confirming) 1100 else 1800,
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    when {
                        confirming -> 3200
                        isManual -> 5000
                        else -> 3500
                    },
                )
            }
            AudioTelemetry.onRecognizerStart(
                when {
                    pending != null -> "confirmacao"
                    isManual -> "manual"
                    else -> "wake_vad"
                },
            )
            holdRecognizerBeepMute()
            startListening(intent)
        }
    }

    private fun handleManualListenFailure(reason: String) {
        manualListenAttempts++
        if (pending != null) {
            AudioTelemetry.onConfirmationRetry()
            EmotionEngine.feel(EmotionEngine.Emotion.AGUARDANDO, "esperando sua resposta ($manualListenAttempts)", bump = 0.45)
        }
        // Quando há confirmação/pergunta pendente (pending != null), aguarda até 3 tentativas;
        // na escuta manual (botão/atalho), permite 1 reabertura rápida automática (maxAllowed = 2)
        // para que um ruído inicial ou pausa ao clicar no botão nunca descarte a escuta de imediato!
        val maxAllowed = if (pending != null) 3 else 2
        if (manualListenAttempts < maxAllowed) {
            log("🎤 $reason (tentativa $manualListenAttempts de $maxAllowed). Aguardando sua fala…")
            setBubbleStatus(if (pending != null) "👂❓" else "👂")
            scope.launch {
                // Reabre a escuta silenciosamente após 220ms sem falar "Hãã..." cego no erro de STT;
                // quem faz perguntas de esclarecimento ("Hãã... qual item?") agora é o próprio LLM!
                delay(220L)
                if (manualListen || pending != null) {
                    setBubbleStatus("👂")
                    startRecognizer(isManual = true)
                }
            }
        } else {
            finishManualListen(reason)
        }
    }

    private fun finishManualListen(reason: String) {
        log("🎤 $reason")
        manualListenAttempts = 0
        manualListen = false
        abandonTransientAudioFocus()
        hideGlow()
        setBubbleStatus("🤖")
        if (!isJobActive()) {
            onAgentPhaseChanged(AgentPhase.IDLE_RELAXED, "standby")
        }
        if (Prefs(this).subtleSound) {
            scope.launch { SoundFeedback.playDone() }
        }
        pending?.let {
            pending = null
            it.complete("")
        }
        scheduleWakeRestart(1200)
    }

    private fun speakPromptWithCallback(text: String, onFinished: () -> Unit) {
        val t = tts
        if (t == null) {
            scope.launch {
                delay(350)
                onFinished()
            }
            return
        }

        val utteranceId = "prompt_${System.currentTimeMillis()}"
        lastTtsSpokenNormalized = normalizeEchoText(text)
        isSpeakingTts = true
        var invoked = false
        val finishOnce = {
            if (!invoked) {
                invoked = true
                lastTtsEndedAtMs = System.currentTimeMillis()
                isSpeakingTts = false
                scope.launch {
                    delay(500)
                    onFinished()
                }
            }
        }

        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                isSpeakingTts = true
            }
            override fun onDone(id: String?) {
                if (id == utteranceId) finishOnce()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) finishOnce()
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) finishOnce()
            }
        })

        val neuralId = Prefs(this).neuralVoice
        if (NeuralTtsEngine.isAvailable(this, neuralId)) {
            isSpeakingTts = true
            voice.markSpeaking(text)
            val started = NeuralTtsEngine.speak(
                ctx = this,
                text = text,
                voiceId = neuralId,
                speed = voice.baseProsody().second,
                onStart = { isSpeakingTts = true },
                onDone = {
                    lastTtsEndedAtMs = System.currentTimeMillis()
                    isSpeakingTts = false
                    voice.markStopped()
                    scope.launch {
                        delay(400)
                        onFinished()
                    }
                }
            )
            if (started) return
        }
        val res = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            finishOnce()
        } else {
            val fallbackMs = (text.length * 90L).coerceIn(3500L, 14000L)
            scope.launch {
                delay(fallbackMs)
                finishOnce()
            }
        }
    }

    // ---- AgentUi ----
    override fun log(line: String) {
        android.util.Log.i("AgentePhone", line)
        scope.launch {
            logView.append(line + "\n")
            val lines = logView.text.lines(); if (lines.size > 200) logView.text = lines.takeLast(150).joinToString("\n")
            (logView.parent as ScrollView).post { (logView.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        }
    }

    @Volatile var suppressNextTts: Boolean = false

    /**
     * FONTE ÚNICA DE VERDADE do estado da fala: delega ao [TtsVoiceController].
     *
     * Antes este estado vivia duplicado em três campos deste arquivo. Com o controlador
     * extraído, manter cópias faria o anti-eco olhar para um estado velho e engolir a sua
     * resposta. Como propriedade computada, as ~10 atribuições espalhadas pelo arquivo
     * continuam funcionando e sincronizam sozinhas.
     */
    var isSpeakingTts: Boolean
        get() = voice.isSpeaking
        set(v) {
            if (v) voice.isSpeaking = true else voice.markStopped()
        }

    private var lastTtsSpokenNormalized: String
        get() = voice.lastSpokenNormalized
        set(_) { /* definido via voice.markSpeaking(text) */ }

    private var lastTtsEndedAtMs: Long
        get() = voice.lastEndedAtMs
        set(v) { voice.lastEndedAtMs = v }

    private fun normalizeEchoText(s: String): String = voice.normalizeEcho(s)

    /** Filtro anti-eco acústico — lógica no TtsVoiceController. */
    private fun isEchoFromOwnTts(candidate: String): Boolean =
        voice.isEchoFromOwnTts(candidate, confirming = pending != null)

    override fun say(text: String) {
        showSpeechBubble(text, 5400L)
        AdaStateMachine.transition(AdaStateMachine.AdaState.SPEAKING_REPLY, "say", text.take(40))
        if (pending == null && !isJobActive()) {
            hideGlow()
        }
        if (suppressNextTts) {
            suppressNextTts = false
            return
        }
        val prefs = Prefs(this)
        if (prefs.speak && NeuralTtsEngine.isAvailable(this, prefs.neuralVoice)) {
            lastTtsSpokenNormalized = normalizeEchoText(text)
            isSpeakingTts = true
            voice.markSpeaking(text)
            AudioTelemetry.setMicState(AudioTelemetry.MicState.SPEAKING_TTS)
            scope.launch {
                vad.stop()
                cancelRecognizerSession(destroyInstance = false)
                if (wakeActive && pending == null && text.length >= 45) {
                    delay(750L)
                    if (isSpeakingTts && wakeActive && !manualListen) {
                        startRecognizer(isManual = false, allowDuringTtsBargeIn = true)
                    }
                }
            }
            val started = NeuralTtsEngine.speak(
                ctx = this,
                text = text,
                voiceId = prefs.neuralVoice,
                speed = voice.baseProsody().second,
                onStart = { isSpeakingTts = true },
                onDone = {
                    lastTtsEndedAtMs = System.currentTimeMillis()
                    isSpeakingTts = false
                    voice.markStopped()
                    if (wakeActive && pending == null) {
                        AdaStateMachine.transition(AdaStateMachine.AdaState.FOLLOWUP_WINDOW, "tts_done")
                        agent.ambient.openFollowUpWindow(60_000L)
                        scheduleWakeRestart(180L)
                    } else if (pending == null) {
                        AdaStateMachine.transition(AdaStateMachine.AdaState.STANDBY_VAD, "tts_done")
                    }
                }
            )
            if (started) return
        }
        val t = tts
        if (prefs.speak && t != null) {
            // Prosódia emocional por cima do perfil: a ADA soa aliviada, tensa ou animada
            voice.applyEmotionalProsody(t)
            val uttId = "say_${System.currentTimeMillis()}"
            lastTtsSpokenNormalized = normalizeEchoText(text)
            isSpeakingTts = true
            voice.markSpeaking(text)
            AudioTelemetry.setMicState(AudioTelemetry.MicState.SPEAKING_TTS)
            scope.launch {
                vad.stop()
                cancelRecognizerSession(destroyInstance = false)
                // Abre Barge-In após 750ms apenas em respostas longas (>= 45 chars) para não cortar falas curtas
                if (wakeActive && pending == null && text.length >= 45) {
                    delay(750L)
                    if (isSpeakingTts && wakeActive && !manualListen) {
                        startRecognizer(isManual = false, allowDuringTtsBargeIn = true)
                    }
                }
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    isSpeakingTts = true
                }
                override fun onDone(id: String?) {
                    if (id == uttId) {
                        lastTtsEndedAtMs = System.currentTimeMillis()
                        isSpeakingTts = false
                        voice.markStopped()
                        // Renova a janela Live de 60s a partir do momento em que o X0 TERMINA de falar!
                        if (wakeActive && pending == null) {
                            agent.ambient.openFollowUpWindow(60_000L)
                            scheduleWakeRestart(180L)
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (id == uttId) {
                        lastTtsEndedAtMs = System.currentTimeMillis()
                        isSpeakingTts = false
                    }
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == uttId) {
                        lastTtsEndedAtMs = System.currentTimeMillis()
                        isSpeakingTts = false
                    }
                }
            })
            // Streaming por frase no TTS: começa a falar a 1ª frase na hora e enfileira as seguintes
            val sentences = if (text.length > 75) {
                text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                listOf(text)
            }
            var res = TextToSpeech.SUCCESS
            sentences.forEachIndexed { idx, part ->
                val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val id = if (idx == sentences.lastIndex) uttId else "${uttId}_$idx"
                val r = t.speak(part, mode, null, id)
                if (idx == 0) res = r
            }
            if (res != TextToSpeech.SUCCESS) {
                lastTtsEndedAtMs = System.currentTimeMillis()
                isSpeakingTts = false
            } else {
                // Fallback de segurança proporcional ao tamanho do texto
                val maxWaitMs = (text.length * 85L).coerceIn(2500L, 16000L)
                scope.launch {
                    delay(maxWaitMs)
                    if (isSpeakingTts) {
                        lastTtsEndedAtMs = System.currentTimeMillis()
                        isSpeakingTts = false
                    }
                }
            }
        }
    }

    override suspend fun ask(question: String): String {
        val d = CompletableDeferred<String>()
        withContext(Dispatchers.Main) {
            // Para qualquer sessão anterior ANTES de definir `pending = d`, impedindo que o cancelamento
            // dispare `onError(5)` como se o usuário já tivesse falhado na resposta!
            vad.stop()
            cancelRecognizerSession(destroyInstance = false)
            hideGlow()
            pending = d
            AudioTelemetry.onConfirmationAsked(question)
            onAgentPhaseChanged(AgentPhase.ASKING_HELP, "aguardando resposta")
            if (Prefs(this@BubbleService).subtleSound) SoundFeedback.playPrompt()
            log("❓ $question")
            showSpeechBubble("❓ $question", 9500L)
            say(question)
            // Aguarda o X0 terminar de falar a pergunta inteira antes de abrir o microfone!
            // (Assim o startRecognizer nunca corta a própria voz do X0 no meio da confirmação)
            scope.launch {
                delay(450L)
                var waited = 0
                while (isSpeakingTts && waited < 40) {
                    delay(250L)
                    waited++
                }
                delay(400L) // margem acústica pós-TTS
                if (pending === d) {
                    setBubbleStatus("👂❓")
                    showSpeechBubble("🎙️ Pode confirmar falando ('sim', 'pode fazer') ou dizer o que ajustar…", 6500L)
                    listenManual(resetAttempts = true)
                }
            }
        }
        val r = kotlinx.coroutines.withTimeoutOrNull(90_000) { d.await() } ?: "(sem resposta)"
        withContext(Dispatchers.Main) {
            if (pending === d) pending = null
            setBubbleStatus(if (isJobActive()) "⏳" else "🤖")
            hideGlow()
        }
        return r
    }
}
