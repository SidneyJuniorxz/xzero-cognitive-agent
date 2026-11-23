package com.xzero.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * API de monitoramento em tempo real via SSE (Server-Sent Events) + REST na porta 3030.
 * Expõe dados de raciocínio, erros e métricas para o dashboard sem conflitar com a porta 3029.
 */
object RealtimeMonitoringAPI {

    private const val THINKING_PORT = 3030
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sseClients = CopyOnWriteArrayList<Socket>()
    @Volatile private var serverRunning = false
    @Volatile private var restServerSocket: ServerSocket? = null
    /** Guardado para servir o dashboard a partir dos assets do app. */
    @Volatile private var appContext: Context? = null

    /**
     * Inicia servidor de monitoramento HTTP/SSE na porta 3030.
     */
    fun startServer(ctx: Context) {
        appContext = ctx.applicationContext
        val sockAlive = restServerSocket?.let { !it.isClosed } == true
        if (serverRunning && sockAlive) return

        serverRunning = true
        Log.i("RealtimeMonitoringAPI", "🚀 Iniciando servidor de monitoramento SSE+REST na porta $THINKING_PORT")

        thread(isDaemon = true) {
            startRESTServer()
        }
    }

    /**
     * Para o servidor.
     */
    fun stopServer() {
        serverRunning = false
        runCatching { restServerSocket?.close() }
        restServerSocket = null
        sseClients.forEach { runCatching { it.close() } }
        sseClients.clear()
        Log.i("RealtimeMonitoringAPI", "⛔ Servidor de monitoramento parado")
    }

