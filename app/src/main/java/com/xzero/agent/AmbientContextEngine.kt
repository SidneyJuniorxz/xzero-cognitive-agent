package com.xzero.agent

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * AmbientContextEngine (XZERO v3.1 — ChatGPT Live Fluid Voice + Sensor Fusion + /btw):
 * 1. Transcrição Contínua Fluida estilo ChatGPT Live (Zero Consumo de Tokens em Background):
 *    - Avalia todas as hipóteses do SpeechRecognizer (top-5) para nunca perder "XZERO", "ECS ZERO", "X0".
 *    - Guarda os trechos falados recentemente em memória curta deslizante (base 45s, prolongada até 180s).
 *    - Janela de Follow-Up Live (25s após resposta do XZERO): permite continuar a conversa naturalmente
 *      sem repetir a wake word, validado 100% por script local (zero tokens se for conversa paralela).
 * 2. Fusão com Sensores Físicos em Background (ProactiveWatcher):
 *    - Quando o usuário pega o celular da mesa (Pick-Up), inclina na postura de voz ou agita (Shake),
 *      o script local recebe bônus de atenção sensorial, aumentando a fluidez de ativação para falar com o X0.
 * 3. Sistema `/btw` em Paralelo (ativável por Shake, falando "XZERO"/"X0" novamente enquanto pensa, ou direto):
 *    - Permite injetar contexto em paralelo ao pensamento atual, recalcular rota, trocar objetivo ou cancelar.
 */
class AmbientContextEngine(private val graph: KnowledgeGraphMemory) {

    data class TranscriptChunk(
        val text: String,
        val timestampMs: Long,
        val chunkScore: Double,
        val reason: String
    )

    enum class TriggerDecisionType {
        KEEP_LISTENING,          // Continua transcrevendo na memória curta (ou esquece se zerar) — 0 tokens LLM
        ACTIVATE_BY_WAKE_NAME,   // Chamou "XZERO" / "ECS ZERO" / "X0" -> ativa LLM com contexto curto recuperado
        ACTIVATE_BY_INTENT_SCORE,// Pontuação de intenção (fala + sensores + follow-up) atingiu limiar -> ativa LLM
        PARALLEL_BTW_INJECTION   // Comando /btw (via shake, "x0" enquanto pensa, ou fala em paralelo)
    }

    data class ScreenContextSnapshot(
        val packageName: String,
        val summary: String,
        val visibleTokens: Set<String>,
        val capturedAtMs: Long = System.currentTimeMillis()
    ) {
        companion object {
            private val STOPWORDS = setOf(
                "para", "como", "mais", "aqui", "isso", "esse", "essa", "está", "esta",
                "tudo", "nada", "pode", "fazer", "com", "sem", "uma", "uns", "das", "dos",
                "que", "por", "não", "nao", "sim", "app", "foco", "tela", "view", "button"
            )

            fun fromRawDump(rawDump: String?, capturedAtMs: Long = System.currentTimeMillis()): ScreenContextSnapshot? {
                if (rawDump.isNullOrBlank()) return null
                val lines = rawDump.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                val pkg = lines.firstOrNull { it.startsWith("App em foco:") }
                    ?.removePrefix("App em foco:")
                    ?.trim()
                    .orEmpty()
                val usefulLines = lines.drop(1)
                    .filter { !it.startsWith("---") }
                    .take(14)
                val summary = usefulLines.joinToString(" | ").take(580)
                val tokens = buildSet {
                    val textToTokenize = "$pkg ${usefulLines.joinToString(" ")}"
                    Regex("[\\p{L}0-9]{4,}").findAll(textToTokenize.lowercase(Locale("pt", "BR"))).forEach { m ->
                        val tok = m.value
                        if (tok !in STOPWORDS) add(tok)
                    }
                }
                if (pkg.isBlank() && summary.isBlank()) return null
                return ScreenContextSnapshot(pkg, summary, tokens, capturedAtMs)
            }
        }
    }

    data class EvaluationResult(
        val decision: TriggerDecisionType,
        val effectiveTaskPrompt: String,
        val rollingScore: Double,
        val memoryWindowSec: Int,
        val bufferedChunksCount: Int,
        val matchedTrigger: String,
        val screenMatched: Boolean = false
    )

    enum class BtwDirectiveType {
        APPEND_PARALLEL_CONTEXT, // Inclui contexto/detalhe em paralelo sem interromper os acertos atuais
        RECALCULATE_ROUTE,       // Muda o caminho/estratégia mantendo o objetivo e passos já feitos
        SWITCH_MAIN_TASK,        // Substitui por uma nova tarefa principal
        PAUSE_AND_WAIT,          // Pausa o fluxo primário e aguarda o usuário terminar de falar/mostrar a tela
        CANCEL_CURRENT_TASK      // Cancela imediatamente a tarefa em andamento
    }

