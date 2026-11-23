package com.xzero.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Dono de tudo que é VOZ DE SAÍDA da ADA: escolha da voz do sistema, perfil de timbre
 * (Jarvis / natural / rápido), prosódia emocional e o filtro anti-eco acústico.
 *
 * Extraído do BubbleService (que acumulava 1971 linhas de voz + overlay + orquestração).
 * Esta é a costura mais limpa do arquivo: não depende de nenhuma View, só do TextToSpeech
 * e das Prefs — por isso foi a primeira a sair.
 */
class TtsVoiceController(private val ctx: Context) {

    companion object {
        private const val TAG = "AgentePhone"
        /** Janela normal do anti-eco após o alto-falante calar (inclui margem para latência do SODA). */
        private const val ECHO_WINDOW_MS = 11_500L
        /** Janela curta durante confirmação pendente (resposta curta não pode ser engolida). */
        private const val ECHO_WINDOW_CONFIRMING_MS = 1200L
    }

    @Volatile var isSpeaking: Boolean = false
    @Volatile var lastSpokenNormalized: String = ""
        private set
    @Volatile var lastSpokenRaw: String = ""
        private set
    @Volatile var startedAtMs: Long = 0L
        private set
    @Volatile var lastEndedAtMs: Long = 0L

    /**
     * Pontuação "quão Jarvis é esta voz": grave/masculina, local (sem rede) e alta qualidade.
     * Os marcadores vêm da nomenclatura do Google TTS (pt-br-x-ptd-local, pt-br-x-afs-local…).
     * Medido no celular Android: ptd e pte empatam em 70; afs (feminina) fica em 0.
     */
    fun jarvisScore(v: Voice): Int {
        val n = v.name.lowercase()
        var sc = 0
        if (n.contains("male") && !n.contains("female")) sc += 50
        if (n.contains("ptd") || n.contains("pte")) sc += 30
        if (!v.isNetworkConnectionRequired) sc += 25
        if (n.contains("neural") || n.contains("journey") || n.contains("studio")) sc += 20
        if (v.quality >= 400) sc += 15
        if (n.contains("female") || n.contains("afs") || n.contains("pfp")) sc -= 40
        return sc
    }

    private fun brVoices(t: TextToSpeech): List<Voice> =
        (t.voices ?: emptySet()).filter {
            it.locale.language.equals("pt", ignoreCase = true) &&
                it.locale.country.equals("BR", ignoreCase = true)
        }

    /** Aplica voz + pitch/velocidade do perfil escolhido (ou da voz fixada manualmente). */
    fun applyProfile(t: TextToSpeech) {
        val prefs = Prefs(ctx)
        val profile = prefs.voiceProfile
        t.language = Locale("pt", "BR")
        runCatching {
            val br = brVoices(t)
            val pinned = prefs.pinnedVoiceName
            val pinnedVoice = if (pinned.isNotBlank()) br.firstOrNull { it.name == pinned } else null
            val best = when {
                pinnedVoice != null -> pinnedVoice
                profile == "jarvis" -> br.maxByOrNull { jarvisScore(it) } ?: br.firstOrNull()
                else -> br.firstOrNull { v ->
                    val n = v.name.lowercase()
                    !v.isNetworkConnectionRequired &&
                        (n.contains("neural") || n.contains("journey") || n.contains("x-") || v.quality >= 400)
                } ?: br.firstOrNull { !it.isNetworkConnectionRequired } ?: br.firstOrNull()
            }
            if (best != null) {
                t.voice = best
                Log.i(TAG, "Voz TTS [$profile]: ${best.name} (qualidade ${best.quality})")
            }
        }
        val (pitch, rate) = baseProsody(profile)
        t.setPitch(pitch)
        t.setSpeechRate(rate)
    }

    /** Timbre base de cada perfil, antes da modulação emocional. */
    fun baseProsody(profile: String = Prefs(ctx).voiceProfile): Pair<Float, Float> = when (profile) {
        "jarvis" -> 0.86f to 1.05f
        "fast" -> 1.00f to 1.24f
        else -> 1.02f to 1.08f
    }

    /** Aplica o timbre do perfil já modulado pela emoção atual da ADA. */
    fun applyEmotionalProsody(t: TextToSpeech) {
        runCatching {
            val (basePitch, baseRate) = baseProsody()
            val (pitchMul, rateMul) = EmotionEngine.prosody()
            t.setPitch((basePitch * pitchMul).coerceIn(0.6f, 1.6f))
            t.setSpeechRate((baseRate * rateMul).coerceIn(0.6f, 1.8f))
        }
    }

    fun markSpeaking(text: String) {
        lastSpokenRaw = text
        lastSpokenNormalized = normalizeEcho(text)
        startedAtMs = System.currentTimeMillis()
        isSpeaking = true
    }

