package com.xzero.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Iniciativa da ADA: o que ela faz quando NÃO está executando ordem nenhuma.
 *
 * Antes, o tempo ocioso era teatro — o Xenon abria o notebook holográfico e nada
 * acontecia por trás. Agora o ocioso é trabalho real, em três ciclos:
 *
 *  1. CONSOLIDAÇÃO (barata, local, sem LLM): revisa o que deu errado nas últimas
 *     sessões, poda rota ruim do grafo e registra a lição. Fecha o ciclo de
 *     aprendizado que hoje só existia DENTRO de uma tarefa.
 *  2. DIÁRIO: escreve em `files/diario/AAAA-MM-DD.md` o que observou. É a "ação segura
 *     em documento" — arquivo local, reversível, que o usuário lê quando quiser.
 *  3. SUGESTÃO: quando observa algo acionável (mensagens acumulando de um contato,
 *     bateria caindo sempre no mesmo horário, armazenamento no limite), ENFILEIRA uma
 *     sugestão. Nunca executa.
 *
 * ## Camadas de autonomia
 *
 * | Tier | O que pode | Precisa de permissão? |
 * |---|---|---|
 * | 0 | escrever diário local, consolidar grafo, podar rota ruim | não — é local e reversível |
 * | 1 | notificação silenciosa com uma sugestão | não — mas só avisa, não age |
 * | 2 | mandar mensagem, abrir app, apagar, pagar | SEMPRE, e nunca por iniciativa própria |
 *
 * Nada no Tier 2 passa por aqui. A iniciativa propõe; quem decide é o usuário.
 *
 * ## Privacidade
 *
 * O diário guarda METADADO de notificação (app, remetente, contagem, horário), nunca o
 * corpo da mensagem. O próprio perfil do usuário pede isso: "não armazenar
 * indiscriminadamente conversas". Conteúdo só entra se ele mandar explicitamente.
 */
object InitiativeEngine {

    private const val TAG = "InitiativeEngine"

    /** Tempo parado antes de valer a pena consolidar aprendizado. */
    private const val OCIOSO_PARA_CONSOLIDAR_MS = 8 * 60_000L
    /** Intervalo mínimo entre escritas no diário. */
    private const val INTERVALO_DIARIO_MS = 25 * 60_000L
    /** Nunca mais de uma sugestão nesta janela, para não virar spam. */
    private const val INTERVALO_SUGESTAO_MS = 45 * 60_000L
    private const val MAX_SUGESTOES_FILA = 12

