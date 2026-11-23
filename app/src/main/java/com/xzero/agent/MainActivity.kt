package com.xzero.agent

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * XZERO v3.1 Control Center (Mobile Native Edition):
 * Reproduz no próprio aplicativo Android o painel executivo do XZERO Control Center,
 * incluindo os 4 cards de telemetria ao vivo, o Palco Interativo com o X0 (XenonSpriteView) animado,
 * a tabela de Status dos Serviços Internos & Permissões, o console `/btw` + MCP e todas as configurações de API.
 */
class MainActivity : Activity() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private var liveRefreshRunning = false

    // Referências de UI atualizadas em tempo real (a cada 2s)
    private lateinit var headerStateBadge: TextView
    private lateinit var headerVoiceBadge: TextView
    private lateinit var btnToggleBubble: Button

    // 4 KPI Cards
    private lateinit var kpiEnergyVal: TextView
    private lateinit var kpiEnergySub: TextView
    private lateinit var kpiRamVal: TextView
    private lateinit var kpiRamSub: TextView
    private lateinit var kpiSensorsVal: TextView
    private lateinit var kpiSensorsSub: TextView
    private lateinit var kpiCogVal: TextView
    private lateinit var kpiCogSub: TextView

    // Palco Ao Vivo do X0 (Xenon)
    private lateinit var stageXenon: XenonSpriteView
    private lateinit var stageBadge: TextView
    private lateinit var stageSpeech: TextView
    private var stageFloatAnim: ObjectAnimator? = null

    // Tabela de Serviços Internos & Permissões
    private lateinit var overallHealthBadge: TextView
    private lateinit var pillBubble: TextView
    private lateinit var pillA11y: TextView
    private lateinit var pillNotif: TextView
    private lateinit var pillRecorder: TextView
    private lateinit var pillWake: TextView
    private lateinit var pillLlm: TextView
    private lateinit var pillOverlay: TextView
    private lateinit var pillMcp: TextView
    private lateinit var mcpListSummaryView: TextView
    private lateinit var rulesListContainer: LinearLayout
    private lateinit var audioLiveLabel: TextView
    private lateinit var emotionLabel: TextView
    private lateinit var monitoringLiveLabel: TextView
    private lateinit var ruleIdField: EditText
    private lateinit var ruleTitleField: EditText
    private lateinit var ruleInstField: EditText
    private lateinit var ruleModeGroup: RadioGroup

    private val refreshTick = object : Runnable {
        override fun run() {
            if (!liveRefreshRunning) return
            runCatching { refreshLiveTelemetry() }
            uiHandler.postDelayed(this, 2200L)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun cardBg(
        fillHex: String = "#111827",
        strokeHex: String = "#1F2937",
        cornerDp: Int = 14,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpF(cornerDp.toFloat())
        setColor(Color.parseColor(fillHex))
        setStroke(dp(strokeDp), Color.parseColor(strokeHex))
    }

    private fun pillBg(fillHex: String, strokeHex: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpF(999f)
        setColor(Color.parseColor(fillHex))
        setStroke(dp(1), Color.parseColor(strokeHex))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)

        window.statusBarColor = Color.parseColor("#080C14")
        window.navigationBarColor = Color.parseColor("#080C14")

        val mainScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080C14"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(32))
        }

        // =========================================================================
        // 1. HEADER: XZERO CONTROL CENTER + BADGES + BOTÕES DE AÇÃO RÁPIDA
        // =========================================================================
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#263554", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val neonLogoBox = FrameLayout(this).apply {
            background = cardBg("#161F38", "#8B5CF6", cornerDp = 12, strokeDp = 2)
            val lp = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
            layoutParams = lp
            addView(TextView(this@MainActivity).apply {
                text = "⚡"
                textSize = 20f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        titleRow.addView(neonLogoBox)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        titleCol.addView(TextView(this).apply {
            text = "X0 / X-Zero Control Center"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 18.5f
            setTypeface(typeface, Typeface.BOLD)
        })

        titleCol.addView(TextView(this).apply {
            text = "Harness Cognitivo Autônomo • celular Android • Grafo Vetorial + RL Step Scoring + Live Voice + /btw"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(6))
        })

        val badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val deviceBadge = makeBadge("celular Android", "#062C30", "#14B8A6", "#2DD4BF")
        headerStateBadge = makeBadge("Pronto (Idle)", "#1E1B4B", "#6366F1", "#A5B4FC")
        headerVoiceBadge = makeBadge("🎙️ Live X0 / X-Zero ON", "#052E16", "#22C55E", "#4ADE80")

        badgesRow.addView(deviceBadge)
        badgesRow.addView(headerStateBadge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        badgesRow.addView(headerVoiceBadge, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        titleCol.addView(badgesRow)
        titleRow.addView(titleCol)
        headerCard.addView(titleRow)

        // Barra horizontal de botões rápidos (idêntica ao topo do Control Center)
        val actionsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(12), 0, 0)
        }
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnListenNow = makeActionButton("🎙️ Escutar Agora", "#0284C7", "#38BDF8", "#FFFFFF") {
            if (BubbleService.instance == null) {
                BubbleService.start(this)
            }
            uiHandler.postDelayed({
                BubbleService.instance?.activateFromAssistantTrigger()
            }, 250)
            Toast.makeText(this, "🎙️ Escuta imediata X0 / X-Zero ativada!", Toast.LENGTH_SHORT).show()
        }

        val btnHeal = makeActionButton("🩺 Healthcheck & Heal", "#1E293B", "#334155", "#E2E8F0") {
            runCatching { HealthMonitor.checkAndHeal(this, heal = true) }
            refreshLiveTelemetry()
            Toast.makeText(this, "🩺 Healthcheck & Auto-Cura executados!", Toast.LENGTH_SHORT).show()
        }

        btnToggleBubble = makeActionButton("▶ Iniciar Bolha", "#065F46", "#10B981", "#ECFDF5") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else if (BubbleService.instance != null) {
                stopService(Intent(this, BubbleService::class.java))
                uiHandler.postDelayed({ refreshLiveTelemetry() }, 350)
            } else {
                BubbleService.start(this)
                uiHandler.postDelayed({ refreshLiveTelemetry() }, 400)
            }
        }

        val btnUnstick = makeActionButton("🛑 Destravar UI", "#450A0A", "#EF4444", "#FCA5A5") {
            HealthMonitor.forceRecoverUi(this)
            refreshLiveTelemetry()
            Toast.makeText(this, "🛑 UI destravada e estado limpo!", Toast.LENGTH_SHORT).show()
        }

        val btnDemoRec = makeActionButton("🎬 Gravar Demo", "#312E81", "#6366F1", "#E0E7FF") {
            if (DemoRecorderService.isRecording) {
                DemoRecorderService.stop(this)
                Toast.makeText(this, "⏹️ Finalizando vídeo Demo...", Toast.LENGTH_SHORT).show()
            } else if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                DemoProjectionActivity.launch(this, startFrontCam = true)
            }
        }

        val btnDemoCam = makeActionButton("📷 Câmera Demo", "#1E3A8A", "#3B82F6", "#EFF6FF") {
            val res = DemoRecorderService.switchCamera()
            Toast.makeText(this, "📷 $res", Toast.LENGTH_SHORT).show()
        }

        actionsRow.addView(btnListenNow)
        actionsRow.addView(btnHeal, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actionsRow.addView(btnToggleBubble, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actionsRow.addView(btnDemoRec, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actionsRow.addView(btnDemoCam, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actionsRow.addView(btnUnstick, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        actionsScroll.addView(actionsRow)
        headerCard.addView(actionsScroll)

        container.addView(headerCard)

        // Barra de 2 abas para uso cotidiano limpo (✨ Dia a Dia vs 🛠️ Engenharia)
        val modeTabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        lateinit var btnTabDaily: Button
        lateinit var btnTabEng: Button
        var applyModeTab: (Boolean) -> Unit = {}
        btnTabDaily = makeActionButton("✨ Dia a Dia", "#0284C7", "#38BDF8", "#FFFFFF") { applyModeTab(true) }
        btnTabEng = makeActionButton("🛠️ Engenharia", "#1E293B", "#334155", "#94A3B8") { applyModeTab(false) }
        modeTabBar.addView(btnTabDaily, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        modeTabBar.addView(btnTabEng, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        container.addView(modeTabBar)

        // =========================================================================
        // 2. GRID 2x2 DOS 4 CARDS DE TELEMETRIA AO VIVO (Energia, RAM, Sensores, Cognição)
        // =========================================================================
        val gridRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        val cardEnergy = buildKpiCard("🔋 Energia & Térmica", "75% ⚡", "Temperatura: 30°C | Rede: wifi").also {
            kpiEnergyVal = it.second
            kpiEnergySub = it.third
        }
        val cardRam = buildKpiCard("💾 Memória RAM & Uptime", "2020 / 7382 MB", "Uptime: 680 min | Auto-Heals: 0").also {
            kpiRamVal = it.second
            kpiRamSub = it.third
        }

        gridRow1.addView(cardEnergy.first, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(6) })
        gridRow1.addView(cardRam.first, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(6) })
        container.addView(gridRow1)

        val gridRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        val cardSensors = buildKpiCard("💡 Sensores Físicos Ao Vivo", "253 lux", "Movimento: parado / sobre superfície").also {
            kpiSensorsVal = it.second
            kpiSensorsSub = it.third
        }
        val cardCog = buildKpiCard("🧠 Cognição & Vigias Ativas", "15 relações", "1 vigias | 52 rotas campeãs").also {
            kpiCogVal = it.second
            kpiCogSub = it.third
        }

        gridRow2.addView(cardSensors.first, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(6) })
        gridRow2.addView(cardCog.first, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(6) })
        container.addView(gridRow2)

        // =========================================================================
        // 3. PALCO INTERATIVO DO X0 (ONDE FICAVA O ESPELHO DA TELA NO DASHBOARD)
        // =========================================================================
        val stageCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }

        val stageHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stageHeader.addView(TextView(this).apply {
            text = "🎮 Espelho Ao Vivo da ADA (Xenon Animado)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        stageBadge = makeBadge("🎙️ X0 ON", "#062C30", "#14B8A6", "#2DD4BF")
        stageHeader.addView(stageBadge)
        stageCard.addView(stageHeader)

        // Visor/Espelho com o XenonSpriteView animado + Balão de fala sincronizado
        val stageViewport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg("#070B14", "#1E3A5F", cornerDp = 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }

        val spriteHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(132))
            clipChildren = false
            clipToPadding = false
        }

        stageXenon = XenonSpriteView(this).apply {
            setAuraColor(Color.parseColor("#35D7FF"))
            setAction(XenonSpriteView.Action.IDLE)
            setOnClickListener {
                val actions = listOf(
                    XenonSpriteView.Action.WAVE to "👋 Olá! Estou sempre atento por voz, sensores ou /btw!",
                    XenonSpriteView.Action.JUMP to "🚀 Pronto para acelerar suas tarefas na sua organização!",
                    XenonSpriteView.Action.HOLOGRAM to "🔮 Consultando o Grafo Vetorial Híbrido e Rotas Campeãs...",
                    XenonSpriteView.Action.EXPLAIN to "💬 Diga 'X0', sacuda o celular para /btw ou apenas pegue o aparelho!"
                )
                val pick = actions.random()
                setAction(pick.first)
                stageSpeech.text = pick.second
                BubbleService.instance?.triggerDemoAnimation(pick.first.name, pick.second, -1)
            }
        }
        spriteHolder.addView(stageXenon, FrameLayout.LayoutParams(dp(114), dp(124), Gravity.CENTER))
        stageViewport.addView(spriteHolder)

        // Inicia flutuação suave no X0 do palco
        stageFloatAnim = ObjectAnimator.ofFloat(stageXenon, View.TRANSLATION_Y, 0f, -dpF(5f), 0f).apply {
            duration = 2100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        val stageRightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) }
        }

        stageSpeech = TextView(this).apply {
            text = "⚡ X0 / X-Zero: Modo Live ativo! Fale 'X0' / 'X-Zero', levante o celular da mesa ou sacuda enquanto penso para injetar um /btw em paralelo."
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 13f
            setLineSpacing(dpF(2f), 1.08f)
            background = cardBg("#081426", "#35D7FF", cornerDp = 12, strokeDp = 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        stageRightCol.addView(stageSpeech)

        stageRightCol.addView(TextView(this).apply {
            text = "💡 Toque no X0 ou nos botões abaixo para testar os sprites 8x11:"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            setPadding(dp(2), dp(8), 0, dp(4))
        })

        val animBtnsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val animBtnsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun addAnimBtn(label: String, act: XenonSpriteView.Action, msg: String, auraHex: String = "#35D7FF") {
            val b = makeActionButton(label, "#131C31", "#2563EB", "#93C5FD") {
                stageXenon.setAuraColor(Color.parseColor(auraHex))
                stageXenon.setAction(act)
                stageSpeech.text = msg
                BubbleService.instance?.triggerDemoAnimation(act.name, msg, -1)
            }
            animBtnsRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }

        addAnimBtn("👋 Acenar", XenonSpriteView.Action.WAVE, "👋 Oi usuário! Escuta 'X0' e sensores prontos.", "#00F5D4")
        addAnimBtn("🚀 Pular", XenonSpriteView.Action.JUMP, "🚀 Energia máxima! Vamos executar.", "#35D7FF")
        addAnimBtn("🔮 Holograma", XenonSpriteView.Action.HOLOGRAM, "🔮 Lendo Grafo Vetorial + Política Q(s,a)...", "#A855F7")
        addAnimBtn("🧠 Pensar + /btw", XenonSpriteView.Action.THINK, "⏳🎙️ PENSANDO + OUVINDO /BTW: pode falar ou sacudir o celular!", "#F72585")
        addAnimBtn("🏃 Correr", XenonSpriteView.Action.RUN_RIGHT, "🏃 Correndo pela tela sincronizado com a label!", "#38BDF8")

        animBtnsScroll.addView(animBtnsRow)
        stageRightCol.addView(animBtnsScroll)
        stageViewport.addView(stageRightCol)
        stageCard.addView(stageViewport)
        container.addView(stageCard)

        // =========================================================================
        // 4. TABELA: STATUS DOS SERVIÇOS INTERNOS & PERMISSÕES (IDÊNTICA AO ANEXO)
        // =========================================================================
        val servicesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }

        val srvHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        srvHeader.addView(TextView(this).apply {
            text = "🛡️ Status dos Serviços Internos & Permissões"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        overallHealthBadge = makeBadge("100% Saudável", "#052E16", "#16A34A", "#4ADE80")
        srvHeader.addView(overallHealthBadge)
        servicesCard.addView(srvHeader)

        // Cabeçalho das colunas
        val colHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        colHeader.addView(TextView(this).apply {
            text = "COMPONENTE / SERVIÇO"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.1f)
        })
        colHeader.addView(TextView(this).apply {
            text = "ESTADO"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -2, 0.8f)
        })
        colHeader.addView(TextView(this).apply {
            text = "PAPEL NA ARQUITETURA X0 / X-ZERO"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.4f)
        })
        servicesCard.addView(colHeader)

        pillBubble = addServiceRow(
            servicesCard,
            "BubbleService (Xenon Pet)",
            "Ativo",
            "Xenon animado (8x11 + folha de emoções), balão flutuante, TTS Neural pt-BR e orquestrador."
        ) {
            if (BubbleService.instance == null) BubbleService.start(this) else BubbleService.instance?.activateFromAssistantTrigger()
            refreshLiveTelemetry()
        }

        pillA11y = addServiceRow(
            servicesCard,
            "AgentAccessibilityService",
            "Conectado",
            "Leitura da árvore de UI, toques, gestos, auto-click no WhatsApp e anti-ANR."
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        pillNotif = addServiceRow(
            servicesCard,
            "NotifListener + ProactiveWatcher",
            "Monitorando",
            "Interceptação de notificações (WhatsApp, E-mail, Claude, Codex) e resposta direta."
        ) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        pillRecorder = addServiceRow(
            servicesCard,
            "Gravador Ambiente (.m4a)",
            "Standby",
            "Captura silenciosa de áudio AAC em segundo plano quando solicitado ou combinado."
        ) {}

        pillWake = addServiceRow(
            servicesCard,
            "Wake Word ('X0 / X-Zero' + /btw)",
            "Ativo + Shake",
            "Escuta contínua zero-token por voz, sensores (Pick-Up/Shake) e /btw enquanto pensa."
        ) {
            p.wakeWord = !p.wakeWord
            BubbleService.instance?.syncWakeWord()
            refreshLiveTelemetry()
        }

        pillLlm = addServiceRow(
            servicesCard,
            "Motor LLM Primário",
            "GEMINI",
            "Roteador multimodal com fallback automático (Gemini → Claude → OpenAI)."
        ) {}

        pillOverlay = addServiceRow(
            servicesCard,
            "Sobreposição & Config. Sistema",
            "Autorizado",
            "Permissão de janela sobre outros apps e ajuste autônomo de sistema."
        ) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        pillMcp = addServiceRow(
            servicesCard,
            "Conector MCP (JSON-RPC)",
            "1 Servidor",
            "Integração com ferramentas remotas via Model Context Protocol (tools/list, tools/call)."
        ) {}

        container.addView(servicesCard)

        // =========================================================================
        // 4B. MONITORAMENTO AO VIVO NO CELULAR (Raciocínio, Erros & Métricas)
        // =========================================================================
        val monCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        monCard.addView(TextView(this).apply {
            text = "📊 Monitoramento Ao Vivo (Raciocínio, Erros & Métricas)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        monitoringLiveLabel = TextView(this).apply {
            text = "Carregando telemetria de raciocínio..."
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, dp(6), 0, dp(8))
        }
        monCard.addView(monitoringLiveLabel)
        val swMonServer = android.widget.Switch(this).apply {
            text = "📡 Servidor Monitoramento HTTP (:3030) em background"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            isChecked = Prefs(this@MainActivity).monitoringServerEnabled
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                Prefs(this@MainActivity).monitoringServerEnabled = checked
                if (checked) {
                    RealtimeMonitoringAPI.startServer(this@MainActivity)
                    Toast.makeText(this@MainActivity, "Monitoramento :3030 ligado", Toast.LENGTH_SHORT).show()
                } else {
                    RealtimeMonitoringAPI.stopServer()
                    Toast.makeText(this@MainActivity, "Monitoramento :3030 pausado (economia de bateria)", Toast.LENGTH_SHORT).show()
                }
            }
        }
        monCard.addView(swMonServer)
        monCard.addView(makeActionButton("🌐 Abrir Dashboard Completo (127.0.0.1:3030)", "#0F766E", "#14B8A6", "#FFFFFF") {
            RealtimeMonitoringAPI.startServer(this)
            uiHandler.postDelayed({
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:3030/")))
            }, 180)
        })
        monCard.addView(makeActionButton("🏛️ Painel Gerencial Harness (/harness)", "#1E3A8A", "#38BDF8", "#FFFFFF") {
            RealtimeMonitoringAPI.startServer(this)
            uiHandler.postDelayed({
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:3030/harness")))
            }, 180)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        container.addView(monCard)

        // =========================================================================
        // 5. CONSOLE DE COMANDO DIRETO & INJEÇÃO PARALELA (/BTW)
        // =========================================================================
        val consoleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        consoleCard.addView(TextView(this).apply {
            text = "💬 Console Rápido: Comando Direto ou Injeção /btw"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        consoleCard.addView(TextView(this).apply {
            text = "Envie uma tarefa principal ou injete um contexto /btw em paralelo enquanto o X0 pensa:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(8))
        })

        val cmdInput = EditText(this).apply {
            hint = "Ex: verifique a bateria ou /btw inclua também o horário da reunião..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            background = cardBg("#090D16", "#334155", cornerDp = 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        consoleCard.addView(cmdInput)

        val consoleBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val btnSendTask = makeActionButton("🚀 Executar Tarefa", "#0284C7", "#38BDF8", "#FFFFFF") {
            val txt = cmdInput.text.toString().trim()
            if (txt.isNotEmpty()) {
                if (BubbleService.instance == null) BubbleService.start(this)
                cmdInput.setText("")
                uiHandler.postDelayed({
                    BubbleService.instance?.handleText(txt, fromPanel = false)
                }, 200)
                Toast.makeText(this, "🚀 Enviado ao X0 / X-Zero!", Toast.LENGTH_SHORT).show()
            }
        }
        val btnSendBtw = makeActionButton("📌 Injetar /btw em Paralelo", "#4C1D95", "#8B5CF6", "#EDE9FE") {
            val txt = cmdInput.text.toString().trim()
            if (txt.isNotEmpty()) {
                if (BubbleService.instance == null) BubbleService.start(this)
                cmdInput.setText("")
                val btwText = if (txt.startsWith("/btw", ignoreCase = true)) txt else "/btw $txt"
                uiHandler.postDelayed({
                    BubbleService.instance?.handleText(btwText, fromPanel = false)
                }, 150)
                Toast.makeText(this, "📌 Contexto /btw injetado em paralelo!", Toast.LENGTH_SHORT).show()
            }
        }
        consoleBtns.addView(btnSendTask)
        consoleBtns.addView(btnSendBtw, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        consoleCard.addView(consoleBtns)
        container.addView(consoleCard)

        // =========================================================================
        // 5B. MOTOR DE REGRAS & GUARDRAILS CONFIGURÁVEIS DO X0 (CUA SECURITY)
        // =========================================================================
        val rulesCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        rulesCard.addView(TextView(this).apply {
            text = "🛡️ Regras & Guardrails do X0 (Confirmar / Bloquear / Alterar)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        rulesCard.addView(TextView(this).apply {
            text = "Toque em uma regra para editar, alterne Ativa/Inativa ou crie novas regras de segurança:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(8))
        })

        rulesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rulesCard.addView(rulesListContainer)

        ruleIdField = makeStyledField("", false, "ID da regra (vazio = criar nova)").apply {
            visibility = View.GONE
        }
        ruleTitleField = makeStyledField("", false, "Título da Regra (ex: Nunca apagar nada no WhatsApp)")
        ruleInstField = makeStyledField("", false, "Instrução detalhada (ex: Proibido clicar em Apagar/Limpar)")
        rulesCard.addView(ruleIdField)
        rulesCard.addView(ruleTitleField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        rulesCard.addView(ruleInstField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        ruleModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        val rbBlock = RadioButton(this).apply { text = "🛑 Bloquear"; setTextColor(Color.WHITE); id = 101; textSize = 11.5f }
        val rbConfirm = RadioButton(this).apply { text = "⚠️ Confirmar"; setTextColor(Color.WHITE); id = 102; textSize = 11.5f }
        val rbBehavior = RadioButton(this).apply { text = "🧠 Comportamento"; setTextColor(Color.WHITE); id = 103; textSize = 11.5f }
        ruleModeGroup.addView(rbBlock); ruleModeGroup.addView(rbConfirm); ruleModeGroup.addView(rbBehavior)
        ruleModeGroup.check(102)
        rulesCard.addView(ruleModeGroup)

        val ruleBtnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val btnSaveRule = makeActionButton("💾 Salvar / Alterar Regra", "#065F46", "#10B981", "#ECFDF5") {
            val title = ruleTitleField.text.toString().trim()
            val inst = ruleInstField.text.toString().trim()
            val idVal = ruleIdField.text.toString().trim().takeIf { it.isNotEmpty() }
            val modeStr = when (ruleModeGroup.checkedRadioButtonId) {
                101 -> "BLOCK_ALWAYS"
                102 -> "CONFIRM_ALWAYS"
                else -> "BEHAVIOR_PROMPT"
            }
            if (title.isNotEmpty()) {
                val re = BubbleService.instance?.getAgentInstance()?.rules ?: RuleEngine(this)
                val msg = re.addOrUpdateRule(title, inst.ifEmpty { title }, modeStr, "*", "", idVal)
                ruleIdField.setText("")
                ruleTitleField.setText("")
                ruleInstField.setText("")
                refreshLiveTelemetry()
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        val btnClearRuleForm = makeActionButton("➕ Limpar p/ Nova", "#1E293B", "#334155", "#E2E8F0") {
            ruleIdField.setText("")
            ruleTitleField.setText("")
            ruleInstField.setText("")
            ruleModeGroup.check(102)
        }
        ruleBtnsRow.addView(btnSaveRule)
        ruleBtnsRow.addView(btnClearRuleForm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        rulesCard.addView(ruleBtnsRow)
        container.addView(rulesCard)

        // =========================================================================
        // 6. GERENCIADOR DE SERVIDORES MCP (MODEL CONTEXT PROTOCOL)
        // =========================================================================
        val mcpCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        mcpCard.addView(TextView(this).apply {
            text = "🔌 Servidores MCP Conectados (Model Context Protocol)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        mcpListSummaryView = TextView(this).apply {
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        }
        mcpCard.addView(mcpListSummaryView)

        val mcpNameField = makeStyledField("", false, "Nome do servidor MCP (ex: host-erp-mcp)")
        val mcpUrlField = makeStyledField("", false, "URL JSON-RPC do MCP (ex: https://mcp.example.com/rpc)")
        mcpCard.addView(mcpNameField)
        mcpCard.addView(mcpUrlField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val btnAddMcp = makeActionButton("➕ Salvar / Conectar Servidor MCP", "#065F46", "#10B981", "#ECFDF5") {
            val name = mcpNameField.text.toString().trim()
            val url = mcpUrlField.text.toString().trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                val mcp = BubbleService.instance?.getAgentInstance()?.mcp ?: McpClientManager(this)
                val res = mcp.addOrUpdateServer(name, url, "", true)
                mcpNameField.setText("")
                mcpUrlField.setText("")
                refreshLiveTelemetry()
                Toast.makeText(this, res, Toast.LENGTH_LONG).show()
            }
        }
        mcpCard.addView(btnAddMcp, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        container.addView(mcpCard)

        // =========================================================================
        // 7. CONFIGURAÇÃO DE PROVEDORES LLM, CHAVES API, MODELOS & VOZ (TODAS AS OPÇÕES)
        // =========================================================================
        val configCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        configCard.addView(TextView(this).apply {
            text = "⚙️ Configuração de Chaves API, Modelos LLM & Voz"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        fun sectionLabel(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }

        configCard.addView(sectionLabel("Chave Gemini (Google AI)"))
        val gk = makeStyledField(p.geminiKey, true, "AIza..."); configCard.addView(gk)
        configCard.addView(sectionLabel("Modelo Gemini"))
        val gm = makeStyledField(p.geminiModel, false, "gemini-3.1-flash-lite"); configCard.addView(gm)

        configCard.addView(sectionLabel("Chave Claude (Anthropic)"))
        val ck = makeStyledField(p.claudeKey, true, "sk-ant-..."); configCard.addView(ck)
        configCard.addView(sectionLabel("Modelo Claude"))
        val cm = makeStyledField(p.claudeModel, false, "claude-3-5-haiku-latest"); configCard.addView(cm)

        configCard.addView(sectionLabel("Chave OpenAI"))
        val ok = makeStyledField(p.openaiKey, true, "sk-proj-..."); configCard.addView(ok)
        configCard.addView(sectionLabel("Modelo OpenAI"))
        val om = makeStyledField(p.openaiModel, false, "gpt-4o-mini"); configCard.addView(om)

        configCard.addView(sectionLabel("Provedor Principal (os demais operam como fallback automático)"))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rGem = RadioButton(this).apply { text = "Gemini"; setTextColor(Color.WHITE); id = 1 }
        val rCla = RadioButton(this).apply { text = "Claude"; setTextColor(Color.WHITE); id = 2 }
        val rOai = RadioButton(this).apply { text = "OpenAI"; setTextColor(Color.WHITE); id = 3 }
        rg.addView(rGem); rg.addView(rCla); rg.addView(rOai)
        rg.check(when (p.primary) { "claude" -> 2; "openai" -> 3; else -> 1 })
        configCard.addView(rg)

        val speak = CheckBox(this).apply {
            text = "Voz TTS Neural (falar respostas em voz alta)"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = p.speak
        }
        val subtle = CheckBox(this).apply {
            text = "Efeitos sonoros discretos (bipes de escuta e conclusão)"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = p.subtleSound
        }
        val wake = CheckBox(this).apply {
            text = "Modo Voz Live ('X0 / X-Zero' contínuo + Sensores + /btw enquanto pensa)"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = p.wakeWord
        }
        val semConfirm = CheckBox(this).apply {
            text = "Confirmação Semântica por Embeddings (Gemini -> Cache Local <1ms)"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = p.semanticConfirmEnabled
        }
        configCard.addView(sectionLabel("Perfil de Voz TTS"))
        val rgVoice = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rJarvis = RadioButton(this).apply { text = "Jarvis (Grave)"; setTextColor(Color.WHITE); id = 11 }
        val rNatural = RadioButton(this).apply { text = "Natural BR"; setTextColor(Color.WHITE); id = 12 }
        val rFast = RadioButton(this).apply { text = "Rápido"; setTextColor(Color.WHITE); id = 13 }
        rgVoice.addView(rJarvis); rgVoice.addView(rNatural); rgVoice.addView(rFast)
        rgVoice.check(when (p.voiceProfile) { "natural" -> 12; "fast" -> 13; else -> 11 })
        configCard.addView(rgVoice)

        val lblNeural = TextView(this).apply {
            text = "🗣️ Voz Neural Offline (pt-BR):"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
        }
        configCard.addView(lblNeural)
        val rgNeural = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rFaber = RadioButton(this).apply { text = "Faber (Neural)"; setTextColor(Color.WHITE); id = 21 }
        val rCadu = RadioButton(this).apply { text = "Cadu (Neural)"; setTextColor(Color.WHITE); id = 22 }
        val rSys = RadioButton(this).apply { text = "Google TTS"; setTextColor(Color.WHITE); id = 23 }
        rgNeural.addView(rFaber); rgNeural.addView(rCadu); rgNeural.addView(rSys)
        rgNeural.check(when (p.neuralVoice) { "cadu" -> 22; "system" -> 23; else -> 21 })
        configCard.addView(rgNeural)

        val semSummary = runCatching {
            val sj = VoiceSemantics.toJson()
            val c = sj.optJSONObject("confirm")
            val i = sj.optJSONObject("intent")
            "Embeddings: confirm=${c?.optInt("embedded", 0) ?: 0}/${c?.optInt("total", 0) ?: 0} | intent=${i?.optInt("embedded", 0) ?: 0}/${i?.optInt("total", 0) ?: 0}"
        }.getOrElse { "Embeddings: inicializando..." }
        configCard.addView(TextView(this).apply {
            text = "🧠 $semSummary"
            textSize = 11.5f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(4), 0, dp(4))
        })

        configCard.addView(speak)
        configCard.addView(subtle)
        configCard.addView(wake)
        configCard.addView(semConfirm)

        val btnSaveConfig = makeActionButton("💾 Salvar Configurações de IA e Voz", "#0284C7", "#38BDF8", "#FFFFFF") {
            p.geminiKey = gk.text.toString()
            p.geminiModel = gm.text.toString()
            p.claudeKey = ck.text.toString()
            p.claudeModel = cm.text.toString()
            p.openaiKey = ok.text.toString()
            p.openaiModel = om.text.toString()
            p.primary = when (rg.checkedRadioButtonId) { 2 -> "claude"; 3 -> "openai"; else -> "gemini" }
            p.voiceProfile = when (rgVoice.checkedRadioButtonId) { 12 -> "natural"; 13 -> "fast"; else -> "jarvis" }
            p.neuralVoice = when (rgNeural.checkedRadioButtonId) { 22 -> "cadu"; 23 -> "system"; else -> "faber" }
            p.speak = speak.isChecked
            p.subtleSound = subtle.isChecked
            p.wakeWord = wake.isChecked
            p.semanticConfirmEnabled = semConfirm.isChecked
            BubbleService.instance?.syncWakeWord()
            BubbleService.instance?.reloadVoiceProfile()
            refreshLiveTelemetry()
            Toast.makeText(this, "💾 Configurações salvas e voz [${p.voiceProfile}] aplicada!", Toast.LENGTH_SHORT).show()
        }
        configCard.addView(btnSaveConfig, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        container.addView(configCard)

        // =========================================================================
        // 8. CENTRAL DE ATALHOS DE PERMISSÕES & INTEGRAÇÕES DO SISTEMA ANDROID
        // =========================================================================
        val permsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        permsCard.addView(TextView(this).apply {
            text = "🔐 Atalhos de Permissões & Integração com o Sistema"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        fun addPermButton(label: String, action: () -> Unit) {
            val b = makeActionButton(label, "#1E293B", "#334155", "#E2E8F0") { action() }
            permsCard.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        addPermButton("✨ Testar Brilho Neon (Gemini Glow + Escuta)") {
            if (BubbleService.instance == null) BubbleService.start(this)
            uiHandler.postDelayed({ BubbleService.instance?.activateFromAssistantTrigger() }, 250)
        }
        addPermButton("⚡ Definir X0 / X-Zero como Assistente Padrão (Botão Power)") {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
        addPermButton("♿ Configurar Serviço de Acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addPermButton("🔔 Configurar Acesso a Notificações (Vigias Proativas)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addPermButton("🌙 Configurar Permissão Não Perturbe") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        addPermButton("⚙ Permitir Alterar Configurações do Sistema") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        addPermButton("🎤📞 Solicitar Permissões de Microfone, Telefone e Contatos") {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.CALL_PHONE,
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.ANSWER_PHONE_CALLS
                ),
                1
            )
        }

        // ============ CARD: ÁUDIO, VOZ E EMOÇÃO (controle direto no celular) ============
        val audioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#263554", cornerDp = 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        audioCard.addView(TextView(this).apply {
            text = "🎙️ Áudio, Voz & Emoção"
            textSize = 15f
            setTextColor(Color.parseColor("#F1F5F9"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        audioLiveLabel = TextView(this).apply {
            text = "medindo…"
            textSize = 11.5f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(6), 0, dp(6))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        audioCard.addView(audioLiveLabel)

        emotionLabel = TextView(this).apply {
            text = "😌 CALMO"
            textSize = 12.5f
            setTextColor(Color.parseColor("#22C55E"))
            setPadding(0, 0, 0, dp(6))
        }
        audioCard.addView(emotionLabel)

        audioCard.addView(sectionLabel("Sensibilidade do despertar (limiar aprendido)"))
        val sensRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sensRow.addView(makeActionButton("➖ Mais sensível", "#065F46", "#10B981", "#FFFFFF") {
            val novo = (Prefs(this).vadSnrOffsetDb - 1.5).coerceIn(-6.0, 14.0)
            VadAutoTuner.setOffsetDb(this, novo)
            Toast.makeText(this, "Limiar ${String.format("%+.1f", novo)} dB — acorda mais fácil", Toast.LENGTH_SHORT).show()
        })
        sensRow.addView(makeActionButton("➕ Mais exigente", "#7C2D12", "#F97316", "#FFFFFF") {
            val novo = (Prefs(this).vadSnrOffsetDb + 1.5).coerceIn(-6.0, 14.0)
            VadAutoTuner.setOffsetDb(this, novo)
            Toast.makeText(this, "Limiar ${String.format("%+.1f", novo)} dB — ignora mais ruído", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        audioCard.addView(sensRow)

        val gateCheck = CheckBox(this).apply {
            text = "Só acordar com a MINHA voz (precisa de perfil maduro)"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = Prefs(this@MainActivity).voicePrintGate
            setOnCheckedChangeListener { _, checked ->
                Prefs(this@MainActivity).voicePrintGate = checked
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "Gate de voz ligado (${VoicePrint.profileSamples} amostras)" else "Gate de voz desligado",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        audioCard.addView(gateCheck)

        val resetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resetRow.addView(makeActionButton("🔄 Zerar calibração", "#1E293B", "#475569", "#E2E8F0") {
            VadAutoTuner.reset(); AudioTelemetry.reset()
            Toast.makeText(this, "Calibração do despertar zerada", Toast.LENGTH_SHORT).show()
        })
        resetRow.addView(makeActionButton("🗣️ Zerar perfil de voz", "#1E293B", "#475569", "#E2E8F0") {
            VoicePrint.reset()
            Toast.makeText(this, "Perfil de voz apagado — será reaprendido", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        audioCard.addView(resetRow)
        container.addView(audioCard)

        container.addView(permsCard)

        val dailyViews = listOf<View>(stageCard, consoleCard, gridRow1)
        val engViews = listOf<View>(gridRow2, monCard, servicesCard, rulesCard, mcpCard, configCard, audioCard, permsCard)
        applyModeTab = { showDaily ->
            dailyViews.forEach { it.visibility = if (showDaily) View.VISIBLE else View.GONE }
            engViews.forEach { it.visibility = if (showDaily) View.GONE else View.VISIBLE }
            btnTabDaily.background = cardBg(if (showDaily) "#0284C7" else "#1E293B", if (showDaily) "#38BDF8" else "#334155", cornerDp = 10)
            btnTabDaily.setTextColor(Color.parseColor(if (showDaily) "#FFFFFF" else "#94A3B8"))
            btnTabEng.background = cardBg(if (!showDaily) "#0284C7" else "#1E293B", if (!showDaily) "#38BDF8" else "#334155", cornerDp = 10)
            btnTabEng.setTextColor(Color.parseColor(if (!showDaily) "#FFFFFF" else "#94A3B8"))
        }
        applyModeTab(true)

        mainScroll.addView(container)
        setContentView(mainScroll)
    }

    override fun onResume() {
        super.onResume()
        liveRefreshRunning = true
        // Promove o BubbleService a partir da Activity em primeiro plano (TOP) para garantir
        // permissão total de FOREGROUND_SERVICE_TYPE_MICROPHONE no Android 14!
        if (Settings.canDrawOverlays(this)) {
            runCatching { BubbleService.start(this) }
        }
        // Oculta a bolha flutuante externa enquanto o Control Center está aberto, pois o X0 já está no Palco Ao Vivo!
        BubbleService.instance?.setOverlayVisible(false)
        // Abre a janela conversacional Live enquanto o usuário está com o Control Center aberto
        BubbleService.instance?.getAgentInstance()?.ambient?.openFollowUpWindow(60_000L)
        BubbleService.instance?.ensureWakeRecognizerHealthy()
        refreshLiveTelemetry()
        uiHandler.removeCallbacks(refreshTick)
        uiHandler.postDelayed(refreshTick, 2000L)
    }

    override fun onPause() {
        liveRefreshRunning = false
        uiHandler.removeCallbacks(refreshTick)
        // Ao sair do Control Center (ir para Home ou outro app), devolve o X0 flutuante para a tela!
        BubbleService.instance?.setOverlayVisible(true)
        super.onPause()
    }

    override fun onDestroy() {
        liveRefreshRunning = false
        uiHandler.removeCallbacks(refreshTick)
        stageFloatAnim?.cancel()
        super.onDestroy()
    }

    // ---- Helpers Visuais ----

    private fun makeBadge(textStr: String, bgHex: String, borderHex: String, textHex: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor(textHex))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = pillBg(bgHex, borderHex)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
    }

    private fun styleBadge(tv: TextView, textStr: String, bgHex: String, borderHex: String, textHex: String) {
        tv.text = textStr
        tv.setTextColor(Color.parseColor(textHex))
        tv.background = pillBg(bgHex, borderHex)
    }

    private fun makeActionButton(label: String, bgHex: String, borderHex: String, textHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            background = cardBg(bgHex, borderHex, cornerDp = 10, strokeDp = 1)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
        }
    }

    private fun makeStyledField(initial: String, secret: Boolean, hintStr: String): EditText {
        return EditText(this).apply {
            setText(initial)
            hint = hintStr
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13.5f
            background = cardBg("#090D16", "#334155", cornerDp = 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        }
    }

    private fun buildKpiCard(title: String, initialValue: String, initialSub: String): Triple<LinearLayout, TextView, TextView> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0F172A", "#1E293B", cornerDp = 14)
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        val valTv = TextView(this).apply {
            text = initialValue
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        }
        val subTv = TextView(this).apply {
            text = initialSub
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
        }
        card.addView(titleTv)
        card.addView(valTv)
        card.addView(subTv)
        return Triple(card, valTv, subTv)
    }

    private fun addServiceRow(
        parent: LinearLayout,
        componentName: String,
        initialState: String,
        roleDesc: String,
        onRowClick: () -> Unit
    ): TextView {
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
        parent.addView(divider)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { onRowClick() }
        }

        val nameTv = TextView(this).apply {
            text = componentName
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.1f).apply { rightMargin = dp(6) }
        }

        val pillHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 0.85f).apply { rightMargin = dp(6) }
        }
        val statePill = makeBadge(initialState, "#052E16", "#16A34A", "#4ADE80").apply {
            gravity = Gravity.CENTER
        }
        pillHolder.addView(statePill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val descTv = TextView(this).apply {
            text = roleDesc
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.4f)
        }

        row.addView(nameTv)
        row.addView(pillHolder)
        row.addView(descTv)
        parent.addView(row)
        return statePill
    }

    /**
     * Atualiza todos os indicadores, sensores, contadores do Grafo Vetorial, Rotas Campeãs,
     * estado do X0 (Pensando + Ouvindo /btw vs Live) e tabela de serviços em tempo real.
     */
    private fun refreshLiveTelemetry() {
        val p = Prefs(this)
        val h = HealthMonitor.checkAndHeal(this, heal = false)
        val dev = h.optJSONObject("device") ?: JSONObject()
        val srv = h.optJSONObject("services") ?: JSONObject()
        val ag = h.optJSONObject("agent") ?: JSONObject()
        val sens = h.optJSONObject("sensors") ?: JSONObject()

        val bubbleRunning = BubbleService.instance != null
        if (bubbleRunning && hasWindowFocus()) {
            BubbleService.instance?.setOverlayVisible(false)
        }
        BubbleService.instance?.latestLiveSpeechText?.let { liveText ->
            if (liveText.isNotBlank() && stageSpeech.text.toString() != liveText) {
                stageSpeech.text = liveText
            }
        }
        // Áudio/voz/emoção ao vivo no card novo
        runCatching {
            val a = AudioTelemetry.getJson()
            val live = a.optJSONObject("live") ?: JSONObject()
            val cnt = a.optJSONObject("counters") ?: JSONObject()
            val tune = a.optJSONObject("autotune") ?: JSONObject()
            val vp = a.optJSONObject("voiceprint") ?: JSONObject()
            val sem = VoiceSemantics.toJson().optJSONObject("confirm") ?: JSONObject()
            audioLiveLabel.text = buildString {
                append("nível ").append(live.optDouble("rms_dbfs")).append(" dBFS")
                append("  piso ").append(live.optDouble("noise_floor_dbfs"))
                append("  limiar ").append(live.optDouble("threshold_dbfs")).append('\n')
                append("estado ").append(a.optString("mic_state"))
                append("  SNR ").append(live.optDouble("snr_db")).append(" dB").append('\n')
                append("despertares ").append(cnt.optInt("vad_triggers"))
                append(" | à toa ").append(cnt.optInt("vad_triggers_sem_transcricao"))
                append(" | score ").append(tune.optInt("score"))
                append(" (").append(tune.optDouble("offset_db")).append(" dB)").append('\n')
                append("voz: ").append(vp.optInt("amostras_perfil")).append(" amostras")
                if (vp.optBoolean("perfil_maduro")) append(" ✅")
                append(" | embeddings ").append(sem.optInt("embedded")).append('/').append(sem.optInt("total"))
            }
            val emo = a.optJSONObject("emocao") ?: JSONObject()
            emotionLabel.text = "${emo.optString("emoji")} ${emo.optString("emocao")} · ${emo.optString("motivo").take(40)}"
            emotionLabel.setTextColor(runCatching { EmotionEngine.auraColorInt() }.getOrDefault(Color.parseColor("#22C55E")))
        }
        runCatching {
            val m = MetricsCollector.getMetrics()
            val th = ThinkingLogger.getCurrentThinking().takeLast(3)
            val errs = ErrorDetector.getErrorHistory().takeLast(2)
            monitoringLiveLabel.text = buildString {
                append("Ações: ").append(m.successfulActions).append('/').append(m.totalActions)
                append(" | Sucesso: ").append(String.format(Locale.US, "%.0f%%", m.successRate))
                append(" | Tempo: ").append(String.format(Locale.US, "%.0fms", m.avgResponseTime))
                append(" | Erros: ").append(m.errorCount).append('\n')
                if (th.isEmpty()) {
                    append("🧠 Sem passos ativos ainda.\n")
                } else {
                    th.forEach { s ->
                        append("🧠 [").append(s.stepNumber).append("] ").append(s.actionTaken ?: s.phase)
                        append(": ").append(s.details.take(45)).append('\n')
                    }
                }
                if (errs.isNotEmpty()) {
                    errs.forEach { e ->
                        append("⚠️ ").append(e.type.name).append(": ").append(e.reason.take(45)).append('\n')
                    }
                }
            }.trimEnd()
        }

        val isBusy = ag.optBoolean("busy", false)
        val stepDesc = ag.optString("step", "idle")
        val a11yOk = srv.optBoolean("accessibility", false)
        val notifOk = srv.optBoolean("notifications", false)
        val overlayOk = srv.optBoolean("overlay_permission", false)
        val writeOk = srv.optBoolean("write_settings", false)

        // 1. Header Badges & Botão da Bolha
        if (isBusy) {
            styleBadge(headerStateBadge, "⏳🎙️ Pensando + Ouvindo /btw ($stepDesc)", "#4A044E", "#EC4899", "#FBCFE8")
            styleBadge(stageBadge, "📌🎙️ PENSANDO + OUVINDO /BTW", "#4A044E", "#00F5D4", "#00F5D4")
            stageXenon.setAuraColor(Color.parseColor("#F72585"))
        } else if (bubbleRunning) {
            styleBadge(headerStateBadge, "Pronto (Idle)", "#1E1B4B", "#6366F1", "#A5B4FC")
            styleBadge(stageBadge, if (p.wakeWord) "💬🎙️ LIVE (X0 ON)" else "🤖 ONLINE", "#062C30", "#14B8A6", "#2DD4BF")
            stageXenon.setAuraColor(Color.parseColor("#35D7FF"))
        } else {
            styleBadge(headerStateBadge, "Bolha Parada", "#450A0A", "#EF4444", "#FCA5A5")
            styleBadge(stageBadge, "⏸️ STANDBY", "#1E293B", "#64748B", "#94A3B8")
        }

        if (p.wakeWord && bubbleRunning) {
            styleBadge(headerVoiceBadge, "🎙️ Live X0 + /btw Ativo", "#052E16", "#22C55E", "#4ADE80")
        } else {
            styleBadge(headerVoiceBadge, "🔇 Wake Off", "#1E293B", "#475569", "#94A3B8")
        }

        btnToggleBubble.text = if (bubbleRunning) "⏹ Parar Bolha" else "▶ Iniciar Bolha"

        // 2. KPI 1: Energia & Térmica
        val batPct = dev.optInt("battery_pct", 0)
        val charging = dev.optBoolean("charging", false)
        val tempC = dev.optDouble("temp_c", 0.0)
        val net = dev.optString("network", "wifi")
        kpiEnergyVal.text = "$batPct% ${if (charging) "⚡" else "🔋"}"
        kpiEnergySub.text = "Temperatura: ${String.format(Locale.US, "%.0f", tempC)}°C | Rede: $net"

        // 3. KPI 2: Memória RAM & Uptime
        val ramAvail = dev.optLong("ram_avail_mb", 0)
        val ramTotal = dev.optLong("ram_total_mb", 0)
        val uptimeMin = dev.optLong("uptime_min", 0)
        val heals = h.optInt("recovery_count", 0)
        kpiRamVal.text = "$ramAvail / $ramTotal MB"
        kpiRamSub.text = "Uptime: $uptimeMin min | Auto-Heals: $heals"

        // 4. KPI 3: Sensores Físicos Ao Vivo
        val lux = sens.optDouble("ambient_lux", -1.0)
        val motion = sens.optString("motion_state", "parado")
        val posture = if (ProactiveWatcher.inConversationalPosture) "Postura Voz: Ativa" else "Modo: ${ProactiveWatcher.sensorMode}"
        kpiSensorsVal.text = if (lux >= 0) "${ lux.toInt() } lux" else "Auto lux"
        kpiSensorsSub.text = "Movimento: $motion | $posture"

        // 5. KPI 4: Cognição, Grafo Vetorial, Vigias & Score de Intenção
        var edgesCount = 0
        var routesCount = 0
        runCatching {
            val gf = File(filesDir, "knowledge_graph_v2.json")
            if (gf.exists()) {
                val root = JSONObject(gf.readText())
                edgesCount = (root.optJSONArray("edges") ?: JSONArray()).length()
                routesCount = (root.optJSONArray("routes") ?: JSONArray()).length()
            }
        }
        var watchesCount = 0
        runCatching {
            val wf = File(filesDir, "proactive_watches.json")
            if (wf.exists()) {
                watchesCount = JSONArray(wf.readText()).length()
            }
        }
        val ambientScore = BubbleService.instance?.getAgentInstance()?.ambient?.rollingIntentScore ?: 0.0
        kpiCogVal.text = "$edgesCount relações"
        kpiCogSub.text = "$watchesCount vigias | $routesCount rotas campeãs | Voz: ${String.format(Locale.US, "%.1f", ambientScore)}"

        // 6. Tabela de Serviços Internos & Permissões
        if (bubbleRunning) {
            styleBadge(pillBubble, "Ativo", "#052E16", "#16A34A", "#4ADE80")
        } else {
            styleBadge(pillBubble, "Inativo (Toque p/ Iniciar)", "#450A0A", "#EF4444", "#FCA5A5")
        }

        if (a11yOk) {
            styleBadge(pillA11y, "Conectado", "#052E16", "#16A34A", "#4ADE80")
        } else {
            styleBadge(pillA11y, "Desativado", "#450A0A", "#EF4444", "#FCA5A5")
        }

        if (notifOk) {
            styleBadge(pillNotif, "Monitorando", "#052E16", "#16A34A", "#4ADE80")
        } else {
            styleBadge(pillNotif, "Pendente", "#422006", "#F59E0B", "#FDE68A")
        }

        if (ProactiveWatcher.isRecordingAudio) {
            styleBadge(pillRecorder, "🔴 Gravando", "#450A0A", "#EF4444", "#FCA5A5")
        } else {
            styleBadge(pillRecorder, "Standby", "#062C30", "#14B8A6", "#2DD4BF")
        }

        if (p.wakeWord) {
            styleBadge(pillWake, "Ativo + Shake + /btw", "#052E16", "#16A34A", "#4ADE80")
        } else {
            styleBadge(pillWake, "Desativado", "#1E293B", "#64748B", "#94A3B8")
        }

        val primaryUpper = p.primary.uppercase(Locale.US)
        val activeModel = when (p.primary) {
            "claude" -> p.claudeModel
            "openai" -> p.openaiModel
            else -> p.geminiModel
        }
        styleBadge(pillLlm, "$primaryUpper ($activeModel)", "#2E1065", "#8B5CF6", "#DDD6FE")

        if (overlayOk && writeOk) {
            styleBadge(pillOverlay, "Autorizado", "#052E16", "#16A34A", "#4ADE80")
        } else {
            styleBadge(pillOverlay, "Configurar", "#422006", "#F59E0B", "#FDE68A")
        }

        val mcpMgr = BubbleService.instance?.getAgentInstance()?.mcp ?: McpClientManager(this)
        val mcpJson = mcpMgr.toJson()
        val mcpCount = mcpJson.optInt("count", 0)
        styleBadge(pillMcp, "$mcpCount Servidor(es)", "#062C30", "#14B8A6", "#2DD4BF")
        mcpListSummaryView.text = mcpMgr.listServersSummary()

        renderRulesListIfChanged()

        val allHealthy = bubbleRunning && a11yOk && notifOk && overlayOk
        if (allHealthy) {
            styleBadge(overallHealthBadge, "100% Saudável", "#052E16", "#16A34A", "#4ADE80")
        } else {
            styleBadge(overallHealthBadge, "Atenção Requerida", "#422006", "#F59E0B", "#FDE68A")
        }
    }

    private var lastRenderedRulesHash: Int = 0

    private fun renderRulesListIfChanged(force: Boolean = false) {
        val re = BubbleService.instance?.getAgentInstance()?.rules ?: RuleEngine(this)
        val allRules = re.getAllRules()
        val currentHash = allRules.hashCode()
        if (!force && currentHash == lastRenderedRulesHash && rulesListContainer.childCount > 0) return
        lastRenderedRulesHash = currentHash
        rulesListContainer.removeAllViews()

        for (r in allRules) {
            val rowCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg(if (r.enabled) "#0B1324" else "#090D16", if (r.enabled) "#1E3A5F" else "#1E293B", cornerDp = 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val modeLabel = when (r.mode) {
                RuleEngine.EnforcementMode.BLOCK_ALWAYS -> "🛑 BLOQUEIO"
                RuleEngine.EnforcementMode.CONFIRM_ALWAYS -> "⚠️ CONFIRMAR"
                RuleEngine.EnforcementMode.BEHAVIOR_PROMPT -> "🧠 COMPORTAMENTO"
            }
            val modeBadge = makeBadge(
                modeLabel,
                when (r.mode) {
                    RuleEngine.EnforcementMode.BLOCK_ALWAYS -> "#450A0A"
                    RuleEngine.EnforcementMode.CONFIRM_ALWAYS -> "#422006"
                    RuleEngine.EnforcementMode.BEHAVIOR_PROMPT -> "#1E1B4B"
                },
                when (r.mode) {
                    RuleEngine.EnforcementMode.BLOCK_ALWAYS -> "#EF4444"
                    RuleEngine.EnforcementMode.CONFIRM_ALWAYS -> "#F59E0B"
                    RuleEngine.EnforcementMode.BEHAVIOR_PROMPT -> "#6366F1"
                },
                when (r.mode) {
                    RuleEngine.EnforcementMode.BLOCK_ALWAYS -> "#FCA5A5"
                    RuleEngine.EnforcementMode.CONFIRM_ALWAYS -> "#FDE68A"
                    RuleEngine.EnforcementMode.BEHAVIOR_PROMPT -> "#A5B4FC"
                }
            )
            topRow.addView(modeBadge)

            topRow.addView(TextView(this).apply {
                text = r.title
                setTextColor(if (r.enabled) Color.parseColor("#F8FAFC") else Color.parseColor("#64748B"))
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) }
            })

            val btnToggle = makeActionButton(
                if (r.enabled) "✅ Ativa" else "⏸️ Off",
                if (r.enabled) "#052E16" else "#1E293B",
                if (r.enabled) "#16A34A" else "#475569",
                if (r.enabled) "#4ADE80" else "#94A3B8"
            ) {
                re.toggleRule(r.id)
                renderRulesListIfChanged(force = true)
            }
            val btnEdit = makeActionButton("✏️", "#131C31", "#2563EB", "#93C5FD") {
                ruleIdField.setText(r.id)
                ruleTitleField.setText(r.title)
                ruleInstField.setText(r.instruction)
                ruleModeGroup.check(
                    when (r.mode) {
                        RuleEngine.EnforcementMode.BLOCK_ALWAYS -> 101
                        RuleEngine.EnforcementMode.CONFIRM_ALWAYS -> 102
                        RuleEngine.EnforcementMode.BEHAVIOR_PROMPT -> 103
                    }
                )
                Toast.makeText(this, "✏️ Editando regra '${r.title}' nos campos abaixo", Toast.LENGTH_SHORT).show()
            }
            val btnDel = makeActionButton("🗑️", "#450A0A", "#EF4444", "#FCA5A5") {
                re.removeRule(r.id)
                renderRulesListIfChanged(force = true)
            }

            topRow.addView(btnToggle)
            topRow.addView(btnEdit, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })
            topRow.addView(btnDel, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) })
            rowCard.addView(topRow)

            rowCard.addView(TextView(this).apply {
                text = r.instruction
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })

            rulesListContainer.addView(rowCard)
        }
    }
}
