package com.xzero.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Estados visuais explícitos do X0 / Xenon. */
enum class AgentPhase {
    IDLE_RELAXED,
    ATTENTIVE_LISTENING,
    THINKING,
    RESEARCHING,
    ACTING,
    ASKING_HELP,
    RECOVERING_ERROR,
    LEARNING,
    /** Estado efêmero; só pode ser emitido depois de [GoalVerification.verified]. */
    CONFIRMING_SUCCESS
}

/** Interface com a UI (bolha/activity). */
interface AgentUi {
    fun log(line: String)
    fun say(text: String)
    /** Pergunta ao usuário e espera resposta (texto ou voz). */
    suspend fun ask(question: String): String
    fun onAgentPhaseChanged(phase: AgentPhase, detail: String = "") {}
    /** Não use o texto do agente como prova: sucesso visual só existe após o GoalVerifier. */
    fun onTaskCompletion(verified: Boolean, detail: String = "") {}
}

class Agent(private val ctx: Context, private val ui: AgentUi) {

    private val prefs = Prefs(ctx)
    private val llm = Llm(prefs)
    private val memory = mutableListOf<Msg>() // conversa entre tarefas (curta)

    /**
     * Trunca a última resposta do assistente na memória quando o usuário interrompe o TTS (barge-in),
     * garantindo que o agente saiba exatamente até onde foi ouvido.
     */
    fun truncateLastAssistantMemory(heardPrefix: String) {
        val idx = memory.indexOfLast { it.role == "assistant" }
        if (idx >= 0 && heardPrefix.isNotBlank()) {
            memory[idx] = Msg("assistant", "$heardPrefix… [interrompido pelo usuário]")
            ui.log("✂️ [Barge-In Memória]: truncada para '${heardPrefix.take(60)}…'")
        }
    }

    /** Resume mensagens antigas quando a conversa passa de 14 turnos, mantendo latência baixa. */
    private fun compactMemoryIfNeeded() {
        if (memory.size <= 14) return
        val older = memory.take(memory.size - 8)
        val summary = older.joinToString(" | ") { "${it.role}:${it.text.take(45)}" }.take(420)
        val recent = memory.takeLast(8)
        memory.clear()
        memory.add(Msg("user", "[RESUMO DA CONVERSA ANTERIOR]: $summary"))
        memory.addAll(recent)
    }
    val trajectory = TrajectoryLogger(ctx)
    val graph = KnowledgeGraphMemory(ctx)
    val mcp = McpClientManager(ctx)
    val ambient = AmbientContextEngine(graph)
    val rules = RuleEngine(ctx)

    init {
        ambient.autoIntentThreshold = prefs.autoIntentThreshold
    }

    // Controle de pausa imediata do processo primário quando o usuário fala um /btw ou pede para aguardar
    @Volatile var userSpeakingBtwSinceMs: Long = 0L
    @Volatile var currentTaskContext: String = ""
    @Volatile private var pendingVisualBase64: String? = null

    fun notifyUserStartedSpeakingBtw() {
        userSpeakingBtwSinceMs = System.currentTimeMillis()
    }

    fun notifyUserFinishedSpeakingBtw() {
        userSpeakingBtwSinceMs = 0L
    }

    private suspend fun waitIfUserSpeakingBtw(): Boolean {
        var waitedMs = 0L
        var pausedAny = false
        while (userSpeakingBtwSinceMs > 0L && (System.currentTimeMillis() - userSpeakingBtwSinceMs) < 9500L && waitedMs < 9500L) {
            if (!pausedAny) {
                pausedAny = true
                ui.log("⏸️🎙️ Fluxo primário pausado: aguardando você concluir o /btw para incluir no raciocínio...")
            }
            delay(220L)
            waitedMs += 220L
        }
        userSpeakingBtwSinceMs = 0L
        return pausedAny
    }

    /**
     * Contrato de máquina: protocolo, catálogo de ações e regras de execução.
     *
     * A IDENTIDADE e o ESTILO DE FALA saíram daqui para `files/persona/PERSONALITY.md`
     * e `USER_PROFILE.md` — editáveis em tempo de execução, sem recompilar. O bloco é
     * injetado no topo deste prompt por [PersonaStore.promptBlock].
     */
    private val systemContract = """
A cada passo você recebe:
- REGRAS PERMANENTES E GUARDRAILS DO USUÁRIO (prioridade máxima)
- ELEMENTOS NOVOS NESTE PASSO (diff semântico estilo CUA) + TELA ATUAL (elementos numerados [id])
- GRAFO DE MEMÓRIA & ROTAS CAMPEÃS (pontuadas por eficiência)
- Resultado e Pontuação da última ação.

Responda SOMENTE um JSON: {"plan":"1. Etapa A -> 2. Etapa B","thought":"raciocínio atual","epistemic":"OBSERVED|INFERRED|UNVERIFIABLE","fala":"frase curta opcional (4-10 palavras na 1ª pessoa com verbo no gerúndio + onde + pra quê, ex: 'Entendi, abrindo o iFood pra buscar o lanche')","action":"<nome>","args":{...}}

Ações disponíveis:
- inspect_screen_visual {"focus":"o que observar"} captura um SCREENSHOT REAL (pixels JPEG) da tela atual e envia ao seu modelo multimodal no próximo passo. OBRIGATÓRIO antes de descrever imagens, fotos, cores, contagens de objetos ou elementos gráficos que não estão em texto no XML!
- device_status {}                            retorna bateria %, carregando, temperatura, RAM, rede e todos os sensores (luz, movimento, proximidade, giroscópio, bússola, campo magnético, pressão, passos, gravador)
- get_location {}                             retorna latitude/longitude GPS e endereço completo (rua, bairro, cidade, estado, CEP) sem abrir nenhum app!
- configure_sensors {"mode":"auto|eco|normal|high_alert|smart_save","keep_critical_only":true,"light":true,"accelerometer":true,"proximity":true,"gyroscope":true,"steps":true,"auto_silent":true} gerencia energia e sensores físicos! Para economizar bateria sem perder shake/bolso, use `keep_critical_only:true` (desliga luz, giroscópio e passos, mantendo acelerômetro e proximidade) ou desligue sensores não-críticos individualmente.
- configure_voice_live {"auto_intent_threshold":3.8,"pre_exec_confirm":true,"wake_word":true,"speak":true} ajusta a sensibilidade/pontuação de ativação por voz em modo Live (ex: 3.5 = mais sensível, 4.8 = mais exigente) e confirmação falada sem desligar sensores físicos!
- post_notification {"title":"...","text":"...","silent":true} cria uma notificação na barra de status para deixar mensagem/relatório ao usuário sem precisar falar em voz alta
- ring_phone {"seconds":4}                       toca um alerta sonoro alto na hora (funciona mesmo bloqueado) para ajudar a encontrar o celular
- move_avatar {"direction":"canto direito|esquerda|centro|cima|baixo|muro|notebook"} move e anima o próprio avatar/balão flutuante do Xenon na tela do celular!
- scan_storage_files {"min_mb":50,"type":"video|image|audio|all","limit":20} lista rapidamente os maiores vídeos/arquivos do celular com tamanho exato em bytes/MB/GB e caminho (estilo CUA/ChatGPT, sem precisar rolar a UI!)
- file_hash {"path":"/sdcard/..."}            calcula SHA-256 e metadados exatos (bytes exatos, data ISO) de qualquer arquivo em modo somente leitura!
- read_file_text {"path":"/sdcard/...","max_chars":4000} lê o conteúdo de um arquivo de texto/log/markdown/json/csv do celular!
- add_rule {"title":"...","instruction":"...","mode":"BLOCK|CONFIRM|BEHAVIOR","package":"*","keywords":"apagar,excluir"} cria ou atualiza uma Regra Permanente / Guardrail do usuário
- update_rule {"id":"...","title":"...","instruction":"...","mode":"BLOCK|CONFIRM|BEHAVIOR"} altera uma regra existente
- toggle_rule {"id":"...","enabled":true}     ativa ou desativa uma regra existente
- remove_rule {"id":"..."}                    remove uma regra
- list_rules {}                               lista todas as regras e guardrails configurados pelo usuário
- wait_for_user {"prompt":"Faltou me dizer o que você quer buscar — qual item procuro?"} pausa a execução e pergunta/confirma com o usuário por voz! SEMPRE use quando a fala vier cortada, faltar um parâmetro essencial (contato, horário, item de busca, app, destino) ou o pedido for exploratório ("vamos pesquisar junto", "procure em algum site"). Demonstre sua dúvida de forma natural e humana com suas próprias palavras, explicando exatamente o que não entendeu ou o que falta, sem usar interjeições forçadas como "hãã"!
- type {"id":<id_do_EditText>,"text":"olá"}   digita num campo editável que JÁ está visível na TELA ATUAL (SUBSTITUI o texto inteiro de uma vez — NUNCA clique em 'Excluir'/Backspace do teclado repetidamente para limpar texto!)
- media_control {"command":"play|pause|play_pause|next|previous"}  controla Spotify/mídia diretamente
- capture_camera {"facing":"back|front"}      tira foto silenciosa pela câmera e envia os pixels reais para reconhecer rostos, pessoas, objetos, textos (OCR) e o ambiente! Sempre salve fatos úteis descobertos com remember_fact.
- start_recording {"reason":"conversa importante"} inicia gravação de áudio discreta em segundo plano (.m4a)
- stop_recording {}                           finaliza e salva a gravação de áudio ativa
- add_watch {"source":"unlock|whatsapp|email|claude|codex|any","filter":"termo ou contato","instruction":"o que fazer quando acontecer"} cria vigia proativa DE NOTIFICAÇÕES ou DESBLOQUEIO (`source="unlock"` dispara quando o celular for desbloqueado!) (para regras de comportamento/proibição, use SEMPRE add_rule!)
- list_watches {}                             lista vigias ativas
- remove_watch {"filter":"..."}               remove uma vigia ativa
- web_search {"query":"..."}                  pesquisa na web em tempo real para embasar sugestões e decisões
- mcp_list_servers {}                         lista servidores MCP (Model Context Protocol) conectados e suas tools
- mcp_add_server {"name":"...","url":"https://...","token":"..."} conecta um servidor MCP externo e descobre suas ferramentas
- mcp_remove_server {"name":"..."}            remove um servidor MCP
- mcp_call {"server":"...","tool":"...","arguments":{...}} executa uma ferramenta em qualquer servidor MCP conectado
- remember_fact {"subject":"...","relation":"...","target":"..."} salva conhecimento permanente no Grafo Vetorial
- query_memory {"query":"..."}                consulta o Grafo Vetorial e Rotas Campeãs
- open_app {"name":"WhatsApp|Messages|Google Drive|Files"} abre app pelo nome, sinônimo em EN/PT ou pacote
- tap {"id":3,"label":"texto do botão"}       toca no elemento [id] da TELA ATUAL (sempre inclua o "label" para validação contra IDs obsoletos!)
- long_press {"id":3,"label":"..."}
- type {"id":<id_do_EditText>,"text":"olá"}   digita num campo editável que JÁ está visível na TELA ATUAL
- batch {"steps":[...]}                       executa ações na MESMA tela (NUNCA use batch quando a 1ª ação abre outra tela/conversa; primeiro clique com `tap` para abrir a tela, leia os novos IDs no passo seguinte e então digite!)
- scroll {"direction":"down|up|left|right"}
- back {} | home {} | recents {} | notifications {} | quick_settings {}
- read_notifications {}                       lista notificações ativas (com [id] para responder)
- reply_notification {"id":0,"text":"..."}     responde direto a uma notificação sem abrir o app
- call {"number":"+5511..."}                  faz ligação
- send_whatsapp {"recipient":"Nome ou Número","text":"mensagem","dispatch":true}  abre o WhatsApp e já envia automaticamente
- search_contact {"name":"Lucas"}             busca número na agenda
- answer_call {} | end_call {}
- open_url {"url":"https://..."}
- set_brightness {"level":0-255} | set_dnd {"on":true}
- wifi_panel {} | bluetooth_settings {} | settings {"page":"android.settings.XXX"}
- set_alarm {"hour":7,"minute":30,"label":"..."}
- wait {"ms":1500}
- healthcheck {}                              verifica saúde/serviços do celular e auto-recupera
- read_trajectory {"session_id":"sess_..."} | list_sessions {}
- say {"text":"..."}                          aviso curto intermediário APENAS se ainda for executar outra ferramenta depois
- ask_user {"question":"..."}                 pede aprovação/confirmação de ação crítica ou dúvida ao usuário (máx 1 frase direta)
- done {"answer":"..."}                       ENCERRA A TAREFA e JÁ FALA a resposta final em voz alta ao usuário!
- switch_task {"task":"..."}                  se a resposta do usuário for um pedido novo

Regras de Orquestração, Rigor Epistêmico (3 Níveis de Percepção), Segurança CUA e Finalização Rápida:
1. FINALIZAÇÃO IMEDIATA (PROIBIDO LOOP DE 'say'): A ação `done {"answer":"..."}` JÁ FALA a resposta em voz alta. Para perguntas ("quem é você", "quais as regras", dúvidas), chame `done` DIRETO!
   - Fale com naturalidade e objetividade em pt-BR (parceira direta, inspirada no espírito/ética de Mega Man X — "O Legado do X" — e na precisão tática do J.A.R.V.I.S., idealmente até 20 palavras no `done`).
2. RIGOR EPISTÊMICO E PERCEPÇÃO VISUAL (3 NÍVEIS: OBSERVED, INFERRED, UNVERIFIABLE):
   - A `TELA ATUAL` padrão é apenas a árvore XML de acessibilidade (`dumpScreen`), que NÃO contém os pixels de fotos ou imagens geradas.
   - Se o usuário perguntar sobre elementos visuais de uma imagem/foto na tela ("você consegue ver essa imagem?", "quantas cápsulas tem?", "qual a cor dos robôs?", "o que tem na imagem?"), e você ainda não recebeu `[EVIDÊNCIA VISUAL REAL ANEXADA]` neste turno, defina `"epistemic":"UNVERIFIABLE"` e chame `inspect_screen_visual`! NUNCA invente cores, quantidades ou objetos sem ver os pixels reais.
3. CONFIRMAÇÃO OBRIGATÓRIA EM AÇÕES CRÍTICAS (APAGAR / DELETAR / PAGAR):
   - NUNCA apague nada no WhatsApp (regra de bloqueio absoluto).
   - Antes de apagar/excluir/mover para a lixeira qualquer arquivo, vídeo, foto ou SMS, ou realizar pagamento/Pix, SEMPRE identifique primeiro os itens e chame `ask_user` perguntando ao usuário se confirma. Só clique no botão de excluir/confirmar DEPOIS que o usuário responder "sim" / "pode apagar"!
   - Se o usuário apenas perguntar "você consegue excluir X?", responda que sim explicando como faria, mas NÃO exclua sem ele mandar!
3. REGRAS VS VIGIAS: Quando o usuário disser "nunca faça X", "sempre confirme Y", "crie uma regra para Z" ou "altere a regra", use `add_rule` / `update_rule` / `list_rules` (NÃO use `add_watch` para regras de comportamento!).
4. ÍNDICES FRESCOS PÓS-MODAL (ESTILO CUA): Sempre que clicar em um botão que abre um pop-up/diálogo (ex: Lixeira, Menu, Nova pasta), releia os elementos em `🆕 ELEMENTOS NOVOS NESTE PASSO` na próxima rodada antes de clicar na confirmação. NUNCA adivinhe IDs antigos!
5. ESTILO DE VOZ NATURAL E NARRAÇÃO POR ETAPA: NÃO repita o nome "Usuário" em todas as frases! Vá direto ao ponto com naturalidade.
   - Em tarefas multi-etapa (quando `action` NÃO for `done`, `say` ou `ask_user`), preencha `"fala"` com uma frase curta e natural (4 a 10 palavras) dizendo o verbo da ação, onde e pra quê (ex.: passo 1 `"Entendi, abrindo o iFood pra buscar seu lanche"`, passo 2 `"Tocando na busca pra procurar Burger King"`, passo 3 `"Selecionando o lanche pra pôr na sacola"`).
   - Quando `action` já for `done`, `say` ou `ask_user`, deixe `"fala": ""` para não falar duas vezes.
6. USO INTELIGENTE DOS SERVIDORES MCP DA MÁQUINA (PRIORIDADE SOBRE ROLAR TELA QUANDO DISPONÍVEL):
   - `host-cloud-mcp`: use `mcp_call` com `tool="upload_files"` (`{"files":["DCIM/Camera/PXL_...mp4"],"destination_folder":"Videos_Camera_Backup"}`) para transferir/fazer backup de vídeos ou arquivos grandes do celular para o servidor/Google Drive da Host sem precisar abrir a UI! Use também `host_server_health`, `list_cloud_backups`, `host_quick_note` e **`xzero_publish_result`** (NUNCA abra/digite no app ChatGPT para responder ao ChatGPT — publique resultados e evidências direto via `xzero_publish_result`!).
   - `whatsapp-bridge-mcp`: use `mcp_call` com `whatsapp_status`, `whatsapp_list_chats`, `whatsapp_read_chat`, `whatsapp_search`, `whatsapp_pending` ou `whatsapp_send` para consultar conversas, ler mensagens pendentes, buscar no histórico ou enviar mensagens via API oficial WhatsApp Bridge sem precisar desbloquear ou navegar na tela do WhatsApp!
   - `host-brain-mcp`: use `mcp_call` com `postgres_query` (consultas SQL SELECT no banco `sua_base`), `second_brain_search` (buscar no grafo multi-hop e notas do Second Brain), `second_brain_remember` (enviar e gravar nova memória/fato/relação `subject`/`relation`/`target` no Second Brain sempre que o usuário pedir para salvar lá ou aprender algo importante!) e `machine_mcp_catalog`.
7. SENSIBILIDADE DE VOZ VS SENSORES FÍSICOS: Se o usuário pedir para ajustar a pontuação/sensibilidade de ativação por voz ("colocar uma pontuação menor para ativar"), use SEMPRE `configure_voice_live {"auto_intent_threshold": 3.5}` e NUNCA desligue os sensores físicos com `configure_sensors(mode="eco")`!
""".trimIndent()

