package com.xzero.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Grafo de Orquestração Cognitiva: transforma uma ideia ou pedido natural do usuário
 * em uma execução estruturada por nós claros:
 *
 *   INTENT_CLASSIFY -> CONTEXT_ENRICH -> RISK_GATE -> EXECUTE / SANITIZE -> VERIFY -> RECOVER / LEARN
 *
 * Complementa o [KnowledgeGraphMemory] (memória declarativa + Q(s,a) + GoldenRoutes),
 * garantindo que o agente não caia cegamente em loops de UI nem falhe por JSON incompleto
 * em ações compostas como `batch`.
 */
object IdeaToActionGraph {

    private const val TAG = "IdeaGraph"

    enum class IntentKind {
        CONVERSATIONAL,
        ACTION_SIMPLE,
        IDEA_COMPLEX
    }

    enum class RiskTier {
        TIER_0_SAFE,        // leitura local, consulta, resposta
        TIER_1_REVERSIBLE,  // abrir app, navegar, mídia, alarme
        TIER_2_SENSITIVE    // enviar mensagem externa, ligar, gravar áudio
    }

    enum class GraphNode {
        INTENT_CLASSIFY,
        ROUTE_MEMORY_LOOKUP,
        RISK_GATE,
        EXECUTING,
        BATCH_SANITIZE,
        VERIFYING,
        RECOVERING,
        COMPLETED
    }

    data class ExecutionPlanContext(
        val intentKind: IntentKind,
        val riskTier: RiskTier,
        val promptHint: String,
        val nodesVisited: MutableList<String> = mutableListOf()
    )

    @Volatile private var lastSessionTask: String = ""
    @Volatile private var lastIntentKind: IntentKind = IntentKind.CONVERSATIONAL
    @Volatile private var lastRiskTier: RiskTier = RiskTier.TIER_0_SAFE
    @Volatile private var lastBatchFixes: Int = 0
    @Volatile private var lastNodes: List<String> = emptyList()

    /**
     * Nó 1: Classifica a ideia/comando recebido e gera uma diretriz leve de orquestração
     * para orientar o LLM a transformar a ideia em ação natural e objetiva.
     */
    fun prepare(rawTask: String): ExecutionPlanContext {
        val clean = rawTask
            .substringBefore("[Contexto visual")
            .substringBefore("[CONTEXTO CURTO")
            .trim()
        val lower = clean.lowercase(Locale("pt", "BR"))

        val isSensitive = listOf(
            "manda", "mandar", "envia", "enviar", "responde", "responder",
            "liga ", "ligar", "chama ", "gravar", "gravação", "apaga", "apagar", "paga", "pagar"
        ).any { lower.contains(it) }

        val isSimpleAction = listOf(
            "abre ", "abrir ", "abra ", "notifica", "alarme", "volume", "brilho",
            "toca ", "tocar ", "pausa", "voltar", "tela inicial", "bateria", "que horas"
        ).any { lower.contains(it) }

        val isIdeaComplex = !isSimpleAction && (
            clean.length > 55 ||
                listOf("preciso ", "quero que ", "prepara", "organiza", "resolve", "pesquisa e ", "procura ", "planeja", "ideia")
                    .any { lower.contains(it) }
            )

        val kind = when {
            isIdeaComplex -> IntentKind.IDEA_COMPLEX
            isSimpleAction || isSensitive -> IntentKind.ACTION_SIMPLE
            else -> IntentKind.CONVERSATIONAL
        }

        val risk = when {
            isSensitive -> RiskTier.TIER_2_SENSITIVE
            isSimpleAction || isIdeaComplex -> RiskTier.TIER_1_REVERSIBLE
            else -> RiskTier.TIER_0_SAFE
        }

        val hint = when (kind) {
            IntentKind.IDEA_COMPLEX ->
                "[ORQUESTRAÇÃO EM GRAFO — IDEIA COMPLEXA | Risco=$risk]: Quebre o pedido em no máximo 3 sub-etapas claras, prefira ações diretas (open_app, read_notifications, web_search) antes de navegar por múltiplos cliques, e valide cada transição de tela."
            IntentKind.ACTION_SIMPLE ->
                "[ORQUESTRAÇÃO EM GRAFO — AÇÃO DIRETA | Risco=$risk]: Execute a ação mais direta possível em 1–2 passos e finalize com resposta natural e curta."
            IntentKind.CONVERSATIONAL ->
                "[ORQUESTRAÇÃO EM GRAFO — CONVERSA]: Responda de forma natural, calorosa e direta via `done` sem acionar navegação de tela desnecessária."
        }

        val ctx = ExecutionPlanContext(
            intentKind = kind,
            riskTier = risk,
            promptHint = hint,
            nodesVisited = mutableListOf(GraphNode.INTENT_CLASSIFY.name, GraphNode.ROUTE_MEMORY_LOOKUP.name)
        )
        lastSessionTask = clean.take(90)
        lastIntentKind = kind
        lastRiskTier = risk
        lastNodes = ctx.nodesVisited.toList()
        Log.i(TAG, "🕸️ [Grafo Ideia→Ação] kind=$kind risk=$risk task='${clean.take(60)}'")
        return ctx
    }

