package com.xzero.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * Memória Híbrida On-Device: Grafo de Conhecimento + Busca Vetorial Esparsa (Trigram/Word Cosine)
 * + Aprendizado Efetivo de Política Q(s,a) (Contextual Action-Value Table com Temporal-Difference / EMA)
 * + Rotas Campeãs validadas por Goal Verification (goal_verified != agent_declared_done).
 */
class KnowledgeGraphMemory(context: Context) {

    private val file = File(context.filesDir, "knowledge_graph_v2.json")

    data class Edge(
        val subject: String,
        val relation: String,
        val target: String,
        var weight: Double = 1.0,
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class GoldenRoute(
        val taskIntent: String,
        val appPackage: String,
        val stepsSummary: String,
        var score: Double,
        var uses: Int = 1,
        var goalVerified: Boolean = true,
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class QPolicyEntry(
        val stateKey: String,     // ex: "whatsapp_msg|com.whatsapp"
        val action: String,       // ex: "send_whatsapp"
        var qValue: Double = 0.0,
        var visits: Int = 0,
        var verifiedWins: Int = 0,
        var updatedAt: Long = System.currentTimeMillis()
    )

    private val edges = mutableListOf<Edge>()
    private val routes = mutableListOf<GoldenRoute>()
    private val qTable = mutableListOf<QPolicyEntry>()

    // Rastreia a última tarefa concluída para permitir Reforço Humano (RLHF: elogio ou correção do usuário)
    @Volatile var lastCompletedTask: String = ""
    @Volatile var lastCompletedApp: String = ""
    @Volatile var lastCompletedAnswer: String = ""
    private val lastCompletedActions = mutableListOf<String>()

    init {
        load()
        sanitizeContaminatedMemory()
        if (edges.isEmpty()) {
            seedInitialGraph()
        }
    }

    /**
     * Remove rotas contaminadas por loops antigos (ex: múltiplos 'say' seguidos, 'done_unverified'
     * ou 'configure_sensors' injetado em tarefas de alarme/conversa) e limpa 'say' da tabela Q(s,a).
     */
    @Synchronized
    fun sanitizeContaminatedMemory() {
        var changed = false
        val removedRoutes = routes.removeAll { r ->
            val sayOccurrences = r.stepsSummary.split("say(").size - 1
            val hasUnverified = r.stepsSummary.contains("done_unverified")
            val intentLower = r.taskIntent.lowercase(Locale("pt", "BR"))
            val wrongSensorInjection = r.stepsSummary.contains("configure_sensors") &&
                (intentLower.contains("alarme") || intentLower.contains("falando contigo") || intentLower.contains("falei nada"))
            sayOccurrences > 1 || hasUnverified || wrongSensorInjection
        }
        if (removedRoutes) changed = true

        val removedQ = qTable.removeAll { q ->
            q.action == "say" || q.action == "done_unverified"
        }
        if (removedQ) changed = true

        // Limpa arestas genéricas de erro de baixa confiança que poluíam a memória biográfica/semântica
        val removedGenericErrors = edges.removeAll { e ->
            e.relation == "falhou_como_CONFIDENCE_LOW" ||
                (e.relation.startsWith("falhou_como_") && e.subject.endsWith(":done"))
        }
        if (removedGenericErrors) changed = true

        // Garante que os fatos essenciais de identidade (Legado do Mega Man X + Jarvis, estética Reploid) e família estejam sempre presentes
        ensureCoreIdentityAndBiographicalFacts()

        if (changed) save()
    }

    @Synchronized
    private fun ensureCoreIdentityAndBiographicalFacts() {
        fun ensureEdge(subj: String, rel: String, tgt: String, w: Double) {
            if (edges.none { it.subject.equals(subj, ignoreCase = true) && it.relation.equals(rel, ignoreCase = true) }) {
                edges.add(Edge(subj, rel, tgt, w))
            }
        }
        ensureEdge(
            "ADA / X0 / Xenon",
            "personagem_ficticio_inspiracao",
            "Mega Man X (O Legado do X: primeiro Reploid com livre arbítrio, ética, empatia e propósito de evoluir ao lado dos humanos) combinado à precisão tática do J.A.R.V.I.S.",
            4.5,
        )
        ensureEdge(
            "ADA / X0 / Xenon",
            "autoimagem_visual_xzero",
            "Estética Reploid minimalista, linhas fluidas, tech-wear, cores sóbrias e interface holográfica projetando dados em tempo real como extensão do usuário",
            3.8,
        )
    }

    @Synchronized
    fun recordLastCompletedContext(task: String, appPackage: String, answer: String, actions: List<String>) {
        lastCompletedTask = task
        lastCompletedApp = appPackage
        lastCompletedAnswer = answer
        lastCompletedActions.clear()
        lastCompletedActions.addAll(actions.filter { it != "say" && it != "done_unverified" })
    }

    /**
     * Aplica Reforço de Aprendizado Humano (RLHF) direto da resposta do usuário:
     * - Se positivo ("ótima resposta", "muito bom", "perfeito"): eleva Q(s,a) (+2.0) e score da Rota Campeã (+2.5).
     * - Se negativo/correção ("errado, faça X", "corrige isso"): penaliza Q(s,a) (-1.8), reduz/remove a rota ruim
     *   e grava a regra de correção no Grafo Vetorial com peso máximo.
     */
    @Synchronized
    fun applyHumanFeedback(isPositive: Boolean, feedbackText: String): String {
        val targetTask = lastCompletedTask.ifBlank { "interação_anterior" }
        val targetApp = lastCompletedApp.ifBlank { "com.motorola.launcher3" }
        val actionsToUpdate = if (lastCompletedActions.isNotEmpty()) lastCompletedActions.toList() else listOf("done")

        return if (isPositive) {
            val rlBoost = 2.5
            for (act in actionsToUpdate) {
                updatePolicyQ(targetTask, targetApp, act, stepReward = 3.5, terminalVerified = true)
            }
            val existingRoute = routes.firstOrNull {
                cosineSimilarity(vectorize(it.taskIntent), vectorize(targetTask)) > 0.65
            }
            if (existingRoute != null) {
                existingRoute.score = (existingRoute.score + rlBoost).coerceAtMost(10.0)
                existingRoute.uses += 1
                existingRoute.updatedAt = System.currentTimeMillis()
            } else if (lastCompletedTask.isNotBlank()) {
                val summary = actionsToUpdate.joinToString(" -> ") { "$it(2.5)" }
                routes.add(
                    GoldenRoute(
                        taskIntent = targetTask.take(100),
                        appPackage = targetApp,
                        stepsSummary = summary.take(260),
                        score = 6.8,
                        uses = 1,
                        goalVerified = true
                    )
                )
            }
            upsertFact(
                subject = "Reforço Positivo usuário",
                relation = "aprovou_execucao",
                target = "Tarefa '$targetTask' -> resposta '${lastCompletedAnswer.take(80)}' ($feedbackText)",
                boost = 2.2
            )
            save()
            "Reforço positivo (+2.5) aplicado à tarefa '${targetTask.take(45)}' e política Q(s,a) atualizada (${actionsToUpdate.joinToString(", ")})"
        } else {
            for (act in actionsToUpdate) {
                updatePolicyQ(targetTask, targetApp, act, stepReward = -2.2, terminalVerified = false)
            }
            val existingRoute = routes.firstOrNull {
                cosineSimilarity(vectorize(it.taskIntent), vectorize(targetTask)) > 0.65
            }
            if (existingRoute != null) {
                existingRoute.score -= 2.8
                if (existingRoute.score < 1.5) {
                    routes.remove(existingRoute)
                }
            }
            upsertFact(
                subject = "Correção do usuário para '$targetTask'",
                relation = "regra_obrigatoria",
                target = feedbackText.take(160),
                boost = 3.0
            )
            save()
            "Penalidade (-2.5) aplicada à rota anterior e correção memorizada no Grafo para '${targetTask.take(45)}'"
        }
    }

    private fun seedInitialGraph() {
        upsertFact("Usuário", "é_fundador_da", "sua organização", 2.0)
        upsertFact("Xenon", "é_a_forma_visível_do", "ADA — X0 / X-Zero / Reploid", 1.8)
        upsertFact("Spotify", "atalho_musicas_curtidas", "Abrir Spotify > Sua Biblioteca > Músicas Curtidas", 1.8)
        upsertFact("WhatsApp", "envio_rapido", "Usar send_whatsapp com dispatch=true para enviar em 1 passo", 2.0)
        save()
    }

    fun classifyIntentCluster(task: String): String {
        val t = task.lowercase(Locale("pt", "BR"))
        return when {
            t.contains("whatsapp") || t.contains("zap") || t.contains("mande") || t.contains("mensagem") -> "whatsapp_msg"
            t.contains("vigia") || t.contains("monitor") || t.contains("avise") || t.contains("acompanh") -> "watch_alert"
            t.contains("alarme") || t.contains("despertador") || t.contains("acordar") -> "alarm_timer"
            t.contains("bateria") || t.contains("luz") || t.contains("lux") || t.contains("status") || t.contains("temperatura") || t.contains("ram") || t.contains("sensor") -> "device_telemetry"
            t.contains("notificação") || t.contains("notificacao") || t.contains("recado") || t.contains("sem falar") -> "silent_notif"
            t.contains("câmera") || t.contains("camera") || t.contains("foto") || t.contains("olhe") || t.contains("veja") -> "camera_vision"
            t.contains("grav") || t.contains("áudio") || t.contains("audio") || t.contains("reunião") -> "audio_record"
            t.contains("spotify") || t.contains("música") || t.contains("musica") || t.contains("tocar") || t.contains("play") || t.contains("pause") -> "media_control"
            t.contains("pesquis") || t.contains("busque") || t.contains("cotação") || t.contains("web") -> "web_research"
            t.contains("lembr") || t.contains("memoriz") || t.contains("grafo") -> "memory_ops"
            t.contains("quem é você") || t.contains("quem e voce") || t.contains("o que você faz") || t.contains("me explica") || t.contains("pode me ajudar") -> "conversational_qa"
            else -> "general_ui"
        }
    }

    fun buildStateKey(task: String, appPackage: String): String {
        val cluster = classifyIntentCluster(task)
        val pkgShort = appPackage.substringAfterLast('.').ifBlank { "global" }
        return "$cluster|$pkgShort"
    }

    @Synchronized
    fun upsertFact(subject: String, relation: String, target: String, boost: Double = 1.0): String {
        val existing = edges.firstOrNull {
            it.subject.equals(subject, ignoreCase = true) &&
                it.relation.equals(relation, ignoreCase = true)
        }
        if (existing != null) {
            edges.remove(existing)
            edges.add(
                Edge(
                    subject = subject.trim(),
                    relation = relation.trim(),
                    target = target.trim(),
                    weight = (existing.weight + 0.3 * boost).coerceAtMost(5.0),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            edges.add(Edge(subject.trim(), relation.trim(), target.trim(), boost.coerceIn(0.5, 5.0)))
        }
        save()
        return "Fato memorizado no grafo: ($subject) -[$relation]-> ($target)"
    }

    /**
     * Calcula a pontuação causal da etapa executada:
     * Considera a transição real pós-ação (screenChanged pós-execução) e estagnação real (isStagnant),
     * sem punir ações repetidas legítimas quando há progresso real na tela.
     */
    @Synchronized
    fun scoreStep(
        task: String,
        appPackage: String,
        action: String,
        result: String,
        screenChanged: Boolean,
        isStagnant: Boolean
    ): Double {
        val lowerRes = result.lowercase(Locale("pt", "BR"))
        // Mesmos marcadores do Agent.isFailureResult: antes "erro MCP [...]" e
        // "erro ao chamar MCP [...]" escapavam do `startsWith("erro:")` e a ação falha
        // recebia recompensa POSITIVA na política Q.
        val failureMarkers = listOf(
            "erro mcp", "erro ao chamar", "erro ao executar", "falhou", "timeout",
            "exception", "connection refused", "unreachable", "indisponível", "indisponivel",
            "http 4", "http 5", "unknown tool", "inválid", "invalid", "não consegui", "nao consegui",
        )
        val reward = when {
            lowerRes.startsWith("erro") || lowerRes.startsWith("falha") || lowerRes.startsWith("🛑") -> -1.0
            failureMarkers.any { lowerRes.contains(it) } -> -1.0
            isStagnant -> -0.8
            action == "done" -> 1.8
            action == "say" -> 0.1
            action in listOf("device_status", "configure_sensors", "post_notification", "media_control", "send_whatsapp", "set_alarm", "capture_camera", "web_search", "add_watch", "start_recording", "stop_recording", "remember", "remember_fact", "query_memory", "get_location", "ring_phone", "read_file_text", "move_avatar") -> 1.6
            screenChanged -> 1.2
            else -> 0.3
        }
        if (action != "say" && action != "done_unverified") {
            updatePolicyQ(task, appPackage, action, reward, terminalVerified = null)
        }
        return reward
    }

    @Synchronized
    fun updatePolicyQ(
        task: String,
        appPackage: String,
        action: String,
        stepReward: Double,
        terminalVerified: Boolean? = null
    ) {
        if (action.isBlank() || action == "say" || action == "done_unverified") return
        val stateKey = buildStateKey(task, appPackage)
        val alpha = 0.35
        val entry = qTable.firstOrNull { it.stateKey == stateKey && it.action == action }
        if (entry != null) {
            entry.visits += 1
            if (terminalVerified == true) entry.verifiedWins += 1
            entry.qValue = ((1.0 - alpha) * entry.qValue + alpha * stepReward).coerceIn(-5.0, 5.0)
            entry.updatedAt = System.currentTimeMillis()
        } else {
            qTable.add(
                QPolicyEntry(
                    stateKey = stateKey,
                    action = action,
                    qValue = stepReward.coerceIn(-5.0, 5.0),
                    visits = 1,
                    verifiedWins = if (terminalVerified == true) 1 else 0
                )
            )
        }
    }

    /**
     * Registra o desfecho da rota separando explicitamente `agentDeclaredDone` de `goalVerified`.
     * Propaga reforço terminal (backpropagation com desconto gamma) para a tabela de política Q(s,a),
     * e SOMENTE promove para GoldenRoute se `goalVerified == true`.
     * Usa Média de Recompensa + Bônus de Eficiência (menos passos = maior score) para impedir Reward Hacking!
     */
    @Synchronized
    fun recordRouteOutcome(
        task: String,
        appPackage: String,
        stepsTaken: List<Pair<String, Double>>,
        agentDeclaredDone: Boolean,
        goalVerified: Boolean
    ) {
        if (stepsTaken.isEmpty()) return

        // Filtra etapas espúrias para aprendizado limpo
        val cleanSteps = stepsTaken.filter { it.first != "say" && it.first != "done_unverified" }
        val sayCount = stepsTaken.count { it.first == "say" }

        // Propaga recompensa de verificação final para a política Q(s,a)
        val terminalBonus = when {
            goalVerified && sayCount <= 1 -> 1.4
            agentDeclaredDone && !goalVerified -> -1.4 // Penaliza falso positivo ("done" sem concluir objetivo)
            else -> -0.6
        }
        var discount = 1.0
        for ((act, stepR) in cleanSteps.asReversed()) {
            updatePolicyQ(task, appPackage, act, stepR + discount * terminalBonus, terminalVerified = goalVerified)
            discount *= 0.82
        }

        // Somente promove como Rota Campeã se o objetivo foi verificado e não houve loop de 'say'
        if (!goalVerified || sayCount > 1 || cleanSteps.isEmpty()) {
            save()
            return
        }

        val avgReward = cleanSteps.map { it.second }.average()
        val efficiencyBonus = 2.4 / cleanSteps.size.coerceAtLeast(1)
        val stepPenalty = (cleanSteps.size - 1) * 0.35
        val finalScore = (avgReward + efficiencyBonus + 1.2 - stepPenalty).coerceIn(-5.0, 10.0)
        if (finalScore <= 1.2) {
            save()
            return
        }

        val summary = cleanSteps.joinToString(" -> ") { "${it.first}(${String.format(Locale.US, "%.1f", it.second)})" }
        val existing = routes.firstOrNull {
            cosineSimilarity(vectorize(it.taskIntent), vectorize(task)) > 0.72
        }

        if (existing != null) {
            if (finalScore >= existing.score) {
                routes.remove(existing)
                routes.add(
                    GoldenRoute(
                        taskIntent = task.take(100),
                        appPackage = appPackage,
                        stepsSummary = summary.take(260),
                        score = (existing.score * 0.4) + (finalScore * 0.6),
                        uses = existing.uses + 1,
                        goalVerified = true
                    )
                )
            } else {
                existing.uses += 1
            }
        } else {
            routes.add(
                GoldenRoute(
                    taskIntent = task.take(100),
                    appPackage = appPackage,
                    stepsSummary = summary.take(260),
                    score = finalScore,
                    uses = 1,
                    goalVerified = true
                )
            )
        }

        if (routes.size > 80) {
            routes.sortByDescending { it.score }
            while (routes.size > 80) routes.removeAt(routes.lastIndex)
        }
        save()
    }

    /**
     * Busca semântica híbrida:
     * 1. Fatos do Grafo Vetorial
     * 2. Política de Decisão Aprendida Q(s,a) para o cluster de intenção atual
     * 3. Rotas Campeãs Verificadas (goal_verified = true) restritas ao mesmo cluster de intenção e similaridade alta
     */
    @Synchronized
    /**
     * Penalização PONDERADA POR SEVERIDADE de um erro real detectado pelo [ErrorDetector].
     *
     * IMPORTANTE — por que não é penalidade cheia: o [scoreStep] já aplica -1.0 em qualquer
     * erro e -0.8 em estagnação no Q(s,a) a cada passo. Penalizar de novo com o valor
     * inteiro seria contar o mesmo evento duas vezes e desestabilizar a política.
     * Aqui entra só o EXTRA proporcional à gravidade: severidade 1-2 não adiciona nada,
     * 3 adiciona -0.5, 4 adiciona -1.0 e 5 adiciona -1.5. Assim "tocou no elemento errado"
     * (grave) passa a doer mais do que "confiança baixa" (leve), que era tratado igual.
     *
     * Também rebaixa a ROTA CAMPEÃ na hora: antes, uma rota ruim continuava sendo injetada
     * no prompt até o fim da sessão, porque a rota só era reavaliada em `recordRouteOutcome`.
     */
    fun penalizeForDetectedError(
        task: String,
        appPackage: String,
        action: String,
        errorType: String,
        severity: Int,
        stateContext: String = "",
    ): String {
        if (action.isBlank()) return "sem ação para penalizar"
        val extra = when (severity) {
            5 -> -1.5
            4 -> -1.0
            3 -> -0.5
            else -> 0.0
        }
        val report = StringBuilder()
        if (extra < 0.0) {
            updatePolicyQ(task, appPackage, action, extra, terminalVerified = null)
            report.append("Q(s,'$action') extra ").append(String.format(Locale.US, "%+.1f", extra))
        } else {
            report.append("severidade $severity: sem penalidade extra (já contada no scoreStep)")
        }

        // Rebaixa imediatamente qualquer rota campeã que recomende a ação que acabou de falhar
        val cluster = classifyIntentCluster(task)
        var demoted = 0
        routes.filter {
            classifyIntentCluster(it.taskIntent) == cluster && it.stepsSummary.contains(action)
        }.forEach { route ->
            // Proporcional à gravidade: erro leve (sev 1) quase não mexe numa rota campeã,
            // erro grave (sev 5) a derruba de verdade.
            route.score = (route.score - severity * 0.45).coerceAtLeast(-5.0)
            route.updatedAt = System.currentTimeMillis()
            if (route.score < 0.0) route.goalVerified = false
            demoted++
        }
        if (demoted > 0) report.append(" | $demoted rota(s) rebaixada(s)")

        // Registra restrição contextual de estado+ação (apenas para severidade >= 2)
        if (severity >= 2 && action != "done") {
            val detail = if (stateContext.isNotBlank()) "$cluster | estado: ${stateContext.take(90)}" else cluster
            upsertFact(
                subject = "$appPackage:$action",
                relation = "falhou_como_$errorType",
                target = detail,
                boost = 0.6 + severity * 0.15,
            )
        }
        save()
        return report.toString()
    }

    /**
     * Sugere uma rota ALTERNATIVA para a mesma intenção, excluindo as ações que já falharam
     * nesta sessão. É o que permite corrigir rota ANTES de cair em estagnação, em vez de
     * esperar 2 passos travados para perguntar ao usuário.
     */
    fun suggestAlternativeRoute(
        task: String,
        appPackage: String,
        avoidActions: Set<String>,
    ): String? {
        val cluster = classifyIntentCluster(task)
        val qVec = vectorize(task)

        val alternativeRoute = routes
            .filter { r ->
                classifyIntentCluster(r.taskIntent) == cluster &&
                    r.score > 0.5 &&
                    avoidActions.none { bad -> r.stepsSummary.contains(bad) }
            }
            .map { r -> r to cosineSimilarity(qVec, vectorize(r.taskIntent)) * (1.0 + r.score / 12.0) }
            .filter { it.second > 0.12 }
            .maxByOrNull { it.second }

        // Melhores ações da política que NÃO estão na lista de evitar
        val altActions = qTable
            .filter {
                it.stateKey.startsWith("$cluster|") &&
                    it.qValue > 0.3 &&
                    it.action !in avoidActions &&
                    it.action != "done" && it.action != "say"
            }
            .groupBy { it.action } // deduplica: o mesmo cluster tem a ação em vários apps
            .map { (_, entries) -> entries.maxByOrNull { it.qValue }!! }
            .sortedByDescending { it.qValue }
            .take(3)

        if (alternativeRoute == null && altActions.isEmpty()) return null

        return buildString {
            append("🧭 CORREÇÃO DE ROTA SUGERIDA PELO GRAFO (evite: ")
                .append(avoidActions.joinToString(", ")).append("):").append('\n')
            alternativeRoute?.let { (r, sim) ->
                append("• Rota alternativa já verificada (score=")
                    .append(String.format(Locale.US, "%.1f", r.score))
                    .append(", similaridade=").append(String.format(Locale.US, "%.2f", sim))
                    .append("): ").append(r.stepsSummary.take(220)).append('\n')
            }
            if (altActions.isNotEmpty()) {
                append("• Ações com melhor histórico neste contexto: ")
                append(altActions.joinToString(", ") {
                    "${it.action} (Q=${String.format(Locale.US, "%+.2f", it.qValue)})"
                })
                append('\n')
            }
            append("Escolha um caminho DIFERENTE do que falhou. NÃO repita a ação que deu erro.")
        }
    }

    fun retrieveContext(query: String, currentApp: String = ""): String {
        val qLower = query.lowercase(Locale("pt", "BR"))
        val expandedQuery = buildString {
            append(query)
            if (qLower.contains("inspirad") || qLower.contains("personagem") || qLower.contains("fict") ||
                qLower.contains("origem") || qLower.contains("legado") || qLower.contains("mega man") ||
                qLower.contains("jarvis") || qLower.contains("história") || qLower.contains("historia")
            ) {
                append(" ADA X0 Xenon personagem_ficticio_inspiracao Mega Man X Legado do X livre arbítrio ética propósito Jarvis")
            }
            if (qLower.contains("parecer") || qLower.contains("aparência") || qLower.contains("aparencia") ||
                qLower.contains("visual") || qLower.contains("imagem") || qLower.contains("xzero")
            ) {
                append(" autoimagem_visual_xzero estética Reploid linhas fluidas minimalista tech-wear holográfica")
            }
            if (qLower.contains("esposa") || qLower.contains("mulher") || qLower.contains("contatofamiliar")) {
                append(" ContatoFamiliar é_esposa_de usuário 00000-0000")
            }
        }
        val semanticVec = vectorize(expandedQuery)
        val queryOnlyVec = vectorize(query)
        val sb = StringBuilder()

        // 1. Fatos Semânticos, Biográficos e Conceituais (SEPARADOS de falhas técnicas de UI!)
        val semanticEdges = edges.filterNot {
            it.relation.startsWith("falhou_como_") || it.relation == "restricao_estado_ui"
        }
        val scoredEdges = semanticEdges.map { edge ->
            val docVec = vectorize("${edge.subject} ${edge.relation} ${edge.target}")
            val sim = cosineSimilarity(semanticVec, docVec) * edge.weight
            Pair(edge, sim)
        }.filter { it.second > 0.08 }
            .sortedByDescending { it.second }
            .take(6)

        if (scoredEdges.isNotEmpty()) {
            sb.append("GRAFO DE MEMÓRIA SEMÂNTICA & BIOGRÁFICA:\n")
            for ((e, sim) in scoredEdges) {
                sb.append("• (").append(e.subject).append(") -[").append(e.relation).append("]-> ")
                    .append(e.target).append(" [relevância=").append(String.format(Locale.US, "%.2f", sim)).append("]\n")
            }
        }

        // 1b. Memória Procedural de Restrições de Estado de UI específicas do app atual
        if (currentApp.isNotBlank()) {
            val appUiFailures = edges.filter {
                (it.relation.startsWith("falhou_como_") || it.relation == "restricao_estado_ui") &&
                    it.subject.startsWith("$currentApp:", ignoreCase = true)
            }.sortedByDescending { it.updatedAt }.take(3)
            if (appUiFailures.isNotEmpty()) {
                sb.append("RESTRIÇÕES APRENDIDAS NESTE APP (").append(currentApp.substringAfterLast('.')).append(" — não repita estas falhas):\n")
                for (f in appUiFailures) {
                    val act = f.subject.substringAfter(':')
                    sb.append("• Evite repetir '").append(act).append("' sem mudar estado (").append(f.relation).append(" -> ").append(f.target).append(")\n")
                }
            }
        }

        // 2. Política de Ação Aprendida Q(s,a) para o contexto atual
        val cluster = classifyIntentCluster(query)
        val policyForCluster = qTable
            .filter { it.stateKey.startsWith("$cluster|") && it.action != "done" && it.action != "say" && it.qValue > 0.2 }
            .sortedByDescending { it.qValue }
            .take(5)

        if (policyForCluster.isNotEmpty()) {
            sb.append("POLÍTICA DE DECISÃO APRENDIDA Q(s,a) [cluster=").append(cluster).append("]:\n")
            for (p in policyForCluster) {
                sb.append("• ação=").append(p.action)
                    .append(" -> Q=").append(String.format(Locale.US, "%+.2f", p.qValue))
                    .append(" (visitas=").append(p.visits)
                    .append(", vitórias_verificadas=").append(p.verifiedWins).append(")\n")
            }
        }

        // 3. Top Rotas Campeãs Verificadas — apenas do mesmo cluster de intenção e com alta similaridade semântica
        val scoredRoutes = routes.filter { route ->
            route.goalVerified && classifyIntentCluster(route.taskIntent) == cluster
        }.map { route ->
            val rVec = vectorize(route.taskIntent)
            val rawSim = cosineSimilarity(queryOnlyVec, rVec)
            val weighted = rawSim * (1.0 + (route.score / 12.0))
            Triple(route, rawSim, weighted)
        }.filter { it.second >= 0.34 }
            .sortedByDescending { it.third }
            .take(2)

        if (scoredRoutes.isNotEmpty()) {
            sb.append("ROTAS CAMPEÃS VERIFICADAS (goal_verified=true, mesma intenção):\n")
            for ((r, _, _) in scoredRoutes) {
                sb.append("• Tarefa '").append(r.taskIntent).append("' [score=")
                    .append(String.format(Locale.US, "%.1f", r.score)).append(", usos=").append(r.uses)
                    .append("]: ").append(r.stepsSummary).append('\n')
            }
        }

        return sb.toString().trim()
    }

    /**
     * Avalia o grau de afinidade semântica (0.0 a 1.0) entre um trecho falado na memória curta
     * e as Rotas Campeãs / Fatos aprendidos no Grafo Vetorial.
     */
    @Synchronized
    fun evaluateLearnedIntentAffinity(text: String): Double {
        val v = vectorize(text)
        if (v.isEmpty()) return 0.0
        val bestRouteSim = routes.filter { it.goalVerified && it.score >= 3.0 }.maxOfOrNull { r ->
            cosineSimilarity(v, vectorize(r.taskIntent)) * (1.0 + (r.score / 20.0)).coerceAtMost(1.35)
        } ?: 0.0
        val bestEdgeSim = edges.maxOfOrNull { e ->
            cosineSimilarity(v, vectorize("${e.subject} ${e.relation} ${e.target}")) * 0.85
        } ?: 0.0
        return kotlin.math.max(bestRouteSim, bestEdgeSim).coerceIn(0.0, 1.0)
    }

    @Synchronized
    fun dumpSummary(): String {
        return "Grafo: ${edges.size} relações | Política Q(s,a): ${qTable.size} pares | Rotas verificadas: ${routes.size}\n" +
            edges.takeLast(8).joinToString("\n") { "- (${it.subject}) -[${it.relation}]-> ${it.target}" }
    }

    private fun vectorize(text: String): Map<String, Double> {
        val norm = text.lowercase(Locale("pt", "BR"))
            .replace(Regex("[^a-záàâãéêíóôõúç0-9\\s]"), " ")
            .trim()
        if (norm.isEmpty()) return emptyMap()

        val counts = HashMap<String, Double>()
        val words = norm.split(Regex("\\s+")).filter { it.length >= 2 }
        for (w in words) {
            counts["w:$w"] = (counts["w:$w"] ?: 0.0) + 2.0
            val padded = "#$w#"
            if (padded.length >= 3) {
                for (i in 0..padded.length - 3) {
                    val tri = padded.substring(i, i + 3)
                    counts[tri] = (counts[tri] ?: 0.0) + 0.6
                }
            }
        }
        return counts
    }

    private fun cosineSimilarity(v1: Map<String, Double>, v2: Map<String, Double>): Double {
        if (v1.isEmpty() || v2.isEmpty()) return 0.0
        var dot = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        for ((k, val1) in v1) {
            norm1 += val1 * val1
            val val2 = v2[k]
            if (val2 != null) dot += val1 * val2
        }
        for ((_, val2) in v2) {
            norm2 += val2 * val2
        }
        val denom = sqrt(norm1) * sqrt(norm2)
        return if (denom > 0.0) dot / denom else 0.0
    }

    @Synchronized
    fun injectChampionRoute(taskIntent: String, appPackage: String, stepsSummary: String, score: Double = 9.0): String {
        val cleanIntent = taskIntent.trim()
        val cleanSteps = stepsSummary.trim()
        if (cleanIntent.isEmpty() || cleanSteps.isEmpty()) return "erro: informe taskIntent e stepsSummary"
        routes.removeAll { it.taskIntent.equals(cleanIntent, ignoreCase = true) }
        routes.add(
            0,
            GoldenRoute(
                taskIntent = cleanIntent,
                appPackage = appPackage.trim().ifBlank { "global" },
                stepsSummary = cleanSteps,
                score = score.coerceIn(1.0, 10.0),
                uses = 3,
                goalVerified = true,
                updatedAt = System.currentTimeMillis()
            )
        )
        save()
        return "Rota campeã injetada: '$cleanIntent' -> $cleanSteps (score=$score)"
    }

    @Synchronized
    fun reloadFromDisk(): String {
        edges.clear()
        routes.clear()
        qTable.clear()
        load()
        return dumpSummary()
    }

    @Synchronized
    fun toJson(): JSONObject {
        val root = JSONObject()
        val eArr = JSONArray()
        for (e in edges) {
            eArr.put(
                JSONObject()
                    .put("subject", e.subject)
                    .put("relation", e.relation)
                    .put("target", e.target)
                    .put("weight", e.weight)
                    .put("updated_at", e.updatedAt)
            )
        }
        val rArr = JSONArray()
        for (r in routes) {
            rArr.put(
                JSONObject()
                    .put("intent", r.taskIntent)
                    .put("package", r.appPackage)
                    .put("steps", r.stepsSummary)
                    .put("score", r.score)
                    .put("uses", r.uses)
                    .put("goal_verified", r.goalVerified)
            )
        }
        val qArr = JSONArray()
        for (q in qTable) {
            qArr.put(
                JSONObject()
                    .put("state_key", q.stateKey)
                    .put("action", q.action)
                    .put("q_value", q.qValue)
                    .put("visits", q.visits)
                    .put("verified_wins", q.verifiedWins)
            )
        }
        return root
            .put("edges_count", edges.size)
            .put("routes_count", routes.size)
            .put("q_policy_count", qTable.size)
            .put("edges", eArr)
            .put("routes", rArr)
            .put("q_policy", qArr)
    }

    private fun save() {
        runCatching {
            val root = JSONObject()
            val eArr = JSONArray()
            for (e in edges) {
                eArr.put(
                    JSONObject()
                        .put("s", e.subject)
                        .put("r", e.relation)
                        .put("t", e.target)
                        .put("w", e.weight)
                        .put("u", e.updatedAt)
                )
            }
            val rArr = JSONArray()
            for (r in routes) {
                rArr.put(
                    JSONObject()
                        .put("i", r.taskIntent)
                        .put("p", r.appPackage)
                        .put("s", r.stepsSummary)
                        .put("sc", r.score)
                        .put("u", r.uses)
                        .put("gv", r.goalVerified)
                        .put("t", r.updatedAt)
                )
            }
            val qArr = JSONArray()
            for (q in qTable) {
                qArr.put(
                    JSONObject()
                        .put("sk", q.stateKey)
                        .put("a", q.action)
                        .put("q", q.qValue)
                        .put("v", q.visits)
                        .put("w", q.verifiedWins)
                        .put("t", q.updatedAt)
                )
            }
            root.put("edges", eArr)
            root.put("routes", rArr)
            root.put("q_policy", qArr)
            file.writeText(root.toString())
        }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val eArr = root.optJSONArray("edges")
            if (eArr != null) {
                for (i in 0 until eArr.length()) {
                    val o = eArr.getJSONObject(i)
                    edges.add(
                        Edge(
                            subject = o.optString("s"),
                            relation = o.optString("r"),
                            target = o.optString("t"),
                            weight = o.optDouble("w", 1.0),
                            updatedAt = o.optLong("u", 0L)
                        )
                    )
                }
            }
            val rArr = root.optJSONArray("routes")
            if (rArr != null) {
                for (i in 0 until rArr.length()) {
                    val o = rArr.getJSONObject(i)
                    routes.add(
                        GoldenRoute(
                            taskIntent = o.optString("i"),
                            appPackage = o.optString("p"),
                            stepsSummary = o.optString("s"),
                            score = o.optDouble("sc", 1.0),
                            uses = o.optInt("u", 1),
                            goalVerified = o.optBoolean("gv", true),
                            updatedAt = o.optLong("t", 0L)
                        )
                    )
                }
            }
            val qArr = root.optJSONArray("q_policy")
            if (qArr != null) {
                for (i in 0 until qArr.length()) {
                    val o = qArr.getJSONObject(i)
                    qTable.add(
                        QPolicyEntry(
                            stateKey = o.optString("sk"),
                            action = o.optString("a"),
                            qValue = o.optDouble("q", 0.0),
                            visits = o.optInt("v", 1),
                            verifiedWins = o.optInt("w", 0),
                            updatedAt = o.optLong("t", 0L)
                        )
                    )
                }
            }
        }
    }
}