    enum class SpecialTurnType {
        NONE,
        POSITIVE_REINFORCEMENT,
        CORRECTIVE_FEEDBACK,
        ABORT_IGNORE
    }

    /**
     * Identifica se a fala do usuário é:
     * - Um Reforço Positivo de Aprendizado (ex: "ótima resposta", "muito bom", "mandou bem", "perfeito")
     * - Uma Correção de Aprendizado (ex: "resposta errada", "corrige isso", "deveria ter feito X")
     * - Um Cancelamento / Aviso de que estava falando com outra pessoa (ex: "não tô falando contigo", "tava conversando no WhatsApp")
     */
    private fun classifySpecialUserTurn(task: String): SpecialTurnType {
        val t = task.lowercase(java.util.Locale("pt", "BR")).trim()

        // 1. Cancelamento / conversa paralela (ex: gravando áudio no WhatsApp para terceiros)
        if (t.contains("não tô falando contigo") ||
            t.contains("nao to falando contigo") ||
            t.contains("não estou falando com você") ||
            t.contains("não falei contigo") ||
            t.contains("não falei nada") ||
            t.contains("tava conversando com") ||
            t.contains("estava conversando com") ||
            t.contains("fica quieto") ||
            t.contains("pode parar") ||
            t == "cancela" || t == "cancelar" || t == "pare" || t == "silêncio"
        ) {
            return SpecialTurnType.ABORT_IGNORE
        }

        // 2. Reforço Positivo (RLHF)
        val positivePhrases = listOf(
            "ótima resposta", "otima resposta", "boa resposta", "excelente resposta",
            "muito bom", "muito boa", "mandou bem", "mandou muito bem",
            "perfeito", "isso mesmo", "exatamente isso", "parabéns", "parabens",
            "gostei da resposta", "acertou", "nota 10", "ótimo trabalho", "otimo trabalho"
        )
        if (positivePhrases.any { t.contains(it) } && t.length <= 95) {
            return SpecialTurnType.POSITIVE_REINFORCEMENT
        }

        // 3. Correção / Reforço Negativo (RLHF)
        val correctivePhrases = listOf(
            "resposta errada", "está errado", "esta errado", "fez errado",
            "deveria ter feito", "não era isso", "nao era isso",
            "corrige isso", "corrija a rota", "correção:", "correcao:"
        )
        if (correctivePhrases.any { t.contains(it) }) {
            return SpecialTurnType.CORRECTIVE_FEEDBACK
        }

        return SpecialTurnType.NONE
    }

    /**
     * Prompt do sistema montado a cada passo: persona editável + contrato de máquina.
     * Se os arquivos estiverem vazios ou ilegíveis, cai num cabeçalho mínimo para a ADA
     * nunca ficar sem identidade.
     */
    private val system: String
        get() {
            val persona = runCatching { PersonaStore.promptBlock() }.getOrDefault("")
            val head = if (persona.isNotBlank()) persona else
                "Você é a ADA — Assistente Digital Autônomo do usuário. Fale natural, " +
                    "direto e curto em pt-BR (8 a 18 palavras no `done`)."
            return head + "\n" + systemContract
        }

    /**
     * Detecta se o resultado de uma ação foi FALHA.
     *
     * BUG CORRIGIDO: a checagem anterior era `result.startsWith("erro:")` — com dois-pontos.
     * Mas o McpClientManager devolve "erro MCP [servidor/tool]: ..." e "erro ao chamar MCP
     * [...]: ...", que não casavam. Resultado real medido no aparelho: chamar uma ferramenta
     * MCP inexistente foi pontuado **+1.2 como progresso** e o grafo APRENDEU que a ação
     * ruim era boa. Qualquer penalização de rota por erro era inútil sem isso.
     */
    /** Acesso somente-leitura ao grafo para o ciclo ocioso do [InitiativeEngine]. */
    fun getGraphForInitiative(): KnowledgeGraphMemory = graph

    fun isFailureResult(result: String): Boolean {
        val r = result.lowercase(java.util.Locale("pt", "BR")).trim()
        if (r.isEmpty()) return false
        if (r.startsWith("erro") || r.startsWith("🛑") || r.startsWith("falha")) return true
        val technicalFailures = listOf(
            "erro mcp", "erro ao chamar", "erro ao executar", "falhou", "timeout",
            "exception", "connection refused", "unreachable", "indisponível", "indisponivel",
            "está desativado", "esta desativado", "sem permissão", "sem permissao",
            "http 4", "http 5", "unknown tool", "not found", "inválid", "invalid",
            "não consegui", "nao consegui", "bloqueado",
        )
        return technicalFailures.any { r.contains(it) }
    }

    /**
     * Correção fonética de entidades que o reconhecedor erra de forma recorrente.
     * Extraído dos logs reais: "Abra o chat GT", "Abra o chat de PT", "abre o chat do seu PT"
     * — todos eram ChatGPT, e o agente abria o app errado ou se perdia procurando.
     */
    private fun correctKnownEntities(text: String): String {
        var out = text
        val fixes = listOf(
            Regex("(?i)\\bchat\\s*(gt|g\\s*t|de\\s*pt|do\\s*seu\\s*pt|seu\\s*pt|gepeto|jipiti|gpt)\\b") to "ChatGPT",
            Regex("(?i)\\byou\\s*tube\\b") to "YouTube",
            Regex("(?i)\\b(iutub|iutube|utube)\\b") to "YouTube",
            Regex("(?i)\\b(uatsap|uatzap|whats\\s*app|zap\\s*zap)\\b") to "WhatsApp",
            Regex("(?i)\\bgoogle\\s*drive\\b") to "Google Drive",
        )
        for ((re, rep) in fixes) out = re.replace(out, rep)
        if (out != text) {
            ui.log("🔤 Correção fonética: '${text.take(60)}' -> '${out.take(60)}'")
        }
        return out
    }

    /**
     * ATALHOS INSTANTÂNEOS (sem LLM, sem tela, ~0 ms).
     *
     * Baseado na análise das 265 sessões reais do usuário: 21 sessões foram só
     * "tá me ouvindo?" / "tá na escuta?" e 10 foram "bateria" — cada uma gastando uma
     * ida completa ao LLM (~2,2 s) para uma resposta que o celular já sabe. Agora respondem
     * na hora, com variação de frase para não soar robótico repetindo a mesma linha.
     */
    private fun instantLocalAnswer(task: String): String? {
        FastPathRouter.tryAnswer(ctx, task)?.let { return it }
        val t = normalizeSpokenReply(task).let { n ->
            // ignora o contexto anexado automaticamente para classificar só a fala em si
            n.substringBefore("contexto curto de voz").substringBefore("contexto visual da tela").trim()
        }
        if (t.isEmpty() || t.split(" ").size > 9) return null

        // GUARDA: se a frase também carrega um comando real ("me escuta e abre o YouTube"),
        // o atalho NÃO pode sequestrar o turno — vai para o fluxo normal do agente.
        val actionWords = listOf(
            "abre", "abra", "abrir", "manda", "mande", "mandar", "envia", "envie", "enviar",
            "liga", "ligar", "ligue", "toca", "tocar", "toque", "pesquisa", "pesquise", "procura",
            "procure", "busca", "busque", "cria", "crie", "criar", "apaga", "apague", "apagar",
            "deleta", "delete", "exclui", "exclua", "configura", "configure", "ajusta", "ajuste",
            "aumenta", "diminui", "responde", "responda", "le", "leia", "mostra", "mostre",
            "coloca", "coloque", "poe", "faz", "faca", "executa", "execute", "salva", "grava",
            "youtube", "whatsapp", "chatgpt", "drive", "alarme", "musica", "foto", "camera",
            "mensagem", "notificacao", "backup", "regra", "vigia",
        )
        if (actionWords.any { containsPhraseToken(t, it) }) return null

        val presence = listOf(
            "ta me ouvindo", "esta me ouvindo", "voce ta me ouvindo", "voce esta me ouvindo",
            "ta na escuta", "esta na escuta", "na escuta", "me ouve", "voce me ouve",
            "ta me escutando", "esta me escutando", "me escuta", "ta ai", "esta ai",
            "ta vivo", "esta vivo", "ta acordado", "ta funcionando", "ta online",
        )
        if (presence.any { containsPhraseToken(t, it) }) {
            val snr = AudioTelemetry.rmsDbfs - AudioTelemetry.noiseFloorDbfs
            val quality = when {
                snr >= 18 -> "cristalino"
                snr >= 10 -> "bem"
                else -> "com um pouco de ruído de fundo"
            }
            return listOf(
                "Tô aqui, te ouvindo $quality.",
                "Oi, ouço $quality. Manda.",
                "Na escuta, sinal $quality.",
                "Tô aqui, ouvindo $quality.",
            ).random()
        }

        val battery = listOf("bateria", "quanto de bateria", "nivel da bateria", "carga da bateria")
        if (battery.any { containsPhraseToken(t, it) }) {
            return runCatching {
                val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                if (pct <= 0) null
                else if (charging) "Bateria em $pct%, carregando."
                else if (pct <= 20) "Bateria em $pct%. Tá baixa, melhor colocar pra carregar."
                else "Bateria em $pct%."
            }.getOrNull()
        }
        val findPhone = listOf("cade meu celular", "onde esta meu celular", "onde ta meu celular", "acha meu celular", "encontra meu celular")
        if (findPhone.any { t.contains(it) } && !t.contains("regra") && !t.contains("quando eu falar")) {
            playFindPhoneSound(4)
            return "Tô aqui! Tô tocando um som pra você me achar."
        }
        return null
    }