    /**
     * Envia atualização em tempo real (SSE) para todos os clientes conectados.
     */
    fun broadcastUpdate(data: JSONObject) {
        if (sseClients.isEmpty()) return
        scope.launch {
            val payload = "data: $data\n\n".toByteArray(Charsets.UTF_8)
            for (socket in sseClients) {
                try {
                    if (socket.isClosed || !socket.isConnected) {
                        sseClients.remove(socket)
                        continue
                    }
                    synchronized(socket) {
                        socket.getOutputStream().write(payload)
                        socket.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    sseClients.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }
    }

    /**
     * REST + SSE Server HTTP.
     */
    private fun startRESTServer() {
        try {
            val serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(THINKING_PORT))
            }
            restServerSocket = serverSocket
            Log.i("RealtimeMonitoringAPI", "✅ REST+SSE Server iniciado na porta $THINKING_PORT")

            while (serverRunning) {
                try {
                    val socket = serverSocket.accept()
                    thread(isDaemon = true) {
                        handleRESTRequest(socket)
                    }
                } catch (e: Exception) {
                    if (serverRunning) {
                        Log.e("RealtimeMonitoringAPI", "Erro ao aceitar REST: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RealtimeMonitoringAPI", "Erro no REST Server: ${e.message}")
        }
    }

    private fun handleSSEStream(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            val headers = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream; charset=utf-8\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            out.write(headers)

            val initialData = JSONObject().apply {
                put("event", "connection_established")
                put("thinking", ThinkingLogger.getThinkingJson())
                put("errors", ErrorDetector.getErrorStats())
                put("metrics", MetricsCollector.getMetrics().toJson())
            }
            out.write("data: $initialData\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
            sseClients.add(socket)
            Log.i("RealtimeMonitoringAPI", "👤 Cliente SSE conectado (total: ${sseClients.size})")

            // Mantém o socket aberto até desconectar ou parar o servidor
            while (serverRunning && !socket.isClosed) {
                Thread.sleep(5000)
                synchronized(socket) {
                    out.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            sseClients.remove(socket)
            runCatching { socket.close() }
        }
    }

    /**
     * Trata requisição HTTP REST/SSE.
     */
    private fun handleRESTRequest(socket: Socket) {
        var keepOpen = false
        try {
            val reader = socket.inputStream.bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val path = parts[1].substringBefore("?")

            var contentLength = 0
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val requestBody = if (contentLength > 0 && contentLength <= 262_144) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r <= 0) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else ""

            if (method == "OPTIONS") {
                val opts = (
                    "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                socket.getOutputStream().write(opts)
                socket.getOutputStream().flush()
                return
            }

            if (path == "/harness" && method == "GET") {
                val html = buildHarnessDashboardHtml().toByteArray(Charsets.UTF_8)
                val headers = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${html.size}\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                socket.getOutputStream().write(headers)
                socket.getOutputStream().write(html)
                socket.getOutputStream().flush()
                return
            }

            // Dashboard servido pelo PRÓPRIO celular: abra http://localhost:3030/ no navegador
            // do aparelho. Mesma origem = SSE e fetch funcionam sem PC e sem CORS.
            if ((path == "/" || path == "/dashboard" || path == "/monitoring.html") && method == "GET") {
                val html = runCatching {
                    appContext?.assets?.open("monitoring.html")?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (html == null) {
                    val msg = "dashboard não encontrado nos assets".toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(
                        ("HTTP/1.1 404 Not Found\r\nContent-Length: ${msg.size}\r\n\r\n").toByteArray(Charsets.UTF_8)
                    )
                    socket.getOutputStream().write(msg)
                } else {
                    val body = html.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Cache-Control: no-store\r\n\r\n"
                            ).toByteArray(Charsets.UTF_8)
                    )
                    socket.getOutputStream().write(body)
                }
                socket.getOutputStream().flush()
                return
            }

            if (path == "/" || path == "/dashboard") {
                val html = buildEmbeddedDashboardHtml().toByteArray(Charsets.UTF_8)
                val headers = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${html.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
                socket.getOutputStream().write(headers)
                socket.getOutputStream().write(html)
                socket.getOutputStream().flush()
                return
            }

            if (path == "/api/agent/stream" && method == "GET") {
                keepOpen = true
                handleSSEStream(socket)
                return
            }

            val response = when {
                path == "/api/agent/thinking" && method == "GET" -> {
                    JSONObject().apply {
                        put("thinking", ThinkingLogger.getThinkingJson())
                        put("count", ThinkingLogger.getCurrentThinking().size)
                    }
                }

                path == "/api/agent/errors" && method == "GET" -> {
                    JSONObject().apply {
                        // put(String, List) serializa via toString() e vira STRING escapada;
                        // o dashboard recebia texto em vez de array. Precisa ser JSONArray.
                        put("errors", JSONArray(ErrorDetector.getErrorHistory().map { it.toJson() }))
                        put("stats", ErrorDetector.getErrorStats())
                    }
                }

                path == "/api/agent/metrics" && method == "GET" -> {
                    MetricsCollector.getMetrics().toJson()
                }

                // Monitoramento do pipeline de áudio: nível do mic, piso de ruído, limiar do VAD,
                // latência para acordar e desfecho de cada confirmação falada.
                path == "/api/agent/audio" && method == "GET" -> {
                    AudioTelemetry.getJson()
                }

                path == "/api/agent/timeline" && method == "GET" -> {
                    JSONObject().apply {
                        put("timeline", JSONArray(MetricsCollector.getTimeline().map { it.toJson() }))
                    }
                }

                path == "/api/agent/analysis" && method == "GET" -> {
                    MetricsCollector.getAnalysis()
                }

                path == "/api/agent/summary" && method == "GET" -> {
                    JSONObject().apply {
                        put("summary", MetricsCollector.getSummary())
                    }
                }

                path == "/api/agent/harness-insights" && method == "GET" -> {
                    buildHarnessInsightsJson()
                }

                path.startsWith("/api/agent/session/") && method == "GET" -> {
                    val rawSid = path.removePrefix("/api/agent/session/").substringBefore("?").trim()
                    val cleanSid = rawSid.replace(Regex("[^a-zA-Z0-9_.-]"), "")
                    val ctx = appContext
                    val out = JSONObject().put("session_id", cleanSid)
                    if (ctx != null && cleanSid.isNotEmpty()) {
                        val file = File(File(ctx.filesDir, "trajectories"), "$cleanSid.jsonl")
                        if (file.exists()) {
                            val stepsArr = JSONArray()
                            var task = ""
                            var startedAt = 0L
                            var outcome = "in_progress"
                            var answer = ""
                            var vReason = ""
                            var endedAt = 0L
                            runCatching {
                                for (line in file.readLines()) {
                                    if (line.isBlank()) continue
                                    val ev = JSONObject(line)
                                    val t = ev.optString("type")
                                    val dt = ev.optJSONObject("data") ?: continue
                                    when (t) {
                                        "session_start" -> {
                                            task = dt.optString("task")
                                            startedAt = dt.optLong("started_at", 0L)
                                        }
                                        "agent_step" -> {
                                            stepsArr.put(
                                                JSONObject()
                                                    .put("step", dt.optInt("step"))
                                                    .put("provider", dt.optString("provider"))
                                                    .put("plan", dt.optString("plan"))
                                                    .put("thought", dt.optString("thought"))
                                                    .put("action", dt.optString("action"))
                                                    .put("args", dt.opt("args")?.toString() ?: "{}")
                                                    .put("result", dt.optString("result").take(350))
                                            )
                                        }
                                        "session_end" -> {
                                            outcome = dt.optString("outcome", outcome)
                                            answer = dt.optString("answer")
                                            vReason = dt.optString("verification_reason")
                                            endedAt = dt.optLong("ended_at", 0L)
                                        }
                                    }
                                }
                            }
                            out.put("found", true)
                                .put("task", task)
                                .put("started_at", startedAt)
                                .put("ended_at", endedAt)
                                .put("outcome", outcome)
                                .put("answer", answer)
                                .put("verification_reason", vReason)
                                .put("steps", stepsArr)
                        } else {
                            out.put("found", false).put("error", "trajetória não encontrada")
                        }
                    } else {
                        out.put("found", false).put("error", "session_id inválido")
                    }
                    out
                }

                path == "/api/agent/system" && method == "GET" -> {
                    JSONObject().apply {
                        val ctx = appContext
                        put("sensores", JSONObject().apply {
                            put("resumo", ProactiveWatcher.getSensorSummary())
                            put("modo", ProactiveWatcher.sensorMode)
                            put("luz_lux", ProactiveWatcher.ambientLux.toDouble())
                            put("bussola_graus", ProactiveWatcher.azimuthDeg)
                            put("campo_mag_ut", ProactiveWatcher.magneticUt.toDouble())
                            put("pressao_hpa", ProactiveWatcher.pressureHpa.toDouble())
                            put("movimento", ProactiveWatcher.deviceMoving)
                            put("proximidade", ProactiveWatcher.proximityNear)
                            put("passos", ProactiveWatcher.stepCount)
                            put("sensores_hw_total", ProactiveWatcher.availableSensorsCount)
                            put("sensores_hw_lista", ProactiveWatcher.availableSensorsNames)
                        })
                        put("habitos", PassiveHabitTracker.toJson())
                        put("iniciativa", InitiativeEngine.toJson())
                        put("visual", BubbleService.instance?.getCurrentVisualStateSummary() ?: JSONObject())
                        put("audio", AudioTelemetry.getJson())
                        if (ctx != null) {
                            val prefs = Prefs(ctx)
                            put("voz", JSONObject().apply {
                                put("perfil", prefs.voiceProfile)
                                put("voz_neural", prefs.neuralVoice)
                                put("faber_disponivel", NeuralTtsEngine.isAvailable(ctx, "faber"))
                                put("cadu_disponivel", NeuralTtsEngine.isAvailable(ctx, "cadu"))
                                put("voz_fixada", prefs.pinnedVoiceName)
                                put("speak", prefs.speak)
                                put("wake_word", prefs.wakeWord)
                            })
                        }
                    }
                }

                path == "/api/agent/voice-profile" && method == "POST" -> {
                    val ctx = appContext
                    val body = runCatching { JSONObject(requestBody) }.getOrNull() ?: JSONObject()
                    val profile = body.optString("profile", "").trim()
                    val neural = body.optString("neural_voice", "").trim()
                    if (ctx != null && (profile in listOf("jarvis", "natural", "fast") || neural in listOf("faber", "cadu", "system"))) {
                        val p = Prefs(ctx)
                        if (profile in listOf("jarvis", "natural", "fast")) p.voiceProfile = profile
                        if (neural in listOf("faber", "cadu", "system")) p.neuralVoice = neural
                        JSONObject().put("ok", true).put("profile", p.voiceProfile).put("neural_voice", p.neuralVoice)
                    } else {
                        JSONObject().put("ok", false).put("error", "parâmetro inválido (profile=jarvis|natural|fast, neural_voice=faber|cadu|system)")
                    }
                }

                path == "/api/agent/watches" && method == "GET" -> {
                    val eventsArr = JSONArray()
                    val ctx = appContext
                    if (ctx != null) {
                        val dir = File(ctx.filesDir, "trajectories")
                        val files = dir.listFiles { f -> f.name.endsWith(".jsonl") }
                            ?.sortedByDescending { it.lastModified() }
                            ?.take(35)
                            ?: emptyList()
                        for (f in files) {
                            if (eventsArr.length() >= 15) break
                            runCatching {
                                val text = f.readText()
                                if (!text.contains("EVENTO DE VIGIA")) return@runCatching
                                var task = ""
                                var startedAt = 0L
                                var outcome = "in_progress"
                                var answer = ""
                                var sid = f.nameWithoutExtension
                                for (line in text.lineSequence()) {
                                    if (line.isBlank()) continue
                                    val ev = JSONObject(line)
                                    val t = ev.optString("type")
                                    val dt = ev.optJSONObject("data") ?: continue
                                    if (t == "session_start") {
                                        task = dt.optString("task")
                                        startedAt = dt.optLong("started_at", 0L)
                                    } else if (t == "session_end") {
                                        outcome = dt.optString("outcome", outcome)
                                        answer = dt.optString("answer")
                                    }
                                }
                                if (task.isNotEmpty()) {
                                    eventsArr.put(
                                        JSONObject()
                                            .put("session_id", sid)
                                            .put("task", task)
                                            .put("started_at", startedAt)
                                            .put("outcome", outcome)
                                            .put("answer", answer)
                                    )
                                }
                            }
                        }
                    }
                    JSONObject()
                        .put("watches", ProactiveWatcher.watchesToJsonArray())
                        .put("recentEvents", eventsArr)
                }

                path == "/api/agent/watches" && method == "POST" -> {
                    val body = runCatching { JSONObject(requestBody) }.getOrNull() ?: JSONObject()
                    val source = body.optString("source", "unlock").trim()
                    val filter = body.optString("filter", "").trim().ifEmpty { if (source == "unlock") "desbloqueio" else "any" }
                    val instruction = body.optString("instruction", "Avisar usuário").trim()
                    val autoOpen = body.optBoolean("autoOpen", false)
                    val msg = ProactiveWatcher.addWatch(source, filter, instruction, autoOpen)
                    JSONObject().put("ok", true).put("message", msg).put("watches", ProactiveWatcher.watchesToJsonArray())
                }

                path.startsWith("/api/agent/watches/delete") && method == "POST" -> {
                    val body = runCatching { JSONObject(requestBody) }.getOrNull() ?: JSONObject()
                    val id = body.optString("id", "").trim()
                    val msg = if (id.isNotEmpty()) ProactiveWatcher.removeWatch(id) else "ID vazio"
                    JSONObject().put("ok", id.isNotEmpty()).put("message", msg).put("watches", ProactiveWatcher.watchesToJsonArray())
                }

                path == "/api/agent/energy" && method == "GET" -> {
                    val ctx = appContext
                    val batJson = JSONObject()
                    val hwSensors = JSONArray()
                    val toolsArr = JSONArray()
                    if (ctx != null) {
                        val batIntent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        val level = batIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = batIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
                        val temp = (batIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
                        val volt = batIntent?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                        val plugged = batIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                        val src = when (plugged) {
                            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                            android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                            else -> "Bateria"
                        }
                        batJson.put("level", pct).put("temperatureC", temp).put("voltageMv", volt).put("source", src)

                        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                        val cfg = ProactiveWatcher.toSensorConfigJson()
                        val list = sm?.getSensorList(android.hardware.Sensor.TYPE_ALL) ?: emptyList()
                        for (s in list.take(25)) {
                            val activeInAda = when (s.type) {
                                android.hardware.Sensor.TYPE_LIGHT -> cfg.optBoolean("light")
                                android.hardware.Sensor.TYPE_ACCELEROMETER -> cfg.optBoolean("accelerometer")
                                android.hardware.Sensor.TYPE_PROXIMITY -> cfg.optBoolean("proximity")
                                android.hardware.Sensor.TYPE_GYROSCOPE -> cfg.optBoolean("gyroscope")
                                android.hardware.Sensor.TYPE_STEP_COUNTER -> cfg.optBoolean("steps")
                                else -> false
                            }
                            hwSensors.put(
                                JSONObject()
                                    .put("name", s.name)
                                    .put("type", s.type)
                                    .put("powerMa", String.format(java.util.Locale.US, "%.2f", s.power).toDouble())
                                    .put("activeInAda", activeInAda)
                            )
                        }

                        val counts = mutableMapOf<String, Int>()
                        val trajDir = File(ctx.filesDir, "trajectories")
                        val tFiles = trajDir.listFiles { f -> f.name.endsWith(".jsonl") }
                            ?.sortedByDescending { it.lastModified() }
                            ?.take(30)
                            ?: emptyList()
                        for (tf in tFiles) {
                            runCatching {
                                for (line in tf.readLines()) {
                                    if (line.isBlank()) continue
                                    val ev = JSONObject(line)
                                    if (ev.optString("type") == "agent_step") {
                                        val act = ev.optJSONObject("data")?.optString("action").orEmpty()
                                        if (act.isNotEmpty()) counts[act] = (counts[act] ?: 0) + 1
                                    }
                                }
                            }
                        }
                        counts.entries.sortedByDescending { it.value }.take(15).forEach { (act, c) ->
                            val impact = when (act) {
                                "capture_camera", "get_location", "inspect_screen_visual" -> "Alto"
                                "open_app", "tap", "type", "web_search", "mcp_call", "send_whatsapp" -> "Médio"
                                else -> "Baixo"
                            }
                            toolsArr.put(JSONObject().put("action", act).put("calls", c).put("impact", impact))
                        }
                    }
                    JSONObject()
                        .put("battery", batJson)
                        .put("sensorConfig", ProactiveWatcher.toSensorConfigJson())
                        .put("sensorSummary", ProactiveWatcher.getSensorSummary())
                        .put("hardwareSensors", hwSensors)
                        .put("tools", toolsArr)
                }

                path == "/api/agent/energy/sensors" && method == "POST" -> {
                    val ctx = appContext
                    val body = runCatching { JSONObject(requestBody) }.getOrNull() ?: JSONObject()
                    val mode = body.optString("mode", "").ifBlank { ProactiveWatcher.sensorMode }
                    val res = if (ctx != null) {
                        ProactiveWatcher.configureSensors(
                            ctx = ctx,
                            mode = mode,
                            light = body.optBoolean("light", ProactiveWatcher.enableLight),
                            accel = body.optBoolean("accelerometer", ProactiveWatcher.enableAccel),
                            proximity = body.optBoolean("proximity", ProactiveWatcher.enableProximity),
                            gyro = body.optBoolean("gyroscope", ProactiveWatcher.enableGyro),
                            steps = body.optBoolean("steps", ProactiveWatcher.enableSteps),
                            autoSilent = body.optBoolean("auto_silent", ProactiveWatcher.autoSilentInDarkOrPocket),
                            keepCriticalOnly = body.optBoolean("keep_critical_only", false)
                        )
                    } else "contexto indisponível"
                    JSONObject().put("ok", ctx != null).put("message", res).put("sensorConfig", ProactiveWatcher.toSensorConfigJson())
                }

                path == "/mcp" && method == "POST" -> {
                    handleMcpJsonRpc(requestBody)
                }

                path == "/health" && method == "GET" -> {
                    JSONObject().apply {
                        put("status", "ok")
                        put("timestamp", System.currentTimeMillis())
                        put("clients_connected", sseClients.size)
                        put("mcp_endpoint", "/mcp")
                    }
                }

                else -> {
                    JSONObject().apply {
                        put("error", "Endpoint não encontrado: $path")
                    }
                }
            }

            val bodyBytes = response.toString().toByteArray(Charsets.UTF_8)
            val headers = (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)

            socket.getOutputStream().write(headers)
            socket.getOutputStream().write(bodyBytes)
            socket.getOutputStream().flush()
        } catch (e: Exception) {
            Log.e("RealtimeMonitoringAPI", "Erro ao processar REST: ${e.message}")
        } finally {
            if (!keepOpen) {
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Servidor MCP nativo (JSON-RPC 2.0 via HTTP POST /mcp na porta 3030) para que
     * ChatGPT, Codex, Claude ou qualquer cliente MCP acesse o estado real do celular
     * (tela, sensores, notificações, diário, telemetria de áudio e envio de comandos à ADA).
     */
    private fun handleMcpJsonRpc(body: String): JSONObject {
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", JSONObject.NULL)
                .put("error", JSONObject().put("code", -32700).put("message", "JSON inválido"))
        val id = req.opt("id") ?: JSONObject.NULL
        val rpcMethod = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        fun ok(result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        fun toolText(text: String) = ok(
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        )

        return when (rpcMethod) {
            "initialize" -> ok(
                JSONObject()
                    .put("protocolVersion", "2024-11-05")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "ada-xzero-phone-mcp").put("version", "1.0.0"))
            )
            "notifications/initialized" -> ok(JSONObject())
            "tools/list" -> {
                fun tool(name: String, desc: String, props: JSONObject = JSONObject()) = JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put("inputSchema", JSONObject().put("type", "object").put("properties", props))
                val tools = JSONArray()
                    .put(tool("phone_status", "Retorna bateria, RAM, rede, temperatura, sensores e estado do áudio/VAD da ADA."))
                    .put(tool("read_screen", "Lê a árvore de acessibilidade atual da tela do celular."))
                    .put(tool("read_notifications", "Lista as notificações ativas no aparelho (título, app e texto)."))
                    .put(tool("read_diary", "Lê o diário autônomo de hoje gerado pela ADA (metadados, sensores e padrões)."))
                    .put(
                        tool(
                            "ask_ada",
                            "Envia uma pergunta ou comando para a ADA executar ou responder no celular.",
                            JSONObject().put("command", JSONObject().put("type", "string").put("description", "Comando ou pergunta em linguagem natural"))
                        )
                    )
                ok(JSONObject().put("tools", tools))
            }
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val text = when (name) {
                    "phone_status" -> {
                        val ctx = appContext
                        val health = if (ctx != null) HealthMonitor.getTelemetry(ctx).toString() else "{}"
                        "Sensores: ${ProactiveWatcher.getSensorSummary()} | Saúde: $health | Áudio: ${AudioTelemetry.getJson()}"
                    }
                    "read_screen" -> AgentAccessibilityService.instance?.dumpScreen()
                        ?: "Acessibilidade indisponível no momento."
                    "read_notifications" -> NotifListener.instance?.summary()?.ifBlank { "(sem notificações ativas)" }
                        ?: "(serviço de notificações desconectado)"
                    "read_diary" -> InitiativeEngine.lerDiarioDeHoje()
                    "ask_ada" -> {
                        val cmd = args.optString("command").trim()
                        if (cmd.isEmpty()) "erro: informe 'command'"
                        else {
                            val svc = BubbleService.instance
                            if (svc == null) "erro: BubbleService offline"
                            else {
                                svc.handleText(cmd, fromPanel = false)
                                "Comando encaminhado para a ADA: $cmd"
                            }
                        }
                    }
                    else -> "erro: ferramenta MCP desconhecida ($name)"
                }
                toolText(text)
            }
            else -> JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "Método não suportado: $rpcMethod"))
        }
    }

    private fun buildEmbeddedDashboardHtml(): String = """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>X0 Monitoramento Ao Vivo</title>
        <style>
        body{background:#090d16;color:#e2e8f0;font-family:system-ui,sans-serif;margin:0;padding:14px}
        h1{font-size:18px;margin:0 0 10px;color:#38bdf8}
        .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:10px;margin-bottom:12px}
        .card{background:#0f172a;border:1px solid #1e293b;border-radius:12px;padding:12px}
        .val{font-size:20px;font-weight:700;color:#22d3ee}
        .lbl{font-size:11px;color:#94a3b8}
        pre{background:#020617;padding:10px;border-radius:8px;overflow:auto;font-size:12px;max-height:240px;white-space:pre-wrap}
        </style>
        </head>
        <body>
        <h1>📊 X0 Monitoramento Ao Vivo</h1>
        <div class="grid">
          <div class="card"><div class="lbl">Taxa de Sucesso</div><div class="val" id="sr">--</div></div>
          <div class="card"><div class="lbl">Tempo Médio</div><div class="val" id="rt">--</div></div>
          <div class="card"><div class="lbl">Ações</div><div class="val" id="ac">--</div></div>
          <div class="card"><div class="lbl">Erros</div><div class="val" id="er">--</div></div>
        </div>
        <div class="card" style="margin-bottom:12px"><b>🧠 Raciocínio em Tempo Real</b><pre id="th">Aguardando passos...</pre></div>
        <div class="card"><b>⚠️ Erros & Timeline</b><pre id="tl">Aguardando eventos...</pre></div>
        <script>
        async function refresh(){
          try{
            const [m,t,e]=await Promise.all([
              fetch('/api/agent/metrics').then(r=>r.json()),
              fetch('/api/agent/thinking').then(r=>r.json()),
              fetch('/api/agent/errors').then(r=>r.json())
            ]);
            document.getElementById('sr').textContent=m.success_rate||'0%';
            document.getElementById('rt').textContent=(m.avg_response_time_ms||0)+'ms';
            document.getElementById('ac').textContent=(m.successful_actions||0)+'/'+(m.total_actions||0);
            document.getElementById('er').textContent=m.error_count||0;
            const steps=(t.thinking||[]).slice(-8).map(s=>'['+(s.step||'?')+'] '+s.phase+': '+(s.details||'')+' -> '+(s.action||'-')).join('\n');
            if(steps) document.getElementById('th').textContent=steps;
            const errs=(e.errors||[]).slice(-5).map(x=>x.type+': '+x.reason+' ('+x.suggestion+')').join('\n');
            document.getElementById('tl').textContent=errs||'Nenhum erro detectado nesta sessão.';
          }catch(_){}
        }
        refresh(); setInterval(refresh, 1500);
        </script>
        </body>
        </html>
    """.trimIndent()

    private fun MetricsCollector.SessionMetrics.toJson(): JSONObject {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("start_time", startTime)
            put("total_actions", totalActions)
            put("successful_actions", successfulActions)
            put("failed_actions", failedActions)
            put("error_count", errorCount)
            put("avg_response_time_ms", String.format("%.0f", avgResponseTime))
            put("avg_confidence", String.format("%.2f", avgConfidence))
            put("routes_used", routesUsed)
            put("graph_queries", graphQueriesCount)
            put("correction_attempts", correctionAttempts)
            put("success_rate", String.format("%.2f%%", successRate * 100))
        }
    }

    private fun MetricsCollector.ActionEvent.toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("action_number", actionNumber)
            put("action", action)
            put("success", success)
            put("response_time_ms", responseTime)
            put("confidence", confidence)
            put("result", result)
        }
    }

    private fun ErrorDetector.DetectedError.toJson(): JSONObject {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("type", type.name)
            put("step", step)
            put("expected", expected)
            put("actual", actual)
            put("reason", reason)
            put("suggestion", suggestion)
            put("severity", severity)
        }
    }

    /**
     * Classifica se um `agent_step` foi uma falha real, a partir do texto do `result`.
     *
     * Existe porque a heurística anterior era `result.contains("erro")`, e o próprio texto
     * de SUCESSO do verificador de meta diz "Sequência de N etapa(s) verificada **sem erros**".
     * Resultado: 25 das 35 conclusões bem-sucedidas eram contadas como falha, o `done` aparecia
     * com 28,6% de acerto no painel e o motor de insights apontava a ação mais saudável do
     * sistema como a mais problemática. Telemetria que mente é pior que telemetria ausente.
     *
     * A ordem importa: sinais estruturados (`goal_verified`, `score=`) vencem qualquer
     * casamento de texto, e negações ("sem erro", "nenhum erro") são descartadas antes de
     * procurar a palavra "erro" solta.
     */
    internal fun classifyStepFailure(action: String, result: String): Boolean {
        if (action == "unconfirmed_timeout" || action == "done_blocked") return true

        // 1. Sinal estruturado e explícito do verificador de meta — é a fonte da verdade.
        Regex("goal_verified\\s*=\\s*(true|false)").find(result)?.let {
            return it.groupValues[1] == "false"
        }

        // 2. Score negativo do harness (score=-1.5). "score=-" só conta com dígito depois.
        if (Regex("score\\s*=\\s*-\\d").containsMatchIn(result)) return true

        if (result.contains("VERIFICATION_FAILED")) return true

        // 3. Só então olhar o texto livre — removendo antes as NEGAÇÕES de erro, que são sucesso.
        val semNegacoes = result
            .replace(Regex("(?i)sem\\s+erros?"), " ")
            .replace(Regex("(?i)nenhum\\s+erro"), " ")
            .replace(Regex("(?i)livre\\s+de\\s+erros?"), " ")

        return Regex("(?i)\\berros?\\b").containsMatchIn(semNegacoes) ||
            Regex("(?i)\\bfalh(a|ou|aram|as)\\b").containsMatchIn(semNegacoes) ||
            semNegacoes.contains("não encontrado", ignoreCase = true) ||
            semNegacoes.contains("não existe", ignoreCase = true)
    }

    /**
     * Agrega KPIs executivos do Harness Cognitivo a partir das trajetórias reais em disco,
     * AudioTelemetry e MetricsCollector, gerando insights acionáveis priorizados.
     */
    private fun buildHarnessInsightsJson(): JSONObject {
        val trajDir = appContext?.let { File(it.filesDir, "trajectories") }
        val files = trajDir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(40)
            ?: emptyList()

        var totalSessions = 0
        var successCount = 0
        var stuckCount = 0
        var timeoutCount = 0
        var cancelledCount = 0
        var totalSteps = 0
        var totalDurationMs = 0L
        var durationSamples = 0

        val outcomeCounts = linkedMapOf<String, Int>()
        val actionTotals = linkedMapOf<String, Int>()
        val actionFailures = linkedMapOf<String, Int>()
        val providerCounts = linkedMapOf<String, Int>()
        val recentSessions = JSONArray()

        for (file in files) {
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            if (lines.isEmpty()) continue
            var sessionId = file.nameWithoutExtension
            var taskText = ""
            var startedAt = 0L
            var endedAt = 0L
            var outcome = "in_progress"
            var reason = ""
            var stepCount = 0

            for (line in lines) {
                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val type = obj.optString("type")
                val data = obj.optJSONObject("data") ?: continue
                when (type) {
                    "session_start" -> {
                        sessionId = obj.optString("session_id", sessionId)
                        taskText = data.optString("task")
                            .substringBefore("[Contexto visual")
                            .substringBefore("[CONTEXTO CURTO")
                            .trim()
                        startedAt = data.optLong("started_at", 0L)
                    }
                    "agent_step" -> {
                        stepCount++
                        val act = data.optString("action", "unknown")
                        val prov = data.optString("provider", "")
                        val res = data.optString("result", "")
                        if (act.isNotBlank()) {
                            actionTotals[act] = (actionTotals[act] ?: 0) + 1
                            if (classifyStepFailure(act, res)) {
                                actionFailures[act] = (actionFailures[act] ?: 0) + 1
                            }
                        }
                        if (prov.isNotBlank()) {
                            providerCounts[prov] = (providerCounts[prov] ?: 0) + 1
                        }
                    }
                    "session_end" -> {
                        outcome = data.optString("outcome", "unknown")
                        reason = data.optString("verification_reason", "")
                        endedAt = data.optLong("ended_at", 0L)
                    }
                }
            }

            totalSessions++
            totalSteps += stepCount
            outcomeCounts[outcome] = (outcomeCounts[outcome] ?: 0) + 1
            when {
                outcome == "success" -> successCount++
                outcome.contains("stuck") || outcome.contains("max_steps") || outcome.contains("loop") -> stuckCount++
                outcome.contains("timeout") || outcome.contains("no_reply") -> timeoutCount++
                outcome.contains("cancel") -> cancelledCount++
            }
            val durMs = if (startedAt > 0L && endedAt >= startedAt) endedAt - startedAt else 0L
            if (durMs > 0L) {
                totalDurationMs += durMs
                durationSamples++
            }

            if (recentSessions.length() < 15) {
                recentSessions.put(JSONObject().apply {
                    put("session_id", sessionId)
                    put("task", taskText.ifBlank { "(sem texto)" }.take(90))
                    put("outcome", outcome)
                    put("steps", stepCount)
                    put("duration_ms", durMs)
                    put("reason", reason.take(110))
                })
            }
        }

        val successRate = if (totalSessions > 0) (successCount * 100.0 / totalSessions) else 0.0
        val avgSteps = if (totalSessions > 0) (totalSteps.toDouble() / totalSessions) else 0.0
        val avgDurMs = if (durationSamples > 0) (totalDurationMs / durationSamples) else 0L
        val timeoutPct = if (totalSessions > 0) (timeoutCount * 100.0 / totalSessions) else 0.0
        val stuckPct = if (totalSessions > 0) (stuckCount * 100.0 / totalSessions) else 0.0

        val audioJson = AudioTelemetry.getJson()
        val micYields = audioJson.optInt("mic_yields", 0)
        val asrErrors = audioJson.optInt("asr_errors", 0)
        val echoDrops = audioJson.optInt("echo_drops", 0)
        val confTotal = audioJson.optInt("confirmations_asked", 0)
        val confTimeout = audioJson.optInt("confirmations_timeout", 0)

        /*
         * Pior ação por taxa real de falha, com piso de amostra (>= 3 execuções) para não
         * eleger um azar isolado. Os insights abaixo passam a NOMEAR a ação concreta em vez
         * de chutar um nome fixo no texto — é a diferença entre "investigue tap/batch" e
         * "mcp_call falhou 4 de 15 vezes".
         */
        val worstAction = actionTotals.entries
            .filter { it.value >= 3 }
            .map { (act, tot) -> Triple(act, tot, actionFailures[act] ?: 0) }
            .filter { it.third > 0 }
            .maxByOrNull { it.third.toDouble() / it.second }
        val worstActionLabel = worstAction
            ?.let { (act, tot, fails) ->
                "$act (${fails}/${tot} falhas, ${String.format(java.util.Locale.US, "%.0f", fails * 100.0 / tot)}%)"
            }
            ?: "nenhuma ação com falha recorrente"

        // Motor de Insights Acionáveis de Alto Nível
        val insights = JSONArray()
        fun addInsight(severity: String, title: String, metric: String, recommendation: String) {
            insights.put(JSONObject().apply {
                put("severity", severity)
                put("title", title)
                put("metric", metric)
                put("recommendation", recommendation)
            })
        }

        if (totalSessions == 0) {
            addInsight("ATENCAO", "Sem trajetórias registradas ainda", "0 sessões", "Execute comandos por voz ou texto para popular os KPIs do harness.")
        } else {
            if (successRate < 65.0) {
                addInsight("CRITICO", "Taxa de sucesso abaixo da meta (65%)", "${String.format(java.util.Locale.US, "%.1f", successRate)}% ($successCount/$totalSessions)", "Revisar sessões com falha na tabela abaixo e reforçar fast-paths locais para comandos frequentes.")
            } else {
                addInsight("SAUDAVEL", "Taxa de conclusão saudável", "${String.format(java.util.Locale.US, "%.1f", successRate)}% ($successCount/$totalSessions)", "Manter monitoramento contínuo e reforçar rotas campeãs via RLHF.")
            }
        }

        if (stuckPct >= 10.0) {
            addInsight("CRITICO", "Estagnação recorrente na UI", "${String.format(java.util.Locale.US, "%.1f", stuckPct)}% ($stuckCount sessões)", "Ação com pior desempenho agora: $worstActionLabel. Inspecionar seus argumentos e pós-condições no Agent.kt.")
        }
        if (timeoutPct >= 15.0 || (confTotal > 0 && confTimeout * 100.0 / confTotal > 25.0)) {
            addInsight("ATENCAO", "Alto índice de timeout em confirmações", "${String.format(java.util.Locale.US, "%.1f", timeoutPct)}% das sessões", "Reduzir ações que exigem confirmação bloqueante e validar o timing visual das bordas.")
        }
        if (micYields >= 2) {
            addInsight("ATENCAO", "Concessões frequentes de microfone", "$micYields yields na sessão", "Verificar se outro app de gravação está ativo ou se a janela de handoff VAD→ASR está suficiente.")
        }
        if (asrErrors >= 3) {
            addInsight("ATENCAO", "Erros recorrentes no SpeechRecognizer", "$asrErrors erros ASR", "Checar qualidade da rede, ruído ambiente (SNR) e timeouts do Google SpeechRecognizer.")
        }
        if (avgSteps > 4.5) {
            addInsight("ATENCAO", "Média elevada de passos por tarefa", "${String.format(java.util.Locale.US, "%.1f", avgSteps)} passos/tarefa", "Adicionar atalhos diretos ou exemplos few-shot no SYSTEM_PROMPT para ações multi-etapa.")
        }
        val mcpFails = actionFailures["mcp_call"] ?: 0
        if (mcpFails > 0) {
            addInsight("ATENCAO", "Chamadas MCP falhando", "$mcpFails falhas em mcp_call", "Garantir que o servidor MCP local esteja ativo ou isolado pelo circuit breaker antes da chamada.")
        }

        val actionStatsArr = JSONArray()
        actionTotals.entries.sortedByDescending { it.value }.take(10).forEach { (act, tot) ->
            val fails = actionFailures[act] ?: 0
            actionStatsArr.put(JSONObject().apply {
                put("action", act)
                put("total", tot)
                put("failures", fails)
                put("success_rate_pct", String.format(java.util.Locale.US, "%.1f", ((tot - fails) * 100.0 / tot.coerceAtLeast(1))))
            })
        }

        val outcomesObj = JSONObject()
        outcomeCounts.forEach { (k, v) -> outcomesObj.put(k, v) }

        val providersObj = JSONObject()
        providerCounts.forEach { (k, v) -> providersObj.put(k, v) }

        return JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("kpis", JSONObject().apply {
                put("total_sessions", totalSessions)
                put("success_count", successCount)
                put("success_rate_pct", String.format(java.util.Locale.US, "%.1f", successRate))
                put("stuck_count", stuckCount)
                put("timeout_count", timeoutCount)
                put("cancelled_count", cancelledCount)
                put("avg_steps", String.format(java.util.Locale.US, "%.2f", avgSteps))
                put("avg_duration_ms", avgDurMs)
            })
            put("outcomes", outcomesObj)
            put("providers", providersObj)
            put("action_stats", actionStatsArr)
            put("worst_action", worstAction?.let { (act, tot, fails) ->
                JSONObject()
                    .put("action", act)
                    .put("total", tot)
                    .put("failures", fails)
                    .put("failure_rate_pct", String.format(java.util.Locale.US, "%.1f", fails * 100.0 / tot))
            } ?: JSONObject.NULL)
            put("audio", JSONObject().apply {
                put("mic_state", audioJson.optString("mic_state", "IDLE"))
                put("mic_yields", micYields)
                put("asr_errors", asrErrors)
                put("echo_drops", echoDrops)
                put("floor_db", audioJson.optDouble("floor_db", -50.0))
                put("snr_db", audioJson.optDouble("snr_db", 0.0))
                put("confirmations_asked", confTotal)
                put("confirmations_timeout", confTimeout)
            })
            put("insights", insights)
            put("recent_sessions", recentSessions)
            put("fsm", AdaStateMachine.toJson())
            put("orchestration_graph", IdeaToActionGraph.toJson())
            put("live_analysis", MetricsCollector.getAnalysis())
        }
    }

    private fun buildHarnessDashboardHtml(): String = """
        <!DOCTYPE html>
        <html lang="pt-BR">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Painel Gerencial — Harness Cognitivo X0/Ada</title>
        <style>
        :root{--bg:#070b14;--card:#0f172a;--border:#1e293b;--text:#e2e8f0;--muted:#94a3b8;--cyan:#22d3ee;--green:#10b981;--amber:#f59e0b;--red:#ef4444;--violet:#a78bfa}
        *{box-sizing:border-box;margin:0;padding:0}
        body{background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,sans-serif;padding:16px;line-height:1.45}
        .wrap{max-width:1360px;margin:0 auto}
        header{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:12px;margin-bottom:16px;padding:14px 18px;background:var(--card);border:1px solid var(--border);border-radius:14px}
        h1{font-size:19px;color:var(--cyan)}
        .sub{font-size:12px;color:var(--muted)}
        .nav{display:flex;gap:8px}
        .btn{padding:7px 12px;border-radius:8px;border:1px solid var(--border);background:#162033;color:var(--text);text-decoration:none;font-size:12px;cursor:pointer}
        .btn:hover{border-color:var(--cyan);color:var(--cyan)}
        .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:16px}
        .card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:14px}
        .kpi-lbl{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}
        .kpi-val{font-size:24px;font-weight:700;margin-top:4px;color:var(--cyan)}
        .kpi-sub{font-size:11px;color:var(--muted);margin-top:2px}
        .grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:14px;margin-bottom:16px}
        h2{font-size:14px;margin-bottom:10px;color:#f8fafc;display:flex;align-items:center;gap:6px}
        .insight{padding:10px 12px;border-radius:10px;border-left:4px solid var(--cyan);background:#0b1220;margin-bottom:8px}
        .insight.CRITICO{border-left-color:var(--red)}
        .insight.ATENCAO{border-left-color:var(--amber)}
        .insight.SAUDAVEL{border-left-color:var(--green)}
        .ins-head{display:flex;justify-content:space-between;font-size:13px;font-weight:600}
        .ins-rec{font-size:12px;color:var(--muted);margin-top:4px}
        .badge{font-size:10px;padding:2px 7px;border-radius:99px;font-weight:700;background:#1e293b}
        .CRITICO .badge{color:var(--red)}
        .ATENCAO .badge{color:var(--amber)}
        .SAUDAVEL .badge{color:var(--green)}
        table{width:100%;border-collapse:collapse;font-size:12px}
        th,td{text-align:left;padding:8px 6px;border-bottom:1px solid #1e293b}
        th{color:var(--muted);font-weight:600;font-size:11px}
        .pill{display:inline-block;padding:2px 8px;border-radius:99px;font-size:11px;font-weight:600}
        .ok{background:rgba(16,185,129,.15);color:var(--green)}
        .warn{background:rgba(245,158,11,.15);color:var(--amber)}
        .err{background:rgba(239,68,68,.15);color:var(--red)}
        .bar-row{margin-bottom:8px}
        .bar-lbl{display:flex;justify-content:space-between;font-size:12px;margin-bottom:3px}
        .bar-bg{height:7px;background:#1e293b;border-radius:99px;overflow:hidden}
        .bar-fill{height:100%;background:linear-gradient(90deg,var(--cyan),var(--violet))}
        </style>
        </head>
        <body>
        <div class="wrap">
          <header>
            <div>
              <h1>🏛️ Painel Gerencial — Harness Cognitivo (X0 / Ada)</h1>
              <div class="sub" id="upd">Carregando telemetria das últimas 40 sessões...</div>
            </div>
            <div class="nav">
              <a class="btn" href="/">📡 Monitor Ao Vivo</a>
              <button class="btn" onclick="loadData()">↻ Atualizar</button>
            </div>
          </header>

          <div class="kpis">
            <div class="card"><div class="kpi-lbl">Taxa de Sucesso</div><div class="kpi-val" id="k-sr">--</div><div class="kpi-sub" id="k-tot">--</div></div>
            <div class="card"><div class="kpi-lbl">Passos / Tarefa</div><div class="kpi-val" id="k-st">--</div><div class="kpi-sub" id="k-dur">Duração média: --</div></div>
            <div class="card"><div class="kpi-lbl">Estagnações</div><div class="kpi-val" style="color:var(--amber)" id="k-stk">--</div><div class="kpi-sub" id="k-to">Timeouts: --</div></div>
            <div class="card"><div class="kpi-lbl">Saúde Voz / Mic</div><div class="kpi-val" style="color:var(--green)" id="k-mic">--</div><div class="kpi-sub" id="k-vad">Yields: -- | ASR Err: --</div></div>
          </div>

          <div class="grid2">
            <div class="card">
              <h2>💡 Insights Acionáveis para Melhoria</h2>
              <div id="insights">Analisando...</div>
            </div>
            <div class="card">
              <h2>⚙️ Performance por Ferramenta / Ação</h2>
              <div id="actions">Carregando...</div>
            </div>
          </div>

          <div class="card">
            <h2>📜 Últimas 15 Sessões Cognitivas</h2>
            <div style="overflow-x:auto">
              <table>
                <thead><tr><th>Sessão</th><th>Comando</th><th>Desfecho</th><th>Passos</th><th>Tempo</th><th>Motivo / Diagnóstico</th></tr></thead>
                <tbody id="sess-body"></tbody>
              </table>
            </div>
          </div>
        </div>
        <script>
        function pillClass(o){
          if(o==='success') return 'ok';
          if(o.includes('stuck')||o.includes('loop')||o.includes('max_steps')) return 'err';
          return 'warn';
        }
        async function loadData(){
          try{
            const d=await fetch('/api/agent/harness-insights').then(r=>r.json());
            const k=d.kpis||{}, a=d.audio||{};
            document.getElementById('upd').textContent='Atualizado às '+new Date().toLocaleTimeString('pt-BR')+' · Base: '+(k.total_sessions||0)+' sessões recentes';
            document.getElementById('k-sr').textContent=(k.success_rate_pct||'0')+'%';
            document.getElementById('k-tot').textContent=(k.success_count||0)+' de '+(k.total_sessions||0)+' concluídas';
            document.getElementById('k-st').textContent=k.avg_steps||'0';
            document.getElementById('k-dur').textContent='Duração média: '+((k.avg_duration_ms||0)/1000).toFixed(1)+'s';
            document.getElementById('k-stk').textContent=k.stuck_count||0;
            document.getElementById('k-to').textContent='Timeouts voz: '+(k.timeout_count||0)+' | Cancel: '+(k.cancelled_count||0);
            document.getElementById('k-mic').textContent=a.mic_state||'IDLE';
            document.getElementById('k-vad').textContent='Yields: '+(a.mic_yields||0)+' | ASR Err: '+(a.asr_errors||0)+' | Ecos: '+(a.echo_drops||0);

            document.getElementById('insights').innerHTML=(d.insights||[]).map(i=>
              '<div class="insight '+i.severity+'"><div class="ins-head"><span>'+i.title+'</span><span class="badge">'+i.severity+' · '+i.metric+'</span></div><div class="ins-rec">'+i.recommendation+'</div></div>'
            ).join('')||'<div class="sub">Sem insights no momento.</div>';

            document.getElementById('actions').innerHTML=(d.action_stats||[]).map(s=>
              '<div class="bar-row"><div class="bar-lbl"><span>'+s.action+' ('+s.total+'x)</span><span>'+s.success_rate_pct+'% ok ('+s.failures+' falhas)</span></div><div class="bar-bg"><div class="bar-fill" style="width:'+s.success_rate_pct+'%"></div></div></div>'
            ).join('')||'<div class="sub">Sem ações registradas ainda.</div>';

            document.getElementById('sess-body').innerHTML=(d.recent_sessions||[]).map(s=>
              '<tr><td><code>'+s.session_id.replace('sess_','')+'</code></td><td>'+s.task+'</td><td><span class="pill '+pillClass(s.outcome)+'">'+s.outcome+'</span></td><td>'+s.steps+'</td><td>'+(s.duration_ms?(s.duration_ms/1000).toFixed(1)+'s':'-')+'</td><td>'+(s.reason||'-')+'</td></tr>'
            ).join('');
          }catch(e){
            document.getElementById('upd').textContent='Erro ao carregar: '+e;
          }
        }
        loadData(); setInterval(loadData, 3500);
        </script>
        </body>
        </html>
    """.trimIndent()
}