    data class BtwDirective(
        val type: BtwDirectiveType,
        val cleanText: String,
        val rawText: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val rollingBuffer = mutableListOf<TranscriptChunk>()
    private val parallelBtwQueue = ConcurrentLinkedQueue<BtwDirective>()
    private val idleBtwBuffer = mutableListOf<String>()

    @Volatile var rollingIntentScore: Double = 0.0
        private set
    @Volatile var lastUpdateMs: Long = System.currentTimeMillis()
        private set
    @Volatile var currentTtlMs: Long = BASE_TTL_MS
        private set
    @Volatile var autoIntentThreshold: Double = 4.8
    @Volatile var userMicPriorityYielded: Boolean = false

    // Janela de conversa contínua estilo ChatGPT Live (45s após resposta do agente ou interação)
    @Volatile var followUpWindowUntilMs: Long = 0L
        private set

    // Janela de /btw armado por Shake ou por falar "XZERO"/"X0" enquanto pensa
    @Volatile var btwArmedUntilMs: Long = 0L
        private set

    // Bônus de intenção vindo de rotinas de sensores em background (pegar o celular, postura de voz)
    @Volatile var sensorAttentionUntilMs: Long = 0L
        private set
    @Volatile var sensorAttentionBoost: Double = 0.0
        private set

    companion object {
        private const val BASE_TTL_MS = 120_000L          // 120s (2 min) de memória curta padrão para acumular mais contexto
        private const val MAX_PROLONGED_TTL_MS = 360_000L // até 6 min quando o usuário está construindo raciocínio/intenção
        private const val DECAY_PER_SEC = 0.04            // decaimento suave por segundo de pausa/silêncio
        private const val MAX_BUFFER_CHUNKS = 24          // guarda até 24 trechos para processar maior volume de dados antes de inferir intenção

        // Todas as variações fonéticas de XZERO / ECS ZERO / X0 em pt-BR e EN (incluindo transcrições reais do Google STT nos logs como "gisele", "fizeram", "achei zero")
        val WAKE_NAMES = listOf(
            "xzero", "x zero", "ecs zero", "ecszero", "ecs 0", "e c s zero",
            "x0", "x 0", "xx zero", "xx 0", "xis zero", "xis 0", "xisero", "ex zero", "ex 0", "ex-zero",
            "xizero", "xi zero", "xi 0", "x-zero", "equis zero", "ekis zero", "iks zero",
            "é que se zero", "eco zero", "ezero", "e zero", "é zero", "o zero", "cê zero", "se zero",
            "achei zero", "achei 0", "fizeram", "gisele", "giselle", "oi gisele", "ei gisele", "fala gisele",
            "ok xzero", "ei xzero", "oi xzero", "olá xzero", "ola xzero", "fala xzero", "bora xzero",
            "ok ecs zero", "ei ecs zero", "ok x0", "ei x0", "oi x0", "olá x0", "ola x0", "fala x0",
            "olá x", "ola x", "oi x", "ei x", "ok x", "fala x", "bora x",
            "ei zero", "oi zero", "olá zero", "ola zero", "ok zero", "fala zero", "bora zero", "aí zero", "ô zero", "alô zero", "zero",
            "ok ada", "ei ada", "oi ada", "olá ada", "ola ada", "fala ada", "bora ada", "aí ada", "ô ada", "alô ada", "ada", "hadda", "a da",
            "ok xzero", "ei xzero", "oi xzero", "olá xzero", "fala xzero", "bora xzero", "ô xzero",
            "xzero", "xzeroe", "replóide", "reployd", "ok xzeroe", "ei xzeroe", "oi xzeroe",
            "xenon", "ei xenon", "oi xenon", "olá xenon", "ola xenon", "zenon", "ok agente", "ei agente", "oi agente"
        )

        // Regex fonético para capturar variações aproximadas do STT em português ("x zero", "olá x", "ada", "xenon", "xzero", etc.)
        private val WAKE_PHONETIC_REGEX = Regex(
            "(?:\\b(?:ok\\s+|ei\\s+|oi\\s+|ol[aá]\\s+|fala\\s+|bora\\s+|ô\\s+|alô\\s+)?(?:(?:x|xx|xis|ex|ecs|ec|equis|ekis|xi|iks|cê|se|é|e|o|achei)\\s*[-.]?\\s*(?:zero|0|z|ero|sero)|ada|hadda|xenon|zenon|xzero[ei]?|reployd)\\b)|(?:\\b(?:ok|ei|oi|ol[aá]|fala|bora)\\s+(?:x|xis)\\b)|(?:^(?:zero|0|fizeram|gisele|giselle|ada|xenon|zenon|xzero[ei]?)\\b)",
            RegexOption.IGNORE_CASE
        )

        val BTW_PREFIXES = listOf(
            "/btw", "\\btw", "btw", "por sinal", "aliás", "alias",
            "detalhe:", "adendo:", "aproveitando,", "em paralelo", "aproveitando"
        )
    }

    /**
     * Ativa a janela de conversa fluida estilo ChatGPT Live após o XZERO responder.
     * Durante essa janela (ex: 45s), o script local permite follow-ups diretos sem exigir wake word,
     * mas ainda filtra conversas externas sem gastar tokens!
     */
    fun openFollowUpWindow(durationMs: Long = 22_000L) {
        followUpWindowUntilMs = System.currentTimeMillis() + durationMs
    }

    fun closeFollowUpWindow() {
        followUpWindowUntilMs = 0L
    }

    fun isInFollowUpWindow(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs < followUpWindowUntilMs
    }

    /**
     * Arma explicitamente o modo `/btw` (ex: quando o usuário sacode o celular ou diz "X0" enquanto pensa).
     */
    fun armBtwMode(durationMs: Long = 22_000L) {
        btwArmedUntilMs = System.currentTimeMillis() + durationMs
    }

    fun isBtwArmed(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs < btwArmedUntilMs
    }

    /**
     * Chamado pelas rotinas de sensores em background (ProactiveWatcher) quando o usuário
     * pega o celular da mesa, levanta o aparelho para falar ou interage fisicamente.
     */
    fun notifySensorPickupOrAttention(boost: Double = 2.0, durationMs: Long = 18_000L) {
        val now = System.currentTimeMillis()
        sensorAttentionUntilMs = now + durationMs
        sensorAttentionBoost = boost.coerceIn(0.5, 3.5)
    }

    fun getCurrentSensorBoost(nowMs: Long = System.currentTimeMillis()): Double {
        var boost = if (nowMs < sensorAttentionUntilMs) sensorAttentionBoost else 0.0
        if (ProactiveWatcher.inConversationalPosture && !ProactiveWatcher.proximityNear) {
            boost += 1.0
        }
        return boost.coerceAtMost(3.5)
    }

    /**
     * Seleciona a melhor hipótese entre as alternativas retornadas pelo SpeechRecognizer do Android.
     * Se a hipótese #2 contiver "x zero" / "x0" e a #1 tiver erro de fonética, prioriza a que tem wake word!
     */
    fun selectBestTranscriptCandidate(candidates: List<String>, isAgentWorking: Boolean): String {
        val valid = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return ""
        if (valid.size == 1) return valid[0]

        // 1. Prioriza qualquer hipótese que contenha a wake word ("xzero", "x0", "ecs zero", etc.)
        for (c in valid) {
            if (detectWakeName(c) != null) return c
        }
        // 2. Prioriza qualquer hipótese com prefixo /btw explícito
        for (c in valid) {
            val l = c.lowercase(Locale("pt", "BR"))
            if (BTW_PREFIXES.any { l.startsWith(it) }) return c
        }
        // 3. Senão, avalia qual hipótese tem maior pontuação de intenção no script local
        var best = valid[0]
        var bestScore = scoreChunkIntent(valid[0]).first
        for (i in 1 until valid.size) {
            val sc = scoreChunkIntent(valid[i]).first
            if (sc > bestScore + 0.8) {
                bestScore = sc
                best = valid[i]
            }
        }
        return best
    }

    /**
     * Aplica decaimento temporal na pontuação de intenção e limpa (zera e esquece)
     * os fragmentos antigos caso o tempo de vida (TTL) expire ou a pontuação chegue a zero.
     */
    @Synchronized
    fun applyDecayAndPrune(nowMs: Long = System.currentTimeMillis()) {
        val elapsedSec = ((nowMs - lastUpdateMs).coerceAtLeast(0L)) / 1000.0
        if (elapsedSec > 1.0 && rollingIntentScore > 0.0) {
            rollingIntentScore = max(0.0, rollingIntentScore - (elapsedSec * DECAY_PER_SEC))
        }
        lastUpdateMs = nowMs

        // Prolonga a janela da memória curta proporcionalmente à pontuação de intenção atual
        currentTtlMs = if (rollingIntentScore >= 2.0) {
            (BASE_TTL_MS + (rollingIntentScore * 16_000L).toLong()).coerceAtMost(MAX_PROLONGED_TTL_MS)
        } else {
            BASE_TTL_MS
        }

        val cutoff = nowMs - currentTtlMs
        val removed = rollingBuffer.removeAll { it.timestampMs < cutoff }

        // Se a memória curta esvaziou por tempo ou a pontuação zerou após longo silêncio (> 40s), zera tudo
        if (rollingBuffer.isEmpty() || (rollingIntentScore <= 0.05 && elapsedSec > 40.0)) {
            if (rollingIntentScore <= 0.05 && elapsedSec > 45.0) {
                rollingBuffer.clear()
                rollingIntentScore = 0.0
                currentTtlMs = BASE_TTL_MS
            }
        } else if (removed && rollingBuffer.isEmpty()) {
            rollingIntentScore = 0.0
            currentTtlMs = BASE_TTL_MS
        }
    }

    /**
     * Detecta se a fala contém o nome do agente (XZERO, ECS ZERO, X0, etc.)
     * e separa o que veio antes (contexto imediato na mesma frase) e o que veio depois (comando).
     */
    fun detectWakeName(raw: String): Pair<String, String>? {
        val norm = raw.lowercase(Locale("pt", "BR"))
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        for (wake in WAKE_NAMES) {
            val idx = norm.indexOf(wake)
            if (idx >= 0) {
                // Evita falso positivo dentro de outra palavra (ex: "ex0" isolado vs meio de palavra)
                val validStart = idx == 0 || !norm[idx - 1].isLetterOrDigit()
                val endIdx = idx + wake.length
                val validEnd = endIdx >= norm.length || !norm[endIdx].isLetterOrDigit()
                if (validStart && validEnd) {
                    val before = raw.take(idx).trim().trimEnd(',', '.', ':', ';', '!', '?')
                    val afterStart = endIdx.coerceAtMost(raw.length)
                    val after = raw.substring(afterStart).trim().trimStart(',', '.', ':', ';', '!', '?', ' ')
                    val contextPlusCmd = buildString {
                        if (before.isNotBlank()) append(before).append(' ')
                        if (after.isNotBlank()) append(after)
                    }.trim()
                    return Pair(wake, contextPlusCmd)
                }
            }
        }

        // Fallback via Regex fonético para transcrições parciais do Google STT
        val m = WAKE_PHONETIC_REGEX.find(raw)
        if (m != null) {
            val matchedWake = m.value.trim()
            val before = raw.take(m.range.first).trim().trimEnd(',', '.', ':', ';', '!', '?')
            val after = raw.substring((m.range.last + 1).coerceAtMost(raw.length)).trim().trimStart(',', '.', ':', ';', '!', '?', ' ')
            val contextPlusCmd = buildString {
                if (before.isNotBlank()) append(before).append(' ')
                if (after.isNotBlank()) append(after)
            }.trim()
            return Pair(matchedWake, contextPlusCmd)
        }
        return null
    }

    /**
     * Detecta se o texto é uma injeção de contexto paralelo estilo `/btw` do Claude:
     * - Pode ser ativado por prefixo (`/btw`, `aliás`, `por sinal`);
     * - Pode ser ativado falando `"XZERO"` ou `"X0"` novamente enquanto o agente pensa;
     * - Pode ser ativado após sacudir o celular (Shake `/btw`);
     * - Ou falando qualquer instrução complementar enquanto o agente está executando (`isAgentCurrentlyWorking`).
     */
    fun detectBtwTrigger(raw: String, isAgentCurrentlyWorking: Boolean): BtwDirective? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val now = System.currentTimeMillis()
        val btwArmed = isBtwArmed(now)
        val lower = trimmed.lowercase(Locale("pt", "BR"))

        val explicitPrefix = BTW_PREFIXES.firstOrNull {
            lower.startsWith("$it ") || lower == it || lower.startsWith("$it:")
        }

        // Verifica se falou "XZERO" / "X0" durante a execução ou com /btw armado
        val wakeMatch = detectWakeName(trimmed)

        if (explicitPrefix != null || isAgentCurrentlyWorking || btwArmed) {
            var clean = when {
                explicitPrefix != null -> trimmed.substring(explicitPrefix.length).trim().trimStart(':', ',', '-', ' ')
                wakeMatch != null -> wakeMatch.second.trim()
                else -> trimmed
            }
            // Se dentro do clean ainda houver um prefixo /btw após remover a wake word (ex: "x0 /btw inclui...")
            val cleanLowerCheck = clean.lowercase(Locale("pt", "BR"))
            val innerPrefix = BTW_PREFIXES.firstOrNull {
                cleanLowerCheck.startsWith("$it ") || cleanLowerCheck == it || cleanLowerCheck.startsWith("$it:")
            }
            if (innerPrefix != null) {
                clean = clean.substring(innerPrefix.length).trim().trimStart(':', ',', '-', ' ')
            }

            // Se o usuário falou apenas "XZERO", "X0" ou "/btw" enquanto o agente pensa, arma a escuta /btw!
            if (clean.isEmpty()) {
                armBtwMode(22_000L)
                return null
            }

            val cLower = clean.lowercase(Locale("pt", "BR"))
            val directiveType = when {
                cLower == "cancela" || cLower == "cancelar" || cLower == "pare" || cLower == "para tudo" ||
                    cLower.contains("cancela a tarefa") || cLower.contains("para o que tá fazendo") -> {
                    BtwDirectiveType.CANCEL_CURRENT_TASK
                }
                cLower == "espera" || cLower == "aguarda" || cLower == "calma" || cLower == "pausa" ||
                    cLower.startsWith("espera ") || cLower.startsWith("aguarda ") || cLower.startsWith("calma ") ||
                    cLower.startsWith("deixa eu ") || cLower.contains("espera um pouco") || cLower.contains("aguarda um pouco") -> {
                    BtwDirectiveType.PAUSE_AND_WAIT
                }
                cLower.startsWith("esquece isso") || cLower.startsWith("muda de tarefa") ||
                    cLower.startsWith("faz outra coisa") || cLower.startsWith("começa outra:") -> {
                    BtwDirectiveType.SWITCH_MAIN_TASK
                }
                cLower.contains("muda o caminho") || cLower.contains("recalcula") ||
                    cLower.contains("em vez disso") || cLower.contains("tenta por outro") ||
                    cLower.contains("corrige a rota") || cLower.contains("usa antes") -> {
                    BtwDirectiveType.RECALCULATE_ROUTE
                }
                else -> BtwDirectiveType.APPEND_PARALLEL_CONTEXT
            }
            return BtwDirective(directiveType, clean, trimmed)
        }
        return null
    }

