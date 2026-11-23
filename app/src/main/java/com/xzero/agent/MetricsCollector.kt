package com.xzero.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coleta métricas de performance e qualidade do agente em tempo real.
 * Mantém histórico de ações e calcula KPIs.
 */
object MetricsCollector {

    data class ActionEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val actionNumber: Int = 0,
        val action: String = "",
        val success: Boolean = false,
        val responseTime: Long = 0,
        val confidence: Double = 0.0,
        val result: String = ""
    )

    data class SessionMetrics(
        val sessionId: String = "",
        val startTime: Long = System.currentTimeMillis(),
        val totalActions: Int = 0,
        val successfulActions: Int = 0,
        val failedActions: Int = 0,
        val errorCount: Int = 0,
        val avgResponseTime: Double = 0.0,
        val avgConfidence: Double = 0.0,
        val routesUsed: Int = 0,
        val graphQueriesCount: Int = 0,
        val correctionAttempts: Int = 0,
        val successRate: Double = 0.0
    )

    @Volatile private var currentSessionId = ""
    private val actionHistory = mutableListOf<ActionEvent>()
    private var sessionStartTime = 0L
    private var totalActions = 0
    private var successfulActions = 0
    private var failedActions = 0
    private var errorCount = 0
    private var routesUsed = 0
    private var graphQueriesCount = 0
    private var correctionAttempts = 0
    private var totalResponseTime = 0L
    private var totalConfidence = 0.0
    private val errorList = mutableListOf<ErrorDetector.DetectedError>()

    fun startSession(sessionId: String) {
        currentSessionId = sessionId
        sessionStartTime = System.currentTimeMillis()
        actionHistory.clear()
        errorList.clear()
        totalActions = 0
        successfulActions = 0
        failedActions = 0
        errorCount = 0
        routesUsed = 0
        graphQueriesCount = 0
        correctionAttempts = 0
        totalResponseTime = 0L
        totalConfidence = 0.0

        Log.i("MetricsCollector", "📊 Sessão iniciada: $sessionId")
    }

    /**
     * Registra uma ação executada.
     */
    fun recordAction(
        action: String,
        success: Boolean,
        responseTime: Long,
        confidence: Double,
        result: String = ""
    ) {
        totalActions++
        totalResponseTime += responseTime
        totalConfidence += confidence

        if (success) {
            successfulActions++
        } else {
            failedActions++
        }

        val event = ActionEvent(
            actionNumber = totalActions,
            action = action,
            success = success,
            responseTime = responseTime,
            confidence = confidence,
            result = result
        )

        actionHistory.add(event)

        Log.i("MetricsCollector", "✅ Action $totalActions: $action ($responseTime ms, conf: $confidence)")

        // Enviar para WebSocket
        RealtimeMonitoringAPI.broadcastUpdate(JSONObject().apply {
            put("event", "action_recorded")
            put("data", event.toJson())
        })
    }

    /**
     * Registra uso de rota conhecida.
     */
    fun recordRouteUsed() {
        routesUsed++
    }

    /**
     * Registra consulta ao grafo.
     */
    fun recordGraphQuery() {
        graphQueriesCount++
    }

    /**
     * Registra tentativa de correção.
     */
    fun recordCorrectionAttempt() {
        correctionAttempts++
    }

    /**
     * Registra erro detectado.
     */
    fun recordError(error: ErrorDetector.DetectedError) {
        errorCount++
        errorList.add(error)

        Log.w("MetricsCollector", "⚠️ Erro #$errorCount: ${error.type} (severity: ${error.severity})")

        // Enviar para WebSocket
        RealtimeMonitoringAPI.broadcastUpdate(JSONObject().apply {
            put("event", "error_recorded")
            put("data", error.toJson())
        })
    }

    /**
     * Retorna métricas atuais.
     */
    fun getMetrics(): SessionMetrics {
        val avgResponseTime = if (totalActions > 0) totalResponseTime.toDouble() / totalActions else 0.0
        val avgConfidence = if (totalActions > 0) totalConfidence / totalActions else 0.0
        val successRate = if (totalActions > 0) successfulActions.toDouble() / totalActions else 0.0

        return SessionMetrics(
            sessionId = currentSessionId,
            startTime = sessionStartTime,
            totalActions = totalActions,
            successfulActions = successfulActions,
            failedActions = failedActions,
            errorCount = errorCount,
            avgResponseTime = avgResponseTime,
            avgConfidence = avgConfidence,
            routesUsed = routesUsed,
            graphQueriesCount = graphQueriesCount,
            correctionAttempts = correctionAttempts,
            successRate = successRate
        )
    }

    /**
     * Retorna timeline de ações.
     */
    fun getTimeline(): List<ActionEvent> {
        return actionHistory.toList()
    }

    /**
     * Retorna análise detalhada.
     */
    fun getAnalysis(): JSONObject {
        val metrics = getMetrics()
        val errorStats = ErrorDetector.getErrorStats()

        return JSONObject().apply {
            put("session_id", metrics.sessionId)
            put("duration_seconds", (System.currentTimeMillis() - metrics.startTime) / 1000)
            put("metrics", JSONObject().apply {
                put("total_actions", metrics.totalActions)
                put("successful_actions", metrics.successfulActions)
                put("failed_actions", metrics.failedActions)
                put("success_rate", String.format("%.2f%%", metrics.successRate * 100))
                put("avg_response_time_ms", String.format("%.0f", metrics.avgResponseTime))
                put("avg_confidence", String.format("%.2f", metrics.avgConfidence))
                put("routes_used", metrics.routesUsed)
                put("graph_queries", metrics.graphQueriesCount)
                put("correction_attempts", metrics.correctionAttempts)
            })
            put("errors", errorStats)
            put("quality_score", calculateQualityScore(metrics))
            put("recommendations", generateRecommendations(metrics))
        }
    }

    /**
     * Calcula score de qualidade geral (0-100).
     */
    private fun calculateQualityScore(metrics: SessionMetrics): Double {
        var score = 100.0

        // Reduz por taxa de sucesso baixa
        score -= (1 - metrics.successRate) * 50

        // Reduz por tempo de resposta alto
        if (metrics.avgResponseTime > 1000) {
            score -= 10
        }

        // Reduz por confiança baixa
        if (metrics.avgConfidence < 0.7) {
            score -= 20
        }

        // Reduz por muitos erros
        score -= minOf(metrics.errorCount * 2, 20).toDouble()

        return maxOf(0.0, score)
    }

    /**
     * Gera recomendações baseadas em métricas.
     */
    private fun generateRecommendations(metrics: SessionMetrics): JSONArray {
        val recommendations = JSONArray()

        if (metrics.successRate < 0.8) {
            recommendations.put("Taxa de sucesso baixa (${String.format("%.0f%%", metrics.successRate * 100)}). Treinar mais no domínio atual.")
        }

        if (metrics.avgConfidence < 0.7) {
            recommendations.put("Confiança baixa. Considere reduzir threshold ou usar mais confirmações do usuário.")
        }

        if (metrics.avgResponseTime > 1000) {
            recommendations.put("Tempo de resposta alto (${metrics.avgResponseTime.toLong()}ms). Otimizar consultas ao grafo.")
        }

        if (metrics.errorCount > metrics.totalActions * 0.2) {
            recommendations.put("Taxa de erro alta. Revisar lógica de detecção de estado.")
        }

        val pattern = ErrorDetector.detectErrorPattern()
        if (pattern != null) {
            recommendations.put(pattern)
        }

        if (recommendations.length() == 0) {
            recommendations.put("✅ Sistema operando normalmente. Ótimo trabalho!")
        }

        return recommendations
    }

    /**
     * Retorna resumo textual.
     */
    fun getSummary(): String {
        val metrics = getMetrics()
        return """
            📊 RESUMO DA SESSÃO
            ├─ Total de ações: ${metrics.totalActions}
            ├─ Taxa de sucesso: ${String.format("%.1f%%", metrics.successRate * 100)}
            ├─ Tempo médio: ${String.format("%.0f", metrics.avgResponseTime)}ms
            ├─ Confiança média: ${String.format("%.2f", metrics.avgConfidence)}
            ├─ Erros detectados: ${metrics.errorCount}
            ├─ Rotas usadas: ${metrics.routesUsed}
            ├─ Correções: ${metrics.correctionAttempts}
            └─ Score de qualidade: ${String.format("%.1f", calculateQualityScore(metrics))}/100
        """.trimIndent()
    }

    /**
     * Termina a sessão.
     */
    fun endSession() {
        val analysis = getAnalysis()
        Log.i("MetricsCollector", "📊 Sessão finalizada:\n${getSummary()}")

        // Enviar para WebSocket
        RealtimeMonitoringAPI.broadcastUpdate(JSONObject().apply {
            put("event", "session_end")
            put("data", analysis)
        })
    }

    private fun ActionEvent.toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("action_number", actionNumber)
            put("action", action)
            put("success", success)
            put("response_time_ms", responseTime)
            put("confidence", confidence)
            put("result", result)
        }
    }

    private fun ErrorDetector.DetectedError.toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("type", type.name)
            put("step", step)
            put("expected", expected)
            put("actual", actual)
            put("reason", reason)
            put("suggestion", suggestion)
            put("severity", severity)
        }
    }
}
