package com.xzero.agent

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Observador passivo de hábitos e padrões de uso (100% local, zero LLM).
 *
 * Aprende com o uso natural do aparelho pelo usuário:
 *  - Transições frequentes entre apps por faixa horária/estado de bateria;
 *  - Sequências repetidas de cliques em apps não-sensíveis (para aprender fluxos);
 *  - Feedback implícito (se uma sugestão/abertura foi seguida ou rejeitada em <20s).
 *
 * Privacidade estrita:
 *  - Bloqueia apps bancários, cofres de senha, autenticação, configurações e SystemUI;
 *  - Nunca registra campos de senha, números longos, e-mails ou textos longos.
 */
object PassiveHabitTracker {

    private const val TAG = "PassiveHabitTracker"
    private const val FILE_NAME = "passive_habits.json"
    private const val MAX_TRANSITIONS = 120
    private const val MAX_WORKFLOWS = 40
    private const val SAVE_INTERVAL_MS = 60_000L

    private val SENSITIVE_KEYWORDS = listOf(
        "bank", "banco", "nubank", "inter", "itau", "bradesco", "santander",
        "caixa", "picpay", "mercadopago", "paypal", "pagseguro", "c6bank",
        "xp", "btg", "nomad", "wise", "1password", "bitwarden", "keepass",
        "authenticator", "authy", "keyguard", "systemui", "biometric",
        "permissioncontroller", "packageinstaller", "settings"
    )

    private val IGNORED_PACKAGES = setOf(
        "com.xzero.agent",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.motorola.launcher3",
        "com.android.launcher3"
    )

    data class TransitionStat(
        val key: String,          // "<bucket>|<fromPkg>-><toPkg>"
        val bucket: String,       // manha | tarde | noite | madrugada
        val fromPkg: String,
        val toPkg: String,
        var count: Int = 0,
        var accepted: Int = 0,
        var rejected: Int = 0,
        var lastSeenMs: Long = 0L,
        var lastSuggestedMs: Long = 0L,
    )

    data class WorkflowStat(
        val signature: String,    // "<pkg>:<step1> -> <step2> -> <step3>"
        val pkg: String,
        var count: Int = 0,
        var lastSeenMs: Long = 0L,
    )

    @Volatile private var ctxRef: Context? = null
    @Volatile private var currentPkg: String = ""
    @Volatile private var currentPkgTs: Long = 0L
    @Volatile private var lastSaveMs: Long = 0L
    @Volatile private var dirtyCount: Int = 0

    @Volatile private var lastSuggestedTargetPkg: String = ""
    @Volatile private var lastSuggestedKey: String = ""
    @Volatile private var lastSuggestedAtMs: Long = 0L

    private val transitions = LinkedHashMap<String, TransitionStat>()
    private val workflows = LinkedHashMap<String, WorkflowStat>()
    private val clickBuffer = ArrayDeque<Pair<String, Long>>() // label, ts

    @Synchronized
    fun init(ctx: Context) {
        if (ctxRef != null) return
        ctxRef = ctx.applicationContext
        load()
        Log.i(TAG, "👁️ PassiveHabitTracker iniciado (${transitions.size} transições, ${workflows.size} fluxos)")
    }

