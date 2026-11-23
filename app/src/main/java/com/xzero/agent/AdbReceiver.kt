package com.xzero.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Configuração/comandos via adb (protegido por permissão DUMP — só o shell do adb consegue enviar).
 *  adb shell am broadcast -a com.xzero.agent.CONFIG -n com.xzero.agent/.AdbReceiver --es gemini_key XXX --es claude_key YYY --es openai_key ZZZ --es primary gemini
 *  adb shell am broadcast -a com.xzero.agent.ASK -n com.xzero.agent/.AdbReceiver --es text "abre o relógio"
 */
class AdbReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "com.xzero.agent.GET_CONFIG" -> {
                val p = Prefs(ctx)
                val svc = BubbleService.instance
                val out = org.json.JSONObject()
                    .put("prefs", p.toJson())
                    .put("sensors", ProactiveWatcher.toSensorConfigJson())
                if (svc != null) {
                    val ambMcp = svc.getAmbientAndMcpStatusJson()
                    out.put("ambient", ambMcp.optJSONObject("ambient"))
                    out.put("mcp", ambMcp.optJSONObject("mcp"))
                } else {
                    out.put("mcp", McpClientManager(ctx).toJson())
                }
                resultData = out.toString()
            }
            "com.xzero.agent.CONFIG" -> {
                val p = Prefs(ctx)
                val svc = BubbleService.instance
                val agent = svc?.getAgentInstance()
                i.getStringExtra("gemini_key")?.let { p.geminiKey = it }
                i.getStringExtra("gemini_model")?.let { p.geminiModel = it }
                i.getStringExtra("claude_key")?.let { p.claudeKey = it }
                i.getStringExtra("claude_model")?.let { p.claudeModel = it }
                i.getStringExtra("openai_key")?.let { p.openaiKey = it }
                i.getStringExtra("openai_model")?.let { p.openaiModel = it }
                i.getStringExtra("primary")?.let { p.primary = it }
                i.getStringExtra("wake_word")?.let { p.wakeWord = it.toBoolean() }
                i.getStringExtra("speak")?.let { p.speak = it.toBoolean() }
                i.getStringExtra("subtle_sound")?.let { p.subtleSound = it.toBoolean() }
                i.getStringExtra("auto_intent_threshold")?.toDoubleOrNull()?.let { th ->
                    p.autoIntentThreshold = th
                    agent?.ambient?.autoIntentThreshold = th.coerceIn(2.0, 8.5)
                }
                i.getStringExtra("pre_exec_confirm")?.let {
                    p.preExecVoiceConfirm = it.toBoolean()
                }
                if (i.hasExtra("sensor_mode") || i.hasExtra("light") || i.hasExtra("accelerometer") ||
                    i.hasExtra("proximity") || i.hasExtra("gyroscope") || i.hasExtra("steps") || i.hasExtra("auto_silent")
                ) {
                    val mode = i.getStringExtra("sensor_mode") ?: ProactiveWatcher.sensorMode
                    p.sensorMode = mode
                    ProactiveWatcher.configureSensors(
                        ctx = ctx,
                        mode = mode,
                        light = i.getStringExtra("light")?.toBoolean() ?: ProactiveWatcher.enableLight,
                        accel = i.getStringExtra("accelerometer")?.toBoolean() ?: ProactiveWatcher.enableAccel,
                        proximity = i.getStringExtra("proximity")?.toBoolean() ?: ProactiveWatcher.enableProximity,
                        gyro = i.getStringExtra("gyroscope")?.toBoolean() ?: ProactiveWatcher.enableGyro,
                        steps = i.getStringExtra("steps")?.toBoolean() ?: ProactiveWatcher.enableSteps,
                        autoSilent = i.getStringExtra("auto_silent")?.toBoolean() ?: ProactiveWatcher.autoSilentInDarkOrPocket
                    )
                }
                svc?.syncWakeWord()
                resultData = "ok gemini=${p.geminiKey.isNotBlank()} claude=${p.claudeKey.isNotBlank()} openai=${p.openaiKey.isNotBlank()} primary=${p.primary} wake=${p.wakeWord} speak=${p.speak} threshold=${p.autoIntentThreshold} sensors=${ProactiveWatcher.sensorMode}"
            }
            "com.xzero.agent.ASK" -> {
                val t = i.getStringExtra("text") ?: return
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                svc.handleText(t); resultData = "enviado"
            }
            "com.xzero.agent.WAKE" -> {
                val t = i.getStringExtra("text") ?: return
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                resultData = svc.processWakeTranscript(t)
            }
            "com.xzero.agent.LISTEN" -> {
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                svc.activateFromAssistantTrigger()
                resultData = "listening_activated"
            }
            "com.xzero.agent.HEALTH" -> {
                val heal = i.getBooleanExtra("heal", true)
                resultData = runCatching { HealthMonitor.checkAndHeal(ctx, heal = heal).toString() }
                    .getOrElse { "{\"error\":\"${it.message}\"}" }
            }
            "com.xzero.agent.TRAJECTORY" -> {
                val logger = TrajectoryLogger(ctx)
                val sid = i.getStringExtra("session_id")
                resultData = if (!sid.isNullOrBlank()) logger.readSession(sid) else logger.listSessions()
            }
            "com.xzero.agent.RECOVER" -> {
                resultData = HealthMonitor.forceRecoverUi(ctx)
            }
            "com.xzero.agent.ANIMATE" -> {
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada"; return }
                val act = i.getStringExtra("anim")?.uppercase() ?: "WAVE"
                val speech = i.getStringExtra("speech")
                val targetX = i.getIntExtra("target_x", -1)
                svc.triggerDemoAnimation(act, speech, targetX)
                resultData = "anim=$act"
            }
            "com.xzero.agent.BTW" -> {
                val t = i.getStringExtra("text") ?: return
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                val prefixed = if (t.trim().startsWith("/btw", ignoreCase = true)) t else "/btw $t"
                svc.handleText(prefixed, fromPanel = false)
                resultData = "btw_injected"
            }
            "com.xzero.agent.MCP" -> {
                val svc = BubbleService.instance
                val mcp = svc?.getAgentInstance()?.mcp ?: McpClientManager(ctx)
                val op = i.getStringExtra("op")?.lowercase() ?: "list"
                if (op == "add" || op == "sync" || op == "call") {
                    val pr = goAsync()
                    val name = i.getStringExtra("name") ?: ""
                    val url = i.getStringExtra("url") ?: ""
                    val token = i.getStringExtra("token") ?: ""
                    val enabled = i.getBooleanExtra("enabled", true)
                    val server = i.getStringExtra("server") ?: ""
                    val tool = i.getStringExtra("tool") ?: ""
                    val argsStr = i.getStringExtra("args_json") ?: "{}"
                    Thread {
                        try {
                            pr.resultData = when (op) {
                                "add" -> mcp.addOrUpdateServer(name = name, url = url, authToken = token, enabled = enabled)
                                "sync" -> mcp.syncAllServers()
                                "call" -> {
                                    val argsObj = runCatching { org.json.JSONObject(argsStr) }.getOrElse { org.json.JSONObject() }
                                    mcp.callTool(serverName = server, toolName = tool, arguments = argsObj)
                                }
                                else -> mcp.listServersSummary()
                            }
                        } catch (t: Throwable) {
                            pr.resultData = "erro MCP: ${t.message}"
                        } finally {
                            pr.finish()
                        }
                    }.start()
                    return
                }
                resultData = when (op) {
                    "remove" -> mcp.removeServer(i.getStringExtra("name") ?: "")
                    "reload" -> mcp.reloadFromDisk()
                    "json" -> mcp.toJson().toString()
                    else -> mcp.listServersSummary()
                }
            }
            "com.xzero.agent.AMBIENT_STATUS" -> {
                val svc = BubbleService.instance
                resultData = if (svc != null) {
                    svc.getAmbientAndMcpStatusJson().toString()
                } else {
                    McpClientManager(ctx).toJson().toString()
                }
            }
            "com.xzero.agent.SHAKE" -> {
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                svc.onShakeDetected()
                resultData = if (svc.isJobActive()) "shake_btw_armed" else "shake_listen_activated"
            }
            "com.xzero.agent.PICKUP" -> {
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                svc.onPhonePickedUp()
                resultData = "pickup_sensor_boost_active"
            }
            "com.xzero.agent.RULES" -> {
                val svc = BubbleService.instance
                val re = svc?.getAgentInstance()?.rules ?: RuleEngine(ctx)
                val op = i.getStringExtra("op")?.lowercase() ?: "list"
                resultData = when (op) {
                    "add", "update" -> re.addOrUpdateRule(
                        title = i.getStringExtra("title") ?: "",
                        instruction = i.getStringExtra("instruction") ?: "",
                        modeStr = i.getStringExtra("mode") ?: "BEHAVIOR_PROMPT",
                        targetPkg = i.getStringExtra("package") ?: "*",
                        keywordsCsv = i.getStringExtra("keywords") ?: "",
                        ruleId = i.getStringExtra("id")
                    )
                    "toggle" -> re.toggleRule(i.getStringExtra("id") ?: "")
                    "remove" -> re.removeRule(i.getStringExtra("id") ?: "")
                    "json" -> re.toJsonArray().toString()
                    else -> re.listRulesSummary()
                }
            }
            "com.xzero.agent.BARGE_IN" -> {
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada"; return }
                svc.interruptTtsBargeIn("ADB Barge-In")
                resultData = "barge_in_stopped_tts"
            }
            "com.xzero.agent.GRAPH" -> {
                val svc = BubbleService.instance
                val g = svc?.getAgentInstance()?.graph ?: KnowledgeGraphMemory(ctx)
                val op = i.getStringExtra("op")?.lowercase() ?: "summary"
                resultData = when (op) {
                    "json" -> g.toJson().toString()
                    "query" -> g.retrieveContext(i.getStringExtra("query") ?: "")
                    "upsert_fact" -> g.upsertFact(
                        subject = i.getStringExtra("subject") ?: "Usuário",
                        relation = i.getStringExtra("relation") ?: "info",
                        target = i.getStringExtra("target") ?: "",
                        boost = i.getStringExtra("boost")?.toDoubleOrNull() ?: 1.5
                    )
                    "add_route" -> g.injectChampionRoute(
                        taskIntent = i.getStringExtra("intent") ?: "",
                        appPackage = i.getStringExtra("package") ?: "global",
                        stepsSummary = i.getStringExtra("steps") ?: "",
                        score = i.getStringExtra("score")?.toDoubleOrNull() ?: 9.2
                    )
                    "rlhf" -> g.applyHumanFeedback(
                        isPositive = i.getStringExtra("positive")?.toBoolean() ?: true,
                        feedbackText = i.getStringExtra("feedback") ?: "Aprovado via MCP"
                    )
                    "reload" -> g.reloadFromDisk()
                    else -> g.dumpSummary()
                }
            }
            // Monitoramento/calibração do áudio (acordar + aceitar a ação):
            //   op=status  -> telemetria completa em JSON
            //   op=reset   -> zera a pontuação aprendida do despertar
            //   op=offset --es db "2.5" -> força um ajuste manual do limiar em dB
            "com.xzero.agent.AUDIO" -> {
                VadAutoTuner.init(ctx)
                resultData = when (i.getStringExtra("op")?.lowercase() ?: "status") {
                    "reset" -> {
                        VadAutoTuner.reset()
                        AudioTelemetry.reset()
                        "calibração e telemetria de áudio zeradas"
                    }
                    "offset" -> {
                        val db = i.getStringExtra("db")?.toDoubleOrNull()
                        if (db == null) "informe --es db \"<valor em dB>\""
                        else {
                            VadAutoTuner.setOffsetDb(ctx, db)
                            "offset do limiar ajustado para ${VadAutoTuner.snrOffsetDb} dB"
                        }
                    }
                    "voiceprint_reset" -> {
                        VoicePrint.init(ctx)
                        VoicePrint.reset()
                        "perfil de voz zerado"
                    }
                    "gate" -> {
                        val on = i.getStringExtra("enabled")?.toBoolean() ?: false
                        Prefs(ctx).voicePrintGate = on
                        "gate de voz ${if (on) "LIGADO (só acorda com a voz do usuário)" else "desligado"}"
                    }
                    else -> AudioTelemetry.getJson().toString()
                }
            }
            // Controle de voz TTS:
            //   op=list            -> vozes pt-BR instaladas, ranqueadas pelo critério Jarvis
            //   op=set --es profile jarvis|natural|fast
            //   op=say  --es text "frase de teste"
            // Personalidade editável em runtime:
            //   op=status                 -> onde estão os arquivos e tamanhos
            //   op=show --es file PERSONALITY.md|USER_PROFILE.md
            //   op=prompt                 -> bloco exato que vai para o LLM
            //   op=reload                 -> força recarga
            //   op=reset --es file <nome> -> volta ao padrão de fábrica
            // Checkpoint de tarefa: status | clear
            // Iniciativa própria: status | tick (força um ciclo) | diario | scan_storage | critical_check | research | on | off
            "com.xzero.agent.INITIATIVE" -> {
                InitiativeEngine.init(ctx)
                resultData = when (i.getStringExtra("op")?.lowercase() ?: "status") {
                    "tick" -> {
                        val g = BubbleService.instance?.getAgentInstance()?.getGraphForInitiative()
                        val s2 = InitiativeEngine.tickForcado(g)
                        s2 ?: "ciclo rodou sem sugestão nova"
                    }
                    "diario" -> InitiativeEngine.lerDiarioDeHoje()
                    "scan_storage" -> {
                        val minMb = i.getLongExtra("min_mb", 25L)
                        InitiativeEngine.escanearArquivosGrandesRapido(minMb = minMb, limit = 8)
                            .ifBlank { "nenhum arquivo grande encontrado acima de ${minMb}MB" }
                    }
                    "critical_check" -> {
                        InitiativeEngine.verificarLogsCriticosEArmazenamento()
                        InitiativeEngine.toJson().toString()
                    }
                    "research" -> {
                        val q = i.getStringExtra("query").orEmpty().trim()
                        if (q.isBlank()) "informe --es query \"...\""
                        else {
                            InitiativeEngine.pesquisarETrazerParaTela(q, "adb")
                            "pesquisa proativa disparada para a tela: $q"
                        }
                    }
                    "on" -> { Prefs(ctx).iniciativaAtiva = true; "iniciativa ligada" }
                    "off" -> { Prefs(ctx).iniciativaAtiva = false; "iniciativa desligada" }
                    else -> InitiativeEngine.toJson().toString()
                }
            }
            "com.xzero.agent.CHECKPOINT" -> {
                TaskCheckpoint.init(ctx)
                resultData = when (i.getStringExtra("op")?.lowercase() ?: "status") {
                    "clear" -> { TaskCheckpoint.clear(); "checkpoint apagado" }
                    else -> TaskCheckpoint.toJson().toString()
                }
            }
            "com.xzero.agent.PERSONA" -> {
                PersonaStore.init(ctx)
                val file = i.getStringExtra("file") ?: PersonaStore.PERSONALITY
                resultData = when (i.getStringExtra("op")?.lowercase() ?: "status") {
                    "show" -> PersonaStore.read(file).ifBlank { "($file vazio ou inexistente)" }
                    "prompt" -> PersonaStore.promptBlock().ifBlank { "(bloco vazio)" }
                    "reload" -> {
                        PersonaStore.forceReload()
                        "persona recarregada: ${PersonaStore.promptBlock().length} chars"
                    }
                    "reset" -> PersonaStore.resetToSeed(file)
                    else -> PersonaStore.toJson().toString()
                }
            }
            "com.xzero.agent.VOICE" -> {
                val svc = BubbleService.instance
                if (svc == null) {
                    resultData = "bolha não está rodando"
                } else {
                    resultData = when (i.getStringExtra("op")?.lowercase() ?: "list") {
                        "set" -> {
                            val prof = i.getStringExtra("profile")?.lowercase() ?: "jarvis"
                            Prefs(ctx).voiceProfile = if (prof in listOf("jarvis", "natural", "fast")) prof else "jarvis"
                            svc.reloadVoiceProfile()
                            "perfil de voz = ${Prefs(ctx).voiceProfile}"
                        }
                        "pin" -> {
                            val name = i.getStringExtra("name") ?: ""
                            Prefs(ctx).pinnedVoiceName = name
                            svc.reloadVoiceProfile()
                            if (name.isBlank()) "voz liberada (volta ao ranking automático)"
                            else "voz fixada em '$name'"
                        }
                        "say" -> {
                            val txt = i.getStringExtra("text") ?: "Teste de voz do X Zero."
                            svc.speakTest(txt)
                            "falando: $txt"
                        }
                        else -> svc.listVoicesJson().toString()
                    }
                }
            }
            "com.xzero.agent.WATCHES" -> {
                val op = i.getStringExtra("op")?.lowercase() ?: "list"
                resultData = when (op) {
                    "json" -> ProactiveWatcher.watchesToJsonArray().toString()
                    "add" -> ProactiveWatcher.addWatch(
                        source = i.getStringExtra("source") ?: "whatsapp",
                        filter = i.getStringExtra("filter") ?: "",
                        instruction = i.getStringExtra("instruction") ?: "Avisar o usuário e sugerir ação",
                        autoOpen = i.getStringExtra("auto_open")?.toBoolean() ?: false
                    )
                    "remove" -> ProactiveWatcher.removeWatch(i.getStringExtra("id") ?: i.getStringExtra("filter") ?: "")
                    else -> ProactiveWatcher.listWatches()
                }
            }
            "com.xzero.agent.EXEC_TOOL" -> {
                val act = i.getStringExtra("action") ?: "device_status"
                if (act == "probe_cameras") {
                    resultData = CameraVision.probeConcurrentCameras(ctx)
                    return
                }
                if (act == "demo_start") {
                    DemoProjectionActivity.launch(ctx, startFrontCam = true)
                    resultData = "demo_projection_prompt_launched"
                    return
                }
                if (act == "demo_switch") {
                    resultData = DemoRecorderService.switchCamera()
                    return
                }
                if (act == "demo_stop") {
                    DemoRecorderService.stop(ctx)
                    resultData = "demo_stopped"
                    return
                }
                val svc = BubbleService.instance
                if (svc == null) { BubbleService.start(ctx); resultData = "bolha iniciada, reenvie"; return }
                val argsJson = i.getStringExtra("args_json") ?: "{}"
                val pr = goAsync()
                Thread {
                    try {
                        pr.resultData = svc.getAgentInstance().executeExternalToolBlocking(act, argsJson)
                    } catch (t: Throwable) {
                        pr.resultData = "erro ao executar tool '$act': ${t.message}"
                    } finally {
                        pr.finish()
                    }
                }.start()
                return
            }
            "com.xzero.agent.SCREEN_TREE" -> {
                val dumpStr = AgentAccessibilityService.instance?.dumpScreen()
                resultData = if (dumpStr == null) {
                    "erro: AgentAccessibilityService desconectado"
                } else {
                    val curApp = dumpStr.lineSequence().firstOrNull()?.removePrefix("App em foco: ")?.trim().orEmpty()
                    org.json.JSONObject()
                        .put("package", curApp)
                        .put("formatted_tree", dumpStr)
                        .toString()
                }
            }
        }
    }
}
