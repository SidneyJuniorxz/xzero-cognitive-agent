package com.xzero.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * McpClientManager (XZERO v3):
 * Conector nativo de servidores MCP (Model Context Protocol) via JSON-RPC 2.0 / HTTP.
 * Permite registrar servidores MCP externos (Cloudflare Workers, n8n, bancos de dados, GitHub,
 * automações sua organização), descobrir ferramentas (`tools/list`) e executá-las (`tools/call`).
 */
class McpClientManager(context: Context) {

    private val file = File(context.filesDir, "mcp_servers.json")

    data class McpToolInfo(
        val name: String,
        val description: String,
        val schemaSummary: String = "{}",
        /** Argumentos marcados como `required` no `inputSchema` do servidor. */
        val requiredArgs: List<String> = emptyList(),
        /** Todos os nomes de argumento aceitos (`inputSchema.properties`). */
        val acceptedArgs: List<String> = emptyList()
    ) {
        /**
         * Valida os argumentos ANTES de gastar uma chamada de rede.
         *
         * Motivo real: o agente chamou `whatsapp_send` três vezes seguidas com o nome de
         * argumento errado e recebeu `{"error":"jid e text obrigatórios"}` do servidor em
         * todas. Cada tentativa custou um round-trip, um passo do plano e uma entrada no
         * detector de ciclo — sem nunca dizer ao agente QUAL argumento faltava. Validar
         * aqui devolve a informação exata no primeiro passo.
         *
         * Devolve `null` quando está tudo certo. Só valida quando o servidor realmente
         * declarou um schema: sem schema, nada é bloqueado (não engessa servidor novo).
         */
        fun validationError(arguments: JSONObject): String? {
            if (requiredArgs.isEmpty()) return null
            val informados = arguments.keys().asSequence().toSet()
            val faltando = requiredArgs.filter { req ->
                val v = if (informados.contains(req)) arguments.opt(req) else null
                v == null || v == JSONObject.NULL || (v is String && v.isBlank())
            }
            if (faltando.isEmpty()) return null

            // Sugere o argumento certo quando o agente usou um nome parecido/sinônimo.
            val desconhecidos = informados.filter { it !in acceptedArgs }
            val dica = if (desconhecidos.isNotEmpty() && acceptedArgs.isNotEmpty()) {
                " Você enviou ${desconhecidos.joinToString(", ")}, que esta ferramenta não aceita."
            } else ""

            return "erro de argumento em '$name': falta ${faltando.joinToString(", ")}." +
                dica +
                " Argumentos aceitos: ${acceptedArgs.joinToString(", ").ifBlank { "(schema vazio)" }}." +
                " Corrija os nomes e chame UMA vez — não repita a mesma chamada."
        }
    }

    data class McpServerConfig(
        val name: String,
        var url: String,
        var authToken: String = "",
        var enabled: Boolean = true,
        val tools: MutableList<McpToolInfo> = mutableListOf(),
        var lastSyncMs: Long = 0L,
        var lastStatus: String = "configurado",
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L,
        var lastLatencyMs: Long = 0L
    ) {
        fun isInCooldown(now: Long = System.currentTimeMillis()): Boolean =
            cooldownUntilMs > now

        fun isHealthyNow(now: Long = System.currentTimeMillis()): Boolean =
            enabled && !isInCooldown(now) &&
                !lastStatus.startsWith("offline") &&
                !lastStatus.startsWith("aguardando/offline") &&
                !lastStatus.startsWith("auto-descartado")
    }

    private val servers = mutableListOf<McpServerConfig>()

    init {
        load()
    }