    fun isSensitivePackage(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.US)
        if (p in IGNORED_PACKAGES) return true
        return SENSITIVE_KEYWORDS.any { p.contains(it) }
    }

    private fun currentTimeBucket(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> "manha"
            in 12..17 -> "tarde"
            in 18..23 -> "noite"
            else -> "madrugada"
        }
    }

    private fun isCharging(ctx: Context): Boolean = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.isCharging
    }.getOrDefault(false)

    @Synchronized
    fun onAppWindowChanged(ctx: Context, pkg: String, isAgentActing: Boolean = false) {
        if (ctxRef == null) init(ctx)
        if (pkg.isBlank() || pkg == "com.xzero.agent") return
        val now = System.currentTimeMillis()
        if (pkg == currentPkg && now - currentPkgTs < 1500L) return

        // Feedback implícito se o agente sugeriu/abriu algo nos últimos 20s
        if (lastSuggestedKey.isNotBlank() && now - lastSuggestedAtMs <= 20_000L) {
            val stat = transitions[lastSuggestedKey]
            if (stat != null) {
                if (pkg == lastSuggestedTargetPkg) {
                    stat.accepted++
                    Log.i(TAG, "✅ Hábito confirmado pelo uso: ${stat.key}")
                    lastSuggestedKey = ""
                } else if (currentPkg == lastSuggestedTargetPkg && pkg != lastSuggestedTargetPkg && now - currentPkgTs < 10_000L) {
                    stat.rejected++
                    Log.i(TAG, "↩️ Hábito rejeitado (trocou rápido para $pkg): ${stat.key}")
                    lastSuggestedKey = ""
                }
            }
        }

        val prev = currentPkg
        val prevTs = currentPkgTs
        currentPkg = pkg
        currentPkgTs = now
        clickBuffer.clear()

        if (isAgentActing || prev.isBlank() || prev == pkg) return
        if (isSensitivePackage(prev) || isSensitivePackage(pkg)) return
        // Só conta transição se o usuário ficou pouco tempo ou trocou em sequência (< 15 min)
        if (now - prevTs > 15 * 60_000L) return

        val bucket = currentTimeBucket()
        val key = "$bucket|$prev->$pkg"
        val stat = transitions.getOrPut(key) {
            TransitionStat(key = key, bucket = bucket, fromPkg = prev, toPkg = pkg)
        }
        stat.count++
        stat.lastSeenMs = now
        trimTransitions()
        maybePersist(now)
    }

    @Synchronized
    fun onUserClick(ctx: Context, pkg: String, rawLabel: String, isPassword: Boolean, isAgentActing: Boolean = false) {
        if (ctxRef == null) init(ctx)
        if (isAgentActing || isPassword || pkg.isBlank() || isSensitivePackage(pkg)) return
        val label = sanitizeLabel(rawLabel)
        if (label.isBlank()) return

        val now = System.currentTimeMillis()
        // Descarta cliques antigos (> 45s) para formar apenas fluxos coesos
        while (clickBuffer.isNotEmpty() && now - clickBuffer.first().second > 45_000L) {
            clickBuffer.removeFirst()
        }
        if (clickBuffer.lastOrNull()?.first == label && now - clickBuffer.last().second < 900L) return
        clickBuffer.addLast(label to now)
        while (clickBuffer.size > 6) clickBuffer.removeFirst()

        if (clickBuffer.size >= 3) {
            val tail = clickBuffer.toList().takeLast(3).joinToString(" -> ") { it.first }
            val sig = "$pkg:$tail"
            val wf = workflows.getOrPut(sig) { WorkflowStat(signature = sig, pkg = pkg) }
            wf.count++
            wf.lastSeenMs = now
            trimWorkflows()
            maybePersist(now)
        }
    }

    @Synchronized
    fun markSuggestionShown(acaoProposta: String, targetPkg: String = "") {
        lastSuggestedAtMs = System.currentTimeMillis()
        if (acaoProposta.startsWith("habit_open:")) {
            val key = acaoProposta.removePrefix("habit_open:")
            lastSuggestedKey = key
            lastSuggestedTargetPkg = transitions[key]?.toPkg ?: targetPkg
            transitions[key]?.lastSuggestedMs = lastSuggestedAtMs
            maybePersist(lastSuggestedAtMs, force = true)
        }
    }

    /**
     * Retorna uma sugestão proativa baseada na rotina atual (app em foco + horário),
     * apenas se houver alta confiança e não tiver sido sugerida/rejeitada recentemente.
     */
    @Synchronized
    fun nextHabitSuggestion(): InitiativeEngine.Sugestao? {
        val focus = currentPkg
        if (focus.isBlank() || isSensitivePackage(focus)) return null
        val bucket = currentTimeBucket()
        val now = System.currentTimeMillis()

        val candidates = transitions.values.filter { it.bucket == bucket && it.fromPkg == focus }
        if (candidates.isEmpty()) return null
        val totalFromHere = candidates.sumOf { it.count }.coerceAtLeast(1)
        val best = candidates.maxByOrNull { it.count + it.accepted * 2 - it.rejected * 3 } ?: return null

        val confidence = best.count.toDouble() / totalFromHere.toDouble()
        val recentlySuggested = now - best.lastSuggestedMs < 12 * 60 * 60_000L
        if (best.count < 3 || confidence < 0.60 || best.rejected > best.accepted + 1 || recentlySuggested) {
            return null
        }

        val toShort = shortAppName(best.toPkg)
        val fromShort = shortAppName(best.fromPkg)
        val chargingHint = ctxRef?.let { if (isCharging(it)) " (no carregador)" else "" }.orEmpty()
        return InitiativeEngine.Sugestao(
            id = "hab_${now}",
            texto = "Percebi que à $bucket$chargingHint você costuma abrir $toShort depois de $fromShort. Quer que eu já abra?",
            motivo = "rotina observada ${best.count}x (${(confidence * 100).toInt()}%)",
            acaoProposta = "habit_open:${best.key}",
        )
    }

    @Synchronized
    fun topRoutines(limit: Int = 5): List<TransitionStat> =
        transitions.values.filter { it.count >= 2 }
            .sortedByDescending { it.count + it.accepted * 2 - it.rejected * 2 }
            .take(limit)

    @Synchronized
    fun topWorkflows(limit: Int = 5): List<WorkflowStat> =
        workflows.values.filter { it.count >= 2 }
            .sortedByDescending { it.count }
            .take(limit)

    @Synchronized
    fun toJson(): JSONObject = JSONObject()
        .put("app_atual", currentPkg)
        .put("faixa", currentTimeBucket())
        .put("transicoes_rastreadas", transitions.size)
        .put("fluxos_rastreados", workflows.size)
        .put("rotinas_principais", JSONArray(topRoutines(5).map {
            JSONObject()
                .put("chave", it.key)
                .put("contagem", it.count)
                .put("aceitos", it.accepted)
                .put("rejeitados", it.rejected)
        }))
        .put("fluxos_principais", JSONArray(topWorkflows(5).map {
            JSONObject()
                .put("fluxo", it.signature)
                .put("contagem", it.count)
        }))

    private fun shortAppName(pkg: String): String =
        pkg.substringAfterLast('.').replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString()
        }.ifBlank { pkg }

    private fun sanitizeLabel(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || clean.length > 32) return ""
        // Nunca guarda e-mail ou sequências numéricas (telefone, CPF, código, valor)
        if (clean.contains('@') || Regex("\\d{4,}").containsMatchIn(clean)) return ""
        return clean
    }

    private fun trimTransitions() {
        while (transitions.size > MAX_TRANSITIONS) {
            val oldest = transitions.values.minByOrNull { it.count * 1_000_000L + it.lastSeenMs / 1000L }?.key ?: break
            transitions.remove(oldest)
        }
    }

    private fun trimWorkflows() {
        while (workflows.size > MAX_WORKFLOWS) {
            val oldest = workflows.values.minByOrNull { it.count * 1_000_000L + it.lastSeenMs / 1000L }?.signature ?: break
            workflows.remove(oldest)
        }
    }

    private fun maybePersist(now: Long, force: Boolean = false) {
        dirtyCount++
        if (!force && dirtyCount < 15 && now - lastSaveMs < SAVE_INTERVAL_MS) return
        dirtyCount = 0
        lastSaveMs = now
        val ctx = ctxRef ?: return
        runCatching {
            val root = JSONObject()
            val trArr = JSONArray()
            transitions.values.forEach {
                trArr.put(JSONObject()
                    .put("key", it.key)
                    .put("bucket", it.bucket)
                    .put("from", it.fromPkg)
                    .put("to", it.toPkg)
                    .put("count", it.count)
                    .put("accepted", it.accepted)
                    .put("rejected", it.rejected)
                    .put("lastSeenMs", it.lastSeenMs)
                    .put("lastSuggestedMs", it.lastSuggestedMs))
            }
            val wfArr = JSONArray()
            workflows.values.forEach {
                wfArr.put(JSONObject()
                    .put("sig", it.signature)
                    .put("pkg", it.pkg)
                    .put("count", it.count)
                    .put("lastSeenMs", it.lastSeenMs))
            }
            root.put("transitions", trArr)
            root.put("workflows", wfArr)
            File(ctx.filesDir, FILE_NAME).writeText(root.toString())
        }.onFailure { Log.w(TAG, "Erro ao salvar hábitos: ${it.message}") }
    }

    private fun load() {
        val ctx = ctxRef ?: return
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            val trArr = root.optJSONArray("transitions") ?: JSONArray()
            for (i in 0 until trArr.length()) {
                val o = trArr.getJSONObject(i)
                val key = o.optString("key")
                if (key.isNotBlank()) {
                    transitions[key] = TransitionStat(
                        key = key,
                        bucket = o.optString("bucket", "dia"),
                        fromPkg = o.optString("from"),
                        toPkg = o.optString("to"),
                        count = o.optInt("count", 1),
                        accepted = o.optInt("accepted", 0),
                        rejected = o.optInt("rejected", 0),
                        lastSeenMs = o.optLong("lastSeenMs", 0L),
                        lastSuggestedMs = o.optLong("lastSuggestedMs", 0L),
                    )
                }
            }
            val wfArr = root.optJSONArray("workflows") ?: JSONArray()
            for (i in 0 until wfArr.length()) {
                val o = wfArr.getJSONObject(i)
                val sig = o.optString("sig")
                if (sig.isNotBlank()) {
                    workflows[sig] = WorkflowStat(
                        signature = sig,
                        pkg = o.optString("pkg"),
                        count = o.optInt("count", 1),
                        lastSeenMs = o.optLong("lastSeenMs", 0L),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Erro ao carregar hábitos: ${it.message}") }
    }
}
