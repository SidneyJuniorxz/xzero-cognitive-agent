package support

import com.xzero.agent.AgentPhase
import com.xzero.agent.AgentUi

/**
 * [AgentUi] de teste: grava tudo que o agente diria/perguntaria/logaria e responde às
 * perguntas por um roteiro fixo, para que o loop rode sem voz, sem TTS e sem humano.
 */
class RecordingUi(answers: List<String> = emptyList()) : AgentUi {

    val logs = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    val questions = mutableListOf<String>()
    val phases = mutableListOf<Pair<AgentPhase, String>>()
    var completion: Pair<Boolean, String>? = null
        private set

    private val scriptedAnswers = ArrayDeque(answers)

    override fun log(line: String) { logs += line }

    override fun say(text: String) { spoken += text }

    override suspend fun ask(question: String): String {
        questions += question
        return if (scriptedAnswers.isEmpty()) "(sem resposta)" else scriptedAnswers.removeFirst()
    }

    override fun onAgentPhaseChanged(phase: AgentPhase, detail: String) { phases += phase to detail }

    override fun onTaskCompletion(verified: Boolean, detail: String) { completion = verified to detail }

    fun logsContaining(fragment: String): List<String> = logs.filter { it.contains(fragment) }
}