    @Synchronized
    fun addOrUpdateServer(name: String, url: String, authToken: String = "", enabled: Boolean = true): String {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || cleanUrl.isEmpty()) {
            return "erro: informe name e url válidos para o servidor MCP"
        }
        val existing = servers.firstOrNull { it.name.equals(cleanName, ignoreCase = true) }
        val server = if (existing != null) {
            existing.url = cleanUrl
            if (authToken.isNotBlank()) existing.authToken = authToken.trim()
            existing.enabled = enabled
            existing
        } else {
            val s = McpServerConfig(cleanName, cleanUrl, authToken.trim(), enabled)
            servers.add(s)
            s
        }
        val syncMsg = discoverServerTools(server)
        save()
        return "Servidor MCP '${server.name}' salvo (${server.url}) | $syncMsg"
    }

    fun syncAllServers(): String {
        val enabledServers = synchronized(this) { servers.filter { it.enabled }.toList() }
        if (enabledServers.isEmpty()) return "Nenhum servidor MCP registrado para sincronizar."
        val pool = java.util.concurrent.Executors.newFixedThreadPool(enabledServers.size.coerceAtMost(4))
        val results = try {
            enabledServers.map { s ->
                pool.submit<String> { "${s.name}: ${discoverServerTools(s)}" }
            }.map { fut ->
                runCatching { fut.get(6, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { "timeout_sync" }
            }
        } finally {
            pool.shutdownNow()
        }
        save()
        return results.joinToString(" | ")
    }

    private fun recordFailure(server: McpServerConfig, reason: String) {
        server.consecutiveFailures++
        val now = System.currentTimeMillis()
        val isLocal = server.url.contains("127.0.0.1") || server.url.contains("localhost")
        // Backoff EXPONENCIAL com teto de 6h.
        // Antes o cooldown era fixo (10 min para local), então um servidor morto há dias
        // voltava a ser sondado a cada 10 min — medido: 36 chamadas MCP, 35 falhas (97%),
        // cada uma pagando connect timeout. Agora a espera dobra a cada falha seguida.
        val base = if (isLocal) 10 * 60_000L else 90_000L
        val fator = 1L shl (server.consecutiveFailures - 1).coerceIn(0, 6)
        val cooldownMs = (base * fator).coerceAtMost(6 * 60 * 60_000L)
        server.cooldownUntilMs = now + cooldownMs
        server.lastStatus = if (server.consecutiveFailures >= 3) {
            "auto-descartado (${reason.take(40)})"
        } else {
            "offline (${reason.take(45)})"
        }
        // Depois de 8 falhas seguidas o servidor é desligado de vez: parar de tentar é
        // mais honesto do que insistir. O usuário reativa com mcp_add_server quando subir.
        if (server.consecutiveFailures >= 8) {
            server.enabled = false
            server.lastStatus = "desligado após ${server.consecutiveFailures} falhas seguidas — reative quando o servidor subir"
            android.util.Log.w("McpClientManager", "🔌 MCP '${server.name}' DESLIGADO após ${server.consecutiveFailures} falhas")
        }
        android.util.Log.w("McpClientManager", "⚠️ MCP '${server.name}' falhou (${server.consecutiveFailures}x): ${server.lastStatus} | cooldown=${cooldownMs / 60000}min")
    }

    private fun recordSuccess(server: McpServerConfig, latencyMs: Long) {
        server.consecutiveFailures = 0
        server.cooldownUntilMs = 0L
        server.lastLatencyMs = latencyMs
    }

    @Synchronized
    fun reloadFromDisk(): String {
        load()
        return listServersSummary()
    }

    @Synchronized
    fun removeServer(name: String): String {
        val removed = servers.removeAll { it.name.equals(name.trim(), ignoreCase = true) }
        save()
        return if (removed) "Servidor MCP '$name' removido." else "Servidor MCP '$name' não encontrado."
    }

    /**
     * Faz handshake JSON-RPC 2.0 (`tools/list`) com o servidor MCP para descobrir ferramentas.
     */
    fun discoverServerTools(server: McpServerConfig): String {
        val t0 = System.currentTimeMillis()
        return try {
            val req = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "tools/list")
                .put("params", JSONObject())

            val resp = postJsonRpc(server.url, server.authToken, req, connectTimeoutMs = 2500, readTimeoutMs = 4000)
            val resultObj = resp.optJSONObject("result") ?: resp
            val toolsArr = resultObj.optJSONArray("tools")
            server.tools.clear()
            if (toolsArr != null) {
                for (i in 0 until toolsArr.length()) {
                    val t = toolsArr.getJSONObject(i)
                    val tName = t.optString("name")
                    val tDesc = t.optString("description", "Ferramenta MCP")
                    val inputSchema = t.optJSONObject("inputSchema")
                    val propNames = inputSchema?.optJSONObject("properties")?.names()
                    val schema = propNames?.toString() ?: "{}"
                    val accepted = (0 until (propNames?.length() ?: 0)).mapNotNull { propNames?.optString(it) }
                        .filter { it.isNotBlank() }
                    val requiredArr = inputSchema?.optJSONArray("required")
                    val required = (0 until (requiredArr?.length() ?: 0)).mapNotNull { requiredArr?.optString(it) }
                        .filter { it.isNotBlank() }
                    if (tName.isNotBlank()) {
                        server.tools.add(
                            McpToolInfo(tName, tDesc.take(140), schema, required, accepted)
                        )
                    }
                }
            }
            val lat = System.currentTimeMillis() - t0
            recordSuccess(server, lat)
            server.lastSyncMs = System.currentTimeMillis()
            server.lastStatus = "online (${server.tools.size} tools, ${lat}ms)"
            save()
            "conectado: ${server.tools.size} ferramenta(s) em ${lat}ms"
        } catch (e: Exception) {
            recordFailure(server, e.message ?: "erro_conexao")
            save()
            "descartado/offline (${e.message?.take(55)})"
        }
    }

    /**
     * Executa uma ferramenta (`tools/call`) em um servidor MCP conectado.
     */
    fun callTool(serverName: String, toolName: String, arguments: JSONObject): String {
        val srv = synchronized(this) {
            if (serverName.isBlank()) {
                servers.firstOrNull { it.isHealthyNow() && it.tools.any { t -> t.name.equals(toolName, ignoreCase = true) } }
                    ?: servers.firstOrNull { it.isHealthyNow() }
            } else {
                servers.firstOrNull { it.name.equals(serverName.trim(), ignoreCase = true) }
            }
        } ?: return "erro: servidor MCP '$serverName' indisponível ou descartado por lentidão/offline. Use ações nativas na tela."

        if (!srv.enabled) return "erro: servidor MCP '${srv.name}' está desativado."
        if (srv.isInCooldown()) {
            val remSec = ((srv.cooldownUntilMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(1L)
            return "erro: servidor MCP '${srv.name}' em cooldown por falha/lentidão (${remSec}s). Use outra abordagem nativa."
        }

        /*
         * Validação local ANTES do round-trip. Uma chamada malformada não deve consumir
         * rede, nem contar como falha do servidor no circuit breaker: o servidor está são,
         * foi o argumento que veio errado. Por isso este retorno não passa por
         * `recordFailure` — penalizar o MCP aqui isolaria um servidor saudável.
         */
        srv.tools.firstOrNull { it.name.equals(toolName, ignoreCase = true) }
            ?.validationError(arguments)
            ?.let { return it }

        val t0 = System.currentTimeMillis()
        return try {
            val params = JSONObject()
                .put("name", toolName)
                .put("arguments", arguments)
            val req = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", System.currentTimeMillis() % 100000)
                .put("method", "tools/call")
                .put("params", params)

            val resp = postJsonRpc(srv.url, srv.authToken, req, connectTimeoutMs = 3000, readTimeoutMs = 12000)
            val lat = System.currentTimeMillis() - t0
            if (resp.has("error")) {
                val errObj = resp.optJSONObject("error")
                return "erro MCP [${srv.name}/$toolName]: ${errObj?.optString("message") ?: resp.optString("error")}"
            }
            recordSuccess(srv, lat)
            srv.lastStatus = "online (${srv.tools.size} tools, ${lat}ms)"
            val result = resp.optJSONObject("result") ?: resp
            val contentArr = result.optJSONArray("content")
            if (contentArr != null && contentArr.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.optJSONObject(i)
                    if (item != null) {
                        sb.append(item.optString("text", item.toString())).append('\n')
                    }
                }
                "MCP [${srv.name}/$toolName]: ${sb.toString().trim().take(1200)}"
            } else {
                "MCP [${srv.name}/$toolName]: ${result.toString().take(1200)}"
            }
        } catch (e: Exception) {
            recordFailure(srv, e.message ?: "erro_call")
            save()
            "erro ao chamar MCP [${srv.name}/$toolName] (servidor isolado temporariamente): ${e.message}"
        }
    }

    @Synchronized
    fun listServersSummary(): String {
        if (servers.isEmpty()) {
            return "Nenhum servidor MCP conectado no momento. Use mcp_add_server {\"name\":\"...\",\"url\":\"https://...\"} para conectar."
        }
        return servers.joinToString("\n") { s ->
            val toolsStr = if (s.tools.isEmpty()) "(nenhuma tool listada)"
            else s.tools.joinToString(", ") { "${it.name}: ${it.description.take(45)}" }
            "• MCP [${s.name}] (${s.url}) status=${s.lastStatus} -> Tools: $toolsStr"
        }
    }

    @Synchronized
    fun getPromptContextSummary(): String {
        val active = servers.filter { it.isHealthyNow() }
        if (active.isEmpty()) return ""
        val sb = StringBuilder("SERVIDORES MCP CONECTADOS AO XZERO:\n")
        for (s in active) {
            sb.append("• [").append(s.name).append("] (").append(s.statusShort()).append("): ")
            if (s.tools.isEmpty()) {
                sb.append("chame via mcp_call {\"server\":\"").append(s.name).append("\",\"tool\":\"<nome>\",\"arguments\":{...}}\n")
            } else {
                sb.append(s.tools.joinToString("; ") { "${it.name}(${it.signatureForPrompt()})" }).append('\n')
            }
        }
        return sb.toString().trim()
    }

    private fun McpServerConfig.statusShort() = if (enabled) lastStatus else "desativado"

    /**
     * Assinatura mostrada ao LLM no prompt. Marca com `*` os argumentos obrigatórios, para
     * que o modelo acerte o nome na PRIMEIRA chamada — antes custava três tentativas e um
     * ciclo detectado descobrir que `whatsapp_send` exige `jid` e não `recipient`.
     */
    private fun McpToolInfo.signatureForPrompt(): String {
        if (acceptedArgs.isEmpty()) return schemaSummary
        return acceptedArgs.joinToString(", ") { arg ->
            if (arg in requiredArgs) "$arg*" else arg
        }
    }

    @Synchronized
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in servers) {
            val tArr = JSONArray()
            for (t in s.tools) {
                tArr.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("schema", t.schemaSummary)
                )
            }
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("has_token", s.authToken.isNotBlank())
                    .put("enabled", s.enabled)
                    .put("last_status", s.lastStatus)
                    .put("last_sync_ms", s.lastSyncMs)
                    .put("tools", tArr)
            )
        }
        return JSONObject().put("servers", arr).put("count", servers.size)
    }

    private fun postJsonRpc(
        urlStr: String,
        authToken: String,
        payload: JSONObject,
        connectTimeoutMs: Int = 3000,
        readTimeoutMs: Int = 12000
    ): JSONObject {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
            return try {
                exec.submit<JSONObject> { postJsonRpcInternal(urlStr, authToken, payload, connectTimeoutMs, readTimeoutMs) }
                    .get((connectTimeoutMs + readTimeoutMs + 1000).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            } finally {
                exec.shutdownNow()
            }
        }
        return postJsonRpcInternal(urlStr, authToken, payload, connectTimeoutMs, readTimeoutMs)
    }

    private fun postJsonRpcInternal(
        urlStr: String,
        authToken: String,
        payload: JSONObject,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (authToken.isNotBlank()) {
                val headerVal = if (authToken.lowercase(Locale.US).startsWith("bearer ")) authToken else "Bearer $authToken"
                setRequestProperty("Authorization", headerVal)
            }
        }
        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty().trim()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${raw.take(160)}")
        }
        // Suporta tanto JSON puro quanto resposta SSE ("data: {...}") de servidores MCP modernos
        val jsonLine = if (raw.startsWith("event:") || raw.contains("\ndata:")) {
            raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("data:") && it.contains("{") }
                ?.removePrefix("data:")?.trim() ?: raw
        } else {
            raw
        }
        return JSONObject(jsonLine)
    }

    @Synchronized
    private fun save() {
        runCatching {
            val arr = JSONArray()
            for (s in servers) {
                val tArr = JSONArray()
                for (t in s.tools) {
                    tArr.put(
                        JSONObject()
                            .put("n", t.name)
                            .put("d", t.description)
                            .put("s", t.schemaSummary)
                            .put("r", JSONArray(t.requiredArgs))
                            .put("a", JSONArray(t.acceptedArgs))
                    )
                }
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("url", s.url)
                        .put("token", s.authToken)
                        .put("enabled", s.enabled)
                        .put("status", s.lastStatus)
                        .put("sync", s.lastSyncMs)
                        .put("tools", tArr)
                )
            }
            file.writeText(JSONObject().put("servers", arr).toString())
        }
    }

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("servers") ?: return
            servers.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val toolsList = mutableListOf<McpToolInfo>()
                val tArr = o.optJSONArray("tools")
                if (tArr != null) {
                    for (j in 0 until tArr.length()) {
                        val to = tArr.getJSONObject(j)
                        fun strList(key: String): List<String> {
                            val a = to.optJSONArray(key) ?: return emptyList()
                            return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                        }
                        toolsList.add(
                            McpToolInfo(
                                name = to.optString("n"),
                                description = to.optString("d"),
                                schemaSummary = to.optString("s", "{}"),
                                requiredArgs = strList("r"),
                                acceptedArgs = strList("a")
                            )
                        )
                    }
                }
                servers.add(
                    McpServerConfig(
                        name = o.optString("name"),
                        url = o.optString("url"),
                        authToken = o.optString("token", ""),
                        enabled = o.optBoolean("enabled", true),
                        tools = toolsList,
                        lastSyncMs = o.optLong("sync", 0L),
                        lastStatus = o.optString("status", "configurado")
                    )
                )
            }
        }
    }
}
