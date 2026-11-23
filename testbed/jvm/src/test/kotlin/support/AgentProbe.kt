package support

import android.content.Context
import com.xzero.agent.Agent
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Acesso de leitura às funções de decisão do [Agent].
 *
 * Elas são `private` no app e o combinado deste branch é NÃO alterar app/ — então o
 * banco de testes chega nelas por reflexão. É deliberadamente a única "ginástica" do
 * testbed: se um dia o app expor essas funções como `internal`, basta trocar o corpo
 * daqui pelas chamadas diretas, sem reescrever nenhuma suíte.
 */
class AgentProbe(ctx: Context, val ui: RecordingUi = RecordingUi()) {

    val agent = Agent(ctx, ui)

    private fun fn(name: String): KFunction<*> =
        Agent::class.declaredMemberFunctions.firstOrNull { it.name == name }
            ?.apply { isAccessible = true }
            ?: error("função '$name' não existe mais em Agent — o testbed precisa ser atualizado")

    private fun call(name: String, vararg args: Any?): Any? {
        val f = fn(name)
        return f.call(agent, *args)
    }

    /** Resultado do GoalVerifier achatado para asserção (a data class é privada). */
    data class Verification(val verified: Boolean, val reason: String)

    fun verifyGoal(
        task: String,
        initialApp: String = "",
        currentApp: String = "",
        executedActions: List<Pair<String, String>> = emptyList(),
        lastResult: String = "(início)",
        currentScreenDump: String = "",
        hasVisualEvidence: Boolean = false,
    ): Verification {
        val raw = call(
            "verifyGoalCompletion",
            task, initialApp, currentApp, executedActions, lastResult, currentScreenDump, hasVisualEvidence,
        )!!
        val props = raw::class.memberProperties.associateBy { it.name }
        fun <T> read(name: String): T {
            @Suppress("UNCHECKED_CAST")
            return props.getValue(name).apply { isAccessible = true }.call(raw) as T
        }
        return Verification(read("verified"), read("reason"))
    }

    /** Classe + método da cascata de confirmação ("palavra_chave", "lexico", "semantico", "ajuste"). */
    fun classifyConfirmation(reply: String): Pair<Agent.ConfirmClass, String> {
        @Suppress("UNCHECKED_CAST")
        return call("classifyConfirmationReply", reply) as Pair<Agent.ConfirmClass, String>
    }

    fun specialTurn(task: String): Agent.SpecialTurnType =
        call("classifySpecialUserTurn", task) as Agent.SpecialTurnType

    fun instantAnswer(task: String): String? = call("instantLocalAnswer", task) as String?

    fun requiresVisualEvidence(task: String): Boolean = call("requiresVisualEvidence", task) as Boolean

    fun requiresDeviceTool(task: String): Boolean = call("requiresDeviceTool", task) as Boolean

    fun stripTaskMetadata(raw: String): String = call("stripTaskMetadata", raw) as String

    /** Nome do modo de intenção local (CONVERSA / SISTEMA_OU_MCP / ACAO_NA_TELA). */
    fun localIntent(task: String, executedActions: List<Pair<String, String>> = emptyList()): String =
        (call("classifyLocalIntent", task, executedActions) as Enum<*>).name

    fun requiresPreExecConfirmation(action: String, args: org.json.JSONObject): Boolean =
        call("requiresPreExecutionVoiceConfirmation", action, args) as Boolean
}
