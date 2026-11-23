package support

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LLM falso para rodar o loop do agente fora do celular, sem rede e sem gastar token.
 *
 * O [com.xzero.agent.Llm] tem as URLs dos provedores fixas no código e o combinado
 * deste branch é não alterar app/ — então a interceptação acontece uma camada abaixo:
 * trocamos o handler de "https" da JVM por um que responde às chamadas de LLM com um
 * roteiro, delega qualquer outro host para o handler original e registra todo request
 * (útil para asserção do prompt enviado).
 *
 * Exige `--add-opens=java.base/java.net=ALL-UNNAMED` (configurado no build.gradle.kts).
 */
object FakeLlm {

    /** Resposta que o provedor devolverá, já no formato de cada API. */
    data class Scripted(val status: Int, val body: String)

    /** Request capturado, para inspecionar o prompt que o agente realmente mandou. */
    data class Captured(val url: String, val body: String)

    private val queue = ConcurrentLinkedQueue<Scripted>()
    val requests = mutableListOf<Captured>()

    @Volatile private var installed = false

    private val llmHosts = setOf(
        "generativelanguage.googleapis.com",
        "api.anthropic.com",
        "api.openai.com",
    )

    // ---------- instalação ----------

    @Synchronized
    fun install() {
        if (installed) return
        val handlersField = URL::class.java.getDeclaredField("handlers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val handlers = handlersField.get(null) as MutableMap<String, URLStreamHandler>
        for (proto in listOf("https", "http")) {
            val original = resolveHandler(proto)
            handlers[proto] = InterceptingHandler(original)
        }
        installed = true
    }

    private fun resolveHandler(protocol: String): URLStreamHandler? = runCatching {
        val u = URL("$protocol://localhost/")
        URL::class.java.getDeclaredField("handler").apply { isAccessible = true }.get(u) as URLStreamHandler
    }.getOrNull()

    /** Zera roteiro e histórico — chamar no @Before de cada teste. */
    fun reset() {
        queue.clear()
        requests.clear()
    }

    // ---------- roteiro ----------

    /** Enfileira decisões `{action,args}` que o agente receberá, em ordem, como se fossem do Gemini. */
    fun enqueueDecisions(vararg decisions: JSONObject) {
        decisions.forEach { queue.add(Scripted(200, geminiEnvelope(it.toString()))) }
    }

    /** Enfileira um texto cru (para simular resposta fora do formato JSON). */
    fun enqueueRawText(vararg texts: String) {
        texts.forEach { queue.add(Scripted(200, geminiEnvelope(it))) }
    }

    /** Enfileira uma falha HTTP do provedor (timeout/quota/500). */
    fun enqueueFailure(status: Int = 500, message: String = "erro simulado do provedor") {
        queue.add(Scripted(status, JSONObject().put("error", JSONObject().put("message", message)).toString()))
    }

    fun pendingResponses(): Int = queue.size

    /** Atalho: decisão `done` com a resposta falada. */
    fun done(answer: String): JSONObject = JSONObject()
        .put("action", "done")
        .put("args", JSONObject().put("answer", answer))
        .put("thought", "testbed")
        .put("epistemic", "OBSERVED")

    /** Atalho: qualquer ação com argumentos. */
    fun action(name: String, args: JSONObject = JSONObject(), thought: String = "testbed"): JSONObject =
        JSONObject().put("action", name).put("args", args).put("thought", thought)

    private fun geminiEnvelope(text: String): String = JSONObject()
        .put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            ),
        )
        .toString()

    // ---------- plumbing ----------

    /** Vetor estável derivado do texto pedido: mesma entrada -> mesmo embedding. */
    private fun embeddingEnvelope(requestBody: String): String {
        val seed = requestBody.hashCode().toLong()
        val values = JSONArray()
        var x = seed or 1L
        repeat(16) {
            x = x * 6364136223846793005L + 1442695040888963407L
            values.put(((x shr 33).toDouble() / Int.MAX_VALUE).coerceIn(-1.0, 1.0))
        }
        return JSONObject().put("embedding", JSONObject().put("values", values)).toString()
    }

    private class InterceptingHandler(private val delegate: URLStreamHandler?) : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection {
            if (u.host in llmHosts) return FakeLlmConnection(u)
            val d = delegate ?: throw IOException("testbed sem handler original para ${u.protocol}")
            val m = URLStreamHandler::class.java
                .getDeclaredMethod("openConnection", URL::class.java)
                .apply { isAccessible = true }
            return m.invoke(d, u) as URLConnection
        }
    }

    private class FakeLlmConnection(url: URL) : HttpURLConnection(url) {
        private val sent = ByteArrayOutputStream()
        private var scripted: Scripted? = null

        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = sent

        private fun serve(): Scripted = scripted ?: run {
            val body = sent.toString(Charsets.UTF_8)
            requests += Captured(url.toString(), body)
            // Embeddings são o "professor" offline do SemanticMatcher e podem acontecer em
            // paralelo a qualquer turno. Se consumissem o roteiro de decisões, o teste
            // ficaria não-determinístico — então têm resposta própria, derivada do texto.
            val next = if (url.toString().contains("embedContent")) {
                Scripted(200, embeddingEnvelope(body))
            } else {
                queue.poll() ?: Scripted(200, geminiEnvelope(done("roteiro do testbed acabou").toString()))
            }
            scripted = next
            next
        }

        override fun getResponseCode(): Int = serve().status

        override fun getInputStream(): InputStream {
            val s = serve()
            if (s.status !in 200..299) throw IOException("HTTP ${s.status}")
            return ByteArrayInputStream(s.body.toByteArray())
        }

        override fun getErrorStream(): InputStream? {
            val s = serve()
            return if (s.status in 200..299) null else ByteArrayInputStream(s.body.toByteArray())
        }
    }
}
