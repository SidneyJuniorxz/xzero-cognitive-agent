package com.xzero.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Msg(
    val role: String, // "user" | "assistant"
    val text: String,
    val imageBase64: String? = null,
    val imageMime: String = "image/jpeg",
)

class LlmException(msg: String) : Exception(msg)

/** Cliente LLM com fallback entre Gemini, Claude (Anthropic) e OpenAI. Sempre pede saída JSON. */
class Llm(private val prefs: Prefs) {

    var lastProvider: String = "-"
        private set

    fun complete(system: String, history: List<Msg>, preferStrongModel: Boolean = false): String {
        val all = listOf("gemini", "claude", "openai")
        val order = listOf(prefs.primary).filter { it in all } + all.filter { it != prefs.primary }
        val errors = mutableListOf<String>()
        for (prov in order) {
            try {
                val out = when (prov) {
                    "gemini" -> if (prefs.geminiKey.isBlank()) throw LlmException("sem chave") else gemini(system, history, preferStrongModel)
                    "claude" -> if (prefs.claudeKey.isBlank()) throw LlmException("sem chave") else claude(system, history)
                    else -> if (prefs.openaiKey.isBlank()) throw LlmException("sem chave")
                        else openaiCompat("https://api.openai.com/v1/chat/completions", prefs.openaiKey, prefs.openaiModel, system, history)
                }
                if (prov != "gemini") lastProvider = prov
                return out
            } catch (e: Exception) {
                errors += "$prov: ${e.message?.take(90)}"
            }
        }
        throw LlmException("Todos os provedores falharam → " + errors.joinToString(" | "))
    }

    private fun gemini(system: String, history: List<Msg>, preferStrongModel: Boolean = false): String {
        val contents = JSONArray()
        history.forEach { m ->
            val parts = JSONArray().put(JSONObject().put("text", m.text))
            if (!m.imageBase64.isNullOrBlank()) {
                parts.put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", m.imageMime)
                            .put("data", m.imageBase64),
                    ),
                )
            }
            contents.put(
                JSONObject()
                    .put("role", if (m.role == "assistant") "model" else "user")
                    .put("parts", parts),
            )
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2))
        val baseList = listOf(
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite"
        )
        val candidates = if (preferStrongModel) {
            (baseList + prefs.geminiModel).distinct()
        } else {
            (listOf(prefs.geminiModel) + baseList).distinct()
        }
        var lastErr = "sem modelo"
        for (m in candidates) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
                val res = post(url, body.toString(), mapOf("x-goog-api-key" to prefs.geminiKey))
                val cand = JSONObject(res).optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw LlmException("resposta vazia")
                val parts = cand.getJSONObject("content").getJSONArray("parts")
                val sb = StringBuilder()
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
                lastProvider = "gemini:$m"
                return sb.toString()
            } catch (e: Exception) {
                lastErr = "$m(${e.message?.take(50)})"
            }
        }
        throw LlmException(lastErr)
    }

    private fun claude(system: String, history: List<Msg>): String {
        val msgs = JSONArray()
        history.forEach { m ->
            msgs.put(JSONObject().put("role", m.role).put("content", m.text))
        }
        val body = JSONObject()
            .put("model", prefs.claudeModel)
            .put("max_tokens", 1024)
            .put("temperature", 0.2)
            .put("system", system)
            .put("messages", msgs)
        val res = post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf("x-api-key" to prefs.claudeKey, "anthropic-version" to "2023-06-01")
        )
        val content = JSONObject(res).optJSONArray("content") ?: throw LlmException("resposta vazia")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.optString("type") == "text") sb.append(part.optString("text"))
        }
        return sb.toString()
    }

    private fun openaiCompat(url: String, key: String, model: String, system: String, history: List<Msg>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("temperature", 0.2)
            .put("response_format", JSONObject().put("type", "json_object"))
        val res = post(url, body.toString(), mapOf("Authorization" to "Bearer $key"))
        return JSONObject(res).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    /**
     * Embedding de texto (pt-BR) via Gemini — usado APENAS como "professor" offline do
     * [SemanticMatcher]: a chamada nunca entra no caminho crítico da voz; o vetor volta,
     * é gravado em cache no disco e dali em diante a comparação é local (cosseno, <1 ms).
     * Retorna null se não houver chave ou se todos os modelos falharem.
     */
    fun embed(text: String): DoubleArray? {
        if (prefs.geminiKey.isBlank() || text.isBlank()) return null
        val models = listOf("gemini-embedding-001", "text-embedding-004", "embedding-001")
        val body = JSONObject()
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
            .put("taskType", "SEMANTIC_SIMILARITY")
        for (m in models) {
            try {
                val res = post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$m:embedContent",
                    JSONObject(body.toString()).put("model", "models/$m").toString(),
                    mapOf("x-goog-api-key" to prefs.geminiKey),
                )
                val values = JSONObject(res).optJSONObject("embedding")?.optJSONArray("values")
                    ?: continue
                val out = DoubleArray(values.length()) { values.optDouble(it, 0.0) }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {
                // tenta o próximo modelo
            }
        }
        return null
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw LlmException("HTTP $code ${text.take(160)}")
        return text
    }
}