    fun recordNode(ctx: ExecutionPlanContext?, node: GraphNode) {
        if (ctx == null) return
        if (ctx.nodesVisited.lastOrNull() != node.name) {
            ctx.nodesVisited.add(node.name)
            lastNodes = ctx.nodesVisited.toList()
        }
    }

    /**
     * Nó de Sanitização de `batch`: corrige o principal gargalo medido no painel /harness
     * (47,6% de falha em `batch`, frequentemente por o LLM omitir o campo `"action"` nos sub-passos
     * ou emitir `batch {}` vazio).
     *
     * Retorna `null` se o batch for inválido/vazio (evitando desperdiçar um passo de UI).
     */
    fun sanitizeBatchArgs(args: JSONObject, ctx: ExecutionPlanContext? = null): JSONObject? {
        val steps = args.optJSONArray("steps") ?: return null
        if (steps.length() == 0) return null

        val fixedSteps = JSONArray()
        var fixes = 0
        for (i in 0 until steps.length()) {
            val raw = steps.optJSONObject(i) ?: continue
            val copy = JSONObject(raw.toString())
            var act = copy.optString("action").trim()
            if (act.isBlank()) {
                act = when {
                    copy.has("text") && copy.optString("text").isNotBlank() -> "type"
                    copy.has("id") || copy.has("label") -> "tap"
                    copy.has("dir") -> "scroll"
                    copy.has("name") -> "open_app"
                    else -> ""
                }
                if (act.isNotBlank()) {
                    copy.put("action", act)
                    fixes++
                }
            }
            if (act.isNotBlank()) {
                // Normaliza sub-argumentos aninhados se vieram planos no objeto do step
                if (!copy.has("args")) {
                    val subArgs = JSONObject()
                    val keys = copy.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k != "action" && k != "args") {
                            subArgs.put(k, copy.get(k))
                        }
                    }
                    copy.put("args", subArgs)
                }
                fixedSteps.put(copy)
            }
        }

        if (fixedSteps.length() == 0) return null
        if (fixes > 0) {
            lastBatchFixes += fixes
            recordNode(ctx, GraphNode.BATCH_SANITIZE)
            Log.i(TAG, "🛠️ [BatchSanitizer] Corrigidos $fixes passo(s) sem 'action' explícita")
        }
        return JSONObject().put("steps", fixedSteps)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("last_task", lastSessionTask)
        put("intent_kind", lastIntentKind.name)
        put("risk_tier", lastRiskTier.name)
        put("batch_auto_fixes", lastBatchFixes)
        put("nodes_visited", JSONArray(lastNodes))
    }
}