    /**
     * Script/Robô Local de Pontuação de Intenção (Zero Tokens LLM):
     * Combina:
     * 1. Frases e verbos de intenção de falar com o X0;
     * 2. Palavras de contexto / trabalho / sensores / ações no celular;
     * 3. Janela de Follow-Up estilo ChatGPT Live (25s pós-fala do XZERO);
     * 4. Leituras dos sensores em background (Pick-up da mesa, Postura de voz na mão);
     * 5. Afinidade aprendida no Grafo Vetorial e Política Q(s,a).
     */
    fun scoreChunkIntent(text: String, nowMs: Long = System.currentTimeMillis()): Pair<Double, String> {
        val lower = text.lowercase(Locale("pt", "BR")).trim()
        if (lower.length < 3) return Pair(0.0, "curto")

        // Se o usuário estiver explicitamente dizendo que conversa com outra pessoa ou encerrando a janela Live, zera e fecha!
        if (lower.contains("não tô falando contigo") || lower.contains("tava conversando com") ||
            lower.contains("falando com outra pessoa") || lower.contains("continuando com a minha conversa") ||
            lower == "agora nada" || lower == "só isso" || lower == "so isso" ||
            lower.contains("desativa modo live") || lower.contains("desativar modo live") ||
            lower == "fica quieto" || lower == "silêncio"
        ) {
            closeFollowUpWindow()
            return Pair(-5.0, "conversa_externa_ou_fim_live")
        }

        var score = 0.0
        val reasons = mutableListOf<String>()

        // 0. Reforço RLHF imediato (quando dentro da janela de follow-up ou logo após tarefa)
        val rlhfPhrases = listOf(
            "ótima resposta", "otima resposta", "excelente resposta", "muito bom",
            "mandou bem", "perfeito", "isso mesmo", "resposta errada", "está errado",
            "não era isso", "corrija isso"
        )
        if (rlhfPhrases.any { lower.contains(it) }) {
            score += 7.5
            reasons.add("rlhf_feedback")
        }

        // 1. Frases conversacionais e check-ins diretos no Modo Live (ex: "tá me ouvindo", "tá na escuta", "que horas são")
        val liveCheckInPhrases = listOf(
            "tá me ouvindo", "ta me ouvindo", "está me ouvindo", "esta me ouvindo",
            "tá na escuta", "ta na escuta", "está na escuta", "na escuta",
            "me ouve", "me escuta", "está me escutando", "tá me escutando", "você me ouve",
            "tá aí", "ta ai", "está aí", "você está aí", "alô", "oi tudo bem", "olá tudo bem",
            "tudo bem com você", "tudo bem", "bom dia", "boa tarde", "boa noite",
            "que horas são", "que horas sao", "que dia é hoje", "como você está",
            "pode me ajudar", "me responde", "responde aí", "teste de voz", "testando"
        )
        for (c in liveCheckInPhrases) {
            if (lower.contains(c) || lower == "oi" || lower == "olá" || lower == "ola" || lower == "teste") {
                score += 4.2
                reasons.add("live_conversacional($c)")
                break
            }
        }

        // 1B. Verbos e expressões que indicam intenção clara de falar com o X0
        val strongIntentPhrases = listOf(
            "eu quero que você", "quero que você", "quero que voce", "você pode", "voce pode",
            "você consegue", "voce consegue", "consegue abrir", "consegue ver", "consegue mandar",
            "consegue pesquisar", "consegue fazer", "pode abrir", "pode fechar", "pode tocar",
            "pode falar", "pode me falar", "pode me dizer", "me conte", "fale sobre", "fala sobre",
            "qual que é", "qual que e", "quanto que tem", "quanto tem de", "o que você sabe",
            "veja se tem", "veja se ", "olha se tem", "olha se ", "volte uma tela", "volta uma tela",
            "volte para", "volta para", "role a tela", "rola a tela", "desce a tela", "sobe a tela",
            "reproduza", "toque no", "clique no", "clica no", "leia as mensagens", "mais mensagens",
            "preciso mandar", "manda um whatsapp", "mande uma mensagem", "envia para", "responde para",
            "cria um alarme", "me acorda às", "coloca despertador", "fique de vigia",
            "vigia o whatsapp", "monitora o", "começa a gravar", "grava essa conversa", "para de gravar",
            "pesquisa pra mim", "pesquise sobre", "qual a bateria", "como estão os sensores",
            "leia a bateria", "status do celular", "abre o app", "abre o ", "abra o ", "abre a ", "abra a ",
            "abrir o ", "abrir a ", "abrir um ", "fechar o ", "fechar a ", "fecha o ", "fecha a ",
            "lembra que", "memoriza isso", "salve esse fato", "salva no grafo", "anota aí",
            "cria uma notificação", "deixe uma notificação", "consulta os fatos", "consulte os fatos",
            "me ajuda com", "me diz ", "me fala ", "me fale ", "verifica pra mim", "checa o ", "olha o ",
            "lista os servidores", "liste os servidores", "conecta no mcp", "chama o mcp",
            "quais são", "como funciona", "me explica", "quanto está", "qual é", "qual a versão", "armazenamento livre",
            "canto direito", "canto esquerdo", "parte direita", "parte esquerda", "para o canto", "pro canto",
            "para o centro", "pro centro", "pro meio", "vai para a direita", "vai para a esquerda",
            "desce do muro", "pula do muro", "movimentar", "mover o avatar", "animação do boneco",
            "anda pra frente", "anda para frente", "vem pra frente", "anda de costas", "anda pra tras", "anda para tras"
        )
        for (p in strongIntentPhrases) {
            if (lower.contains(p)) {
                score += 4.8
                reasons.add("frase_intencao($p)")
                break
            }
        }

        // 2. Palavras-chave que indicam intenção/contexto de trabalho para o X0 (sem palavras casuais como "horas"/"tempo")
        val contextBuildingTokens = listOf(
            "preciso", "lembrar", "orçamento", "reunião", "cliente", "hospital",
            "responder", "mandar", "ligar", "agenda", "android", "versão", "versao",
            "armazenamento", "espaço livre", "arquivos", "sistema", "computador",
            "servidor", "porta", "bateria", "sensor", "sensores", "notificação", "whatsapp",
            "mensagem", "email", "alarme", "gravar", "áudio", "memória", "grafo", "mcp",
            "pesquisar", "calcular", "resumo", "status", "configurar", "ajuda", "celular",
            "tela", "volume", "brilho", "wifi", "bluetooth", "lanterna", "regra", "vigia"
        )
        val matchedTokens = contextBuildingTokens.count { lower.contains(it) }
        if (matchedTokens > 0) {
            val tokenBoost = (matchedTokens * 1.1).coerceAtMost(3.3)
            score += tokenBoost
            reasons.add("palavras_intencao($matchedTokens)")
        }

        // 3. Perguntas diretas ou comandos imperativos no início da frase ("qual...", "como...", "onde...", "abre...")
        val directQuestionPrefixes = listOf(
            "qual ", "quais ", "como ", "onde ", "quando ", "quanto ", "quem ", "por que ", "porque ", "o que ",
            "você consegue ", "voce consegue ", "você pode ", "voce pode ", "consegue ", "pode ",
            "me mostra ", "mostra ", "mostre ", "consulta ", "consulte ", "verifica ", "verifique ",
            "executa ", "execute ", "faça ", "faz ", "abre ", "abra ", "abrir ", "pesquisa ", "pesquise ",
            "lista ", "liste ", "calcula ", "calcule ", "me fala ", "me diz ", "explica "
        )
        if (directQuestionPrefixes.any { lower.startsWith(it) }) {
            score += 3.2
            reasons.add("pergunta_ou_comando_direto")
        }

        // 4. Bônus da Janela de Follow-Up estilo ChatGPT Live (após resposta do XZERO):
        //    Só aplica se já houver indício de pergunta/comando (score > 0), evitando disparar em conversa casual na sala
        if (isInFollowUpWindow(nowMs) && score > 0.0) {
            score += 1.6
            reasons.add("chatgpt_live_followup")
        }

        // 5. Bônus de Rotinas de Sensores em Background (pegou o celular da mesa / postura de voz)
        val sensorBoost = getCurrentSensorBoost(nowMs)
        if (sensorBoost > 0.0 && score > 0.0) {
            score += (sensorBoost * 0.65).coerceAtMost(1.5)
            reasons.add("sensor_boost(+${String.format(Locale.US, "%.1f", sensorBoost * 0.65)})")
        }

        // 6. Pontuação Aprendida do Grafo Vetorial e Rotas Campeãs (SOMENTE se já houver sinal de comando na frase atual!)
        if (score >= 1.5) {
            val learnedBoost = graph.evaluateLearnedIntentAffinity(text)
            if (learnedBoost > 0.45) {
                score += (learnedBoost * 1.5).coerceAtMost(1.4)
                reasons.add("grafo_aprendido(${String.format(Locale.US, "%.2f", learnedBoost)})")
            }
        }

        // 7. Camada semântica (cascata local do VoiceSemantics): reconhece comando mesmo com
        //    palavras fora das listas ("bota a música", "dá uma olhada nas mensagens").
        //    Limitada a +2.6 de propósito: sozinha ela NUNCA cruza o limiar de ativação —
        //    precisa de outro sinal (wake, pergunta direta, tela, sensor) para disparar.
        VoiceSemantics.intent.classify(text, setOf("comando", "conversa"))?.let { m ->
            when (m.label) {
                "comando" -> {
                    val boost = (m.score * 2.6).coerceAtMost(2.6)
                    score += boost
                    reasons.add("semantico_comando[${m.method}](+${String.format(Locale.US, "%.1f", boost)})")
                }
                "conversa" -> {
                    score -= 0.8
                    reasons.add("semantico_conversa[${m.method}](-0.8)")
                }
            }
        }

        // Se não bateu em nada relevante, pequeno decaimento por conversa casual (0 tokens consumidos)
        if (score <= 0.0) {
            return Pair(-0.5, if (reasons.isEmpty()) "conversa_casual" else "conversa_casual+" + reasons.joinToString("+"))
        }

        return Pair(score.coerceAtMost(10.0), reasons.joinToString("+"))
    }

