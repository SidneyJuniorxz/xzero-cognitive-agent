package com.xzero.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registra cada passo do raciocínio do agente em tempo real.
 * Envia para dashboard via WebSocket e salva em arquivo JSONL para análise.
 */
object ThinkingLogger {

    data class ThinkingStep(
        val timestamp: Long = System.currentTimeMillis(),
        val stepNumber: Int = 0,
        val phase: String = "", // "parse", "memory_query", "planning", "decision", "execution", "verification"
        val details: String = "",
        val metrics: Map<String, Any?> = emptyMap(),
        val confidence: Double = 0.0,
        val actionTaken: String? = null,
        val result: String? = null
    )

    @Volatile private var currentSession = ""
    @Volatile private var stepCounter = 0
    private val thinkingHistory = mutableListOf<ThinkingStep>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    fun startSession(sessionId: String) {
        currentSession = sessionId
        stepCounter = 0
        thinkingHistory.clear()
        Log.i("ThinkingLogger", "📓 Sessão iniciada: $sessionId")
    }

    fun logPhase(
        phase: String,
        details: String,
        metrics: Map<String, Any?> = emptyMap(),
        confidence: Double = 0.0,
        actionTaken: String? = null,
        result: String? = null
    ) {
        stepCounter++
        val step = ThinkingStep(
            timestamp = System.currentTimeMillis(),
            stepNumber = stepCounter,
            phase = phase,
            details = details,
            metrics = metrics,
            confidence = confidence,
            actionTaken = actionTaken,
            result = result
        )

        thinkingHistory.add(step)

        // Log imediato
        Log.i("ThinkingLogger", "🧠 Step $stepCounter [$phase]: $details (conf: $confidence)")

        // Enviar para WebSocket
        scope.launch {
            sendToWebSocket("thinking_step", step.toJson())
        }

        // Salvar em arquivo
        scope.launch {
            saveToFile(step)
        }
    }

    fun logError(
        errorType: String,
        step: Int,
        expected: String,
        actual: String,
        reason: String,
        severity: Int,
        suggestion: String = ""
    ) {
        Log.w("ThinkingLogger", "⚠️ ERROR [$errorType] Step $step: esperado=$expected, atual=$actual, razão=$reason")

        val errorJson = JSONObject()
            .put("timestamp", dateFormat.format(Date()))
            .put("type", errorType)
            .put("step", step)
            .put("expected", expected)
            .put("actual", actual)
            .put("reason", reason)
            .put("severity", severity)
            // Faltava a sugestão aqui: o dashboard renderizava "💡 undefined"
            .put("suggestion", suggestion)

        scope.launch {
            sendToWebSocket("error_detected", errorJson)
        }
    }

    fun getCurrentThinking(): List<ThinkingStep> {
        return thinkingHistory.toList()
    }

    fun getThinkingJson(): JSONArray {
        val array = JSONArray()
        thinkingHistory.forEach { step ->
            array.put(step.toJson())
        }
        return array
    }

    fun endSession(success: Boolean = true) {
        Log.i("ThinkingLogger", "📓 Sessão finalizada: $currentSession (sucesso: $success)")
        scope.launch {
            sendToWebSocket("session_end", JSONObject().apply {
                put("session_id", currentSession)
                put("success", success)
                put("total_steps", stepCounter)
                put("timestamp", dateFormat.format(Date()))
            })
        }
    }

    private fun saveToFile(step: ThinkingStep) {
        try {
            val file = File("/data/data/com.xzero.agent/files/thinking_logs.jsonl")
            file.appendText(step.toJson().toString() + "\n")
        } catch (e: Exception) {
            Log.e("ThinkingLogger", "Erro ao salvar em arquivo: ${e.message}")
        }
    }

    private fun sendToWebSocket(event: String, data: JSONObject) {
        try {
            val message = JSONObject()
                .put("event", event)
                .put("data", data)
                .put("timestamp", dateFormat.format(Date()))

            // Será implementado após RealtimeMonitoringAPI estar pronto
            RealtimeMonitoringAPI.broadcastUpdate(message)
        } catch (e: Exception) {
            Log.e("ThinkingLogger", "Erro ao enviar para WebSocket: ${e.message}")
        }
    }

    private fun ThinkingStep.toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", dateFormat.format(Date(timestamp)))
            put("step", stepNumber)
            put("phase", phase)
            put("details", details)
            put("confidence", confidence)
            put("metrics", JSONObject(metrics))
            actionTaken?.let { put("action", it) }
            result?.let { put("result", it) }
        }
    }
}