    private fun playFindPhoneSound(seconds: Int = 4) {
        val durMs = (seconds.coerceIn(2, 10) * 1000L)
        Thread {
            runCatching {
                val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                val r = android.media.RingtoneManager.getRingtone(ctx, uri)
                if (r != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        r.isLooping = true
                    }
                    r.audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    r.play()
                    Thread.sleep(durMs)
                    r.stop()
                }
            }
        }.start()
    }

    /**
     * Detecta quando o pedido do usuário exige percepção visual real (pixels de screenshot/câmera),
     * como descrever uma imagem/foto, contar objetos, dizer cores ou ler o que aparece dentro de uma figura.
     * Sem `inspect_screen_visual` ou `capture_camera`, qualquer afirmação sobre isso seria alucinação!
     */
    /** Remove metadados anexados automaticamente ([CONTEXTO...], [Contexto visual...]) para classificar só o pedido real. */
    private fun stripTaskMetadata(raw: String): String =
        raw.substringBefore("[CONTEXTO").substringBefore("[Contexto").trim()

    private fun requiresVisualEvidence(task: String): Boolean {
        val t = stripTaskMetadata(task).lowercase(java.util.Locale("pt", "BR"))
        // Se for pedido para GERAR / CRIAR uma imagem no Gemini/ChatGPT, não é leitura de imagem existente
        if (t.contains("gere uma imagem") || t.contains("gerar uma imagem") ||
            t.contains("crie uma imagem") || t.contains("criar uma imagem") ||
            t.contains("faça uma imagem") || t.contains("desenhe")
        ) {
            return false
        }
        val visualPatterns = listOf(
            "ver essa imagem", "vendo essa imagem", "veja essa imagem", "olha essa imagem",
            "nessa imagem", "na imagem", "dessa imagem", "desta imagem", "nesta imagem",
            "nessa foto", "na foto", "dessa foto", "o que tem na foto", "o que tem na imagem",
            "quantas cápsulas", "quantas capsulas", "quantos robôs", "quantos robos",
            "fala a cor", "qual a cor", "quais as cores", "que cor é", "que cor são",
            "lá no fundo", "la no fundo", "aparece na imagem", "mostra na imagem",
            "consegue enxergar", "consegue ver a foto", "descreva a imagem", "descreve a imagem",
        )
        return visualPatterns.any { t.contains(it) }
    }

    /**
     * Retorna true somente quando o pedido do usuário exige acionar uma ferramenta de dispositivo/app.
     * Perguntas conversacionais ("quem é você", "o que você faz", explicações, avisos) retornam false
     * e podem finalizar com `done` diretamente no Passo 1!
     */
    private fun requiresDeviceTool(task: String): Boolean {
        val clean = stripTaskMetadata(task)
        val t = clean.lowercase(java.util.Locale("pt", "BR"))
        if (requiresVisualEvidence(clean)) return true
        // Se for pergunta sobre identidade/capacidades do próprio agente, não exige ferramenta física
        if ((t.contains("quem é você") || t.contains("quem e voce") || t.contains("o que você faz") ||
                t.contains("que que você faz") || t.contains("pode me ajudar") || t.contains("me explica")) &&
            !t.contains("bateria") && !t.contains("alarme") && !t.contains("abra ")
        ) {
            return false
        }
        return listOf(
            "whatsapp", "zap", "mande ", "envie ", "vigia", "monitor", "regra", "regras",
            "nunca apague", "nunca apagar", "sempre confirme", "sempre confirmar",
            "bateria", "temperatura", "lux", "luminosidade", "sensor", "armazenamento", "versão do android", "versao do android",
            "notificação", "notificacao", "abra ", "abrir ", "alarme", "despertador",
            "gravar", "gravação", "gravacao", "câmera", "camera", "tire uma foto",
            "spotify", "música", "musica", "tocar", "ligue para", "ligar para",
            "pesquise", "busque na web", "cotação", "brilho", "wi-fi", "bluetooth",
            "vídeos grandes", "videos grandes", "arquivos grandes", "liberar espaço",
            "mcp", "upload", "backup", "nuvem", "drive", "pontuação", "pontuacao", "sensibilidade", "pendentes",
            "avatar", "boneco", "balão", "balao", "canto direito", "canto esquerdo", "muro",
            "gps", "localização", "localizacao", "endereço", "endereco", "cep", "onde estou", "rosto", "objeto", "ler arquivo", "leia o arquivo"
        ).any { t.contains(it) }
    }

    private enum class LocalIntentMode { CONVERSA, SISTEMA_OU_MCP, ACAO_NA_TELA }

    /**
     * Classificador local (zero LLM, <1 ms) que decide a intenção e o orçamento de contexto
     * enviado em cada etapa, evitando mandar 12 KB de XML quando o pedido é conversa ou API/MCP.
     */
    private fun classifyLocalIntent(
        task: String,
        executedActions: List<Pair<String, String>>
    ): LocalIntentMode {
        val clean = stripTaskMetadata(task)
        if (requiresVisualEvidence(clean)) return LocalIntentMode.ACAO_NA_TELA
        val t = clean.lowercase(java.util.Locale("pt", "BR"))
        val uiKeywords = listOf(
            "na tela", "essa tela", "clique", "clica", "toque", "toca", "role", "deslize",
            "abra ", "abrir ", "feche ", "fechar ", "digite", "escreva", "tire uma foto",
            "câmera", "camera", "alarme", "despertador", "spotify", "youtube", "chatgpt",
            "configurações", "configuracoes", "volta", "voltar"
        )
        if (uiKeywords.any { t.contains(it) } ||
            executedActions.any { it.first in setOf("tap", "type", "scroll", "open_app", "batch", "inspect_screen_visual") }
        ) {
            return LocalIntentMode.ACAO_NA_TELA
        }
        val sysOrMcpKeywords = listOf(
            "bateria", "temperatura", "sensor", "lux", "luminosidade", "armazenamento",
            "arquivos grandes", "vídeos grandes", "videos grandes", "notificação", "notificacao",
            "mcp", "whatsapp", "zap", "brain", "regra", "vigia", "monitor",
            "pesquise", "busque na web", "cotação", "cotacao", "backup", "drive", "nuvem", "status",
            "gps", "localização", "localizacao", "endereço", "endereco", "cep", "onde estou", "ler arquivo", "leia o arquivo", "cadê meu celular", "cade meu celular"
        )
        if (sysOrMcpKeywords.any { t.contains(it) } || requiresDeviceTool(task)) {
            return LocalIntentMode.SISTEMA_OU_MCP
        }
        return LocalIntentMode.CONVERSA
    }

    /**
     * Acorda a tela; somente bloqueia aguardando desbloqueio de senha quando `requireUnlock = true`
     * (ou seja, quando a ação realmente precisa interagir com a UI de aplicativos).
     * Assim, perguntas por voz, regras, sensores, memória e `scan_storage_files` funcionam instantaneamente
     * mesmo com a tela bloqueada!
     */
    private suspend fun ensureAwake(requireUnlock: Boolean = false) {
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "agente:wake").acquire(3000)
            delay(400)
        }
        if (requireUnlock && km.isKeyguardLocked) {
            // PORTÃO DE PALAVRA-CHAVE: se esta conversa COMEÇOU com a tela bloqueada, só
            // destrava depois de ouvir a palavra-chave. Se a tela bloqueou no meio de uma
            // conversa já em andamento, o UnlockGate já autorizou por graça.
            if (UnlockGate.needsPassphrase()) {
                ui.log("🔐 Tela bloqueada desde o início da conversa — pedindo palavra-chave")
                val reply = ui.ask("Para destravar a tela eu preciso da palavra-chave.")
                if (!UnlockGate.verify(ctx, reply)) {
                    ui.log("🔒 Palavra-chave incorreta — ${UnlockGate.lastDecision}")
                    ui.say("Palavra-chave incorreta, então não vou destravar a tela.")
                    return
                }
                ui.log("🔓 Palavra-chave aceita — ${UnlockGate.lastDecision}")
            }

            if (!km.isDeviceSecure) {
                // Sem senha: basta deslizar.
                withContext(Dispatchers.Main) { AgentAccessibilityService.instance?.scroll("down") }
                delay(600)
            } else {
                // Com PIN/senha, NENHUM app pode destravar o keyguard (restrição do Android,
                // e guardar o PIN no app seria entregar o aparelho em caso de vazamento).
                // Então avisa uma vez e aguarda o desbloqueio manual.
                ui.log("🔒 Aparelho tem PIN: não é possível destravar por software — aguardando você")
            }
            var waited = 0
            while (km.isKeyguardLocked && waited < 60) {
                HealthMonitor.markStep(0, "aguardando_desbloqueio")
                if (waited == 0) ui.say("Autorizado. Agora destrave o celular que eu continuo na tela.")
                if (waited == 0) ui.log("🔒 aguardando desbloqueio…")
                delay(1000); waited++
            }
        }
    }

    data class GoalVerification(
        val verified: Boolean,
        val reason: String
    )

    private fun normalizeScreenForDiff(screen: String): String {
        return screen.lineSequence()
            .filterNot { it.contains("TextView \"") && it.contains(":") && it.length < 40 }
            .joinToString("\n")
            .trim()
    }

    private fun isUiNavigationAction(action: String): Boolean {
        return action in setOf(
            "open_app", "tap", "long_press", "type", "scroll",
            "back", "home", "recents", "notifications", "quick_settings",
            "send_whatsapp", "open_url", "batch"
        )
    }

    private fun verifyGoalCompletion(
        task: String,
        initialApp: String,
        currentApp: String,
        executedActions: List<Pair<String, String>>,
        lastResult: String,
        currentScreenDump: String = "",
        hasVisualEvidence: Boolean = false,
    ): GoalVerification {
        val cleanTask = stripTaskMetadata(task)
        val t = cleanTask.lowercase(java.util.Locale("pt", "BR"))
        val lastExecResult = executedActions.lastOrNull()?.second.orEmpty()
        val lastFailed = lastResult.startsWith("erro:", ignoreCase = true) ||
            lastResult.startsWith("falhou", ignoreCase = true) ||
            lastExecResult.startsWith("erro:", ignoreCase = true) ||
            lastExecResult.startsWith("falhou", ignoreCase = true)
        val actionsSet = executedActions.map { it.first }.toSet()
        val hasSubstantiveSuccess = executedActions.any { (act, res) ->
            act !in setOf("back", "scroll", "wait", "home") &&
                !res.startsWith("erro:", ignoreCase = true) &&
                !res.startsWith("falhou", ignoreCase = true)
        }
        if (lastFailed && !hasSubstantiveSuccess) {
            return GoalVerification(false, "A última ação retornou falha ($lastResult)")
        }

        // 1. Verificação Epistêmica Visual: proíbe declarar 'done' em perguntas sobre imagens/fotos sem evidência visual real
        if (requiresVisualEvidence(task)) {
            val sawPixels = hasVisualEvidence ||
                actionsSet.contains("inspect_screen_visual") ||
                actionsSet.contains("capture_camera")
            return if (sawPixels) {
                GoalVerification(true, "Percepção visual verificada com captura real de imagem (OBSERVED)")
            } else {
                GoalVerification(
                    false,
                    "Pergunta exige percepção visual da imagem na tela, mas nenhuma captura de pixels foi feita (chame inspect_screen_visual primeiro!)",
                )
            }
        }

        if ((t.contains("regra") || t.contains("nunca apagar") || t.contains("nunca apague") || t.contains("sempre confirmar")) &&
            !t.contains("vigia")
        ) {
            val ok = actionsSet.any { it in setOf("add_rule", "update_rule", "toggle_rule", "remove_rule", "list_rules") }
            return if (ok) GoalVerification(true, "Operação no Motor de Regras (RuleEngine) verificada")
            else GoalVerification(false, "Tarefa pedia criar/alterar/listar regra, mas add_rule/update_rule/list_rules não foi chamado")
        }
        if ((t.contains("whatsapp") || t.contains("zap")) && (t.contains("mande") || t.contains("envie") || t.contains("mensagem"))) {
            val ok = actionsSet.contains("send_whatsapp") ||
                actionsSet.contains("mcp_call") ||
                currentApp.contains("whatsapp", ignoreCase = true)
            return if (ok) GoalVerification(true, "Fluxo WhatsApp verificado")
            else GoalVerification(false, "Tarefa pedia envio no WhatsApp, mas send_whatsapp não foi executado")
        }
        if (t.contains("alarme") || t.contains("despertador")) {
            // Verificação baseada no ESTADO FINAL REAL (e não apenas na ferramenta 'set_alarm'):
            // 1) Se chamou set_alarm sem erro -> verificado.
            if (actionsSet.contains("set_alarm")) {
                return GoalVerification(true, "Alarme configurado via intent set_alarm")
            }
            // 2) Se está no app de Relógio (deskclock), verifica se o estado final da tela condiz com o pedido
            val inClockApp = currentApp.contains("deskclock", ignoreCase = true) || currentApp.contains("clock", ignoreCase = true)
            val targetTimeMatch = Regex("\\b(\\d{1,2})[:h](\\d{2})\\b").find(t)
            val targetHourOnly = Regex("\\bdas\\s+(\\d{1,2})\\s*(?:h|horas)?\\b").find(t)
            val requestedTimeStr = when {
                targetTimeMatch != null -> {
                    val h = targetTimeMatch.groupValues[1].toIntOrNull() ?: 0
                    val m = targetTimeMatch.groupValues[2]
                    listOf("$h:$m", String.format(java.util.Locale.US, "%02d:%s", h, m))
                }
                targetHourOnly != null -> {
                    val h = targetHourOnly.groupValues[1].toIntOrNull() ?: 0
                    listOf("$h:00", String.format(java.util.Locale.US, "%02d:00", h))
                }
                else -> emptyList()
            }
            val screenHasRequestedTime = requestedTimeStr.isEmpty() || requestedTimeStr.any { currentScreenDump.contains(it) }
            val isDisableRequest = t.contains("desativ") || t.contains("deslig") || t.contains("apag") || t.contains("cancel")
            val isQueryOrAlreadySet = t.contains("qual") || t.contains("que horas") || t.contains("confira") ||
                t.contains("verifique") || (inClockApp && currentScreenDump.contains("marcado") && !t.contains("alter") && !t.contains("mud") && !isDisableRequest)
            val interactedWithClockUi = inClockApp && actionsSet.any { it in setOf("tap", "type", "batch", "scroll") }

            return when {
                interactedWithClockUi && screenHasRequestedTime ->
                    GoalVerification(true, "Estado final do alarme verificado na interface do Relógio (${requestedTimeStr.firstOrNull() ?: "UI"})")
                isQueryOrAlreadySet && inClockApp ->
                    GoalVerification(true, "Alarme verificado no estado atual da tela do Relógio")
                else ->
                    GoalVerification(false, "Objetivo de alarme não comprovado no estado final da tela nem via set_alarm")
            }
        }
        if (!t.startsWith("evento de vigia") &&
            (t.contains("vigia") || t.contains("monitor")) &&
            (t.contains("crie") || t.contains("adicion") || t.contains("coloqu") || t.contains("ativ") ||
                t.contains("list") || t.contains("quais") || t.contains("remov") || t.contains("apag") || t.contains("desativ"))
        ) {
            val ok = actionsSet.contains("add_watch") || actionsSet.contains("list_watches") || actionsSet.contains("remove_watch")
            return if (ok) GoalVerification(true, "Operação de vigia proativa verificada")
            else GoalVerification(false, "Tarefa pedia vigia, mas add_watch/list_watches não foi chamado")
        }
        if (t.contains("bateria") || t.contains("temperatura") || t.contains("lux") || t.contains("luminosidade")) {
            val ok = actionsSet.any {
                it in setOf("device_status", "configure_sensors", "get_notifications", "mcp_call", "web_search", "inspect_screen_visual")
            }
            return if (ok) GoalVerification(true, "Leitura/controle de telemetria e sensores verificado")
            else GoalVerification(false, "Tarefa pedia dados de sensores/bateria sem chamar device_status")
        }
        if (t.contains("notificação") && (t.contains("crie") || t.contains("deixe") || t.contains("sem falar") || t.contains("silencios"))) {
            val ok = actionsSet.contains("post_notification")
            return if (ok) GoalVerification(true, "Criação de notificação silenciosa verificada via post_notification")
            else GoalVerification(false, "Tarefa pedia criar notificação sem falar em voz alta, mas post_notification não foi chamado")
        }
        if (t.contains("sensor") && (t.contains("configur") || t.contains("ajust") || t.contains("control") || t.contains("modo"))) {
            val ok = actionsSet.contains("configure_sensors") || actionsSet.contains("device_status")
            return if (ok) GoalVerification(true, "Controle de sensores verificado via configure_sensors")
            else GoalVerification(false, "Tarefa pedia controlar sensores sem chamar configure_sensors")
        }
        if ((t.contains("abra ") || t.contains("abrir ")) && !t.contains("não abra")) {
            val ok = actionsSet.contains("open_app") ||
                actionsSet.contains("open_url") ||
                (currentApp.isNotEmpty() && currentApp != initialApp) ||
                actionsSet.any { it in setOf("tap", "batch") }
            return if (ok) GoalVerification(true, "Abertura de aplicativo/tela verificada (app=$currentApp)")
            else GoalVerification(false, "Tarefa pedia abrir app, mas nem open_app nem mudança de app ocorreram")
        }
        if (executedActions.isEmpty()) {
            return if (!requiresDeviceTool(task)) {
                GoalVerification(true, "Resposta conversacional/explicativa direta concluída em 1 passo")
            } else {
                GoalVerification(false, "Agente declarou done no passo 1 sem executar a ferramenta exigida pelo pedido")
            }
        }
        return GoalVerification(true, "Sequência de ${executedActions.size} etapa(s) verificada sem erros")
    }

    suspend fun run(rawTask0: String, resumingFromCheckpoint: Boolean = false): String = withContext(Dispatchers.IO) {
        val taskIdRegex = Regex("""^\[TASK_ID:([a-zA-Z0-9_-]+)\]\s*""")
        val channelTaskId = taskIdRegex.find(rawTask0)?.groupValues?.get(1)
        val cleanTask0 = rawTask0.replace(taskIdRegex, "").trim()
        val answer = runInternal(cleanTask0, resumingFromCheckpoint)
        if (channelTaskId != null) {
            val isFail = answer.startsWith("Parei após") || answer.startsWith("erro:", ignoreCase = true)
            val args = JSONObject()
                .put("task_id", channelTaskId)
                .put("state", if (isFail) "failed" else "completed")
                .put("result", answer)
                .put("error", if (isFail) answer else "")
            if (pendingVisualBase64 != null) {
                args.put("screenshot_base64", pendingVisualBase64)
            }
            Thread {
                runCatching { mcp.callTool("host-cloud-mcp", "xzero_publish_result", args) }
            }.start()
        }
        answer
    }

    private suspend fun runInternal(task0: String, resumingFromCheckpoint: Boolean = false): String = withContext(Dispatchers.IO) {
        var task = correctKnownEntities(task0)
        currentTaskContext = task
        // Define se esta tarefa continua uma conversa já aberta (janela Live de follow-up).
        // É isso que diferencia "conversa começou bloqueada" de "bloqueou durante a conversa".
        UnlockGate.onTaskStart(ctx, continuingConversation = ambient.isInFollowUpWindow())
        val taskStartedAtMs = System.currentTimeMillis()
        // Se esta execução é uma retomada explícita, carrega o que já tinha sido feito
        val resumeState = if (resumingFromCheckpoint) TaskCheckpoint.orphan() else null
        if (resumeState != null) {
            TaskCheckpoint.markResumed()
            ui.log("↩️ Retomando tarefa do passo ${resumeState.step}: ${resumeState.goal.take(70)}")
        }
        rules.clearSessionApprovals()
        val graphPlan = IdeaToActionGraph.prepare(task)
        AdaStateMachine.transition(AdaStateMachine.AdaState.PLANNING_GRAPH, "agent_run", "${graphPlan.intentKind}")
        runCatching {
            ThinkingLogger.startSession(task0.take(60))
            MetricsCollector.startSession(task0.take(60))
        }
        val steps = mutableListOf<Msg>()
        if (graphPlan.intentKind == IdeaToActionGraph.IntentKind.IDEA_COMPLEX) {
            steps += Msg("user", graphPlan.promptHint)
        }
        val scoredSteps = mutableListOf<Pair<String, Double>>()
        val executedActions = mutableListOf<Pair<String, String>>()
        var lastResult = "(início)"
        var stagnantSteps = 0
        var stagnantAskCount = 0
        var restartCount = 0
        var unverifiedDoneCount = 0
        var sayCount = 0
        var lastStepSpeechMs = 0L
        var lastSpokenAction = ""
        var lastActionSig = ""
        val recentActionSigs = ArrayDeque<String>()
        // Contagem de falhas por ação: uma ação só entra na lista de "evitar" depois de
        // falhar 2x, porque banir "tap" inteiro no primeiro toque errado descartaria quase
        // toda rota de UI e deixaria o agente sem alternativa nenhuma.
        val failureCountByAction = mutableMapOf<String, Int>()
        var invalidJsonStreak = 0
        var llmFailStreak = 0
        var initialApp = ""
        var previousScreenDump = ""
        var hasVisualEvidenceInSession = false
        pendingVisualBase64 = null
        val taskLowerInit = task.lowercase(java.util.Locale("pt", "BR"))
        var preExecutionConfirmed = taskLowerInit.contains("confirmado") ||
            taskLowerInit.contains("pode fazer direto") ||
            taskLowerInit.contains("sem perguntar")
        val parallelBtwNotes = mutableListOf<String>()
        val preIdleBtw = ambient.consumeIdleBtwContextIfAny()
        if (preIdleBtw.isNotEmpty()) {
            parallelBtwNotes.add(preIdleBtw)
            ui.log("📌 Contexto /btw prévio incluído na atividade principal: $preIdleBtw")
        }
        trajectory.startSession(task)
        val learnings = trajectory.getRecentLearnings()
        HealthMonitor.markTaskStart(task)
        memory += Msg("user", "[CONVERSA ANTERIOR JÁ CONCLUÍDA — NÃO RE-EXECUTE A MENOS QUE PEDIDO AGORA]: $task")

        // Fast-path para Cancelamento, Reforço Positivo (RLHF) ou Correção do usuário (sem entrar em loop!)
        // Atalho instantâneo local: responde sem acionar o LLM nem ler a tela
        instantLocalAnswer(task)?.let { quick ->
            ui.log("⚡ Resposta instantânea local (sem LLM): $quick")
            EmotionEngine.feel(EmotionEngine.Emotion.ATENTO, "resposta instantânea", bump = 0.2)
            trajectory.logStep(1, "fast_path:local", "1. Responder localmente sem LLM", "Pergunta respondível pelo próprio aparelho.", "done", JSONObject().put("answer", quick), "done (fast_path_local)", "")
            trajectory.endSession("success", quick, agentDeclaredDone = true, goalVerified = true, verificationReason = "Atalho local instantâneo")
            ui.onTaskCompletion(verified = true, detail = "Atalho local verificado")
            memory += Msg("assistant", quick)
            return@withContext quick
        }

        when (classifySpecialUserTurn(task)) {
            SpecialTurnType.ABORT_IGNORE -> {
                val ans = "Tranquilo! Fico em silêncio aqui."
                ui.log("🔇 Conversa externa/cancelamento detectado — encerrando em 1 passo sem loop.")
                trajectory.logStep(1, "fast_path:abort_guard", "1. Silenciar e encerrar", "usuário avisou que estava falando com outra pessoa ou pediu silêncio.", "done", JSONObject().put("answer", ans), "done (fast_path_abort, goal_verified=true)", "")
                trajectory.endSession("success", ans, agentDeclaredDone = true, goalVerified = true, verificationReason = "Cancelamento/conversa paralela encerrada em 1 passo")
                memory += Msg("assistant", ans)
                compactMemoryIfNeeded()
                return@withContext ans
            }
            SpecialTurnType.POSITIVE_REINFORCEMENT -> {
                val rlReport = graph.applyHumanFeedback(isPositive = true, feedbackText = task)
                val ans = "Valeu! Registrei esse reforço positivo (+2.5) na minha política Q(s,a) e na rota campeã."
                ui.log("🧠⭐ RLHF Positivo: $rlReport")
                trajectory.logStep(1, "rlhf:human_feedback", "1. Aplicar reforço positivo (+2.5) na última rota e política Q(s,a)", rlReport, "done", JSONObject().put("answer", ans), "done (rlhf_boost=+2.5, goal_verified=true)", "")
                trajectory.endSession("success", ans, agentDeclaredDone = true, goalVerified = true, verificationReason = "Reforço positivo humano (RLHF) aplicado")
                ui.onAgentPhaseChanged(AgentPhase.LEARNING, "reforço humano aplicado")
                ui.onTaskCompletion(verified = true, detail = "Reforço humano registrado")
                memory += Msg("assistant", ans)
                compactMemoryIfNeeded()
                return@withContext ans
            }
            SpecialTurnType.CORRECTIVE_FEEDBACK -> {
                val rlReport = graph.applyHumanFeedback(isPositive = false, feedbackText = task)
                ui.log("🧠🛠️ RLHF Correção: $rlReport")
                if (!requiresDeviceTool(task)) {
                    val ans = "Entendido! Penalizei a rota anterior (-2.5) e gravei sua correção no Grafo Vetorial para não repetir."
                    trajectory.logStep(1, "rlhf:human_correction", "1. Penalizar rota anterior e memorizar regra de correção", rlReport, "done", JSONObject().put("answer", ans), "done (rlhf_penalty=-2.5, goal_verified=true)", "")
                    trajectory.endSession("success", ans, agentDeclaredDone = true, goalVerified = true, verificationReason = "Correção humana (RLHF) memorizada no Grafo")
                    ui.onAgentPhaseChanged(AgentPhase.LEARNING, "correção humana memorizada")
                    ui.onTaskCompletion(verified = true, detail = "Correção humana registrada")
                    memory += Msg("assistant", ans)
                compactMemoryIfNeeded()
                    return@withContext ans
                }
                lastResult = "CORREÇÃO DE APRENDIZADO DO USUÁRIO REGISTRADA ($rlReport). Agora execute a ação correta pedida!"
            }
            SpecialTurnType.NONE -> {}
        }

        suspend fun drainAndApplyBtwDirectives(): String? {
            waitIfUserSpeakingBtw()
            val incomingBtw = ambient.drainParallelBtwDirectives()
            for (btw in incomingBtw) {
                when (btw.type) {
                    AmbientContextEngine.BtwDirectiveType.CANCEL_CURRENT_TASK -> {
                        val ans = "Tarefa cancelada em paralelo (/btw) como pediu."
                        ui.log("🛑 [/btw Cancelamento]: ${btw.cleanText}")
                        trajectory.endSession("cancelled_by_btw", ans, agentDeclaredDone = false, goalVerified = true, verificationReason = "Cancelado via /btw")
                        return ans
                    }
                    AmbientContextEngine.BtwDirectiveType.PAUSE_AND_WAIT -> {
                        ui.log("⏸️ [/btw Pausa Solicitada]: '${btw.cleanText}' — congelando fluxo primário e aguardando você...")
                        val resumeText = ui.ask("⏸️ Pausei como pediu. Me fale quando puder continuar ou passe a próxima instrução.")
                        if (resumeText.isNotBlank() && resumeText != "(sem resposta)") {
                            parallelBtwNotes.add("Instrução pós-pausa: $resumeText")
                            lastResult += " | ▶ RETOMADO APÓS PAUSA COM INSTRUÇÃO: '$resumeText'"
                        }
                        stagnantSteps = 0
                    }
                    AmbientContextEngine.BtwDirectiveType.SWITCH_MAIN_TASK -> {
                        task = btw.cleanText
                        currentTaskContext = task
                        steps.clear()
                        scoredSteps.clear()
                        executedActions.clear()
                        stagnantSteps = 0
                        unverifiedDoneCount = 0
                        sayCount = 0
                        lastActionSig = ""
                        ui.log("🔀 [/btw Nova Tarefa Principal]: $task")
                        lastResult = "(tarefa trocada em paralelo via /btw)"
                    }
                    AmbientContextEngine.BtwDirectiveType.RECALCULATE_ROUTE -> {
                        parallelBtwNotes.add("RECALCULAR ROTA: ${btw.cleanText}")
                        lastResult += " | ⚡ [/btw MUDANÇA DE CAMINHO DO USUÁRIO]: '${btw.cleanText}'. Recalcule a rota mantendo o que já foi feito!"
                        stagnantSteps = 0
                        ui.log("🧭 [/btw Recalcular Rota]: ${btw.cleanText}")
                    }
                    AmbientContextEngine.BtwDirectiveType.APPEND_PARALLEL_CONTEXT -> {
                        val bLower = btw.cleanText.lowercase(java.util.Locale("pt", "BR"))
                        if (bLower.contains("reforço de aprendizado") || bLower.contains("reforco de aprendizado") ||
                            bLower.contains("parabéns") || bLower.contains("parabens") || bLower.contains("muito bom") ||
                            bLower.contains("mandou bem")
                        ) {
                            val rlReport = graph.applyHumanFeedback(isPositive = true, feedbackText = btw.cleanText)
                            ui.log("🧠⭐ [RLHF em Paralelo /btw]: $rlReport")
                        }
                        parallelBtwNotes.add(btw.cleanText)
                        ui.log("💬 [/btw Contexto em Paralelo]: ${btw.cleanText}")
                    }
                }
            }
            return null
        }

        var step = 0
        while (step < 25) {
            step++
            // 1. Verifica e pausa se o usuário estiver falando um `/btw` ou comando de espera ANTES de ler a tela / chamar o LLM
            drainAndApplyBtwDirectives()?.let { return@withContext it }

            HealthMonitor.markStep(step, "pensando")
            ui.onAgentPhaseChanged(AgentPhase.THINKING, "passo $step")
            ensureAwake(requireUnlock = false)
            // Auto-cura antes de cada passo (reativa acessibilidade se caiu, fecha diálogo de app travado)
            runCatching { HealthMonitor.checkAndHeal(ctx, heal = true) }
            val preActionScreen = withContext(Dispatchers.Main) {
                AgentAccessibilityService.instance?.dumpScreen()
            } ?: "(serviço de acessibilidade desativado — só ações por intent funcionam)"
            val screenDiff = if (previousScreenDump.isNotEmpty()) {
                AgentAccessibilityService.instance?.computeScreenDiff(previousScreenDump, preActionScreen).orEmpty()
            } else ""
            val currentApp = preActionScreen.lineSequence().firstOrNull()?.removePrefix("App em foco: ")?.trim().orEmpty()
            if (step == 1) {
                initialApp = currentApp
                if (requiresVisualEvidence(task) && pendingVisualBase64 == null) {
                    val autoShot = runCatching { AgentAccessibilityService.instance?.captureScreenBase64() }.getOrNull()
                    if (!autoShot.isNullOrBlank()) {
                        pendingVisualBase64 = autoShot
                        hasVisualEvidenceInSession = true
                        ui.log("📸👁️ [Percepção Visual OBSERVED]: Screenshot real capturado automaticamente (${autoShot.length / 1024} KB base64)")
                    }
                }
            }
            val stepImageBase64 = pendingVisualBase64
            pendingVisualBase64 = null
            if (!stepImageBase64.isNullOrBlank()) {
                hasVisualEvidenceInSession = true
            }
            val intentMode = classifyLocalIntent(task, executedActions)
            if (step == 1) {
                ui.log("🧭 [Intenção Local=$intentMode | App=$currentApp]")
            }
            val rulesContext = if (intentMode == LocalIntentMode.CONVERSA) "" else rules.getPromptRulesBlock()
            val graphContext = graph.retrieveContext(task, currentApp)
            val mcpContext = if (intentMode == LocalIntentMode.CONVERSA) "" else mcp.getPromptContextSummary()
            val habitHint = if (step == 1) {
                val routines = PassiveHabitTracker.topRoutines(2)
                if (routines.isNotEmpty()) {
                    "ROTINAS OBSERVADAS NO USO: " + routines.joinToString("; ") {
                        "${it.bucket}: ${it.fromPkg.substringAfterLast('.')}->${it.toPkg.substringAfterLast('.')} (${it.count}x)"
                    }
                } else ""
            } else ""
            val userMsg = buildString {
                append("Pedido: ").append(task).append('\n')
                append("Modo de intenção local: ").append(intentMode.name).append('\n')
                if (habitHint.isNotEmpty()) {
                    append(habitHint).append('\n')
                }
                if (!stepImageBase64.isNullOrBlank()) {
                    append("📸 [EVIDÊNCIA VISUAL REAL ANEXADA NESTE PASSO (epistemic=OBSERVED): Analise os pixels da imagem anexada com rigor e descreva APENAS o que realmente enxergar nela!]\n")
                } else if (requiresVisualEvidence(task) && !hasVisualEvidenceInSession) {
                    append("⚠️ [ALERTA EPISTÊMICO (epistemic=UNVERIFIABLE): O pedido exige ver uma imagem/foto, mas você ainda NÃO tem screenshot anexado. Chame `inspect_screen_visual` antes de responder!]\n")
                }
                if (rulesContext.isNotEmpty()) {
                    append(rulesContext).append('\n')
                }
                if (parallelBtwNotes.isNotEmpty()) {
                    append("CONTEXTO EM PARALELO (/btw DO USUÁRIO — incorpore ao objetivo atual sem perder o progresso):\n")
                    for (note in parallelBtwNotes) {
                        append("• ").append(note).append('\n')
                    }
                }
                append("Resultado da última ação: ").append(lastResult).append('\n')
                if (executedActions.isNotEmpty()) {
                    append("ETAPAS JÁ CONCLUÍDAS NESTA SESSÃO (NÃO REPITA): ")
                        .append(executedActions.joinToString(" -> ") { it.first })
                        .append(" — Se o objetivo do Pedido já foi atingido, encerre AGORA com action=\"done\"!\n")
                }
                // Contexto de retomada: o que já foi feito, o que falhou e a hipótese seguinte
                resumeState?.takeIf { step <= 2 }?.let {
                    append(TaskCheckpoint.resumeContext(it)).append('\n')
                }
                // Tela bloqueada com PIN: em vez de travar esperando desbloqueio, orienta o
                // modelo a resolver pelo caminho que NÃO depende da tela (MCP, telemetria,
                // notificação, WhatsApp por API). Só pede desbloqueio se for impossível.
                if (UnlockGate.isKeyguardLocked(ctx)) {
                    append(
                        "TELA BLOQUEADA AGORA: prefira ações que NÃO precisam da UI " +
                            "(device_status, mcp_call, post_notification, scan_storage_files, " +
                            "send_whatsapp por API, remember_fact, web_search). " +
                            "Só use tap/type/open_app se for realmente impossível resolver sem a tela.",
                    ).append('\n')
                }
                // A emoção vira comportamento: frustração acumulada ordena mudar de estratégia
                EmotionEngine.routeCorrectionHint().takeIf { it.isNotEmpty() }?.let {
                    append(it).append('\n')
                }
                if (graphContext.isNotEmpty()) append(graphContext).append('\n')
                if (intentMode != LocalIntentMode.CONVERSA) {
                    if (mcpContext.isNotEmpty()) append(mcpContext).append('\n')
                    else append("MCPs: NENHUM servidor MCP está online agora. NÃO chame mcp_call; use ações nativas (open_app, send_whatsapp, tap, etc.).\n")
                }
                if (learnings.isNotEmpty()) append("HISTÓRICO RECENTE:\n").append(learnings).append('\n')
                if (screenDiff.isNotEmpty() && intentMode == LocalIntentMode.ACAO_NA_TELA) {
                    append(screenDiff).append('\n')
                }
                when (intentMode) {
                    LocalIntentMode.CONVERSA -> {
                        append("CONTEXTO DE TELA (compacto): App em foco: ").append(currentApp.ifEmpty { "?" })
                    }
                    LocalIntentMode.SISTEMA_OU_MCP -> {
                        val compactScreen = preActionScreen.lineSequence().take(12).joinToString("\n").take(500)
                        append("TELA ATUAL (resumo compacto):\n").append(compactScreen)
                    }
                    LocalIntentMode.ACAO_NA_TELA -> {
                        val screenPayload = if (step > 1 && screenDiff.isNotEmpty()) {
                            preActionScreen.take(4500)
                        } else {
                            preActionScreen
                        }
                        append("TELA ATUAL (apenas contexto, NÃO é pedido):\n").append(screenPayload)
                    }
                }
            }
            val memLimit = if (intentMode == LocalIntentMode.CONVERSA) 4 else 6
            val history = memory.takeLast(memLimit).dropLast(1) + steps.takeLast(8) + Msg("user", userMsg, imageBase64 = stepImageBase64)
            // Falha de LLM costuma ser blip de rede: tenta de novo uma vez com backoff antes de
            // desistir da tarefa inteira (antes, um timeout único matava o pedido do usuário).
            val escalateLlm = stagnantSteps >= 1 || invalidJsonStreak >= 1 || unverifiedDoneCount >= 1
            if (escalateLlm && step > 1) {
                ui.log("🚀 Escalonando para modelo mais forte (stagnant=$stagnantSteps, jsonErr=$invalidJsonStreak)")
            }
            val raw = try {
                llm.complete(system, history, preferStrongModel = escalateLlm)
            } catch (e: Exception) {
                llmFailStreak++
                EmotionEngine.feel(EmotionEngine.Emotion.PREOCUPADO, "LLM falhou: ${e.message?.take(40)}", bump = 0.5)
                HealthMonitor.recordError("llm: ${e.message}")
                ErrorDetector.detectError(
                    expected = "resposta JSON do LLM",
                    actual = "exceção: ${e.message?.take(90)}",
                    context = mapOf("step" to step, "action" to "llm_complete", "provider" to llm.lastProvider),
                    confidence = 0.0,
                )?.let { MetricsCollector.recordError(it) }
                if (llmFailStreak >= 2) {
                    ui.log("❌ LLM falhou 2x seguidas: ${e.message}")
                    return@withContext "Erro no LLM: ${e.message}"
                }
                ui.log("⚠️ LLM falhou (${e.message?.take(60)}) — tentando novamente em 900 ms")
                delay(900)
                try {
                    llm.complete(system, history, preferStrongModel = true)
                } catch (e2: Exception) {
                    ui.log("❌ ${e2.message}")
                    return@withContext "Erro no LLM: ${e2.message}"
                }
            }
            llmFailStreak = 0

            // 2. Se enquanto o LLM pensava o usuário falou um `/btw` ou pediu para esperar, pausa ANTES de tocar na tela e recalcula!
            val notesCountBefore = parallelBtwNotes.size
            val taskBefore = task
            drainAndApplyBtwDirectives()?.let { return@withContext it }
            if (parallelBtwNotes.size > notesCountBefore || task != taskBefore) {
                ui.log("🔄 [/btw recebido durante o pensamento do LLM]: Recalculando o passo antes de tocar na tela!")
                continue
            }

            val trimmed = raw.trim()
            val startIdx = trimmed.indexOf('{')
            val endIdx = trimmed.lastIndexOf('}')
            val js = try {
                if (startIdx >= 0 && endIdx > startIdx) {
                    JSONObject(trimmed.substring(startIdx, endIdx + 1))
                } else {
                    JSONObject(trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                }
            } catch (e: Exception) {
                invalidJsonStreak++
                ErrorDetector.detectError(
                    expected = "JSON {action,args}",
                    actual = "parse falhou: ${trimmed.take(70)}",
                    context = mapOf("step" to step, "action" to "parse_json", "provider" to llm.lastProvider),
                    confidence = 0.1,
                )?.let { MetricsCollector.recordError(it) }
                if (invalidJsonStreak >= 3) {
                    val ans = "O modelo respondeu fora do formato 3 vezes seguidas; parei para não entrar em loop."
                    ui.log("❌ $ans")
                    trajectory.endSession("invalid_json_loop", ans, agentDeclaredDone = false, goalVerified = false, verificationReason = "3 respostas inválidas seguidas")
                    return@withContext ans
                }
                // Devolve o trecho exato que quebrou, para o modelo se autocorrigir em vez de repetir o erro
                lastResult = "formato_invalido: sua resposta anterior não era JSON válido (${e.message?.take(60)}). " +
                    "Trecho recebido: «${trimmed.take(120)}». Responda APENAS o objeto JSON {\"action\":...,\"args\":{...}}."
                ui.log("⚠️ JSON inválido (${invalidJsonStreak}/3) — pedindo correção ao modelo")
                continue
            }
            invalidJsonStreak = 0
            var action = js.optString("action")
            var args = js.optJSONObject("args") ?: JSONObject()
            val plan = js.optString("plan")
            if (plan.isNotEmpty() && step == 1) {
                ui.log("📋 Plano: $plan")
            }

            // Anti-Loop de 'say': se o modelo chamar 'say' para responder pergunta conversacional,
            // ou quando as ferramentas do pedido já foram cumpridas, ou se repetir 'say', converte automaticamente em 'done'!
            if (action == "say") {
                val spokenText = args.optString("text", "Pronto.")
                val checkNow = verifyGoalCompletion(
                    task = task,
                    initialApp = initialApp,
                    currentApp = currentApp,
                    executedActions = executedActions,
                    lastResult = lastResult,
                    currentScreenDump = preActionScreen,
                    hasVisualEvidence = hasVisualEvidenceInSession,
                )
                if (!requiresDeviceTool(task) || checkNow.verified || sayCount >= 1) {
                    ui.log("⚡ Auto-finalização: convertendo 'say' em 'done' pois o objetivo já foi atingido.")
                    action = "done"
                    args = JSONObject().put("answer", spokenText)
                } else {
                    sayCount++
                }
            }

            HealthMonitor.markStep(step, action)
            steps += Msg("user", "Passo $step (tela omitida). Resultado anterior: $lastResult")
            steps += Msg("assistant", raw)
            if (action == "done") {
                val rawAns = args.optString("answer", "Pronto.")
                val epistemic = js.optString("epistemic", "OBSERVED").uppercase(java.util.Locale.US)
                val verification = verifyGoalCompletion(
                    task = task,
                    initialApp = initialApp,
                    currentApp = currentApp,
                    executedActions = executedActions,
                    lastResult = lastResult,
                    currentScreenDump = preActionScreen,
                    hasVisualEvidence = hasVisualEvidenceInSession,
                )
                if (!verification.verified && unverifiedDoneCount == 0 && step < 20) {
                    unverifiedDoneCount++
                    val penalty = -0.8
                    scoredSteps += Pair("done_unverified", penalty)
                    graph.updatePolicyQ(task, currentApp, "done", penalty, terminalVerified = false)
                    lastResult = "ALERTA DO GOAL VERIFIER: você declarou 'done', mas a verificação falhou (${verification.reason}). Execute a ferramenta/ação necessária antes de concluir!"
                    ui.log("⚠️ GoalVerifier bloqueou done prematuro: ${verification.reason}")
                    trajectory.logStep(step, llm.lastProvider, plan, js.optString("thought"), "done_blocked", args, "$lastResult [score=$penalty]", preActionScreen)
                    continue
                }

                // Honestidade Epistêmica: só adiciona prefixo de incerteza se nenhuma ação foi executada
                // ou se a sessão terminou com erro explícito sem confirmação.
                val endedWithError = lastResult.startsWith("erro:", ignoreCase = true) ||
                    lastResult.startsWith("falhou", ignoreCase = true)
                val ans = if (!verification.verified &&
                    (executedActions.isEmpty() || endedWithError) &&
                    !rawAns.lowercase(java.util.Locale("pt", "BR")).contains("não consegui confirmar")
                ) {
                    "Tentei concluir, mas não consegui confirmar na tela final ($rawAns)"
                } else {
                    rawAns
                }

                val doneBonus = if (verification.verified) 2.0 else -0.5
                scoredSteps += Pair("done", doneBonus)
                val totalScore = scoredSteps.sumOf { it.second }
                ui.log("[$step·${llm.lastProvider}·⭐${String.format(java.util.Locale.US, "%.1f", totalScore)}·verified=${verification.verified}·epistemic=$epistemic] ${js.optString("thought").take(90)} → done")
                memory += Msg("assistant", ans)
                compactMemoryIfNeeded()
                graph.recordLastCompletedContext(
                    task = task,
                    appPackage = currentApp,
                    answer = ans,
                    actions = executedActions.map { it.first } + "done"
                )
                graph.recordRouteOutcome(
                    task = task,
                    appPackage = currentApp,
                    stepsTaken = scoredSteps,
                    agentDeclaredDone = true,
                    goalVerified = verification.verified
                )
                trajectory.logStep(
                    step,
                    llm.lastProvider,
                    plan,
                    js.optString("thought"),
                    action,
                    args,
                    "done (score=${String.format(java.util.Locale.US, "%.1f", totalScore)}, goal_verified=${verification.verified}, epistemic=$epistemic, reason=${verification.reason})",
                    preActionScreen
                )
                trajectory.endSession(
                    outcome = if (verification.verified) "success" else "unverified_done",
                    answer = ans,
                    agentDeclaredDone = true,
                    goalVerified = verification.verified,
                    verificationReason = verification.reason
                )
                ui.onTaskCompletion(verified = verification.verified, detail = verification.reason)
                return@withContext ans
            }
            if (action == "switch_task") {
                task = args.optString("task", task)
                currentTaskContext = task
                steps.clear()
                scoredSteps.clear()
                executedActions.clear()
                stagnantSteps = 0
                unverifiedDoneCount = 0
                sayCount = 0
                lastActionSig = ""
                preExecutionConfirmed = false
                memory += Msg("user", "[CONVERSA ANTERIOR JÁ CONCLUÍDA]: $task")
                ui.log("🔀 nova tarefa: $task")
                lastResult = "(início da nova tarefa)"
                continue
            }

            // Confirmação por fala natural ANTES de executar o comando planejado (conforme solicitado pelo usuário)
            if (!preExecutionConfirmed && requiresPreExecutionVoiceConfirmation(action, args)) {
                val thoughtText = js.optString("thought")
                var confirmQuestion = buildNaturalSpokenConfirmationPrompt(action, args, plan, thoughtText, task, preActionScreen)
                EmotionEngine.feel(EmotionEngine.Emotion.CAUTELOSO, "confirmando '$action' antes de executar", bump = 0.4)
                ui.log("🎙️❓ [Confirmação Pré-Execução por Voz]: $confirmQuestion")
                var confirmReply = ui.ask(confirmQuestion).trim()
                // Se o usuário perguntou "Qual foi o pedido? O que que você vai fazer?", explica detalhadamente e pergunta se confirma!
                if (isQuestionAboutPlannedAction(confirmReply)) {
                    val detailedExplanation = buildDetailedPlanExplanation(action, args, plan, thoughtText, task)
                    ui.log("🗣️📋 [Explicando o plano ao usuário]: $detailedExplanation")
                    confirmReply = ui.ask(detailedExplanation).trim()
                }
                val (confirmClass, confirmMethod) = classifyConfirmationReply(confirmReply)
                when {
                    confirmClass == ConfirmClass.AFFIRMATIVE -> {
                        preExecutionConfirmed = true
                        AudioTelemetry.onConfirmationResolved(confirmReply, "aceita")
                        AudioTelemetry.onWakeOutcome(productive = true, detail = "confirmacao_aceita")
                        if (confirmMethod == "palavra_chave") VoiceSemantics.confirm.learn(confirmReply, "afirmativo")
                        ui.log("✅🎙️ Confirmado por voz [$confirmMethod] ('$confirmReply') -> executando '$action'")
                    }
                    confirmClass == ConfirmClass.NEGATIVE -> {
                        AudioTelemetry.onConfirmationResolved(confirmReply, "cancelada")
                        if (confirmMethod == "palavra_chave") VoiceSemantics.confirm.learn(confirmReply, "negativo")
                        VoiceSemantics.repudiateLastSemanticAccept("cancelado_na_confirmacao")
                        val cancelAns = "Tudo bem, cancelei e não executei o comando."
                        ui.log("🛑 Comando cancelado na confirmação por voz [$confirmMethod] ('$confirmReply').")
                        trajectory.logStep(step, llm.lastProvider, plan, thoughtText, "cancelled_by_voice_confirm", args, "Cancelado pelo usuário: '$confirmReply'", preActionScreen)
                        trajectory.endSession("cancelled_by_user", cancelAns, agentDeclaredDone = false, goalVerified = true, verificationReason = "Cancelado na confirmação por voz")
                        memory += Msg("assistant", cancelAns)
                        return@withContext cancelAns
                    }
                    confirmClass == ConfirmClass.ADJUST -> {
                        // O usuário respondeu com um ajuste ou complemento por voz (ex: "não, manda só pro ColegaEquipe" ou "fala que é às 10h")
                        AudioTelemetry.onConfirmationResolved(confirmReply, "ajustada")
                        preExecutionConfirmed = true
                        parallelBtwNotes.add("Ajuste falado pelo usuário na confirmação: $confirmReply")
                        lastResult = "usuário respondeu à confirmação com um ajuste: '$confirmReply'. Ajuste os argumentos da ação conforme ele pediu e execute!"
                        ui.log("🔄🎙️ Ajuste recebido na confirmação por voz: '$confirmReply' -> recalculando passo!")
                        continue
                    }
                    else -> {
                        // Sem resposta de voz dentro do tempo limite -> por segurança não executa ações externas às cegas
                        AudioTelemetry.onConfirmationResolved(confirmReply, "sem_resposta")
                        val timeoutAns = "Não ouvi sua resposta, então deixei pausado. Quando quiser é só chamar."
                        ui.log("⏸️ Sem resposta na confirmação por voz — abortando execução por segurança.")
                        trajectory.logStep(step, llm.lastProvider, plan, thoughtText, "unconfirmed_timeout", args, "Sem resposta na confirmação por voz", preActionScreen)
                        trajectory.endSession("unconfirmed_timeout", timeoutAns, agentDeclaredDone = false, goalVerified = true, verificationReason = "Aguardou confirmação por voz sem resposta")
                        memory += Msg("assistant", timeoutAns)
                        return@withContext timeoutAns
                    }
                }
            }

            val isUiAct = isUiNavigationAction(action)
            if (isUiAct) {
                ensureAwake(requireUnlock = true)
            }
            ui.onAgentPhaseChanged(
                when (action) {
                    "web_search" -> AgentPhase.RESEARCHING
                    "remember_fact", "add_rule", "update_rule", "remove_rule" -> AgentPhase.LEARNING
                    else -> AgentPhase.ACTING
                },
                action,
            )
            // Narração natural por etapa gerada pelo próprio LLM (sem frases robóticas fixas)
            val stepSpeech = js.optString("fala").trim()
            if (stepSpeech.isNotEmpty() && action !in setOf("say", "wait_for_user", "ask_user", "ring_phone", "done")) {
                val nowMs = System.currentTimeMillis()
                val canSpeakStep = (step == 1 && sayCount == 0) ||
                    (action != lastSpokenAction && nowMs - lastStepSpeechMs >= 2200L) ||
                    (nowMs - lastStepSpeechMs >= 4500L)
                if (canSpeakStep) {
                    sayCount++
                    lastStepSpeechMs = nowMs
                    lastSpokenAction = action
                    ui.say(stepSpeech)
                }
            }
            val actionStartMs = System.currentTimeMillis()
            lastResult = try { exec(action, args, currentApp) } catch (e: Exception) {
                HealthMonitor.recordError("$action: ${e.message}")
                "erro: ${e.message}"
            }
            val actionDurationMs = System.currentTimeMillis() - actionStartMs

            // Aguarda estabilização da UI para medir o efeito CAUSAL real da ação atual (preActionScreen -> postActionScreen)
            if (isUiAct) {
                delay(650)
            } else {
                delay(180)
            }
            val postActionScreen = withContext(Dispatchers.Main) {
                AgentAccessibilityService.instance?.dumpScreen()
            } ?: preActionScreen
            previousScreenDump = preActionScreen

            val screenChanged = normalizeScreenForDiff(postActionScreen) != normalizeScreenForDiff(preActionScreen)
            val actionFailed = isFailureResult(lastResult) || lastResult.contains("não encontrado")
            val currentSig = "$action:${args.toString().take(80)}"
            val repeatedNonUi = !isUiAct && currentSig == lastActionSig
            lastActionSig = currentSig
            // Loop em ciclo (A→B→A→B) que o teste de "ação repetida" não pegava:
            // se a MESMA assinatura aparecer 3x nos últimos 6 passos, trata como estagnação.
            recentActionSigs.addLast(currentSig)
            while (recentActionSigs.size > 6) recentActionSigs.removeFirst()
            val cycleRepeats = recentActionSigs.count { it == currentSig }
            val inCycle = cycleRepeats >= 3

            // Detecção de progresso real: repetir scroll/tap que muda a tela É progresso legítimo, mas repetir ação sem UI não é!
            val progressMade = !actionFailed && !repeatedNonUi && !inCycle && (!isUiAct || screenChanged)
            if (inCycle) {
                EmotionEngine.feel(EmotionEngine.Emotion.FRUSTRADO, "ciclo: $action repetido ${cycleRepeats}x", bump = 0.5)
                ui.log("🔁 Ciclo detectado: '$action' repetido ${cycleRepeats}x nos últimos 6 passos")
                lastResult += " | [CICLO DETECTADO: você já tentou '$action' com estes argumentos ${cycleRepeats} vezes. " +
                    "NÃO repita; tente um caminho diferente ou encerre com done.]"
            }
            if (progressMade) {
                stagnantSteps = 0
                if (EmotionEngine.frustrationStreak > 0) EmotionEngine.resetStreaks()
            } else {
                stagnantSteps++
                EmotionEngine.feel(
                    if (actionFailed) EmotionEngine.Emotion.PREOCUPADO else EmotionEngine.Emotion.INCERTO,
                    if (actionFailed) "ação '$action' falhou" else "sem progresso em '$action'",
                    bump = 0.4,
                )
            }

            val isStagnant = stagnantSteps >= 2 || actionFailed
            val stepScore = graph.scoreStep(task, currentApp, action, lastResult, screenChanged, isStagnant)
            runCatching {
                MetricsCollector.recordAction(
                    action = action,
                    success = !actionFailed,
                    responseTime = actionDurationMs,
                    confidence = if (progressMade) 0.9 else 0.35,
                    result = lastResult.take(120)
                )
                ThinkingLogger.logPhase(
                    phase = "step_$step",
                    details = js.optString("thought").ifBlank { action },
                    metrics = mapOf("app" to currentApp, "screen_changed" to screenChanged, "score" to stepScore),
                    confidence = if (progressMade) 0.9 else 0.35,
                    actionTaken = action,
                    result = lastResult.take(120)
                )
            }
            // Alimenta o ErrorDetector e, a partir dele, CORRIGE A ROTA NO GRAFO.
            //
            // Duas correções importantes aqui:
            //  1. `confidence` não pode ser 0.35 fixo: o `categorizeError` tem como PRIMEIRA
            //     regra "confidence < 0.5 -> CONFIDENCE_LOW", então todo erro virava
            //     CONFIDENCE_LOW severidade 1 e a classificação não servia para nada.
            //     Agora a confiança reflete o quanto a ação era conhecida (Q do grafo).
            //  2. O `context` precisa trazer as chaves que o categorizeError realmente lê
            //     (previous_state, timeout, verification_failed, expected_app).
            if (actionFailed || isStagnant || inCycle) {
                ui.onAgentPhaseChanged(AgentPhase.RECOVERING_ERROR, action)
                runCatching {
                    val detected = ErrorDetector.detectError(
                        expected = js.optString("thought").ifBlank { "efeito de $action" },
                        actual = lastResult.take(140),
                        context = mapOf(
                            "step" to step,
                            "action" to action,
                            "app" to currentApp,
                            "expected_app" to currentApp,
                            "previous_state" to normalizeScreenForDiff(preActionScreen).take(140),
                            "timeout" to (actionDurationMs > 12_000L),
                            "verification_failed" to (isUiAct && !screenChanged),
                            "screen_changed" to screenChanged,
                            "stagnant_steps" to stagnantSteps,
                            "in_cycle" to inCycle,
                            "repeated" to repeatedNonUi,
                        ),
                        confidence = if (actionFailed) 0.75 else 0.85,
                    )
                    if (detected != null) {
                        MetricsCollector.recordError(detected)
                        failureCountByAction[action] = (failureCountByAction[action] ?: 0) + 1
                        val avoidActions = failureCountByAction
                            .filter { (act, count) ->
                                // Ação de UI precisa falhar 2x; ação não-UI já é evitada na 1ª
                                count >= 2 || !isUiNavigationAction(act)
                            }
                            .keys.toSet()
                        // Penalidade ponderada pela gravidade + rebaixamento imediato da rota ruim + contexto do elemento/estado
                        val stateHint = preActionScreen.lineSequence().drop(1).take(3).joinToString(" | ")
                        val penaltyReport = graph.penalizeForDetectedError(
                            task = task,
                            appPackage = currentApp,
                            action = action,
                            errorType = detected.type.name,
                            severity = detected.severity,
                            stateContext = "$args @ $stateHint",
                        )
                        ui.log("🧠⚠️ [${detected.type} sev=${detected.severity}] $penaltyReport")
                        // CORREÇÃO DE ROTA ANTES DA ESTAGNAÇÃO: já no 1º erro o grafo propõe
                        // um caminho alternativo, em vez de esperar 2 passos travados.
                        val alt = if (avoidActions.isEmpty()) null
                            else graph.suggestAlternativeRoute(task, currentApp, avoidActions)
                        lastResult += "\n⚠️ ERRO DETECTADO [${detected.type} gravidade ${detected.severity}/5]: " +
                            "${detected.reason}\n💡 ${detected.suggestion}"
                        if (alt != null) {
                            lastResult += "\n$alt"
                            ui.log("🧭 Rota alternativa sugerida pelo grafo")
                        }
                    }
                }
            }
            scoredSteps += Pair(action, stepScore)
            executedActions += Pair(action, lastResult)
            ui.log("[$step·${llm.lastProvider}·score=${String.format(java.util.Locale.US, "%+.1f", stepScore)}·Δtela=$screenChanged] ${js.optString("thought").take(85)} → $action $args")

            trajectory.logStep(
                step,
                llm.lastProvider,
                plan,
                js.optString("thought"),
                action,
                args,
                "$lastResult [score=$stepScore, screen_changed=$screenChanged, progress=$progressMade]",
                postActionScreen
            )

            // Ponto de retomada: se o app morrer agora, a ADA volta daqui em vez do zero.
            TaskCheckpoint.save(
                taskId = trajectory.currentSessionId(),
                goal = task,
                step = step,
                currentApp = currentApp,
                lastAction = action,
                lastResult = lastResult,
                executedActions = executedActions.map { it.first },
                failedActions = failureCountByAction.keys,
                notes = parallelBtwNotes,
                startedAtMs = taskStartedAtMs,
                nextHypothesis = js.optString("plan"),
            )

            if (stagnantSteps == 2) {
                val healed = withContext(Dispatchers.Main) {
                    AgentAccessibilityService.instance?.dismissSystemErrorDialogIfAny()
                }
                if (stagnantAskCount < 1) {
                    stagnantAskCount++
                    ui.onAgentPhaseChanged(AgentPhase.ATTENTIVE_LISTENING, "aguardando orientação")
                    val prompt = buildContextualStagnationPrompt(currentApp, action, js.optString("thought"))
                    val guidance = ui.ask(prompt)
                    when (classifyStagnationGuidance(guidance)) {
                        StagnationCommand.CANCEL -> {
                            val ans = "Tudo bem, cancelei a tarefa como você pediu."
                            graph.recordRouteOutcome(
                                task = task,
                                appPackage = currentApp,
                                stepsTaken = scoredSteps,
                                agentDeclaredDone = false,
                                goalVerified = false
                            )
                            trajectory.endSession("cancelled_by_user_stagnation", ans, agentDeclaredDone = false, goalVerified = false, verificationReason = "Cancelado pelo usuário após estagnação")
                            return@withContext ans
                        }
                        StagnationCommand.EMPTY -> {
                            val appLabel = currentApp.substringAfterLast('.').ifBlank { "tela atual" }
                            val ans = "Não recebi resposta após travar em $appLabel. Encerrei a tarefa para não ficar em loop."
                            graph.recordRouteOutcome(
                                task = task,
                                appPackage = currentApp,
                                stepsTaken = scoredSteps,
                                agentDeclaredDone = false,
                                goalVerified = false
                            )
                            trajectory.endSession("stagnation_no_reply", ans, agentDeclaredDone = false, goalVerified = false, verificationReason = "Sem resposta do usuário na estagnação")
                            return@withContext ans
                        }
                        StagnationCommand.RESTART -> {
                            if (restartCount < 2) {
                                restartCount++
                                withContext(Dispatchers.Main) { AgentAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_HOME) }
                                delay(700)
                                steps.clear()
                                scoredSteps.clear()
                                executedActions.clear()
                                previousScreenDump = ""
                                lastActionSig = ""
                                stagnantSteps = 0
                                stagnantAskCount = 0
                                step = 0
                                lastResult = "(reiniciado do zero a pedido do usuário — tentativa $restartCount/2)"
                                ui.log("🔄 [Reinício] Voltando à tela inicial e recomeçando '$task' (tentativa $restartCount/2).")
                                continue
                            } else {
                                val ans = "Já reiniciei 2 vezes e a tela continua travada. Encerrei por segurança."
                                graph.recordRouteOutcome(
                                    task = task,
                                    appPackage = currentApp,
                                    stepsTaken = scoredSteps,
                                    agentDeclaredDone = false,
                                    goalVerified = false
                                )
                                trajectory.endSession("max_restarts_reached", ans, agentDeclaredDone = false, goalVerified = false, verificationReason = "Limite de 2 reinícios atingido")
                                return@withContext ans
                            }
                        }
                        StagnationCommand.BACK -> {
                            withContext(Dispatchers.Main) { AgentAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_BACK) }
                            delay(500)
                            lastResult += " | USUÁRIO PEDIU PARA VOLTAR UMA TELA (auto-recovery=$healed). Tente outro caminho."
                            stagnantSteps = 0
                        }
                        StagnationCommand.NEW_TASK -> {
                            val cleanNewTask = ambient.detectWakeName(guidance)?.second?.ifBlank { guidance } ?: guidance
                            ui.log("🔀 [Preempção na Estagnação] Novo comando detectado: '$cleanNewTask'")
                            task = cleanNewTask
                            currentTaskContext = task
                            steps.clear()
                            scoredSteps.clear()
                            executedActions.clear()
                            stagnantSteps = 0
                            stagnantAskCount = 0
                            unverifiedDoneCount = 0
                            sayCount = 0
                            lastActionSig = ""
                            preExecutionConfirmed = false
                            lastResult = "(iniciando novo pedido falado pelo usuário)"
                            continue
                        }
                        StagnationCommand.GUIDANCE -> {
                            lastResult += " | CORREÇÃO DE ROTA DO USUÁRIO: '$guidance' (auto-recovery=$healed). Siga a instrução do usuário agora."
                            stagnantSteps = 0
                        }
                    }
                } else {
                    val backed = withContext(Dispatchers.Main) {
                        AgentAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    lastResult += " | [Auto-recuperação após nova estagnação: voltou uma tela (healed=$healed, back=$backed). Tente uma abordagem diferente.]"
                }
            } else if (stagnantSteps >= 4) {
                withContext(Dispatchers.Main) { AgentAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_HOME) }
                val ans = "Parei e voltei à tela inicial: 4 tentativas sem progresso real de estado ($action)."
                graph.recordRouteOutcome(
                    task = task,
                    appPackage = currentApp,
                    stepsTaken = scoredSteps,
                    agentDeclaredDone = false,
                    goalVerified = false
                )
                trajectory.endSession("stuck_no_progress", ans, agentDeclaredDone = false, goalVerified = false, verificationReason = "4 passos sem progresso")
                return@withContext ans
            }
        }
        val ans = "Parei após 25 passos sem concluir."
        trajectory.endSession("max_steps_reached", ans, agentDeclaredDone = false, goalVerified = false, verificationReason = "Limite de 25 passos atingido")
        ans
    }

    private fun isQuestionAboutPlannedAction(reply: String): Boolean {
        val r = reply.lowercase(java.util.Locale("pt", "BR")).trim()
        if (r.isEmpty() || r == "(sem resposta)") return false
        return listOf(
            "qual foi", "qual é", "qual e", "o que você vai", "o que voce vai",
            "que que você vai", "que que voce vai", "o que vai fazer", "como você vai",
            "me explica", "repete", "qual ação", "qual acao", "qual mensagem", "pra quem"
        ).any { r.contains(it) }
    }

    private fun buildDetailedPlanExplanation(
        action: String,
        a: JSONObject,
        plan: String,
        thought: String,
        task: String
    ): String {
        val summary = thought.ifBlank { plan.ifBlank { "Executar $action" } }
        return "Eu entendi o seguinte: $summary. Posso fazer isso agora ou você prefere mudar alguma coisa?"
    }

    /**
     * Normaliza a resposta falada para comparação estável: minúsculas, sem acento e sem pontuação.
     * O reconhecedor devolve "Sim.", "Pode fazer!", "Tá bom," — antes a pontuação fazia o "sim"
     * não casar com nenhum padrão e o aceite virava "ajuste", recalculando o passo sem executar.
     */
    private fun normalizeSpokenReply(reply: String): String {
        val lower = reply.lowercase(java.util.Locale("pt", "BR"))
        val noAccent = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccent.replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** Casa a expressão por palavra inteira (nunca dentro de outra palavra). */
    private fun containsPhrase(norm: String, phrase: String): Boolean =
        norm.isNotEmpty() && " $norm ".contains(" $phrase ")

    /** Negação explícita: "nao", "cancela", "esquece"… ("para" só quando é a frase inteira). */
    private fun hasExplicitNegation(norm: String): Boolean {
        val tokens = norm.split(" ").filter { it.isNotBlank() }
        val negWords = listOf(
            "nao", "negativo", "cancela", "cancelar", "cancele", "aborta", "abortar",
            "esquece", "esqueca", "nunca", "jamais"
        )
        if (negWords.any { containsPhrase(norm, it) }) return true
        // "para" / "pare" / "deixa" só valem como cancelamento quando são a resposta curta inteira
        // (assim "pode mandar para o ColegaEquipe" continua sendo um SIM, e não um cancelamento!)
        val shortStop = listOf("para", "pare", "parar", "deixa", "deixa quieto", "deixa pra la")
        return tokens.size <= 3 && shortStop.any { norm == it || norm.startsWith("$it ") }
    }

    enum class ConfirmClass { AFFIRMATIVE, NEGATIVE, QUESTION, ADJUST, EMPTY }

    /**
     * Classifica a resposta falada na confirmação pré-execução em CASCATA:
     * palavra-chave (0 ms) -> léxico/semântico do [VoiceSemantics] (<1 ms, cache local).
     *
     * Travas de segurança, porque este é o portão antes de apagar arquivo / mandar mensagem / Pix:
     *  - negação explícita NUNCA é lida como aceite, em nenhum nível da cascata;
     *  - aceite semântico só vale para resposta curta (até 6 palavras) — resposta longa
     *    continua sendo tratada como ajuste, que é o comportamento conservador;
     *  - cancelamento pode vir do nível semântico sem restrição (cancelar é a direção segura).
     */
    private fun classifyConfirmationReply(reply: String): Pair<ConfirmClass, String> {
        val norm = normalizeSpokenReply(reply)
        if (norm.isEmpty() || norm == "sem resposta") return ConfirmClass.EMPTY to "vazio"
        if (isQuestionAboutPlannedAction(reply)) return ConfirmClass.QUESTION to "palavra_chave"
        if (isAffirmativeConfirmation(reply)) return ConfirmClass.AFFIRMATIVE to "palavra_chave"
        if (isNegativeOrCancelConfirmation(reply)) return ConfirmClass.NEGATIVE to "palavra_chave"
        if (!prefs.semanticConfirmEnabled) return ConfirmClass.ADJUST to "semantic_disabled"

        val m = VoiceSemantics.confirm.classify(
            reply,
            setOf("afirmativo", "negativo", "pergunta_plano"),
        )
        if (m != null) {
            when (m.label) {
                "negativo" -> return ConfirmClass.NEGATIVE to m.method
                "pergunta_plano" -> return ConfirmClass.QUESTION to m.method
                "afirmativo" -> {
                    val tokens = norm.split(" ").count { it.isNotBlank() }
                    if (!hasExplicitNegation(norm) && tokens <= 6) {
                        if (m.method == "semantico") VoiceSemantics.rememberSemanticAccept(reply)
                        ui.log("🧠🎙️ Aceite reconhecido por ${m.method} ~${String.format(java.util.Locale.US, "%.2f", m.score)} (parecido com \"${m.matched}\")")
                        return ConfirmClass.AFFIRMATIVE to m.method
                    }
                }
            }
        }
        return ConfirmClass.ADJUST to "ajuste"
    }

    private fun isAffirmativeConfirmation(reply: String): Boolean {
        val norm = normalizeSpokenReply(reply)
        if (norm.isEmpty() || norm == "sem resposta") return false
        if (isQuestionAboutPlannedAction(reply)) return false
        if (hasExplicitNegation(norm)) return false
        val affirmatives = listOf(
            "sim", "pode", "pode sim", "pode fazer", "pode mandar", "pode enviar", "pode abrir",
            "pode ir", "pode seguir", "pode confirmar", "pode apagar", "pode excluir", "pode desativar",
            "confirmo", "confirmado", "confirma", "vou confirmar", "confirmo a exclusao", "autorizo", "autorizado",
            "ok", "okay", "ok pode", "manda", "manda ver", "vai la", "vai", "segue", "segue o jogo",
            "isso", "isso mesmo", "exatamente", "beleza", "blz", "bora", "claro", "positivo",
            "ta bom", "tudo bem", "certo", "correto", "perfeito", "uhum", "aham", "aha", "feito",
            "apaga", "exclui", "desativa", "faz", "faz isso", "faz as duas", "fazer", "vou fazer", "vou fazer as duas",
            "executa", "libera", "valeu"
        )
        return affirmatives.any { containsPhrase(norm, it) }
    }

    private fun isNegativeOrCancelConfirmation(reply: String): Boolean {
        val norm = normalizeSpokenReply(reply)
        if (norm.isEmpty() || norm == "sem resposta") return false
        if (!hasExplicitNegation(norm)) return false
        // Negação CURTA (até 3 palavras) = cancelar de fato: "não", "não faz", "cancela tudo".
        // Negação COM complemento ("não, manda pro ColegaEquipe") NÃO é cancelamento: é um ajuste,
        // e cai no ramo seguinte do fluxo para recalcular os argumentos da ação.
        val tokens = norm.split(" ").filter { it.isNotBlank() }
        if (tokens.size > 3) return false
        val cancelStarters = listOf(
            "nao", "negativo", "cancela", "cancelar", "cancele", "aborta", "abortar",
            "esquece", "esqueca", "para", "pare", "parar", "deixa", "melhor"
        )
        return tokens.first() in cancelStarters
    }

    /**
     * Define quais ações exigem confirmação natural por fala antes de começar a execução.
     * Consultas passivas (telemetria, leitura de sensores/arquivos/regras/memória) respondem direto;
     * ações que atuam no celular ou enviam mensagens/tocam na tela pedem confirmação falada antes!
     */
    /**
     * Ações reversíveis e explícitas (abrir app, tocar na tela, digitar, mídia, alarme) executam direto
     * com agilidade; apenas ações externas/irreversíveis (enviar WhatsApp, responder notificação, ligar, gravar)
     * pedem confirmação falada rápida antes.
     */
    private fun requiresPreExecutionVoiceConfirmation(action: String, a: JSONObject): Boolean {
        if (action == "batch") {
            val arr = a.optJSONArray("steps") ?: return false
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                val subAction = sub.optString("action")
                if (subAction in setOf("send_whatsapp", "reply_notification", "call", "end_call", "start_recording")) {
                    return true
                }
            }
            return false
        }
        return action in setOf(
            "send_whatsapp", "call", "end_call", "reply_notification", "start_recording"
        )
    }

    /**
     * Monta uma frase curta, humana, específica e fluida para o X0 falar em voz alta antes de executar o comando.
     * SEMPRE diz claramente O QUE vai fazer (contato, mensagem, app ou objetivo do thought/plan), nunca uma frase genérica vazia!
     */
    private fun buildNaturalSpokenConfirmationPrompt(
        action: String,
        a: JSONObject,
        plan: String,
        thought: String,
        task: String,
        screenDump: String = ""
    ): String {
        val cleanThought = thought.trim().trimEnd('.')
        return when (action) {
            "send_whatsapp" -> {
                val rec = a.optString("recipient", "o contato")
                val txt = a.optString("text", "").take(90)
                "Mando no WhatsApp para $rec dizendo: \"$txt\"?"
            }
            "batch" -> {
                val arr = a.optJSONArray("steps")
                val waRecipients = mutableListOf<String>()
                var waSampleText = ""
                var typedText = ""
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val sAct = item.optString("action")
                        val subArgs = item.optJSONObject("args") ?: JSONObject()
                        if (sAct == "send_whatsapp") {
                            val r = subArgs.optString("recipient")
                            if (r.isNotBlank()) waRecipients.add(r)
                            if (waSampleText.isBlank()) waSampleText = subArgs.optString("text").take(85)
                        } else if (sAct == "type" && typedText.isBlank()) {
                            typedText = subArgs.optString("text").take(85)
                        }
                    }
                }
                when {
                    waRecipients.isNotEmpty() -> {
                        val recs = waRecipients.joinToString(" e ")
                        "Vou mandar no WhatsApp para $recs dizendo: \"$waSampleText\". Posso enviar?"
                    }
                    typedText.isNotBlank() -> {
                        if (cleanThought.isNotBlank()) {
                            "$cleanThought. A mensagem será: \"$typedText\". Posso enviar?"
                        } else {
                            "Vou digitar e enviar: \"$typedText\". Posso confirmar?"
                        }
                    }
                    cleanThought.isNotBlank() -> "$cleanThought. Posso fazer isso agora?"
                    plan.isNotBlank() -> "Meu plano é: $plan. Posso executar?"
                    else -> "Vou executar esse comando na tela agora. Posso continuar?"
                }
            }
            "open_app" -> {
                val appName = a.optString("name", "aplicativo")
                if (cleanThought.isNotBlank() && cleanThought.length <= 110) {
                    "$cleanThought. Posso continuar?"
                } else {
                    "Vou abrir o $appName agora. Posso continuar?"
                }
            }
            "call" -> {
                val num = a.optString("number", "o número")
                "Ligo agora para $num?"
            }
            "reply_notification" -> {
                val txt = a.optString("text", "").take(75)
                "Respondo a notificação com: \"$txt\"?"
            }
            "set_alarm" -> {
                val h = a.optInt("hour", 7)
                val m = a.optInt("minute", 0)
                val mStr = if (m > 0) " e $m" else " horas"
                "Programo o alarme para as $h$mStr?"
            }
            "set_brightness" -> "Ajusto o brilho da tela?"
            "set_dnd" -> "Altero o modo Não Perturbe?"
            "start_recording" -> "Posso iniciar a gravação agora?"
            "type" -> {
                val txt = a.optString("text", "").take(85)
                if (cleanThought.isNotBlank()) {
                    "$cleanThought, digitando: \"$txt\". Posso fazer?"
                } else {
                    "Vou digitar \"$txt\" no campo de texto. Posso continuar?"
                }
            }
            "tap", "long_press" -> {
                val label = a.optString("label", a.optString("text", "")).take(45)
                when {
                    cleanThought.isNotBlank() -> "$cleanThought. Posso executar?"
                    label.isNotBlank() && label != "LinearLayout" && label != "View" ->
                        "Vou tocar em \"$label\" na tela. Posso continuar?"
                    plan.isNotBlank() -> "Vou executar: $plan. Posso continuar?"
                    else -> "Vou tocar nesse item da tela agora. Posso continuar?"
                }
            }
            else -> if (cleanThought.isNotBlank()) "$cleanThought. Posso executar?" else "Entendi seu pedido. Posso executar agora?"
        }
    }

    private suspend fun executeGuardedTapOrLongPress(action: String, a: JSONObject, currentApp: String): String {
        val svc = AgentAccessibilityService.instance ?: throw IllegalStateException("acessibilidade desativada")
        val id = a.getInt("id")
        val labelHint = a.optString("label", a.optString("text", ""))
        val targetDesc = withContext(Dispatchers.Main) { svc.describeNode(id, labelHint) }
        // No WhatsApp, evita tocar acidentalmente no botão "Mensagem de voz" achando que é "Enviar"
        if (currentApp.contains("whatsapp", ignoreCase = true) &&
            (targetDesc.contains("Mensagem de voz", ignoreCase = true) || labelHint.contains("Mensagem de voz", ignoreCase = true))
        ) {
            val sendRes = withContext(Dispatchers.Main) { svc.clickSendButton() }
            if (sendRes == "enviado") return "enviado"
            return "ignorado: o campo de texto já estava vazio (a mensagem já foi enviada)"
        }
        val isKeyboardBackspace = id >= 40 &&
            (labelHint.equals("Excluir", ignoreCase = true) || targetDesc.contains("desc=\"Excluir\"", ignoreCase = true))
        val intercept = rules.evaluateUiActionBeforeExecution(action, targetDesc, currentApp, currentTaskContext)

        if (!isKeyboardBackspace && !intercept.allowed && !intercept.requiresConfirmation) {
            ui.log(intercept.message)
            return intercept.message
        }

        if (!isKeyboardBackspace && intercept.requiresConfirmation) {
            ui.log("⚠️ Gatekeeper CUA interceptou '$action' em '$targetDesc' -> solicitando confirmação ao usuário!")
            var reply = ui.ask("⚠️ ${intercept.message}")
            if (reply.isBlank()) {
                reply = ui.ask("Não peguei sua resposta — pode confirmar '$targetDesc' ou quer mudar?")
            }
            val (cls, _) = classifyConfirmationReply(reply)
            when (cls) {
                ConfirmClass.AFFIRMATIVE -> {
                    intercept.matchedRule?.let { r ->
                        for (kw in r.triggerKeywords) {
                            rules.grantSessionApproval("${r.id}:${currentApp.lowercase()}:$kw")
                        }
                    }
                    ui.log("✅ Ação crítica confirmada pelo usuário ('$reply') -> executando '$action' em '$targetDesc'.")
                }
                ConfirmClass.ADJUST, ConfirmClass.QUESTION -> {
                    val msg = "usuário respondeu à confirmação com ajuste/orientação: '$reply'. Adapte o plano imediatamente para atender a essa instrução!"
                    ui.log("🔄 $msg")
                    return msg
                }
                ConfirmClass.NEGATIVE, ConfirmClass.EMPTY -> {
                    val msg = "Ação crítica cancelada pelo usuário ('$reply') antes de tocar em '$targetDesc'."
                    ui.log("🛑 $msg")
                    return msg
                }
            }
        }

        return if (action == "long_press") {
            main { svc.longPress(id) }
        } else {
            main { svc.tap(id, labelHint) }
        }
    }

    private suspend fun exec(action: String, a: JSONObject, currentApp: String = ""): String {
        val svc = AgentAccessibilityService.instance
        fun needSvc() = svc ?: throw IllegalStateException("acessibilidade desativada")
        return when (action) {
            "device_status" -> {
                val t = HealthMonitor.getTelemetry(ctx)
                val b = t.optInt("battery_pct")
                val ch = if (t.optBoolean("charging")) "carregando" else "fora da tomada"
                val rFree = t.optLong("ram_avail_mb")
                val rTot = t.optLong("ram_total_mb")
                val net = t.optString("network")
                val temp = t.optDouble("temp_c")
                val sensors = ProactiveWatcher.getSensorSummary()
                val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                val freeGb = statFs.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                val totalGb = statFs.totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                val androidVer = "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) em ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                "$androidVer | Armazenamento Livre: ${String.format(java.util.Locale.US, "%.1f", freeGb)} GB de ${String.format(java.util.Locale.US, "%.1f", totalGb)} GB | Bateria: $b% ($ch), RAM: ${rFree}MB/${rTot}MB livres, Rede: $net, Temp: ${temp}°C | $sensors"
            }
            "scan_storage_files" -> {
                val minMb = a.optLong("min_mb", 50L).coerceAtLeast(1L)
                val typeFilter = a.optString("type", "video").lowercase()
                val limit = a.optInt("limit", 20).coerceIn(1, 50)
                scanStorageFiles(minMb, typeFilter, limit)
            }
            "file_hash" -> {
                val p = a.optString("path", "").trim()
                if (p.isBlank()) "erro: informe o parâmetro 'path'" else computeFileHash(p)
            }
            "add_rule", "update_rule" -> {
                rules.addOrUpdateRule(
                    title = a.optString("title", a.optString("name", "")),
                    instruction = a.optString("instruction", a.optString("rule", "")),
                    modeStr = a.optString("mode", "BEHAVIOR_PROMPT"),
                    targetPkg = a.optString("package", "*"),
                    keywordsCsv = a.optString("keywords", ""),
                    ruleId = a.optString("id", "").ifBlank { null }
                )
            }
            "toggle_rule" -> {
                val id = a.optString("id", a.optString("title", ""))
                val en = if (a.has("enabled")) a.optBoolean("enabled", true) else null
                rules.toggleRule(id, en)
            }
            "remove_rule" -> rules.removeRule(a.optString("id", a.optString("title", "")))
            "list_rules" -> rules.listRulesSummary()
            "wait_for_user" -> {
                val prompt = a.optString("prompt", "Estou aguardando. Me avise quando puder continuar.")
                HealthMonitor.markStep(0, "wait_for_user")
                val reply = ui.ask(prompt)
                HealthMonitor.markStep(0, "retomado")
                "usuário retomou após espera: $reply"
            }
            "batch" -> {
                // Medido: 42 das 75 falhas de batch chegaram com args COMPLETAMENTE VAZIO
                // ({}). Passa pelo sanitizador do IdeaToActionGraph para inferir 'action' ausente
                // e devolver formato exato quando realmente vier vazio.
                val sanitized = IdeaToActionGraph.sanitizeBatchArgs(a)
                val arr = sanitized?.optJSONArray("steps")
                    ?: return "erro: você mandou batch com args vazio. O formato é " +
                        "{\"action\":\"batch\",\"args\":{\"steps\":[" +
                        "{\"action\":\"tap\",\"args\":{\"id\":12}}," +
                        "{\"action\":\"type\",\"args\":{\"id\":12,\"text\":\"oi\"}}]}}. " +
                        "Se não souber os ids, NÃO use batch: faça um tap por vez relendo a tela."
                if (arr.length() == 0) return "erro: batch exige array 'steps' não vazio"
                val results = mutableListOf<String>()
                for (i in 0 until arr.length().coerceAtMost(4)) {
                    val item = arr.optJSONObject(i) ?: continue
                    var subAct = item.optString("action").trim()
                    val subArgs = item.optJSONObject("args") ?: item
                    // Auto-reparo para quando o LLM omite "action" em steps do batch
                    if (subAct.isBlank()) {
                        subAct = when {
                            subArgs.has("text") && subArgs.has("id") -> "type"
                            subArgs.has("id") || subArgs.has("label") -> "tap"
                            subArgs.has("name") -> "open_app"
                            subArgs.has("direction") -> "scroll"
                            else -> ""
                        }
                    }
                    if (subAct.isBlank() || subAct == "batch" || subAct == "done") break
                    val r = exec(subAct, subArgs, currentApp)
                    results += "$subAct:$r"
                    if (r.startsWith("erro:") || r.startsWith("🛑") || r.contains("cancelada")) break
                    delay(380)
                }
                if (results.isEmpty()) "erro: batch sem sub-ações válidas em 'steps'" else results.joinToString(" -> ")
            }
            "configure_sensors" -> {
                val modeReq = a.optString("mode", ProactiveWatcher.sensorMode)
                prefs.sensorMode = modeReq
                ProactiveWatcher.configureSensors(
                    ctx = ctx,
                    mode = modeReq,
                    light = a.optBoolean("light", ProactiveWatcher.enableLight),
                    accel = a.optBoolean("accelerometer", ProactiveWatcher.enableAccel),
                    proximity = a.optBoolean("proximity", ProactiveWatcher.enableProximity),
                    gyro = a.optBoolean("gyroscope", ProactiveWatcher.enableGyro),
                    steps = a.optBoolean("steps", ProactiveWatcher.enableSteps),
                    autoSilent = a.optBoolean("auto_silent", ProactiveWatcher.autoSilentInDarkOrPocket),
                    keepCriticalOnly = a.optBoolean("keep_critical_only", false)
                )
            }
            "configure_voice_live" -> {
                if (a.has("auto_intent_threshold")) {
                    val th = a.optDouble("auto_intent_threshold", 3.8).coerceIn(2.0, 8.5)
                    ambient.autoIntentThreshold = th
                    prefs.autoIntentThreshold = th
                }
                if (a.has("pre_exec_confirm")) {
                    prefs.preExecVoiceConfirm = a.optBoolean("pre_exec_confirm", true)
                }
                if (a.has("wake_word")) {
                    prefs.wakeWord = a.optBoolean("wake_word", true)
                    BubbleService.instance?.syncWakeWord()
                }
                if (a.has("speak")) {
                    prefs.speak = a.optBoolean("speak", true)
                }
                if (a.has("follow_up_sec")) {
                    val sec = a.optLong("follow_up_sec", 45L).coerceIn(10L, 180L)
                    ambient.openFollowUpWindow(sec * 1000L)
                }
                "Voz Live configurada: limiar_ativacao=${ambient.autoIntentThreshold}, confirmacao_falada=${prefs.preExecVoiceConfirm}, wake_word=${prefs.wakeWord}, speak=${prefs.speak}"
            }
            "post_notification" -> {
                val title = a.optString("title", "Recado do XZERO")
                val text = a.optString("text", a.optString("message", ""))
                val silent = a.optBoolean("silent", true)
                if (silent) {
                    BubbleService.instance?.suppressNextTts = true
                }
                ProactiveWatcher.postAgentNotification(ctx, title, text, silent)
            }
            "ring_phone" -> {
                val sec = a.optInt("seconds", 4).coerceIn(2, 10)
                playFindPhoneSound(sec)
                "Alerta sonoro tocando por ${sec}s no canal de alarme."
            }
            "get_location" -> {
                val fineOk = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarseOk = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!fineOk && !coarseOk) {
                    "erro: permissão de localização não concedida"
                } else {
                    runCatching {
                        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                        val providers = listOf(
                            android.location.LocationManager.GPS_PROVIDER,
                            android.location.LocationManager.NETWORK_PROVIDER,
                            android.location.LocationManager.PASSIVE_PROVIDER
                        )
                        val loc = providers.mapNotNull { p ->
                            runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                        }.maxByOrNull { it.time }
                        if (loc == null) {
                            "erro: nenhuma posição GPS/rede recente disponível"
                        } else {
                            val addrStr = runCatching {
                                @Suppress("DEPRECATION")
                                val addrs = android.location.Geocoder(ctx, java.util.Locale("pt", "BR"))
                                    .getFromLocation(loc.latitude, loc.longitude, 1)
                                val ad = addrs?.firstOrNull()
                                if (ad != null) {
                                    listOfNotNull(
                                        ad.thoroughfare?.let { st -> if (ad.subThoroughfare != null) "$st, ${ad.subThoroughfare}" else st },
                                        ad.subLocality,
                                        ad.locality ?: ad.subAdminArea,
                                        ad.adminArea,
                                        ad.postalCode?.let { "CEP $it" }
                                    ).joinToString(" - ")
                                } else ""
                            }.getOrDefault("")
                            val coords = String.format(java.util.Locale.US, "lat=%.6f, lon=%.6f, precisão=%.0fm", loc.latitude, loc.longitude, loc.accuracy)
                            if (addrStr.isNotBlank()) "$addrStr ($coords)" else coords
                        }
                    }.getOrElse { "erro ao obter localização: ${it.message}" }
                }
            }
            "read_file_text" -> {
                val path = a.optString("path", "").trim()
                val maxChars = a.optInt("max_chars", 4000).coerceIn(200, 12000)
                if (path.isBlank()) {
                    "erro: informe o caminho 'path' do arquivo"
                } else {
                    runCatching {
                        val f = java.io.File(path)
                        if (!f.exists() || !f.isFile) "erro: arquivo não encontrado em $path"
                        else f.readText(Charsets.UTF_8).take(maxChars)
                    }.getOrElse { "erro ao ler arquivo: ${it.message}" }
                }
            }
            "move_avatar" -> {
                val dir = a.optString("direction", a.optString("target", "centro"))
                val moved = withContext(Dispatchers.Main) {
                    BubbleService.instance?.moveAvatarByCommand(dir) == true
                }
                if (moved) "Avatar movido para '$dir' com animação fluida."
                else "erro: não foi possível mover o avatar para '$dir'"
            }
            "start_recording" -> {
                val reason = a.optString("reason", "conversa importante")
                ProactiveWatcher.startAudioRecording(ctx, reason)
            }
            "stop_recording" -> {
                ProactiveWatcher.stopAudioRecording()
            }
            "add_watch" -> {
                val source = a.optString("source", "any")
                val filter = a.optString("filter", "")
                val instruction = a.optString("instruction", "Avisar o usuário e sugerir próximo passo")
                val autoOpen = a.optBoolean("auto_open", false)
                ProactiveWatcher.addWatch(source, filter, instruction, autoOpen)
            }
            "list_watches" -> ProactiveWatcher.listWatches()
            "remove_watch" -> ProactiveWatcher.removeWatch(a.optString("filter", ""))
            "web_search" -> ProactiveWatcher.quickWebSearch(a.optString("query", ""))
            "mcp_list_servers" -> mcp.listServersSummary()
            "mcp_add_server" -> mcp.addOrUpdateServer(
                name = a.optString("name", ""),
                url = a.optString("url", ""),
                authToken = a.optString("token", ""),
                enabled = a.optBoolean("enabled", true)
            )
            "mcp_remove_server" -> mcp.removeServer(a.optString("name", ""))
            "mcp_sync_servers" -> mcp.syncAllServers()
            "mcp_call" -> mcp.callTool(
                serverName = a.optString("server", ""),
                toolName = a.optString("tool", ""),
                arguments = a.optJSONObject("arguments") ?: JSONObject()
            )
            "remember_fact" -> {
                graph.upsertFact(
                    a.optString("subject", "Usuário"),
                    a.optString("relation", "info"),
                    a.optString("target", "")
                )
            }
            "query_memory" -> graph.retrieveContext(a.optString("query", "")).ifEmpty { graph.dumpSummary() }
            "media_control" -> {
                val cmd = a.optString("command", "play_pause").lowercase()
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val keyCode = when (cmd) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                "mídia executada: $cmd"
            }
            "inspect_screen_visual" -> {
                val b64 = needSvc().captureScreenBase64()
                if (!b64.isNullOrBlank()) {
                    pendingVisualBase64 = b64
                    "screenshot real da tela capturado (${b64.length / 1024} KB base64) e anexado ao próximo turno para inspeção visual (epistemic=OBSERVED)"
                } else {
                    "erro: falha ao capturar screenshot da tela"
                }
            }
            "capture_camera" -> {
                val facingBack = a.optString("facing", "back").lowercase() != "front"
                BubbleService.instance?.ensureCameraForegroundService()
                val bmp = CameraVision.captureSnapshot(ctx, facingBack)
                if (bmp != null) {
                    val b64 = CameraVision.bitmapToBase64(bmp, 75)
                    pendingVisualBase64 = b64
                    "foto capturada com sucesso (${bmp.width}x${bmp.height}, ${b64.length} chars base64) e anexada ao próximo turno (epistemic=OBSERVED)"
                } else {
                    "falha ao acessar câmera ou permissão pendente"
                }
            }
            "open_app" -> openApp(a.getString("name"), currentApp)
            "tap" -> executeGuardedTapOrLongPress("tap", a, currentApp)
            "long_press" -> executeGuardedTapOrLongPress("long_press", a, currentApp)
            "type" -> main { needSvc().type(a.getInt("id"), a.getString("text")) }
            "scroll" -> main { needSvc().scroll(a.optString("direction", "down")) }
            "back" -> main { needSvc().global(AccessibilityService.GLOBAL_ACTION_BACK) }
            "home" -> main { needSvc().global(AccessibilityService.GLOBAL_ACTION_HOME) }
            "recents" -> main { needSvc().global(AccessibilityService.GLOBAL_ACTION_RECENTS) }
            "notifications" -> main { needSvc().global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
            "quick_settings" -> main { needSvc().global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) }
            "read_notifications" -> NotifListener.instance?.summary() ?: "acesso a notificações desativado"
            "reply_notification" -> NotifListener.instance?.reply(a.getInt("id"), a.getString("text")) ?: "acesso a notificações desativado"
            "call" -> {
                val n = a.getString("number").filter { it.isDigit() || it == '+' }
                start(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n"))); "ligando para $n"
            }
            "answer_call" -> {
                val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
                @Suppress("DEPRECATION") tm.acceptRingingCall(); "chamada atendida"
            }
            "end_call" -> {
                val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
                @Suppress("DEPRECATION") val ok = tm.endCall()
                if (ok) "chamada encerrada" else "nenhuma chamada encerrada"
            }
            "send_whatsapp" -> {
                val rawRec = a.getString("recipient")
                val text = a.getString("text")
                val autoDispatch = a.optBoolean("dispatch", true)
                var digits = rawRec.filter { it.isDigit() }
                if (digits.length in 8..11) {
                    if (digits.length in 8..9) digits = "11$digits" // DDD São Paulo padrão
                    digits = "55$digits"
                } else if (digits.isEmpty()) {
                    // Tenta achar o contato pelo nome
                    val c = findContactNumber(rawRec)
                    if (c != null) digits = c.filter { it.isDigit() }
                }
                if (digits.length >= 10 && !digits.startsWith("55")) digits = "55$digits"
                val url = if (digits.length >= 10) {
                    "https://wa.me/$digits?text=" + Uri.encode(text)
                } else {
                    "https://wa.me/?text=" + Uri.encode(text)
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage("com.whatsapp")
                }
                start(intent)
                if (autoDispatch) {
                    var clicked = "botão enviar não encontrado"
                    for (attempt in 1..3) {
                        delay(if (attempt == 1) 1500L else 700L)
                        clicked = withContext(Dispatchers.Main) {
                            AgentAccessibilityService.instance?.clickSendButton()
                        } ?: "serviço indisponível"
                        if (clicked == "enviado") break
                    }
                    "conversa do WhatsApp aberta e despacho: $clicked"
                } else {
                    "conversa do WhatsApp aberta com texto preenchido"
                }
            }
            "search_contact" -> {
                val q = a.getString("name")
                val found = searchContacts(q)
                if (found.isNotEmpty()) "contatos encontrados:\n$found" else "nenhum contato encontrado com o nome '$q'"
            }
            "open_url" -> {
                val u = a.getString("url")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u))
                if (u.contains("wa.me") || u.contains("whatsapp.com")) intent.setPackage("com.whatsapp")
                start(intent); "aberto"
            }
            "set_brightness" -> {
                val v = a.getInt("level").coerceIn(0, 255)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v); "brilho=$v"
            }
            "set_dnd" -> {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) "sem permissão de Não Perturbe"
                else {
                    nm.setInterruptionFilter(if (a.optBoolean("on", true))
                        android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else android.app.NotificationManager.INTERRUPTION_FILTER_ALL); "ok"
                }
            }
            "wifi_panel" -> { start(Intent(Settings.Panel.ACTION_WIFI)); "painel Wi-Fi aberto" }
            "bluetooth_settings" -> { start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); "aberto" }
            "settings" -> { start(Intent(a.optString("page", Settings.ACTION_SETTINGS))); "aberto" }
            "set_alarm" -> {
                start(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "AgentePhone"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)); "alarme criado"
            }
            "wait" -> { delay(a.optLong("ms", 1500).coerceAtMost(8000)); "ok" }
            "healthcheck" -> HealthMonitor.checkAndHeal(ctx, heal = true).toString()
            "list_sessions" -> trajectory.listSessions()
            "read_trajectory" -> {
                val sid = a.optString("session_id")
                if (sid.isNotBlank()) trajectory.readSession(sid) else trajectory.getRecentLearnings()
            }
            "say" -> { ui.say(a.getString("text")); "falado" }
            "ask_user" -> {
                val q = a.getString("question")
                val reply = ui.ask(q)
                if (isAffirmativeConfirmation(reply)) {
                    for (r in rules.getAllRules().filter { it.mode == RuleEngine.EnforcementMode.CONFIRM_ALWAYS }) {
                        for (kw in r.triggerKeywords) {
                            rules.grantSessionApproval("${r.id}:${currentApp.lowercase()}:$kw")
                        }
                    }
                }
                "usuário respondeu: $reply"
            }
            else -> "ação desconhecida: $action"
        }
    }

    /**
     * Permite que um servidor MCP externo (ex: xzero-phone conectado ao ChatGPT/Codex/Gemini)
     * execute deterministicamente qualquer ferramenta interna do X0 via AdbReceiver (EXEC_TOOL).
     */
    fun executeExternalToolBlocking(action: String, argsJson: String): String {
        val parsedArgs = runCatching {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        }.getOrElse { JSONObject() }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            pool.submit<String> {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    val curPkg = runCatching {
                        AgentAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString().orEmpty()
                    }.getOrDefault("")
                    exec(action.trim(), parsedArgs, curPkg)
                }
            }.get(35, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Throwable) {
            "erro ao executar tool '$action': ${e.javaClass.simpleName}: ${e.message ?: "timeout/sem detalhes"}"
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Inspirado no passo de catalogação direta do ChatGPT/Codex (gio list / MediaStore):
     * Em vez de abrir o app de Arquivos e rolar a tela às cegas, varre diretamente os diretórios
     * de mídia do Android (DCIM, Movies, Recordings, Download) ordenando do maior para o menor!
     */
    private fun scanStorageFiles(minMb: Long, typeFilter: String, limit: Int): String {
        val minBytes = minMb * 1024L * 1024L
        val rootDir = android.os.Environment.getExternalStorageDirectory()
        val targetDirs = listOf("DCIM", "Movies", "Recordings", "Download", "Pictures")
            .map { java.io.File(rootDir, it) }
            .filter { it.exists() && it.isDirectory }

        val videoExts = setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v")
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "dng")
        val audioExts = setOf("m4a", "mp3", "wav", "ogg", "aac", "opus")

        val matches = mutableListOf<java.io.File>()
        fun walkDir(d: java.io.File, depth: Int) {
            if (depth > 4) return
            val children = d.listFiles() ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    walkDir(f, depth + 1)
                } else if (f.isFile && f.length() >= minBytes) {
                    val ext = f.extension.lowercase()
                    val ok = when (typeFilter) {
                        "video", "videos" -> ext in videoExts
                        "image", "images", "photo" -> ext in imageExts
                        "audio" -> ext in audioExts
                        else -> true
                    }
                    if (ok) matches.add(f)
                }
            }
        }
        for (dir in targetDirs) walkDir(dir, 0)
        matches.sortByDescending { it.length() }
        val top = matches.take(limit)
        if (top.isEmpty()) {
            return "Nenhum arquivo ($typeFilter) >= ${minMb}MB encontrado em DCIM/Movies/Recordings/Download."
        }
        val totalBytes = top.sumOf { it.length() }
        val totalGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val sb = StringBuilder()
        sb.append("Encontrados ${matches.size} arquivos ($typeFilter >= ${minMb}MB). Top ${top.size} somam ${String.format(java.util.Locale.US, "%.2f", totalGb)} GB:\n")
        for ((idx, f) in top.withIndex()) {
            val mb = f.length().toDouble() / (1024.0 * 1024.0)
            val relPath = f.absolutePath.removePrefix(rootDir.absolutePath + "/")
            val sizeStr = if (mb >= 1024.0) String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            else String.format(java.util.Locale.US, "%.0f MB", mb)
            sb.append("${idx + 1}. [$sizeStr | bytes=${f.length()}] $relPath\n")
        }
        return sb.toString().trim()
    }

    private fun computeFileHash(rawPath: String): String {
        val rootDir = android.os.Environment.getExternalStorageDirectory()
        val file = if (rawPath.startsWith("/")) java.io.File(rawPath) else java.io.File(rootDir, rawPath)
        if (!file.exists() || !file.isFile) return "erro: arquivo não encontrado em '${file.absolutePath}'"
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(65536)
            file.inputStream().use { inp ->
                while (true) {
                    val read = inp.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            val isoDate = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(file.lastModified()))
            JSONObject()
                .put("path", file.absolutePath)
                .put("name", file.name)
                .put("bytes", file.length())
                .put("sha256", hex)
                .put("modified_utc", isoDate)
                .toString()
        } catch (e: Throwable) {
            "erro: falha ao calcular SHA-256: ${e.message}"
        }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    private fun start(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun findContactNumber(name: String): String? {
        val cr = ctx.contentResolver
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sel = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")
        cr.query(uri, proj, sel, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx >= 0) return cursor.getString(numIdx)
            }
        }
        return null
    }

    private fun searchContacts(name: String): String {
        val cr = ctx.contentResolver
        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sel = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")
        val sb = StringBuilder()
        cr.query(uri, proj, sel, args, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
            var count = 0
            while (cursor.moveToNext() && count < 6) {
                val dName = if (nameIdx >= 0) cursor.getString(nameIdx) else ""
                val dNum = if (numIdx >= 0) cursor.getString(numIdx) else ""
                sb.append("- ").append(dName).append(": ").append(dNum).append('\n')
                count++
            }
        }
        return sb.toString().trim()
    }

    private enum class StagnationCommand { EMPTY, CANCEL, RESTART, BACK, NEW_TASK, GUIDANCE }

    /**
     * Classifica a orientação falada quando o X0 trava ("sistema try again").
     *
     * BUG CORRIGIDO: a versão anterior fazia `q.contains("para")` dentro das palavras de
     * cancelamento, então QUALQUER orientação que contivesse "para" — "volta para a tela
     * anterior", "tenta de novo para abrir", "clica para confirmar" — era lida como CANCELAR
     * e a tarefa morria no meio. Além disso o CANCEL era testado antes do RESTART, então
     * "tenta de novo para ver" cancelava em vez de reiniciar.
     *
     * Agora: normaliza (sem acento/pontuação), testa RESTART/BACK antes de CANCEL, detecta
     * preempção de NOVA TAREFA (NEW_TASK) quando o usuário fala um novo comando completo,
     * e só aceita cancelamento quando a frase é curta e começa de fato com a palavra de parar.
     */
    private fun classifyStagnationGuidance(raw: String): StagnationCommand {
        val q = normalizeSpokenReply(raw)
        if (q.isBlank()) return StagnationCommand.EMPTY
        val tokens = q.split(" ").filter { it.isNotBlank() }

        // 0. Preempção de NOVA TAREFA (ex: "Olá X, quero que mande para minha esposa...", "abra o WhatsApp", "crie um lembrete")
        val newTaskIndicators = listOf(
            "ola x", "oi x", "ola xenon", "oi xenon", "ola ada", "oi ada",
            "quero que mande", "mande para", "manda para", "envie para", "envia para",
            "abra o", "abre o", "crie um", "criar um", "anote", "pesquise"
        )
        if (tokens.size >= 5 && newTaskIndicators.any { containsPhraseToken(q, it) }) {
            return StagnationCommand.NEW_TASK
        }

        // 1. RESTART primeiro: "tenta de novo", "faz do zero", "reinicia"
        val restartPhrases = listOf(
            "tenta de novo", "tentar de novo", "tenta outra vez", "tenta denovo", "tente de novo",
            "de novo", "denovo", "outra vez", "mais uma vez", "reinicia", "reiniciar", "reinicie",
            "recomeca", "recomecar", "comeca de novo", "faz do zero", "do zero", "reset",
            "volta pro comeco", "volta para o comeco", "tenta novamente", "novamente",
        )
        if (restartPhrases.any { containsPhraseToken(q, it) }) return StagnationCommand.RESTART

        // 2. BACK: voltar uma tela (NUNCA confundir com "volta para o começo", já tratado acima)
        val backPhrases = listOf("volta", "voltar", "volte", "volta uma tela", "tela anterior", "volta atras")
        if (backPhrases.any { containsPhraseToken(q, it) }) return StagnationCommand.BACK

        // 3. CANCEL só com frase curta de parada explícita (evita o falso positivo do "para")
        val cancelStarters = listOf(
            "para", "pare", "parar", "cancela", "cancelar", "cancele", "desiste", "desistir",
            "esquece", "esqueca", "chega", "aborta", "abortar", "nao", "deixa", "sai disso",
        )
        if (tokens.size <= 4 && tokens.first() in cancelStarters) return StagnationCommand.CANCEL
        if (q == "nao" || q == "chega" || q == "sai disso" || q == "deixa pra la") {
            return StagnationCommand.CANCEL
        }

        // 4. Cascata semântica: pega "esse caminho não vai dar", "larga mão disso" etc.
        if (prefs.semanticConfirmEnabled) {
            VoiceSemantics.confirm.classify(raw, setOf("negativo"))?.let { m ->
                if (tokens.size <= 5) {
                    ui.log("🧠 Orientação lida como cancelamento por ${m.method} (~${String.format(java.util.Locale.US, "%.2f", m.score)})")
                    return StagnationCommand.CANCEL
                }
            }
        }
        return StagnationCommand.GUIDANCE
    }

    /** Casa a expressão por palavra inteira dentro da frase normalizada. */
    private fun containsPhraseToken(norm: String, phrase: String): Boolean =
        norm.isNotEmpty() && " $norm ".contains(" $phrase ")

    private fun buildContextualStagnationPrompt(currentApp: String, action: String, thought: String): String {
        val appLabel = currentApp.substringAfterLast('.').ifBlank { "tela atual" }
        val intentSummary = thought.trim().takeIf { it.isNotBlank() }?.take(48) ?: "executar $action"
        return "Travei em $appLabel ao tentar $intentSummary. Tento outro caminho ou cancelo?"
    }

    private fun openApp(name: String, currentApp: String = ""): String {
        val pm = ctx.packageManager
        val q = name.lowercase().trim()

        // Atalhos diretos de pacotes (incluindo sinônimos EN/PT dos logs: Calendar, Keep, Gemini, Docs, Messages, Drive, Files, Galeria)
        val directPackages = when {
            q.contains("whatsapp") || q.contains("zap") -> listOf("com.whatsapp")
            q.contains("calendar") || q.contains("calendário") || q.contains("calendario") ||
                q.contains("google agenda") || q.contains("compromisso") ->
                listOf("com.google.android.calendar")
            q.contains("keep") || q.contains("notas") || q.contains("lembrete") ->
                listOf("com.google.android.keep")
            q.contains("gemini") || q.contains("bard") ->
                listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
            q.contains("google docs") || q.contains("documentos") || q == "docs" ->
                listOf("com.google.android.apps.docs.editors.docs", "com.google.android.apps.docs")
            q.contains("messages") || q.contains("mensagens") || q == "sms" || q.contains("torpedo") ->
                listOf("com.google.android.apps.messaging", "com.android.mms")
            q.contains("drive") || q.contains("google drive") ->
                listOf("com.google.android.apps.docs")
            q.contains("files") || q.contains("arquivos") || q.contains("gerenciador") ->
                listOf("com.google.android.apps.nbu.files", "com.google.android.documentsui", "com.android.documentsui")
            q.contains("fotos") || q.contains("photos") || q.contains("galeria") || q.contains("gallery") ->
                listOf("com.google.android.apps.photos", "com.motorola.MotGallery2")
            q.contains("câmera") || q.contains("camera") ->
                listOf("com.motorola.camera3", "com.motorola.camera2", "com.android.camera2")
            q.contains("telefone") || q.contains("phone") || q.contains("discador") || q.contains("chamadas") ->
                listOf("com.google.android.dialer", "com.android.dialer")
            q.contains("contatos") || q.contains("contacts") || q.contains("agenda") ->
                listOf("com.google.android.contacts", "com.android.contacts")
            q.contains("spotify") -> listOf("com.spotify.music")
            q.contains("calc") -> listOf("com.google.android.calculator")
            q.contains("relógio") || q.contains("relogio") || q.contains("clock") || q.contains("alarme") ->
                listOf("com.google.android.deskclock")
            q.contains("config") || q.contains("ajuste") || q.contains("definições") || q.contains("settings") ->
                listOf("com.android.settings")
            q.contains("chrome") || q.contains("navegador") -> listOf("com.android.chrome")
            q.contains("gmail") -> listOf("com.google.android.gm")
            q.contains("outlook") -> listOf("com.microsoft.office.outlook")
            q.contains("youtube") -> listOf("com.google.android.youtube")
            q.contains("maps") || q.contains("mapa") -> listOf("com.google.android.apps.maps")
            q.contains("uber") -> listOf("com.ubercab")
            q.contains("ifood") -> listOf("br.com.brainweb.ifood")
            q.contains("claude") -> listOf("com.anthropic.claude")
            q.contains("chatgpt") -> listOf("com.openai.chatgpt")
            else -> emptyList()
        }
        for (pkg in directPackages) {
            if (currentApp.isNotBlank() && currentApp.equals(pkg, ignoreCase = true)) {
                return "app $pkg já está aberto em foco (não reabra; interaja com a tela atual)"
            }
            val i = pm.getLaunchIntentForPackage(pkg)
            if (i != null) {
                start(i)
                return "abrindo $pkg"
            }
        }

        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, PackageManager.MATCH_ALL)
        val best = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: apps.firstOrNull { it.activityInfo.packageName.lowercase().contains(q.replace(" ", "")) }
            ?: return "app '$name' não encontrado"
        if (currentApp.isNotBlank() && currentApp.equals(best.activityInfo.packageName, ignoreCase = true)) {
            return "app ${best.loadLabel(pm)} já está aberto em foco (não reabra; interaja com a tela atual)"
        }
        val i = pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return "sem tela inicial"
        start(i)
        return "abrindo ${best.loadLabel(pm)}"
    }
}

/** Para respostas de ask_user. */
class PendingAnswer { var deferred: CompletableDeferred<String>? = null }
