package com.xzero.agent

import android.util.Log
import org.json.JSONObject

/**
 * Detecta e categoriza erros do agente em tempo real.
 * Fornece sugestões de correção para cada erro detectado.
 */
object ErrorDetector {

    enum class ErrorType {
        WRONG_ACTION,           // Ação executada era errada
        WRONG_ELEMENT,          // Tocou/digitou elemento incorreto
        STAGNATION,             // Não progrediu (repetiu ação)
        TIMEOUT,                // Demorou muito
        VERIFICATION_FAILED,    // Verificação pós-ação falhou
        UNKNOWN_STATE,          // UI em estado inesperado
        MEMORY_MISMATCH,        // Realidade diferente da memória
        CONFIDENCE_LOW          // Confiança muito baixa (deveria confirmar)
    }

    data class DetectedError(
        val type: ErrorType,
        val step: Int,
        val expected: String,
        val actual: String,
        val reason: String,
        val suggestion: String,
        val severity: Int,  // 1-5 (1=leve, 5=crítico)
        val timestamp: Long = System.currentTimeMillis()
    )

    private val errorHistory = mutableListOf<DetectedError>()

    /**
     * Detecta erro comparando estado esperado vs real.
     */
    fun detectError(
        expected: String,
        actual: String,
        context: Map<String, Any?>,
        confidence: Double = 0.0
    ): DetectedError? {
        // Se não mudou = problema
        if (expected == actual) {
            return null // OK, sem erro
        }

        val errorType = categorizeError(expected, actual, context, confidence)
        if (errorType == null) {
            return null
        }

        val reason = identifyReason(expected, actual, context)
        val suggestion = generateSuggestion(errorType, reason, context)
        val severity = calculateSeverity(errorType, context)

        val error = DetectedError(
            type = errorType,
            step = context["step"] as? Int ?: 0,
            expected = expected,
            actual = actual,
            reason = reason,
            suggestion = suggestion,
            severity = severity
        )

        errorHistory.add(error)

        Log.w("ErrorDetector", "⚠️ ERRO [$errorType] (severity: $severity): $reason -> Sugestão: $suggestion")

        // Notificar logger e métricas
        ThinkingLogger.logError(
            errorType = errorType.name,
            step = error.step,
            expected = expected,
            actual = actual,
            reason = reason,
            severity = severity,
            suggestion = suggestion
        )

        MetricsCollector.recordError(error)

        return error
    }

    /**
     * Categoriza o tipo de erro baseado em padrões.
     */
    private fun categorizeError(
        expected: String,
        actual: String,
        context: Map<String, Any?>,
        confidence: Double
    ): ErrorType? {
        // Confiança muito baixa = deveria ter confirmado
        if (confidence < 0.5) {
            return ErrorType.CONFIDENCE_LOW
        }

        // Se elemento tocado não existe = wrong element
        if (actual.contains("not found") || actual.contains("null")) {
            return ErrorType.WRONG_ELEMENT
        }

        // Se timeout
        if (context["timeout"] == true) {
            return ErrorType.TIMEOUT
        }

        // Se ficou no mesmo estado = stagnation
        if (context["previous_state"] == actual) {
            return ErrorType.STAGNATION
        }

        // Se verificação falhou
        if (context["verification_failed"] == true) {
            return ErrorType.VERIFICATION_FAILED
        }

        // Se estado totalmente inesperado
        if (!actual.contains(expected) && context["app"] == context["expected_app"]) {
            return ErrorType.UNKNOWN_STATE
        }

        // Ação errada se o resultado não faz sentido
        if (expected.contains("send") && actual.contains("draft")) {
            return ErrorType.WRONG_ACTION
        }

        return ErrorType.MEMORY_MISMATCH
    }

    /**
     * Identifica a razão do erro.
     */
    private fun identifyReason(
        expected: String,
        actual: String,
        context: Map<String, Any?>
    ): String {
        return when {
            context["id_changed"] == true -> "ID de elemento mudou após alteração de estado"
            context["app_crashed"] == true -> "App fechou inesperadamente"
            context["network_error"] == true -> "Erro de rede"
            context["permission_denied"] == true -> "Permissão negada"
            context["timeout"] == true -> "Timeout na operação"
            actual.isEmpty() -> "Elemento não encontrado"
            actual.contains("error") -> "Erro reportado pelo sistema"
            else -> "Discrepância entre esperado ($expected) e atual ($actual)"
        }
    }

