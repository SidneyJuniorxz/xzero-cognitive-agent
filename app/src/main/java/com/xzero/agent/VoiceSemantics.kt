package com.xzero.agent

import android.content.Context
import org.json.JSONObject

/**
 * Dois classificadores semânticos compartilhados por todo o app:
 *
 *  - [confirm]: aceitar/cancelar/perguntar na confirmação pré-execução ("pode fazer?");
 *  - [intent]:  decidir se uma fala do modo Live é comando para o X0 ou conversa da sala.
 *
 * As sementes abaixo cobrem o jeito brasileiro de dizer sim/não que as listas antigas
 * de palavras-chave não pegavam ("demorô", "fecha", "bote pra funcionar", "toca ficha").
 * Tudo que o usuário confirma na prática vira protótipo novo, então a cobertura cresce sozinha.
 */
object VoiceSemantics {

    val confirm = SemanticMatcher(
        name = "confirmacao",
        lexicalMinScore = 0.74,
        semanticMinScore = 0.64,
        semanticMinMargin = 0.08,
        maxPrototypes = 300,
    )

    val intent = SemanticMatcher(
        name = "intencao",
        lexicalMinScore = 0.78,
        semanticMinScore = 0.70,
        semanticMinMargin = 0.10,
        maxPrototypes = 400,
    )

    @Volatile private var lastSemanticAccept: String? = null

    private val confirmSeeds = mapOf(
        "afirmativo" to listOf(
            "sim", "pode", "pode sim", "pode fazer", "pode fazer isso", "pode mandar", "pode enviar",
            "pode abrir", "pode ir", "pode seguir", "pode continuar", "pode executar",
            "confirmo", "confirma", "confirmado", "autorizo", "autorizado", "aprovado",
            "ok", "okay", "beleza", "tranquilo", "fecha", "fechou", "fechado",
            "demorô", "demorou", "pode crer", "é isso", "é isso aí", "isso mesmo", "exatamente",
            "perfeito", "certo", "correto", "claro", "claro que pode", "com certeza", "óbvio",
            "bora", "vamos", "vamo nessa", "vai nessa", "manda", "manda ver", "manda bala",
            "segue", "segue o jogo", "toca ficha", "bote pra funcionar", "põe pra funcionar",
            "faz isso", "faça", "executa", "libera", "tá liberado", "positivo",
            "tá bom", "tudo bem", "sem problema", "uhum", "aham", "isso", "tá",
        ),
        "negativo" to listOf(
            "não", "não faz", "não faça", "não manda", "não envia", "não precisa", "não quero",
            "cancela", "cancelar", "cancela isso", "aborta", "abortar", "esquece", "esquece isso",
            "deixa", "deixa quieto", "deixa pra lá", "melhor não", "negativo", "para", "pare",
            "para tudo", "nem pensar", "de jeito nenhum", "para isso", "não pode", "nada disso",
        ),
        "pergunta_plano" to listOf(
            "o que você vai fazer", "o que que você vai fazer", "qual foi o pedido",
            "me explica", "me explica melhor", "repete", "repete aí", "qual mensagem",
            "pra quem", "pra quem você vai mandar", "como você vai fazer", "qual ação",
            "o que exatamente", "explica o que você entendeu",
        ),
    )

    private val intentSeeds = mapOf(
        "comando" to listOf(
            "abre o whatsapp", "manda mensagem pra", "liga pro", "qual a previsão do tempo",
            "que horas são", "me mostra as notificações", "coloca um alarme", "toca uma música",
            "aumenta o brilho", "ativa o não perturbe", "lê a última mensagem", "responde pra ele",
            "pesquisa no google", "abre a câmera", "tira uma foto", "me lembra de",
            "cria uma regra", "apaga esse arquivo", "quanto de bateria tem",
            "me explica o que tem na tela", "resume essa conversa", "faz um resumo",
        ),
        "conversa" to listOf(
            "então eu falei pra ele", "cara nem me fala", "pois é", "entendi", "tá louco",
            "depois eu te conto", "aí ele respondeu", "não acredito nisso", "que engraçado",
            "tava conversando com ela", "o jogo foi ontem", "vou almoçar agora",
        ),
    )

    fun init(ctx: Context) {
        confirm.init(ctx, confirmSeeds)
        intent.init(ctx, intentSeeds)
    }

    /** Guarda a última frase aceita SÓ pelo nível semântico, para poder desaprender se der errado. */
    fun rememberSemanticAccept(phrase: String) {
        lastSemanticAccept = phrase
    }

    /**
     * Chamado quando o usuário cancela/interrompe logo depois de um aceite semântico:
     * a frase é rebaixada para não repetir o mesmo erro.
     */
    fun repudiateLastSemanticAccept(reason: String) {
        val phrase = lastSemanticAccept ?: return
        lastSemanticAccept = null
        confirm.learn(phrase, "ambiguo_rejeitado", source = "repudiado:$reason")
        android.util.Log.w("VoiceSemantics", "↩️ Aceite semântico desaprendido: '$phrase' ($reason)")
    }

    fun clearSemanticAccept() {
        lastSemanticAccept = null
    }

    /**
     * Inclui os dois formatos de chave de propósito: `confirmacao/intencao` (telemetria e
     * dashboard) e `confirm/intent` com `embedded/total` (card de configuração da MainActivity,
     * que estava lendo chaves inexistentes e por isso exibia sempre "0/0").
     */
    private fun compactJson(m: SemanticMatcher): JSONObject {
        val full = m.toJson()
        return JSONObject()
            .put("embedded", full.optInt("com_embedding"))
            .put("total", full.optInt("prototipos"))
            .put("learned", full.optInt("prototipos_aprendidos"))
            .put("queue", full.optInt("fila_embedding"))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("confirmacao", confirm.toJson())
        .put("intencao", intent.toJson())
        .put("confirm", compactJson(confirm))
        .put("intent", compactJson(intent))
        .put("rotulos_confirmacao", JSONObject(confirm.labelsSummary() as Map<*, *>))
}