    /**
     * Detecta se a frase atual termina em conectivo aberto ("e", "para", "que", "no", "na", "de", "com"...)
     * ou contém pedido explícito de pausa/tempo para pensar ("espera", "aguarda", "deixa eu pensar", "calma"),
     * indicando que o X0 deve aguardar mais dados antes de tentar inferir a intenção final!
     */
    fun isIncompleteOrHesitationPhrase(text: String): Boolean {
        val norm = text.lowercase(Locale("pt", "BR")).trim().trimEnd('.', ',', ';', ':')
        if (norm.isEmpty()) return false
        val waitKeywords = listOf(
            "espera", "aguarda", "calma", "deixa eu pensar", "deixa eu ver",
            "só um segundo", "so um segundo", "um momento", "peraí", "perai"
        )
        if (waitKeywords.any { norm == it || norm.endsWith(" $it") || norm.startsWith("$it ") }) {
            return true
        }
        val openConnectors = setOf(
            "e", "mas", "ou", "para", "pra", "pro", "que", "de", "do", "da", "dos", "das",
            "no", "na", "nos", "nas", "com", "por", "porque", "então", "entao", "aí", "ai",
            "tipo", "como", "quando", "onde", "se", "sobre", "qual", "quais", "algum", "alguma",
            "tem", "gostaria", "quero", "queria", "preciso", "vamos", "posso", "pode", "consegue",
            "abrir", "procurar", "pesquisar", "mandar", "enviar", "falar", "dizer"
        )
        val lastWord = norm.substringAfterLast(' ')
        return lastWord in openConnectors
    }