    /**
     * Gera sugestão de correção.
     */
    private fun generateSuggestion(
        errorType: ErrorType,
        reason: String,
        context: Map<String, Any?>
    ): String {
        return when (errorType) {
            ErrorType.WRONG_ELEMENT ->
                "Releia a tela antes de tocar. Use 'read_screen' para atualizar IDs."

            ErrorType.STAGNATION ->
                "Ação não teve efeito. Tente: usar scroll, esperar, ou releia a tela."

            ErrorType.TIMEOUT ->
                "Operação demorou muito. Tente aumentar o delay ou verificar conectividade."

            ErrorType.VERIFICATION_FAILED ->
                "Ação não teve o efeito esperado. Pode ser delay. Tente wait(1000) depois."

            ErrorType.UNKNOWN_STATE ->
                "UI está em estado inesperado. Use 'back' para voltar ao estado conhecido."

            ErrorType.CONFIDENCE_LOW ->
                "Confiança baixa. Deveria ter pedido confirmação ao usuário antes de agir."

            ErrorType.WRONG_ACTION ->
                "Ação está incorreta para este contexto. Revise o plano."

            ErrorType.MEMORY_MISMATCH ->
                "Memória do agente não bate com realidade. Atualize o grafo de conhecimento."
        }
    }

    /**
     * Calcula severidade do erro (1-5).
     */
    private fun calculateSeverity(
        errorType: ErrorType,
        context: Map<String, Any?>
    ): Int {
        val baseScore = when (errorType) {
            ErrorType.CONFIDENCE_LOW -> 1
            ErrorType.MEMORY_MISMATCH -> 2
            ErrorType.WRONG_ELEMENT -> 3
            ErrorType.STAGNATION -> 3
            ErrorType.VERIFICATION_FAILED -> 3
            ErrorType.TIMEOUT -> 2
            ErrorType.UNKNOWN_STATE -> 4
            ErrorType.WRONG_ACTION -> 5
        }

        // Aumenta severidade se ocorreu múltiplas vezes
        val repeatCount = errorHistory.count { it.type == errorType }
        val repeatBonus = minOf(repeatCount / 3, 1)

        return minOf(baseScore + repeatBonus, 5)
    }

    /**
     * Retorna histórico de erros.
     */
    fun getErrorHistory(): List<DetectedError> {
        return errorHistory.toList()
    }

    /**
     * Retorna estatísticas de erros.
     */
    fun getErrorStats(): JSONObject {
        val byType = errorHistory.groupingBy { it.type }.eachCount()
        val bySeverity = errorHistory.groupingBy { it.severity }.eachCount()
        val avgSeverity = if (errorHistory.isNotEmpty()) {
            errorHistory.map { it.severity }.average()
        } else {
            0.0
        }

        return JSONObject().apply {
            put("total_errors", errorHistory.size)
            put("avg_severity", avgSeverity)
            put("by_type", JSONObject(byType.mapKeys { it.key.name }))
            put("by_severity", JSONObject(bySeverity.mapKeys { it.key.toString() }))
            put("most_common", byType.maxByOrNull { it.value }?.key?.name ?: "none")
        }
    }

    /**
     * Limpa histórico.
     */
    fun clearHistory() {
        errorHistory.clear()
    }

    /**
     * Detecta se há padrão de erro.
     */
    fun detectErrorPattern(): String? {
        if (errorHistory.size < 3) return null

        val recent = errorHistory.takeLast(5)
        val types = recent.map { it.type }

        // Se mesmo erro 3+ vezes = padrão
        val repeating = types.groupingBy { it }.eachCount().filter { it.value >= 3 }
        if (repeating.isNotEmpty()) {
            return "Padrão detectado: ${repeating.keys.first()} ocorreu ${repeating.values.first()}x"
        }

        return null
    }
}