    /**
     * Estima o trecho realmente falado antes de um barge-in (~14 chars/s em pt-BR),
     * para truncar o histórico e impedir o agente de achar que disse o que foi cortado.
     */
    fun estimateSpokenPrefixOnInterrupt(): String? {
        val raw = lastSpokenRaw.trim()
        if (raw.length < 25 || startedAtMs <= 0L) return null
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        val estChars = ((elapsedMs / 1000.0) * 14.0).toInt().coerceIn(8, raw.length)
        if (estChars >= raw.length - 8) return null
        val cut = raw.lastIndexOf(' ', estChars).takeIf { it >= 8 } ?: estChars
        return raw.substring(0, cut).trim()
    }

    fun markStopped() {
        lastEndedAtMs = System.currentTimeMillis()
        isSpeaking = false
    }

    /** Vozes pt-BR instaladas, ranqueadas — alimenta o comando adb VOICE op=list. */
    fun listVoicesJson(t: TextToSpeech?): JSONObject {
        val out = JSONObject()
        if (t == null) return out.put("erro", "TTS ainda não inicializado")
        runCatching {
            val all = t.voices ?: emptySet()
            val arr = JSONArray()
            brVoices(t)
                .sortedWith(compareByDescending<Voice> { jarvisScore(it) }.thenByDescending { it.quality })
                .forEach { v ->
                    arr.put(
                        JSONObject()
                            .put("nome", v.name)
                            .put("qualidade", v.quality)
                            .put("latencia", v.latency)
                            .put("precisa_rede", v.isNetworkConnectionRequired)
                            .put("score_jarvis", jarvisScore(v))
                            .put("features", v.features?.joinToString(",") ?: "")
                    )
                }
            out.put("perfil_atual", Prefs(ctx).voiceProfile)
            out.put("voz_em_uso", t.voice?.name ?: "(padrão do engine)")
            out.put("total_vozes_sistema", all.size)
            out.put("vozes_pt_br", arr)
        }.onFailure { out.put("erro", it.message) }
        return out
    }

    // ---- Anti-eco acústico ----

    fun normalizeEcho(s: String): String =
        Normalizer.normalize(s.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Impede que o microfone transcreva a própria voz da ADA.
     *
     * `confirming` = há pergunta de confirmação pendente. Nesse caso a janela encolhe para
     * 1,2 s e respostas de até 4 palavras nunca são eco: "pode fazer isso" compartilha
     * palavras com "Posso fazer isso agora?" e era descartada, travando o aceite da ação.
     */
    fun isEchoFromOwnTts(candidate: String, confirming: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val window = if (confirming) ECHO_WINDOW_CONFIRMING_MS else ECHO_WINDOW_MS
        if (!isSpeaking && (now - lastEndedAtMs) > window) return false
        val lastNorm = lastSpokenNormalized
        if (lastNorm.isEmpty()) return isSpeaking && !confirming
        val candNorm = normalizeEcho(candidate)
        if (candNorm.isEmpty()) return false
        val candWords = candNorm.split(" ").filter { it.length >= 3 }
        if (confirming && candWords.size <= 4) return false
        if (lastNorm.contains(candNorm) || candNorm.contains(lastNorm)) return true
        val ttsWords = lastNorm.split(" ").filter { it.length >= 3 }.toSet()
        if (candWords.isEmpty() || ttsWords.isEmpty()) return false
        val overlap = candWords.count { it in ttsWords }
        val minRatio = if (confirming) 0.80 else 0.50
        if (overlap >= 2 && (overlap.toDouble() / candWords.size.toDouble()) >= minRatio) return true

        // Similaridade fonética por trigramas de caracteres: captura erros do STT na própria voz da ADA
        // (ex: "te ouco com um pouco de ruido" -> "torco com um pouco de ruido", "mc iguinho" -> "mc guinho")
        if (!confirming && candNorm.length >= 10 && lastNorm.length >= 10) {
            val sim = trigramContainment(candNorm, lastNorm)
            if (sim >= 0.44) return true
        }
        return false
    }

    private fun trigramContainment(a: String, b: String): Double {
        fun trigrams(s: String): Set<String> {
            val clean = s.replace(" ", "")
            if (clean.length < 3) return emptySet()
            val out = HashSet<String>(clean.length)
            for (i in 0..clean.length - 3) {
                out.add(clean.substring(i, i + 3))
            }
            return out
        }
        val ta = trigrams(a)
        val tb = trigrams(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.count { it in tb }.toDouble()
        val smaller = kotlin.math.min(ta.size, tb.size).toDouble()
        return inter / smaller
    }
}