    /**
     * Avalia resultados parciais em tempo real (onPartialResults) sem consumir a memória curta,
     * apenas para exibir feedback visual imediato estilo ChatGPT Live quando o usuário fala "X0", "/btw"
     * ou palavras de intenção!
     */
    fun previewPartialTranscript(partials: List<String>, isAgentWorking: Boolean): Pair<Boolean, String> {
        val candidate = selectBestTranscriptCandidate(partials, isAgentWorking)
        if (candidate.isBlank()) return Pair(false, "")
        if (isAgentWorking || isBtwArmed()) {
            return Pair(true, "⏳ Pensando... | 🎙️ Ouvindo /btw: \"$candidate\"")
        }
        val wake = detectWakeName(candidate)
        if (wake != null) {
            return Pair(true, "🎙️ [${wake.first.uppercase()}] Ouvindo: \"$candidate\"")
        }
        val (score, _) = scoreChunkIntent(candidate)
        if (score >= 2.2 || isInFollowUpWindow()) {
            return Pair(true, "🎙️ Acumulando contexto (${String.format(Locale.US, "%.1f", rollingIntentScore + score)}/${String.format(Locale.US, "%.1f", autoIntentThreshold)}): \"$candidate\"")
        }
        return Pair(false, candidate)
    }

    /**
     * Processa qualquer transcrição final recebida do microfone contínuo:
     * - Guarda na memória curta deslizante (120s base, até 360s, acumulando até 24 trechos).
     * - Verifica `/btw` paralelo (por shake, por falar "X0" novamente enquanto pensa, ou direto).
     * - Verifica nome do agente (`XZERO`, `ECS ZERO`, `X0`).
     * - Acumula maior volume de dados antes de disparar `ACTIVATE_BY_INTENT_SCORE`, respeitando pausas e hesitações!
     */
    private fun computeScreenSpeechCorrelation(cleanText: String, screen: ScreenContextSnapshot?): Pair<Double, String> {
        if (screen == null) return Pair(0.0, "")
        val ageMs = System.currentTimeMillis() - screen.capturedAtMs
        if (ageMs > 12_000L) return Pair(0.0, "")

        val lower = cleanText.lowercase(Locale("pt", "BR"))
        val deicticRefs = listOf(
            "isso", "essa tela", "esse vídeo", "esse video", "esse áudio", "esse audio",
            "essa mensagem", "esse contato", "esse botão", "esse botao", "essa foto",
            "esse link", "olha isso", "o que é isso", "o que tá na tela", "o que está na tela",
            "lê isso", "le isso", "responde ele", "responde ela", "responde isso",
            "fecha isso", "clica aí", "clica ai", "aperta aí", "aperta ai",
            "abre esse", "abre essa", "resume isso", "traduz isso", "manda pra ele", "manda pra ela"
        )
        val hasDeictic = deicticRefs.any { lower.contains(it) }

        val spokenTokens = Regex("[\\p{L}0-9]{4,}").findAll(lower).map { it.value }.toList()
        val matchedTokens = spokenTokens.filter { it in screen.visibleTokens }
        val pkgShort = screen.packageName.substringAfterLast('.').lowercase(Locale("pt", "BR"))
        val mentionsApp = pkgShort.length >= 3 && lower.contains(pkgShort)

        var bonus = 0.0
        val tags = mutableListOf<String>()
        if (hasDeictic) {
            bonus += 2.4
            tags += "deictic_screen"
        }
        if (matchedTokens.isNotEmpty()) {
            bonus += (matchedTokens.size * 1.4).coerceAtMost(3.2)
            tags += "screen_tok:${matchedTokens.take(2).joinToString(",")}"
        }
        if (mentionsApp) {
            bonus += 1.5
            tags += "screen_app:$pkgShort"
        }
        return Pair(bonus.coerceAtMost(4.5), tags.joinToString("+"))
    }