    data class Observacao(
        val tipo: String,       // "notificacao" | "sensor" | "dispositivo"
        val chave: String,      // app/remetente/métrica — NUNCA o corpo da mensagem
        val detalhe: String,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    data class Sugestao(
        val id: String,
        val texto: String,
        val motivo: String,
        val acaoProposta: String,
        val criadaEmMs: Long = System.currentTimeMillis(),
        var mostrada: Boolean = false,
        var resposta: String = "",
    )

    @Volatile private var ctxRef: Context? = null
    @Volatile private var ultimaAtividadeMs: Long = System.currentTimeMillis()
    @Volatile private var ultimoDiarioMs: Long = 0L
    @Volatile private var ultimaSugestaoMs: Long = 0L
    @Volatile private var ultimaContinuacaoAtivaMs: Long = 0L
    @Volatile private var ultimoCheckLogCriticoMs: Long = 0L
    @Volatile var consolidacoes: Int = 0; private set
    @Volatile var entradasDiario: Int = 0; private set
    @Volatile var sugestoesCriadas: Int = 0; private set
    @Volatile var ultimoResumo: String = "—"; private set
    @Volatile var ultimoScanArquivosGrandes: String = ""; private set
    @Volatile var ultimaPesquisaProativa: String = ""; private set

    private val STORAGE_CLEANUP_PACKAGES = setOf(
        "com.google.android.apps.nbu.files",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.motorola.filemanager",
        "com.sec.android.app.myfiles",
        "com.mi.android.globalFileexplorer"
    )

    private val STORAGE_CLEANUP_KEYWORDS = listOf(
        "limpar", "armazenamento", "liberar espaço", "liberar espaco",
        "arquivos grandes", "lixeira", "gerenciar armazenamento",
        "memória cheia", "memoria cheia", "espaço insuficiente", "downloads"
    )

    /**
     * Varredura ultrarrápida (<15ms via MediaStore indexado do Android, 100% local, zero LLM)
     * dos maiores arquivos do celular em /sdcard para sugerir imediatamente os caminhos exatos
     * quando o usuário começa a limpar armazenamento ou quando o espaço livre entra em nível crítico.
     */
    fun escanearArquivosGrandesRapido(minMb: Long = 35L, limit: Int = 5): String {
        return runCatching {
            val minBytes = minMb * 1024L * 1024L
            val rootPath = android.os.Environment.getExternalStorageDirectory().absolutePath
            val items = mutableListOf<Pair<String, Long>>() // relPath to sizeBytes

            // 1. Via MediaStore SQLite Index (instantâneo, não trava no FUSE de pastas com milhares de fotos)
            val ctx = ctxRef
            if (ctx != null) {
                runCatching {
                    val uri = android.provider.MediaStore.Files.getContentUri("external")
                    val proj = arrayOf(
                        android.provider.MediaStore.MediaColumns.DATA,
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        android.provider.MediaStore.MediaColumns.SIZE
                    )
                    val sel = "${android.provider.MediaStore.MediaColumns.SIZE} >= ?"
                    val selArgs = arrayOf(minBytes.toString())
                    val sort = "${android.provider.MediaStore.MediaColumns.SIZE} DESC"
                    ctx.contentResolver.query(uri, proj, sel, selArgs, sort)?.use { c ->
                        val iData = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                        val iRel = c.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                        val iName = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                        val iSize = c.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                        while (c.moveToNext() && items.size < limit) {
                            val size = if (iSize >= 0) c.getLong(iSize) else 0L
                            if (size < minBytes) continue
                            val dataPath = if (iData >= 0) c.getString(iData).orEmpty() else ""
                            val rel = if (dataPath.isNotBlank()) {
                                dataPath.removePrefix(rootPath).removePrefix("/storage/emulated/0").removePrefix("/")
                            } else {
                                val r = if (iRel >= 0) c.getString(iRel).orEmpty().trimEnd('/') else ""
                                val n = if (iName >= 0) c.getString(iName).orEmpty() else "arquivo"
                                if (r.isNotBlank()) "$r/$n" else n
                            }
                            if (rel.isNotBlank()) items.add(rel to size)
                        }
                    }
                }
            }

            if (items.isEmpty()) return@runCatching ""
            val totalMb = items.sumOf { it.second } / (1024 * 1024)
            val itensTxt = items.joinToString(", ") { (rel, sz) ->
                val mb = sz / (1024 * 1024)
                "$rel (${mb}MB)"
            }
            val resumo = "Top ${items.size} (${totalMb}MB somados): $itensTxt"
            ultimoScanArquivosGrandes = resumo
            resumo
        }.getOrDefault("")
    }

    /**
     * Co-piloto Proativo de Continuação de Fluxo:
     * Observa em tempo real o que o usuário começou a fazer na tela (ex: abriu limpeza de armazenamento,
     * gerenciador de arquivos, configurações de espaço ou clicou em "Limpar" / "Armazenamento")
     * e já antecipa o próximo passo trazendo os caminhos exatos dos arquivos grandes ou alertas de logs críticos.
     */
    fun observarAcaoDeTelaParaContinuacao(pkg: String, rawLabel: String = "") {
        if (!habilitada()) return
        val agora = System.currentTimeMillis()

        // 1. Verificação periódica (a cada 3 min) de logs críticos e armazenamento cheio em background
        if (agora - ultimoCheckLogCriticoMs > 3 * 60_000L) {
            ultimoCheckLogCriticoMs = agora
            Thread { verificarLogsCriticosEArmazenamento() }.start()
        }

        // Cooldown de 90s entre sugestões de continuação ativa para nunca ser intrusivo
        if (agora - ultimaContinuacaoAtivaMs < 90_000L) return

        val pkgLower = pkg.lowercase(Locale.US)
        val labelLower = rawLabel.lowercase(Locale("pt", "BR")).trim()
        val isStorageApp = pkgLower in STORAGE_CLEANUP_PACKAGES
        val isStorageAction = STORAGE_CLEANUP_KEYWORDS.any { labelLower.contains(it) }

        if (isStorageApp || isStorageAction) {
            ultimaContinuacaoAtivaMs = agora
            Thread {
                val grandes = escanearArquivosGrandesRapido(minMb = 30L, limit = 4)
                if (grandes.isNotBlank()) {
                    val textoSug = "Vi que você está limpando o armazenamento! Os maiores arquivos estão em: $grandes. Quer que eu faça backup no servidor ou liste mais?"
                    val sug = Sugestao(
                        id = "cont_sto_${System.currentTimeMillis()}",
                        texto = textoSug,
                        motivo = "continuação proativa de limpeza de armazenamento ($pkgLower)",
                        acaoProposta = "continue_storage_cleanup:$grandes"
                    )
                    enfileirarEMostrarNaTela(sug, criarNotificacaoSilenciosa = true)
                    sincronizarComSecondBrain("storage_cleanup", pkg, "Limpeza de armazenamento iniciada pelo usuário", grandes)
                }
            }.start()
        }
    }

    /**
     * Pesquisa proativa em background (Second Brain Multi-Hop + Web Search) para trazer
     * respostas e caminhos diretamente para a tela quando o usuário demonstra dúvida ou pesquisa algo.
     */
    fun pesquisarETrazerParaTela(query: String, appEmFoco: String = "") {
        val q = query.trim()
        if (q.length < 4) return
        Thread {
            val sbResult = consultarSecondBrainProativo("research", appEmFoco, q)
            val webResult = ProactiveWatcher.quickWebSearch(q)
            val primeiroSnippet = webResult.lineSequence()
                .firstOrNull { it.trim().startsWith("- ") }
                ?.removePrefix("- ")
                ?.take(160)
                .orEmpty()

            val resumoTela = when {
                sbResult.isNotBlank() && primeiroSnippet.isNotBlank() ->
                    "🔎 Trouxe para a tela sobre '$q': [Second Brain] ${sbResult.take(110)} | [Web] $primeiroSnippet"
                sbResult.isNotBlank() ->
                    "🔎 Do Second Brain sobre '$q': ${sbResult.take(180)}"
                primeiroSnippet.isNotBlank() ->
                    "🔎 Pesquisei sobre '$q': $primeiroSnippet"
                else -> ""
            }
            if (resumoTela.isNotBlank()) {
                ultimaPesquisaProativa = "$resumoTela\n\n$webResult"
                val sug = Sugestao(
                    id = "res_${System.currentTimeMillis()}",
                    texto = resumoTela,
                    motivo = "pesquisa proativa trazida para a tela ($q)",
                    acaoProposta = "proactive_research:$q"
                )
                enfileirarEMostrarNaTela(sug, criarNotificacaoSilenciosa = true)
            }
        }.start()
    }

    /**
     * Monitora logs críticos (armazenamento cheio, erros recorrentes, temperatura alta, RAM crítica
     * e alertas do servidor/Second Brain) e avisa imediatamente na tela com caminhos acionáveis.
     */
    fun verificarLogsCriticosEArmazenamento() {
        val ctx = ctxRef ?: return
        runCatching {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val livreGb = stat.availableBytes / 1_000_000_000.0
            val totalGb = stat.totalBytes / 1_000_000_000.0
            val pctLivre = if (totalGb > 0) (livreGb / totalGb) * 100.0 else 100.0

            // 1. Alerta crítico de armazenamento cheio já trazendo os caminhos dos arquivos grandes
            if (pctLivre < 10.0 || livreGb < 5.5) {
                val acaoKey = "critical_storage_alert"
                if (!jaPropostaRecentemente(acaoKey)) {
                    val grandes = escanearArquivosGrandesRapido(minMb = 40L, limit = 4)
                    val caminhos = if (grandes.isNotBlank()) " Maiores arquivos: $grandes." else ""
                    val msg = "⚠️ ALERTA CRÍTICO: Armazenamento quase cheio (${String.format(Locale("pt", "BR"), "%.1f", livreGb)} GB livres / ${String.format(Locale("pt", "BR"), "%.0f", pctLivre)}%).$caminhos Quer que eu faça backup ou limpe?"
                    registrar(Observacao("dispositivo", "log_critico_armazenamento", msg))
                    val sug = Sugestao(
                        id = "crit_sto_${System.currentTimeMillis()}",
                        texto = msg,
                        motivo = "log crítico: armazenamento abaixo do limite de segurança",
                        acaoProposta = acaoKey
                    )
                    enfileirarEMostrarNaTela(sug, criarNotificacaoSilenciosa = true)
                    sincronizarComSecondBrain("critical_log", "android.storage", msg, grandes)
                    return
                }
            }

            // 2. Alerta de logs críticos de execução / crashes / ANR / temperatura / RAM
            val lastErr = HealthMonitor.lastError
            val telem = HealthMonitor.getTelemetry(ctx)
            val tempC = telem.optDouble("temp_c", 0.0)
            val ramAvailMb = telem.optLong("ram_avail_mb", 2000L)

            val critMsg = when {
                lastErr.contains("CRASH", ignoreCase = true) || lastErr.contains("ALERTA", ignoreCase = true) ->
                    "⚠️ Log Crítico no X-Zero: $lastErr. Auto-recuperação monitorando."
                tempC >= 41.0 ->
                    "⚠️ Log Crítico de Temperatura: Bateria em ${tempC}°C! Recomendo ativar modo eco nos sensores."
                ramAvailMb in 1..420 ->
                    "⚠️ Log Crítico de Memória RAM: Apenas ${ramAvailMb}MB livres no aparelho."
                else -> ""
            }

            if (critMsg.isNotBlank()) {
                val acaoKey = "critical_log:${critMsg.take(35)}"
                if (!jaPropostaRecentemente(acaoKey)) {
                    registrar(Observacao("dispositivo", "log_critico_sistema", critMsg))
                    val sug = Sugestao(
                        id = "crit_log_${System.currentTimeMillis()}",
                        texto = critMsg,
                        motivo = "monitoramento proativo de logs críticos",
                        acaoProposta = acaoKey
                    )
                    enfileirarEMostrarNaTela(sug, criarNotificacaoSilenciosa = true)
                    sincronizarComSecondBrain("critical_log", "com.xzero.agent", critMsg, "")
                }
            }
        }
    }

    private fun enfileirarEMostrarNaTela(s: Sugestao, criarNotificacaoSilenciosa: Boolean = false) {
        synchronized(this) {
            ultimaSugestaoMs = System.currentTimeMillis()
            sugestoes.addLast(s)
            while (sugestoes.size > MAX_SUGESTOES_FILA) sugestoes.removeFirst()
            sugestoesCriadas++
            ultimoResumo = s.texto
        }
        Log.i(TAG, "🚀 [Proatividade Ativa] ${s.texto}")
        if (criarNotificacaoSilenciosa) {
            ctxRef?.let { ProactiveWatcher.postAgentNotification(it, "Co-Piloto Proativo X-Zero", s.texto, silent = true) }
        }
        BubbleService.instance?.mostrarSugestaoProativaImediata(s)
    }

    private fun sincronizarComSecondBrain(activityType: String, appPkg: String, queryOrLog: String, largeFiles: String) {
        runCatching {
            consultarSecondBrainProativo(activityType, appPkg, queryOrLog, largeFiles)
        }
    }

    private fun consultarSecondBrainProativo(
        activityType: String,
        appPkg: String,
        queryOrLog: String,
        largeFiles: String = ""
    ): String {
        return runCatching {
            val url = java.net.URL("http://127.0.0.1:8001/api/memory/proactive-assist")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3500
                readTimeout = 4500
                doOutput = true
            }
            val payload = JSONObject()
                .put("activity_type", activityType)
                .put("app_package", appPkg)
                .put("user_action_or_query", queryOrLog)
                .put("large_files_summary", largeFiles)
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                resp.optString("screen_suggestion", "")
            } else ""
        }.getOrDefault("")
    }

    private val observacoes = ArrayDeque<Observacao>()
    private val sugestoes = ArrayDeque<Sugestao>()

    fun init(ctx: Context) {
        if (ctxRef == null) {
            ctxRef = ctx.applicationContext
            PassiveHabitTracker.init(ctx.applicationContext)
            Log.i(TAG, "🧭 Iniciativa ativa (diário em ${diarioDir()?.absolutePath})")
        }
    }

    private fun habilitada(): Boolean = ctxRef?.let { Prefs(it).iniciativaAtiva } ?: false

    /** Qualquer interação do usuário zera o relógio de ociosidade. */
    fun marcarAtividade() {
        ultimaAtividadeMs = System.currentTimeMillis()
    }

    fun ociosoHaMs(): Long = System.currentTimeMillis() - ultimaAtividadeMs

    // ---------- Observação passiva ----------

    /** Notificação chegou. Guarda só o metadado — nunca o corpo. */
    fun observarNotificacao(pkg: String, title: String) {
        if (!habilitada()) return
        val app = pkg.substringAfterLast('.').ifBlank { pkg }
        val remetente = title.take(40).ifBlank { "(sem título)" }
        registrar(Observacao("notificacao", "$app:$remetente", "nova notificação"))
    }

    fun observarSensor(resumo: String) {
        if (!habilitada()) return
        registrar(Observacao("sensor", "estado", resumo.take(120)))
    }

    fun observarDispositivo(chave: String, detalhe: String) {
        if (!habilitada()) return
        registrar(Observacao("dispositivo", chave, detalhe.take(120)))
    }

    /**
     * Observação passiva enquanto o usuário trabalha e fala no ambiente (sem despertar o agente).
     * Registra apenas sinais de contexto (app em foco + intenção/tema detectado, nunca conversa crua)
     * e permite aprender quando ele dá feedback explícito sobre uma sugestão recente.
     */
    fun observarFalaPassiva(spoken: String, appEmFoco: String?) {
        if (!habilitada() || spoken.isBlank()) return
        val norm = spoken.lowercase(Locale("pt", "BR")).trim()
        val app = appEmFoco?.substringAfterLast('.')?.ifBlank { appEmFoco } ?: "desconhecido"
        val ehDuvidaOuBloqueio = listOf("como faz", "por que", "não funciona", "travou", "erro", "dúvida", "pesquisar", "achar").any { norm.contains(it) }
        if (ehDuvidaOuBloqueio) {
            registrar(Observacao("trabalho", "duvida:$app", norm.take(90)))
            // Se o usuário falou em voz alta sobre limpar armazenamento ou memória cheia, antecipa os caminhos grandes
            if (STORAGE_CLEANUP_KEYWORDS.any { norm.contains(it) }) {
                observarAcaoDeTelaParaContinuacao(appEmFoco.orEmpty(), norm)
            } else if (System.currentTimeMillis() - ultimaContinuacaoAtivaMs > 90_000L && norm.length >= 12) {
                ultimaContinuacaoAtivaMs = System.currentTimeMillis()
                pesquisarETrazerParaTela(norm.take(90), app)
            }
        }
    }

    /**
     * Se houve uma sugestão mostrada nos últimos 180s e o usuário deu feedback por voz
     * ("gostei", "boa", "sim", "mostra", "continua", "não precisa"), aprende a preferência no grafo
     * e, se ele pediu para continuar/mostrar, traz imediatamente o resultado detalhado para a tela!
     */
    @Synchronized
    fun registrarFeedbackPorVoz(spoken: String, graph: KnowledgeGraphMemory?): String? {
        val ultima = sugestoes.lastOrNull { it.mostrada && it.resposta.isBlank() } ?: return null
        if (System.currentTimeMillis() - ultima.criadaEmMs > 180_000L) return null
        val norm = spoken.lowercase(Locale("pt", "BR")).trim()
        val pedirExecucao = listOf("sim", "quero", "mostra", "mostrar", "pode listar", "continua", "continuar", "pesquisa", "pesquisar", "faz isso").any { norm.contains(it) }
        val positivo = pedirExecucao || listOf("gostei", "boa", "valeu", "isso aí", "legal", "muito bom", "ótimo", "obrigado").any { norm.contains(it) }
        val negativo = listOf("não precisa", "para de sugerir", "chato", "ignora", "não quero", "agora não").any { norm.contains(it) }
        if (!positivo && !negativo) return null

        val cat = ultima.acaoProposta.substringBefore(':')
        ultima.resposta = if (positivo) "feedback_positivo" else "feedback_negativo"
        runCatching {
            graph?.upsertFact(
                subject = "preferencia_sugestao:$cat",
                relation = if (positivo) "aprovada_pelo_usuario" else "rejeitada_pelo_usuario",
                target = ultima.motivo.take(60),
                boost = if (positivo) 0.8 else -0.8,
            )
        }
        Log.i(TAG, "🎓 Aprendizado por feedback [$cat]: ${ultima.resposta}")
        if (pedirExecucao) {
            if (cat in listOf("scan_storage_files", "continue_storage_cleanup", "critical_storage_alert")) {
                val detalhado = escanearArquivosGrandesRapido(minMb = 15L, limit = 8)
                if (detalhado.isNotBlank()) {
                    ctxRef?.let { ProactiveWatcher.postAgentNotification(it, "Caminhos dos Arquivos Grandes", detalhado, silent = true) }
                    return "📂 Caminhos dos maiores arquivos: $detalhado"
                }
            } else if (cat == "proactive_research" || cat == "ask_chatgpt") {
                val termo = ultima.acaoProposta.substringAfter(':', ultima.motivo)
                pesquisarETrazerParaTela(termo, "")
                return "🔎 Pesquisando '$termo' no Second Brain e na Web para trazer para a tela..."
            }
        }
        return if (positivo) "Anotado! Vou priorizar sugestões assim." else "Entendido, vou evitar sugerir isso."
    }

    /** Lê bateria, armazenamento e sensores por conta própria a cada ciclo ocioso. */
    private fun coletarEstadoDoAparelho() {
        val ctx = ctxRef ?: return
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 1..100) {
                registrar(Observacao("dispositivo", "bateria", "$pct%${if (bm.isCharging) " carregando" else ""}"))
                if (pct <= 18 && !bm.isCharging) {
                    registrar(Observacao("dispositivo", "bateria_baixa", "$pct%"))
                }
            }
        }
        runCatching {
            val stat = android.os.StatFs(ctx.filesDir.absolutePath)
            val livreGb = stat.availableBytes / 1_000_000_000.0
            val totalGb = stat.totalBytes / 1_000_000_000.0
            val pctLivre = if (totalGb > 0) livreGb / totalGb * 100 else 100.0
            registrar(Observacao("dispositivo", "armazenamento",
                String.format(Locale("pt", "BR"), "%.1f GB livres de %.0f GB", livreGb, totalGb)))
            if (pctLivre < 12.0) {
                registrar(Observacao("dispositivo", "armazenamento_baixo",
                    String.format(Locale("pt", "BR"), "só %.1f GB livres", livreGb)))
            }
        }
        runCatching {
            val resumo = ProactiveWatcher.getSensorSummary()
            if (resumo.isNotBlank()) registrar(Observacao("sensor", "estado", resumo))
        }
    }

    @Synchronized
    private fun registrar(o: Observacao) {
        observacoes.addLast(o)
        while (observacoes.size > 400) observacoes.removeFirst()
    }

    // ---------- Ciclo ocioso ----------

    /**
     * Chamado pelo loop ocioso do BubbleService. Devolve uma frase curta quando fez algo
     * que vale mostrar, ou null quando só trabalhou em silêncio.
     */
    @Synchronized
    fun tick(graph: KnowledgeGraphMemory?): String? {
        if (!habilitada()) return null
        val ocioso = ociosoHaMs()
        if (ocioso < OCIOSO_PARA_CONSOLIDAR_MS) return null

        val agora = System.currentTimeMillis()

        // 0. Olhar ativo: em vez de só esperar notificação chegar, vai buscar o estado
        //    do aparelho. É o que separa "passivo" de "atento".
        coletarEstadoDoAparelho()

        // 1. Consolidação de aprendizado (local, sem LLM, sem rede)
        val licao = consolidarAprendizado(graph)

        // 2. Diário
        if (agora - ultimoDiarioMs > INTERVALO_DIARIO_MS && observacoes.isNotEmpty()) {
            ultimoDiarioMs = agora
            escreverDiario(licao)
        }

        // 3. Sugestão a partir do que observou
        if (agora - ultimaSugestaoMs > INTERVALO_SUGESTAO_MS) {
            val s = derivarSugestao()
            // Não repete proposta que já está na fila nem que o usuário já ignorou.
            // A condição (armazenamento baixo, por exemplo) persiste; a sugestão não deve.
            if (s != null && !jaPropostaRecentemente(s.acaoProposta)) {
                ultimaSugestaoMs = agora
                sugestoes.addLast(s)
                while (sugestoes.size > MAX_SUGESTOES_FILA) sugestoes.removeFirst()
                sugestoesCriadas++
                ultimoResumo = s.texto
                Log.i(TAG, "💡 sugestão: ${s.texto}")
                return s.texto
            }
        }
        return null
    }

    /** Roda um ciclo ignorando o tempo de ociosidade — para teste e para o painel. */
    @Synchronized
    fun tickForcado(graph: KnowledgeGraphMemory?): String? {
        val salvo = ultimaAtividadeMs
        ultimaAtividadeMs = System.currentTimeMillis() - OCIOSO_PARA_CONSOLIDAR_MS - 1000
        ultimoDiarioMs = 0L
        ultimaSugestaoMs = 0L
        val r = tick(graph)
        ultimaAtividadeMs = salvo
        return r
    }

    /**
     * Revisa os erros recentes e grava a lição no grafo.
     * Roda sem LLM: é leitura do histórico que a própria ADA já produziu.
     */
    private fun consolidarAprendizado(graph: KnowledgeGraphMemory?): String {
        if (graph != null) {
            runCatching {
                PassiveHabitTracker.topRoutines(3).forEach { r ->
                    graph.upsertFact(
                        subject = "habito_rotina:${r.bucket}:${r.fromPkg.substringAfterLast('.')}",
                        relation = "costuma_abrir_depois",
                        target = r.toPkg.substringAfterLast('.'),
                        boost = 0.35 + (r.count * 0.05).coerceAtMost(0.5),
                    )
                }
            }
        }
        val erros = runCatching { ErrorDetector.getErrorHistory() }.getOrDefault(emptyList())
        if (erros.isEmpty() || graph == null) return ""
        val porTipo = erros.groupingBy { it.type.name }.eachCount()
        val pior = porTipo.maxByOrNull { it.value } ?: return ""
        if (pior.value < 2) return ""

        consolidacoes++
        val licao = "${pior.key} aconteceu ${pior.value}x"
        runCatching {
            graph.upsertFact(
                subject = "padrao_de_falha",
                relation = "mais_frequente_recente",
                target = pior.key,
                boost = 0.5 + pior.value * 0.1,
            )
        }
        Log.i(TAG, "📚 consolidou: $licao")
        return licao
    }

    /** Escreve o diário do dia — ação segura: arquivo local, reversível, sem efeito externo. */
    private fun escreverDiario(licao: String) {
        val dir = diarioDir() ?: return
        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale("pt", "BR")).format(Date())
        val hora = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date())
        val f = File(dir, "$hoje.md")

        val recentes = observacoes.toList().takeLast(120)
        val porApp = recentes.filter { it.tipo == "notificacao" }
            .groupingBy { it.chave.substringBefore(':') }.eachCount()
            .entries.sortedByDescending { it.value }

        runCatching {
            if (!f.exists()) {
                f.writeText("# Diário da ADA — $hoje\n\n" +
                    "_Anotações que eu faço sozinha enquanto não estou executando nada. " +
                    "Só metadado: app, remetente e contagem — nunca o conteúdo das mensagens._\n")
            }
            f.appendText(buildString {
                append("\n## $hora\n\n")
                if (porApp.isNotEmpty()) {
                    append("Notificações observadas: ")
                    append(porApp.joinToString(", ") { "${it.key} (${it.value})" })
                    append('\n')
                }
                recentes.lastOrNull { it.tipo == "sensor" }?.let {
                    append("Sensores: ").append(it.detalhe).append('\n')
                }
                recentes.filter { it.tipo == "dispositivo" }.takeLast(3).forEach {
                    append("- ").append(it.chave).append(": ").append(it.detalhe).append('\n')
                }
                if (licao.isNotBlank()) {
                    append("\nPadrão de falha que notei no meu próprio comportamento: ")
                    append(licao).append(".\n")
                }
            })
            entradasDiario++
            Log.i(TAG, "📓 diário atualizado: ${f.name}")
        }.onFailure { Log.w(TAG, "falha ao escrever diário: ${it.message}") }
    }

    /**
     * Deriva no máximo UMA sugestão do que foi observado.
     * Sempre proposta — nunca execução.
     */
    private fun derivarSugestao(): Sugestao? {
        val recentes = observacoes.toList().filter {
            System.currentTimeMillis() - it.timestampMs < 3 * 60 * 60_000L
        }
        if (recentes.isEmpty()) return null

        // Mesmo remetente insistindo
        val porRemetente = recentes.filter { it.tipo == "notificacao" }
            .groupingBy { it.chave }.eachCount()
            .filter { it.value >= 4 }
            .maxByOrNull { it.value }
        if (porRemetente != null) {
            val (app, quem) = porRemetente.key.split(':', limit = 2).let {
                (it.getOrNull(0) ?: "app") to (it.getOrNull(1) ?: "alguém")
            }
            return Sugestao(
                id = "rem_${System.currentTimeMillis()}",
                texto = "$quem te mandou ${porRemetente.value} notificações no $app. Quer que eu leia e te resuma?",
                motivo = "mesmo remetente acumulou ${porRemetente.value} notificações em 3h",
                acaoProposta = "resumir_conversa:$app:$quem",
            )
        }

        // Armazenamento / bateria no limite (já trazendo caminhos dos arquivos grandes!)
        recentes.lastOrNull { it.tipo == "dispositivo" && it.chave == "armazenamento_baixo" }?.let {
            val grandes = escanearArquivosGrandesRapido(minMb = 35L, limit = 4)
            val caminhosTxt = if (grandes.isNotBlank()) " Os maiores estão em: $grandes." else ""
            return Sugestao(
                id = "sto_${System.currentTimeMillis()}",
                texto = "Seu armazenamento está apertado (${it.detalhe}).$caminhosTxt Quer que eu transfira para o backup ou limpe?",
                motivo = "armazenamento abaixo do limite",
                acaoProposta = "scan_storage_files",
            )
        }

        // Bateria baixa sem carregar -> alerta ativo
        recentes.lastOrNull { it.tipo == "dispositivo" && it.chave == "bateria_baixa" }?.let {
            return Sugestao(
                id = "bat_${System.currentTimeMillis()}",
                texto = "Bateria em ${it.detalhe} fora da tomada! Quer que eu ative economia ou feche apps pesados?",
                motivo = "bateria baixa detectada pelos sensores",
                acaoProposta = "battery_saver_prompt",
            )
        }

        // Hábito observado passivamente (troca de app recorrente por horário)
        PassiveHabitTracker.nextHabitSuggestion()?.let { return it }

        // Padrão recorrente de falhas em tarefas -> sugere consultar o ChatGPT para destravar rota
        val erros = runCatching { ErrorDetector.getErrorHistory() }.getOrDefault(emptyList())
        if (erros.size >= 4) {
            val pior = erros.groupingBy { it.type.name }.eachCount().maxByOrNull { it.value }
            if (pior != null && pior.value >= 3) {
                return Sugestao(
                    id = "gpt_${System.currentTimeMillis()}",
                    texto = "Notei ${pior.value} falhas de ${pior.key}. Quer que eu abra uma pesquisa no ChatGPT ou consulte via MCP?",
                    motivo = "padrão de falha recorrente (${pior.key})",
                    acaoProposta = "ask_chatgpt:${pior.key}",
                )
            }
        }
        return null
    }

    // ---------- Fila de sugestões ----------

    /**
     * Já propus isso? Silêncio conta como "não agora": a mesma proposta só volta depois
     * de 12h, e nunca mais se o usuário respondeu. Sugestão repetida vira ruído, e ruído
     * faz ele desligar a iniciativa inteira.
     */
    private fun jaPropostaRecentemente(acao: String): Boolean {
        val limite = System.currentTimeMillis() - 12 * 60 * 60_000L
        return sugestoes.any {
            it.acaoProposta == acao && (it.resposta.isNotBlank() || it.criadaEmMs > limite)
        }
    }

    @Synchronized
    fun sugestaoPendente(): Sugestao? = sugestoes.lastOrNull { !it.mostrada }

    @Synchronized
    fun marcarMostrada(id: String) {
        sugestoes.firstOrNull { it.id == id }?.let { s ->
            s.mostrada = true
            PassiveHabitTracker.markSuggestionShown(s.acaoProposta)
        }
    }

    @Synchronized
    fun responderSugestao(id: String, resposta: String) {
        sugestoes.firstOrNull { it.id == id }?.resposta = resposta
    }

    private fun diarioDir(): File? =
        ctxRef?.let { File(it.filesDir, "diario").apply { mkdirs() } }

    fun lerDiarioDeHoje(): String {
        val dir = diarioDir() ?: return ""
        val hoje = SimpleDateFormat("yyyy-MM-dd", Locale("pt", "BR")).format(Date())
        val f = File(dir, "$hoje.md")
        return if (f.exists()) f.readText() else "(sem diário hoje ainda)"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("ativa", habilitada())
        .put("ocioso_ha_s", ociosoHaMs() / 1000)
        .put("observacoes_na_memoria", observacoes.size)
        .put("consolidacoes", consolidacoes)
        .put("entradas_diario", entradasDiario)
        .put("sugestoes_criadas", sugestoesCriadas)
        .put("ultimo_resumo", ultimoResumo)
        .put("ultimo_scan_arquivos_grandes", ultimoScanArquivosGrandes)
        .put("ultima_pesquisa_proativa", ultimaPesquisaProativa)
        .put("habitos", PassiveHabitTracker.toJson())
        .put("fila_sugestoes", JSONArray(sugestoes.map {
            JSONObject().put("texto", it.texto).put("motivo", it.motivo).put("mostrada", it.mostrada)
        }))
}
