package com.xzero.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ponto de retomada de uma tarefa em andamento.
 *
 * O [TrajectoryLogger] grava o que ACONTECEU (auditoria). Este arquivo guarda o que FALTA
 * fazer: se o app for morto pelo sistema, a bateria acabar ou o usuário sair no meio, a ADA
 * consegue voltar de onde parou em vez de recomeçar do zero.
 *
 * Mede contra dado real: nas 265 sessões, 32 (12%) passaram de 8 passos e 3 bateram no
 * limite de 25 — são exatamente essas que doem ao reiniciar.
 *
 * Segurança na retomada: `preExecutionConfirmed` NUNCA é restaurado como verdadeiro. Uma
 * autorização falada vale para o momento em que foi dada, não para uma retomada futura —
 * senão uma tarefa de apagar arquivo ou mandar mensagem poderia executar sozinha depois
 * de um reinício que o usuário nem presenciou.
 */
object TaskCheckpoint {

    private const val TAG = "TaskCheckpoint"
    private const val FILE = "task_checkpoint.json"
    /** Checkpoint mais velho que isso não vale retomada automática — o contexto da tela mudou. */
    private const val MAX_IDADE_MS = 30 * 60_000L

    data class State(
        val taskId: String,
        val goal: String,
        val step: Int,
        val currentApp: String,
        val lastAction: String,
        val lastResult: String,
        val executedActions: List<String>,
        val failedActions: List<String>,
        val notes: List<String>,
        val startedAtMs: Long,
        val updatedAtMs: Long,
        val completed: Boolean,
        val nextHypothesis: String,
    ) {
        val idadeMs: Long get() = System.currentTimeMillis() - updatedAtMs
        val retomavel: Boolean get() = !completed && idadeMs <= MAX_IDADE_MS && step > 0
    }

    @Volatile private var ctxRef: Context? = null
    @Volatile var saves: Int = 0; private set
    @Volatile var resumes: Int = 0; private set

    fun init(ctx: Context) {
        if (ctxRef == null) ctxRef = ctx.applicationContext
    }

    private fun file(): File? = ctxRef?.let { File(it.filesDir, FILE) }

    /** Grava o estado do passo atual. Chamado a cada iteração do loop (escrita barata). */
    fun save(
        taskId: String,
        goal: String,
        step: Int,
        currentApp: String,
        lastAction: String,
        lastResult: String,
        executedActions: List<String>,
        failedActions: Collection<String>,
        notes: List<String>,
        startedAtMs: Long,
        nextHypothesis: String = "",
    ) {
        val f = file() ?: return
        runCatching {
            val o = JSONObject()
                .put("task_id", taskId)
                .put("goal", goal.take(600))
                .put("step", step)
                .put("current_app", currentApp)
                .put("last_action", lastAction)
                .put("last_result", lastResult.take(400))
                .put("executed_actions", JSONArray(executedActions))
                .put("failed_actions", JSONArray(failedActions.toList()))
                .put("notes", JSONArray(notes.map { it.take(240) }))
                .put("started_at", startedAtMs)
                .put("updated_at", System.currentTimeMillis())
                .put("completed", false)
                .put("next_hypothesis", nextHypothesis.take(400))
            f.writeText(o.toString())
            saves++
        }.onFailure { Log.w(TAG, "falha ao salvar: ${it.message}") }
    }

    /** Marca a tarefa como encerrada — deixa de ser órfã. */
    fun markCompleted(outcome: String) {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.put("completed", true).put("outcome", outcome).put("updated_at", System.currentTimeMillis())
            f.writeText(o.toString())
        }
    }

    fun load(): State? {
        val f = file() ?: return null
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            State(
                taskId = o.optString("task_id"),
                goal = o.optString("goal"),
                step = o.optInt("step"),
                currentApp = o.optString("current_app"),
                lastAction = o.optString("last_action"),
                lastResult = o.optString("last_result"),
                executedActions = o.optJSONArray("executed_actions").toStringList(),
                failedActions = o.optJSONArray("failed_actions").toStringList(),
                notes = o.optJSONArray("notes").toStringList(),
                startedAtMs = o.optLong("started_at"),
                updatedAtMs = o.optLong("updated_at"),
                completed = o.optBoolean("completed", false),
                nextHypothesis = o.optString("next_hypothesis"),
            )
        }.getOrNull()
    }

    /** Checkpoint de tarefa que ficou pelo caminho e ainda vale a pena retomar. */
    fun orphan(): State? = load()?.takeIf { it.retomavel }

    fun clear() {
        runCatching { file()?.delete() }
    }

    fun markResumed() {
        resumes++
    }

    /** Bloco de contexto injetado no prompt quando a tarefa é retomada. */
    fun resumeContext(s: State): String = buildString {
        append("RETOMANDO TAREFA INTERROMPIDA (parou no passo ${s.step}, há ")
        append(s.idadeMs / 1000).append("s):\n")
        append("- Objetivo original: ").append(s.goal).append('\n')
        if (s.executedActions.isNotEmpty()) {
            append("- JÁ EXECUTADO (não repita): ").append(s.executedActions.joinToString(" -> ")).append('\n')
        }
        if (s.failedActions.isNotEmpty()) {
            append("- JÁ FALHOU (evite): ").append(s.failedActions.joinToString(", ")).append('\n')
        }
        append("- Última ação: ").append(s.lastAction)
            .append(" -> ").append(s.lastResult.take(200)).append('\n')
        if (s.nextHypothesis.isNotBlank()) {
            append("- Hipótese seguinte que você mesma registrou: ").append(s.nextHypothesis).append('\n')
        }
        append("A TELA PODE TER MUDADO desde então: releia antes de agir. ")
        append("Qualquer ação sensível precisa de confirmação NOVA — a autorização anterior não vale para esta retomada.")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    fun toJson(): JSONObject {
        val s = load()
        return JSONObject()
            .put("tem_checkpoint", s != null)
            .put("retomavel", s?.retomavel ?: false)
            .put("objetivo", s?.goal ?: "")
            .put("passo", s?.step ?: 0)
            .put("idade_s", (s?.idadeMs ?: 0) / 1000)
            .put("ja_executado", JSONArray(s?.executedActions ?: emptyList<String>()))
            .put("gravacoes", saves)
            .put("retomadas", resumes)
    }
}