    private fun appendScreenContextIfRelevant(prompt: String, screen: ScreenContextSnapshot?, correlationTag: String): String {
        if (prompt.isBlank() || screen == null || screen.summary.isBlank()) return prompt
        val appLabel = screen.packageName.ifBlank { "desconhecido" }
        val relInfo = if (correlationTag.isNotBlank()) " [correlação=$correlationTag]" else ""
        return "$prompt\n[Contexto visual da tela ao acordar$relInfo — App: $appLabel | Elementos: ${screen.summary}]"
    }

    @Synchronized
    fun onTranscriptSegment(
        rawText: String,
        isAgentWorking: Boolean,
        screenContext: ScreenContextSnapshot? = null
    ): EvaluationResult {
        val clean = rawText.trim()
        val now = System.currentTimeMillis()
        applyDecayAndPrune(now)

        if (clean.isEmpty()) {
            return EvaluationResult(
                decision = TriggerDecisionType.KEEP_LISTENING,
                effectiveTaskPrompt = "",
                rollingScore = rollingIntentScore,
                memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                bufferedChunksCount = rollingBuffer.size,
                matchedTrigger = "empty"
            )
        }

        // 1. Verifica se o agente está pensando OU se `/btw` foi armado (por shake, prefixo /btw ou dizendo "x0" enquanto pensa)
        val btwArmedBefore = isBtwArmed(now)
        val btw = detectBtwTrigger(clean, isAgentWorking)
        if (btw != null && (isAgentWorking || btwArmedBefore || clean.lowercase(Locale("pt", "BR")).let { l -> BTW_PREFIXES.any { l.startsWith(it) } })) {
            btwArmedUntilMs = 0L
            enqueueBtwDirective(btw, isAgentWorking)
            return EvaluationResult(
                decision = TriggerDecisionType.PARALLEL_BTW_INJECTION,
                effectiveTaskPrompt = btw.cleanText,
                rollingScore = rollingIntentScore,
                memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                bufferedChunksCount = rollingBuffer.size,
                matchedTrigger = "btw:${btw.type.name}"
            )
        }
        // Caso tenha falado apenas "x0" ou "xzero" enquanto o agente pensa (armou o /btw para a próxima frase)
        if (isAgentWorking && isBtwArmed(now) && !btwArmedBefore) {
            return EvaluationResult(
                decision = TriggerDecisionType.KEEP_LISTENING,
                effectiveTaskPrompt = "",
                rollingScore = rollingIntentScore,
                memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                bufferedChunksCount = rollingBuffer.size,
                matchedTrigger = "btw_armed_by_wake"
            )
        }

        // 2. Verifica se falou o nome do agente (XZERO, ECS ZERO, X0, etc.)
        val wakeMatch = detectWakeName(clean)
        if (wakeMatch != null) {
            val (wakeWordUsed, immediateCmd) = wakeMatch
            // Se falou "X0..." mas terminou em conectivo aberto ou pediu "espera", acumula no buffer e aguarda a continuação!
            if (immediateCmd.isNotBlank() && isIncompleteOrHesitationPhrase(immediateCmd)) {
                rollingBuffer.add(TranscriptChunk(immediateCmd, now, 4.5, "wake_partial:$wakeWordUsed"))
                rollingIntentScore = (rollingIntentScore + 4.5).coerceIn(0.0, 14.0)
                openFollowUpWindow(35_000L)
                return EvaluationResult(
                    decision = TriggerDecisionType.KEEP_LISTENING,
                    effectiveTaskPrompt = "",
                    rollingScore = rollingIntentScore,
                    memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                    bufferedChunksCount = rollingBuffer.size,
                    matchedTrigger = "wake_waiting_completion:$wakeWordUsed"
                )
            }
            val basePrompt = buildPromptWithRollingContext(immediateCmd)
            val (_, screenTag) = computeScreenSpeechCorrelation(immediateCmd, screenContext)
            val compiledPrompt = appendScreenContextIfRelevant(basePrompt, screenContext, screenTag)
            // Consome a memória curta para esta ativação
            rollingBuffer.clear()
            rollingIntentScore = 0.0
            currentTtlMs = BASE_TTL_MS
            return EvaluationResult(
                decision = TriggerDecisionType.ACTIVATE_BY_WAKE_NAME,
                effectiveTaskPrompt = compiledPrompt,
                rollingScore = 10.0,
                memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                bufferedChunksCount = 0,
                matchedTrigger = "wake:$wakeWordUsed",
                screenMatched = screenTag.isNotBlank()
            )
        }

        // 3. Pontua pelo Script/Robô de Intenção + Sensores + Correlação com a Tela e armazena na memória curta deslizante
        val (baseDelta, baseReason) = scoreChunkIntent(clean, now)
        val (screenBonus, screenTag) = if (baseDelta >= -1.0) computeScreenSpeechCorrelation(clean, screenContext) else Pair(0.0, "")
        val isScreenInteractiveApp = screenContext != null &&
            !screenContext.packageName.contains("systemui", ignoreCase = true)
        val screenActiveBoost = if (isScreenInteractiveApp && clean.split(Regex("\\s+")).size >= 2) 3.0 else 0.0
        val delta = baseDelta.coerceAtLeast(0.0) + screenBonus + screenActiveBoost
        val reason = if (screenTag.isNotBlank()) "$baseReason+$screenTag" else baseReason
        if (delta < -2.0) {
            // Usuário deixou claro que é conversa externa -> zera e esquece imediatamente
            rollingBuffer.clear()
            rollingIntentScore = 0.0
            currentTtlMs = BASE_TTL_MS
            return EvaluationResult(
                decision = TriggerDecisionType.KEEP_LISTENING,
                effectiveTaskPrompt = "",
                rollingScore = 0.0,
                memoryWindowSec = (BASE_TTL_MS / 1000L).toInt(),
                bufferedChunksCount = 0,
                matchedTrigger = "cleared:$reason"
            )
        }

        rollingBuffer.add(TranscriptChunk(clean, now, delta.coerceAtLeast(0.0), reason))
        while (rollingBuffer.size > MAX_BUFFER_CHUNKS) {
            rollingBuffer.removeAt(0)
        }

        rollingIntentScore = (rollingIntentScore + delta).coerceIn(0.0, 14.0)
        lastUpdateMs = now
        applyDecayAndPrune(now)

        // Se a frase atual parece incompleta ou pede pausa ("espera", "deixa eu pensar", termina em "e"/"para"),
        // continua escutando e acumulando mais dados na memória curta!
        if (isIncompleteOrHesitationPhrase(clean)) {
            return EvaluationResult(
                decision = TriggerDecisionType.KEEP_LISTENING,
                effectiveTaskPrompt = "",
                rollingScore = rollingIntentScore,
                memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                bufferedChunksCount = rollingBuffer.size,
                matchedTrigger = "aguardando_conclusao_pausa"
            )
        }

        // Exige maior volume de dados acumulados (palavras totais na memória curta >= 5 ou check-in/RLHF explícito)
        // antes de tentar inferir intenção automática sem wake word!
        val totalBufferedWords = rollingBuffer.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
        val isDirectCheckInOrRlhf = reason.contains("live_conversacional") || reason.contains("rlhf_feedback")
        val hasStrongImperative = reason.contains("intenção_forte") || reason.contains("pergunta_direta")
        val hasEnoughDataVolume = isDirectCheckInOrRlhf ||
            isScreenInteractiveApp ||
            isInFollowUpWindow(now) ||
            (hasStrongImperative && totalBufferedWords >= 2) ||
            totalBufferedWords >= 4

        val effectiveThreshold = when {
            isInFollowUpWindow(now) || isScreenInteractiveApp -> 2.2
            hasStrongImperative || getCurrentSensorBoost(now) >= 1.0 -> autoIntentThreshold.coerceIn(2.8, 4.2)
            else -> max(5.2, autoIntentThreshold + 1.0)
        }
        if (hasEnoughDataVolume && rollingIntentScore >= effectiveThreshold) {
            val basePrompt = buildPromptWithRollingContext(clean, triggeredByScore = true)
            val compiledPrompt = if (screenTag.isNotBlank() || isInFollowUpWindow(now)) {
                appendScreenContextIfRelevant(basePrompt, screenContext, screenTag)
            } else {
                basePrompt
            }
            val finalScore = rollingIntentScore
            rollingBuffer.clear()
            rollingIntentScore = 0.0
            currentTtlMs = BASE_TTL_MS
            return EvaluationResult(
                decision = TriggerDecisionType.ACTIVATE_BY_INTENT_SCORE,
                effectiveTaskPrompt = compiledPrompt,
                rollingScore = finalScore,
                memoryWindowSec = (currentTtlMs / 1000L).toInt(),
                bufferedChunksCount = 0,
                matchedTrigger = "intent_score:${String.format(Locale.US, "%.1f", finalScore)}($reason,words=$totalBufferedWords)",
                screenMatched = screenTag.isNotBlank()
            )
        }

        return EvaluationResult(
            decision = TriggerDecisionType.KEEP_LISTENING,
            effectiveTaskPrompt = "",
            rollingScore = rollingIntentScore,
            memoryWindowSec = (currentTtlMs / 1000L).toInt(),
            bufferedChunksCount = rollingBuffer.size,
            matchedTrigger = reason
        )
    }

