package com.xzero.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/**
 * Classificador de frases faladas em CASCATA, do mais rápido ao mais caro.
 *
 * Objetivo: aumentar a assertividade (aceitar "demorô", "fecha", "bote pra funcionar" como SIM)
 * SEM colocar latência no caminho crítico da voz:
 *
 *  1. `exato`     — frase já conhecida, normalizada sem acento/pontuação .......... ~0,01 ms
 *  2. `lexical`   — cosseno de palavras + trigramas (igual ao grafo de memória) ... ~0,05 ms
 *  3. `semantico` — cosseno de embedding em CACHE LOCAL no disco ................. ~0,3 ms
 *  4. desconhecido -> decide pelo caminho antigo E agenda o embedding em background,
 *     para que a MESMA frase caia no nível 3 (local, instantâneo) na próxima vez.
 *
 * O embedding remoto (Gemini) é só o "professor": roda fora do caminho crítico, uma única vez
 * por frase nova. Modelo multilíngue on-device foi descartado de propósito — em pt-BR ele
 * custaria ~120 MB no APK (o MiniLM de 20 MB só funciona em inglês).
 *
 * Cada acerto confirmado pelo usuário vira protótipo novo: o X0 aprende o jeito DELE de falar.
 */
class SemanticMatcher(
    private val name: String,
    private val lexicalMinScore: Double = 0.72,
    private val semanticMinScore: Double = 0.62,
    private val semanticMinMargin: Double = 0.08,
    private val maxPrototypes: Int = 400,
) {
    companion object {
        private const val TAG = "SemanticMatcher"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun normalize(text: String): String {
            val lower = text.lowercase(Locale("pt", "BR"))
            val noAccent = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return noAccent.replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()
        }

        /** Vetor léxico esparso: palavras (peso 2) + trigramas de caractere (peso 0,6). */
        fun lexicalVector(text: String): Map<String, Double> {
            val norm = normalize(text)
            if (norm.isEmpty()) return emptyMap()
            val counts = HashMap<String, Double>()
            for (w in norm.split(" ").filter { it.length >= 2 }) {
                counts["w:$w"] = (counts["w:$w"] ?: 0.0) + 2.0
                val padded = "#$w#"
                for (i in 0..padded.length - 3) {
                    val tri = padded.substring(i, i + 3)
                    counts[tri] = (counts[tri] ?: 0.0) + 0.6
                }
            }
            return counts
        }

        fun lexicalCosine(a: Map<String, Double>, b: Map<String, Double>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for ((k, v) in a) { na += v * v; b[k]?.let { dot += v * it } }
            for ((_, v) in b) nb += v * v
            val den = sqrt(na) * sqrt(nb)
            return if (den > 0) dot / den else 0.0
        }

        fun denseCosine(a: DoubleArray, b: DoubleArray): Double {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            val den = sqrt(na) * sqrt(nb)
            return if (den > 0) dot / den else 0.0
        }
    }

    /**
     * Dimensões guardadas por vetor.
     *
     * Medido no aparelho: o gemini-embedding-001 devolve **3072 dimensões**, e 108
     * protótipos viraram um arquivo de 2,35 MB — lido e parseado inteiro no disco, com
     * 93% dos vetores nunca usados. Embeddings da família Matryoshka concentram o sinal
     * nas primeiras dimensões, então truncar em 512 corta o arquivo em 6x. Como consulta
     * e protótipo são truncados igual, o cosseno continua consistente.
     */
    private val embedDims = 512

    private fun truncar(v: DoubleArray): DoubleArray =
        if (v.size <= embedDims) v else v.copyOf(embedDims)

    data class Prototype(
        val phrase: String,
        val norm: String,
        val label: String,
        var hits: Int = 1,
        var lastUsedMs: Long = System.currentTimeMillis(),
        var vector: DoubleArray? = null,
        val source: String = "aprendido",
    )

    data class Match(val label: String, val score: Double, val method: String, val matched: String)

    private val prototypes = mutableListOf<Prototype>()
    private val lexCache = HashMap<String, Map<String, Double>>()
    @Volatile private var ctxRef: Context? = null
    @Volatile private var llm: Llm? = null
    @Volatile private var dirty = false
    private val pendingEmbeds = ArrayDeque<String>()
    @Volatile private var embedWorkerBusy = false

    // Telemetria por nível da cascata
    @Volatile var hitsExact = 0; private set
    @Volatile var hitsLexical = 0; private set
    @Volatile var hitsSemantic = 0; private set
    @Volatile var misses = 0; private set
    @Volatile var embedsFetched = 0; private set
    @Volatile var embedsFailed = 0; private set

    @Synchronized
    fun init(ctx: Context, seeds: Map<String, List<String>> = emptyMap()) {
        if (ctxRef != null) return
        ctxRef = ctx.applicationContext
        llm = Llm(Prefs(ctx.applicationContext))
        val antes = loadFromDisk()
        // Migração única: arquivos gravados antes da truncagem tinham vetores de 3072 dims
        // e carregavam semente nunca usada. Se algo mudou no carregamento, regrava enxuto.
        if (antes.migrou) {
            dirty = true
            saveToDisk()
            Log.i(TAG, "[$name] migrado: ${antes.truncados} vetores truncados, ${antes.podados} sementes podadas")
        }
        // Semeia os protótipos curados que ainda não existem (nunca sobrescreve o aprendido)
        for ((label, phrases) in seeds) {
            for (ph in phrases) {
                val n = normalize(ph)
                if (n.isNotEmpty() && prototypes.none { it.norm == n }) {
                    prototypes.add(Prototype(ph, n, label, hits = 0, source = "semente"))
                }
            }
        }
        Log.i(TAG, "[$name] ${prototypes.size} protótipos (${prototypes.count { it.vector != null }} com embedding)")
        scheduleMissingEmbeddings()
    }

    private fun lexOf(norm: String): Map<String, Double> =
        lexCache.getOrPut(norm) { lexicalVector(norm) }

    /**
     * Classifica a frase percorrendo a cascata. Retorna null quando nada passa dos limiares
     * (o chamador decide o fallback) e agenda o embedding da frase para a próxima vez.
     */
    @Synchronized
    fun classify(text: String, allowedLabels: Set<String>? = null): Match? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        val pool = prototypes.filter { allowedLabels == null || it.label in allowedLabels }
        if (pool.isEmpty()) return null

        // Nível 1: exato
        pool.firstOrNull { it.norm == norm }?.let {
            it.hits++; it.lastUsedMs = System.currentTimeMillis(); dirty = true
            hitsExact++
            return Match(it.label, 1.0, "exato", it.phrase)
        }

        // Nível 2: léxico (palavra + trigrama)
        val qLex = lexOf(norm)
        var bestLex: Prototype? = null
        var bestLexScore = 0.0
        for (p in pool) {
            val s = lexicalCosine(qLex, lexOf(p.norm))
            if (s > bestLexScore) { bestLexScore = s; bestLex = p }
        }
        if (bestLex != null && bestLexScore >= lexicalMinScore) {
            bestLex.hits++; bestLex.lastUsedMs = System.currentTimeMillis(); dirty = true
            hitsLexical++
            return Match(bestLex.label, bestLexScore, "lexical", bestLex.phrase)
        }

        // Nível 3: semântico com vetores já em cache local
        val qVec = vectorCache[norm]
        if (qVec != null) {
            var best: Prototype? = null; var bestScore = -1.0; var second = -1.0
            for (p in pool) {
                val v = p.vector ?: continue
                val s = denseCosine(qVec, v)
                if (s > bestScore) { second = bestScore; bestScore = s; best = p }
                else if (s > second) second = s
            }
            if (best != null && bestScore >= semanticMinScore && (bestScore - second) >= semanticMinMargin) {
                best.hits++; best.lastUsedMs = System.currentTimeMillis(); dirty = true
                hitsSemantic++
                return Match(best.label, bestScore, "semantico", best.phrase)
            }
        }

        misses++
        enqueueEmbed(norm)
        return null
    }

    /** Registra uma frase confirmada como pertencente a um rótulo (aprendizado contínuo). */
    @Synchronized
    fun learn(text: String, label: String, source: String = "aprendido") {
        val norm = normalize(text)
        if (norm.isEmpty() || norm.split(" ").size > 24) return
        val existing = prototypes.firstOrNull { it.norm == norm }
        if (existing != null) {
            if (existing.label == label) {
                existing.hits++
                existing.lastUsedMs = System.currentTimeMillis()
            } else {
                // Conflito de rótulo: o mais recente/confirmado vence, mas registra no log
                Log.w(TAG, "[$name] '$norm' muda de '${existing.label}' para '$label'")
                prototypes.remove(existing)
                prototypes.add(Prototype(text.trim(), norm, label, hits = 1, source = source))
            }
        } else {
            prototypes.add(Prototype(text.trim(), norm, label, hits = 1, source = source))
            enqueueEmbed(norm)
        }
        pruneIfNeeded()
        dirty = true
        saveAsync()
    }

    private fun pruneIfNeeded() {
        if (prototypes.size <= maxPrototypes) return
        // Descarta primeiro o que nunca foi usado e é mais antigo (nunca remove sementes úteis)
        prototypes.sortWith(compareBy({ it.hits }, { it.lastUsedMs }))
        while (prototypes.size > maxPrototypes) prototypes.removeAt(0)
    }

    // ---- Professor de embeddings (fora do caminho crítico) ----

    private val vectorCache = HashMap<String, DoubleArray>()

    private fun enqueueEmbed(norm: String) {
        if (norm.isBlank() || vectorCache.containsKey(norm)) return
        if (pendingEmbeds.contains(norm)) return
        if (pendingEmbeds.size > 60) return
        pendingEmbeds.addLast(norm)
        drainEmbedQueue()
    }

    /**
     * Só busca embedding do que o usuário realmente usou.
     *
     * Antes, toda semente curada entrava na fila e virava vetor de 3072 dims no disco —
     * 101 dos 108 protótipos nunca foram consultados uma única vez. Semente é pega pelos
     * níveis exato e léxico, que custam ~0; ela só precisa de vetor depois de provar que
     * aparece na vida real.
     */
    private fun scheduleMissingEmbeddings() {
        prototypes
            .filter { it.vector == null && (it.hits > 0 || it.source == "aprendido") }
            .take(20)
            .forEach { enqueueEmbed(it.norm) }
    }

    private fun drainEmbedQueue() {
        if (embedWorkerBusy) return
        embedWorkerBusy = true
        scope.launch {
            try {
                while (true) {
                    val next = synchronized(this@SemanticMatcher) { pendingEmbeds.removeFirstOrNull() } ?: break
                    val vec = runCatching { llm?.embed(next) }.getOrNull()
                    synchronized(this@SemanticMatcher) {
                        if (vec != null) {
                            embedsFetched++
                            val curto = truncar(vec)
                            vectorCache[next] = curto
                            prototypes.firstOrNull { it.norm == next }?.vector = curto
                            dirty = true
                        } else {
                            embedsFailed++
                        }
                    }
                }
                saveToDisk()
            } finally {
                embedWorkerBusy = false
            }
        }
    }

    // ---- Persistência ----

    private fun file(): File? = ctxRef?.let { File(it.filesDir, "semantic_$name.json") }

    fun saveAsync() {
        if (!dirty) return
        scope.launch { saveToDisk() }
    }

    @Synchronized
    fun saveToDisk() {
        val f = file() ?: return
        if (!dirty) return
        // Poda: semente que nunca foi usada não carrega vetor para o disco.
        prototypes.forEach {
            if (it.hits == 0 && it.source == "semente" && it.vector != null) it.vector = null
        }
        runCatching {
            val arr = JSONArray()
            for (p in prototypes) {
                val o = JSONObject()
                    .put("phrase", p.phrase)
                    .put("label", p.label)
                    .put("hits", p.hits)
                    .put("last_used", p.lastUsedMs)
                    .put("source", p.source)
                p.vector?.let { v ->
                    val ja = JSONArray()
                    // Guarda com 4 casas para o arquivo não explodir (768 dim * 400 protótipos)
                    for (x in v) ja.put(Math.round(x * 10000.0) / 10000.0)
                    o.put("vector", ja)
                }
                arr.put(o)
            }
            f.writeText(JSONObject().put("prototypes", arr).toString())
            dirty = false
        }.onFailure { Log.w(TAG, "[$name] falha ao salvar: ${it.message}") }
    }

    data class LoadReport(val truncados: Int, val podados: Int) {
        val migrou: Boolean get() = truncados > 0 || podados > 0
    }

    private fun loadFromDisk(): LoadReport {
        var truncados = 0
        var podados = 0
        val f = file() ?: return LoadReport(0, 0)
        if (!f.exists()) return LoadReport(0, 0)
        runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("prototypes")
                ?: return LoadReport(0, 0)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val phrase = o.optString("phrase")
                val norm = normalize(phrase)
                if (norm.isEmpty()) continue
                val vecArr = o.optJSONArray("vector")
                val hits = o.optInt("hits", 0)
                val source = o.optString("source", "aprendido")
                var vec = if (vecArr != null) {
                    val cheio = DoubleArray(vecArr.length()) { vecArr.optDouble(it, 0.0) }
                    if (cheio.size > embedDims) truncados++
                    truncar(cheio)
                } else {
                    null
                }
                // Semente que nunca foi usada não precisa de vetor no disco
                if (vec != null && hits == 0 && source == "semente") {
                    vec = null
                    podados++
                }
                prototypes.add(
                    Prototype(
                        phrase = phrase,
                        norm = norm,
                        label = o.optString("label"),
                        hits = hits,
                        lastUsedMs = o.optLong("last_used", System.currentTimeMillis()),
                        vector = vec,
                        source = source,
                    )
                )
                if (vec != null) vectorCache[norm] = vec
            }
        }.onFailure { Log.w(TAG, "[$name] falha ao carregar: ${it.message}") }
        return LoadReport(truncados, podados)
    }

    @Synchronized
    fun toJson(): JSONObject = JSONObject()
        .put("nome", name)
        .put("prototipos", prototypes.size)
        .put("prototipos_aprendidos", prototypes.count { it.source == "aprendido" })
        .put("com_embedding", prototypes.count { it.vector != null })
        .put("acertos_exato", hitsExact)
        .put("acertos_lexical", hitsLexical)
        .put("acertos_semantico", hitsSemantic)
        .put("desconhecidos", misses)
        .put("embeddings_baixados", embedsFetched)
        .put("embeddings_falhos", embedsFailed)
        .put("fila_embedding", pendingEmbeds.size)

    @Synchronized
    fun labelsSummary(): Map<String, Int> = prototypes.groupBy { it.label }.mapValues { it.value.size }
}
