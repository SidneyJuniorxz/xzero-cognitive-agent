package com.xzero.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TrajectoryLogger: grava trajetórias completas (pensamentos, planos, ações, telas, feedbacks e resultados)
 * no estilo Antigravity / Claude Code / Codex (formato JSONL / JSON estruturado).
 *
 * O agente pode ler esses registros a qualquer momento para:
 * - Recuperar histórico e contexto de sessões anteriores.
 * - Aprender com falhas e refinamentos passados.
 * - Fornecer auto-análise e relatórios de fluxo.
 */
class TrajectoryLogger(private val ctx: Context) {

    private val baseDir: File
        get() = File(ctx.filesDir, "trajectories").apply { if (!exists()) mkdirs() }

    private val memoryFile: File
        get() = File(ctx.filesDir, "agent_memory.json")

    private var currentSessionId: String = ""
    private var currentTask: String = ""
    private var sessionFile: File? = null

    fun startSession(task: String): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        currentSessionId = "sess_" + sdf.format(Date()) + "_" + (1000..9999).random()
        currentTask = task
        sessionFile = File(baseDir, "$currentSessionId.jsonl")
        logEvent("session_start", JSONObject().put("task", task).put("started_at", System.currentTimeMillis()))
        return currentSessionId
    }

    fun logStep(
        step: Int,
        provider: String,
        plan: String,
        thought: String,
        action: String,
        args: JSONObject,
        result: String,
        screenSummary: String
    ) {
        val stepData = JSONObject()
            .put("step", step)
            .put("provider", provider)
            .put("plan", plan)
            .put("thought", thought)
            .put("action", action)
            .put("args", args)
            .put("result", result)
            .put("screen_summary", screenSummary.take(400))
            .put("timestamp", System.currentTimeMillis())
        logEvent("agent_step", stepData)
    }

    fun logApproval(action: String, args: JSONObject, approved: Boolean, userResponse: String) {
        val data = JSONObject()
            .put("action", action)
            .put("args", args)
            .put("approved", approved)
            .put("user_response", userResponse)
            .put("timestamp", System.currentTimeMillis())
        logEvent("approval_event", data)
    }

    /** Id da sessão em andamento — usado pelo [TaskCheckpoint] para amarrar o ponto de retomada. */
    fun currentSessionId(): String = currentSessionId

    fun endSession(
        outcome: String,
        answer: String,
        agentDeclaredDone: Boolean = outcome == "success",
        goalVerified: Boolean = outcome == "success",
        verificationReason: String = ""
    ) {
        logEvent("session_end", JSONObject()
            .put("outcome", outcome)
            .put("answer", answer)
            .put("agent_declared_done", agentDeclaredDone)
            .put("goal_verified", goalVerified)
            .put("verification_reason", verificationReason)
            .put("ended_at", System.currentTimeMillis()))
        // Encerra (ou preserva) o ponto de retomada.
        // Desfecho que de fato terminou -> checkpoint morre.
        // Desfecho que DESISTIU no meio (limite de passos, travou, sem confirmação) ->
        // checkpoint sobrevive, para o usuário poder dizer "continua" e não recomeçar do zero.
        val desistiu = outcome in setOf(
            "max_steps_reached", "stuck_no_progress", "unconfirmed_timeout",
            "invalid_json_loop", "stagnation_no_reply",
        )
        if (desistiu) {
            android.util.Log.i("TrajectoryLogger", "↩️ Checkpoint preservado ($outcome) — dá para retomar")
        } else {
            TaskCheckpoint.markCompleted(outcome)
        }

        // Atualiza memória de longo prazo com lições aprendidas
        recordMemorySnapshot(outcome, answer, agentDeclaredDone, goalVerified, verificationReason)
    }

    private fun logEvent(type: String, payload: JSONObject) {
        try {
            val entry = JSONObject()
                .put("type", type)
                .put("session_id", currentSessionId)
                .put("data", payload)
            sessionFile?.appendText(entry.toString() + "\n")
        } catch (e: Exception) {
            android.util.Log.e("AgentePhone", "Erro gravando log de trajetória: ${e.message}")
        }
    }

    /** Lê os aprendizados e resumos das últimas 5 sessões para injetar no cérebro do agente. */
    fun getRecentLearnings(): String {
        return try {
            if (!memoryFile.exists()) return "Nenhum aprendizado prévio gravado."
            val arr = JSONArray(memoryFile.readText())
            val sb = StringBuilder()
            val start = (arr.length() - 5).coerceAtLeast(0)
            for (i in start until arr.length()) {
                val item = arr.getJSONObject(i)
                val t = item.optString("task").ifBlank { "(interação rápida)" }
                val r = item.optString("verification_reason").ifBlank { item.optString("lesson", "concluído") }
                sb.append("• [Sessão ").append(item.optString("session_id")).append("] ")
                    .append("Tarefa: \"").append(t.take(70)).append("\" -> ")
                    .append("Resultado: ").append(item.optString("outcome")).append(" | ")
                    .append("Verificação: ").append(r).append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "Sem memórias lidas."
        }
    }

    private fun recordMemorySnapshot(
        outcome: String,
        answer: String,
        agentDeclaredDone: Boolean = false,
        goalVerified: Boolean = false,
        verificationReason: String = ""
    ) {
        try {
            val arr = if (memoryFile.exists()) JSONArray(memoryFile.readText()) else JSONArray()
            val item = JSONObject()
                .put("session_id", currentSessionId)
                .put("task", currentTask.take(120))
                .put("outcome", outcome)
                .put("agent_declared_done", agentDeclaredDone)
                .put("goal_verified", goalVerified)
                .put("verification_reason", verificationReason)
                .put("answer", answer.take(160))
                .put("timestamp", System.currentTimeMillis())
            arr.put(item)
            // Mantém no máximo 50 memórias históricas
            val trimmed = JSONArray()
            val start = (arr.length() - 50).coerceAtLeast(0)
            for (i in start until arr.length()) trimmed.put(arr.get(i))
            memoryFile.writeText(trimmed.toString(2))
        } catch (e: Exception) {
            android.util.Log.e("AgentePhone", "Erro gravando memória: ${e.message}")
        }
    }

    /** Lista os arquivos de sessão disponíveis com detalhes. */
    fun listSessions(limit: Int = 10): String {
        val files = baseDir.listFiles { f -> f.extension == "jsonl" }?.sortedByDescending { it.lastModified() } ?: return "Nenhuma sessão gravada."
        return files.take(limit).joinToString("\n") { f ->
            "- ${f.nameWithoutExtension} (${f.length()} bytes, alterado em ${SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(f.lastModified()))})"
        }
    }

    /** Lê o conteúdo de uma sessão específica pelo ID. */
    fun readSession(sessionId: String): String {
        val f = File(baseDir, if (sessionId.endsWith(".jsonl")) sessionId else "$sessionId.jsonl")
        if (!f.exists()) return "Sessão $sessionId não encontrada."
        return f.readLines().takeLast(30).joinToString("\n")
    }
}