    /**
     * Monta o prompt final recuperando os trechos guardados na memória curta deslizante (até 12 trechos)
     * + qualquer contexto `/btw` que tenha sido pré-acumulado!
     */
    @Synchronized
    fun buildPromptWithRollingContext(currentCommand: String, triggeredByScore: Boolean = false): String {
        val now = System.currentTimeMillis()
        applyDecayAndPrune(now)

        val previousChunks = rollingBuffer
            .filter { it.text != currentCommand && it.chunkScore >= 0.0 }
            .takeLast(12)

        val btwContext = if (idleBtwBuffer.isNotEmpty()) {
            val joined = idleBtwBuffer.joinToString(" | ")
            idleBtwBuffer.clear()
            joined
        } else ""

        val cmd = currentCommand.ifBlank {
            rollingBuffer.lastOrNull()?.text ?: ""
        }

        if (previousChunks.isEmpty() && btwContext.isEmpty() && !triggeredByScore) {
            return cmd
        }

        return buildString {
            append(cmd)
            if (previousChunks.isNotEmpty()) {
                append("\n[CONTEXTO CURTO DE VOZ ACUMULADO NA MEMÓRIA (${previousChunks.size} trechos): ")
                append(previousChunks.joinToString(" -> ") { "\"${it.text}\"" })
                append("]")
            }
            if (btwContext.isNotEmpty()) {
                append("\n[CONTEXTO PARALELO /BTW INCLUÍDO PELO USUÁRIO: ")
                append(btwContext)
                append("]")
            }
        }
    }

    @Synchronized
    fun enqueueBtwDirective(directive: BtwDirective, isAgentWorking: Boolean) {
        if (isAgentWorking) {
            parallelBtwQueue.add(directive)
        } else {
            idleBtwBuffer.add(directive.cleanText)
            while (idleBtwBuffer.size > 6) idleBtwBuffer.removeAt(0)
        }
    }

    fun drainParallelBtwDirectives(): List<BtwDirective> {
        val list = mutableListOf<BtwDirective>()
        while (true) {
            val item = parallelBtwQueue.poll() ?: break
            list.add(item)
        }
        return list
    }

    @Synchronized
    fun consumeIdleBtwContextIfAny(): String {
        if (idleBtwBuffer.isEmpty()) return ""
        val joined = idleBtwBuffer.joinToString(" | ")
        idleBtwBuffer.clear()
        return joined
    }

    @Synchronized
    fun clearShortTermMemory() {
        rollingBuffer.clear()
        idleBtwBuffer.clear()
        parallelBtwQueue.clear()
        rollingIntentScore = 0.0
        currentTtlMs = BASE_TTL_MS
        btwArmedUntilMs = 0L
    }

    @Synchronized
    fun getStatusJson(): JSONObject {
        val now = System.currentTimeMillis()
        applyDecayAndPrune(now)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val chunksArr = JSONArray()
        for (c in rollingBuffer) {
            chunksArr.put(
                JSONObject()
                    .put("text", c.text)
                    .put("score", c.chunkScore)
                    .put("reason", c.reason)
                    .put("time", sdf.format(Date(c.timestampMs)))
            )
        }
        return JSONObject()
            .put("rolling_intent_score", rollingIntentScore)
            .put("auto_intent_threshold", autoIntentThreshold)
            .put("memory_window_sec", currentTtlMs / 1000L)
            .put("buffered_chunks_count", rollingBuffer.size)
            .put("user_mic_priority_yielded", userMicPriorityYielded)
            .put("follow_up_live_active", isInFollowUpWindow(now))
            .put("btw_armed", isBtwArmed(now))
            .put("sensor_boost", getCurrentSensorBoost(now))
            .put("idle_btw_buffer", JSONArray(idleBtwBuffer))
            .put("chunks", chunksArr)
    }
}
