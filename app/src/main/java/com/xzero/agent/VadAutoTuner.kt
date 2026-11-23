package com.xzero.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Auto-calibração do despertar por PONTUAÇÃO DE ACERTOS.
 *
 * Regra pedida pelo usuário: quanto menos o X0 acionar o microfone sem necessidade, melhor;
 * acionar e virar ação ganha score. O score vira um ajuste em dB aplicado ao limiar do VAD:
 *
 *  - disparo PRODUTIVO (virou comando, /btw, confirmação aceita) -> +score e limiar desce
 *    um pouco (fica mais sensível, acorda mais fácil da próxima vez);
 *  - disparo DESPERDIÇADO (microfone abriu e não saiu nada: ruído, no_match, timeout)
 *    -> -score e limiar sobe (fica mais exigente, para parar de acordar à toa);
 *  - disparo NEUTRO (ouviu fala real, mas era só conversa ambiente guardada na memória curta)
 *    -> penalidade pequena, porque abriu o microfone sem gerar ação.
 *
 * O ajuste é persistido em [Prefs], então o X0 continua calibrado depois de reiniciar.
 */
object VadAutoTuner {

    private const val TAG = "VadAutoTuner"

    // Passos do controlador (dB por evento) — limitados em MAX_OFFSET_DB = +4.0 dB
    // para impedir que uma sequência de NO_MATCH deixe o VAD surdo (espiral de +13.9 dB).
    private const val STEP_WASTED_DB = 0.35
    private const val STEP_NEUTRAL_DB = 0.0
    private const val STEP_PRODUCTIVE_DB = -0.8
    private const val MIN_OFFSET_DB = -6.0
    private const val MAX_OFFSET_DB = 4.0

    // Pontuação (só informativa/telemetria; o que muda o comportamento é o offset em dB)
    private const val SCORE_PRODUCTIVE = 3
    private const val SCORE_NEUTRAL = -1
    private const val SCORE_WASTED = -2

    enum class Outcome { PRODUCTIVE, NEUTRAL, WASTED }

    @Volatile private var prefs: Prefs? = null

    @Volatile var snrOffsetDb: Double = 0.0
        private set
    @Volatile var score: Int = 0
        private set
    @Volatile var productive: Int = 0
        private set
    @Volatile var wasted: Int = 0
        private set
    @Volatile var neutral: Int = 0
        private set
    @Volatile var lastOutcome: String = "—"
        private set

    /** Janela curta das últimas decisões, para o dashboard mostrar a tendência. */
    private val recent = ArrayDeque<Outcome>()
    @Volatile private var lastDecayMs: Long = System.currentTimeMillis()

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = Prefs(ctx.applicationContext)
        prefs = p
        if (p.vadSnrOffsetDb > MAX_OFFSET_DB || p.vadScore < -200) {
            Log.w(TAG, "♻️ Resetando calibração viciada em espiral (offset=${fmt(p.vadSnrOffsetDb)}dB, score=${p.vadScore}) -> 0.0dB")
            p.vadSnrOffsetDb = 0.0
            p.vadScore = 0
        }
        snrOffsetDb = p.vadSnrOffsetDb.coerceIn(MIN_OFFSET_DB, MAX_OFFSET_DB)
        score = p.vadScore
        productive = p.vadProductiveWakes
        wasted = p.vadWastedWakes
        Log.i(TAG, "🎚️ Calibração carregada: offset=${fmt(snrOffsetDb)}dB score=$score (acertos=$productive, desperdícios=$wasted)")
    }

    @Synchronized
    fun registerOutcome(outcome: Outcome, detail: String = "") {
        val stepDb = when (outcome) {
            Outcome.PRODUCTIVE -> STEP_PRODUCTIVE_DB
            Outcome.NEUTRAL -> STEP_NEUTRAL_DB
            Outcome.WASTED -> STEP_WASTED_DB
        }
        val points = when (outcome) {
            Outcome.PRODUCTIVE -> SCORE_PRODUCTIVE
            Outcome.NEUTRAL -> SCORE_NEUTRAL
            Outcome.WASTED -> SCORE_WASTED
        }
        when (outcome) {
            Outcome.PRODUCTIVE -> productive++
            Outcome.NEUTRAL -> neutral++
            Outcome.WASTED -> wasted++
        }
        score += points
        snrOffsetDb = (snrOffsetDb + stepDb).coerceIn(MIN_OFFSET_DB, MAX_OFFSET_DB)
        lastOutcome = "${outcome.name.lowercase()}${if (detail.isBlank()) "" else " ($detail)"}"

        recent.addLast(outcome)
        while (recent.size > 30) recent.removeFirst()

        prefs?.let {
            it.vadSnrOffsetDb = snrOffsetDb
            it.vadScore = score
            it.vadProductiveWakes = productive
            it.vadWastedWakes = wasted
        }
        Log.i(
            TAG,
            "🎚️ ${outcome.name} $detail -> score=$score, limiar ${if (stepDb >= 0) "+" else ""}${fmt(stepDb)}dB (offset=${fmt(snrOffsetDb)}dB)",
        )
    }

    /**
     * Decaimento temporal anti-surdez: sem feedback novo, o offset relaxa ~0,5 dB/h em direção
     * a zero. Evita o deadlock "limiar alto -> nunca acorda -> nunca ganha acerto para baixar"
     * (ruído de uma madrugada não pode deixar o X0 surdo para sempre).
     * Chamado pelo micHealthLoop do BubbleService (a cada ~4 s); só grava a cada 0,1 dB.
     */
    @Synchronized
    fun decayTick() {
        val now = System.currentTimeMillis()
        val elapsedMin = (now - lastDecayMs) / 60_000.0
        if (elapsedMin < 1.0) return
        lastDecayMs = now
        if (snrOffsetDb == 0.0) return
        val step = 0.06 * elapsedMin // ~3,6 dB por hora (recuperação ágil pós-ruído)
        val before = snrOffsetDb
        snrOffsetDb = when {
            snrOffsetDb > 0 -> (snrOffsetDb - step).coerceAtLeast(0.0)
            else -> (snrOffsetDb + step).coerceAtMost(0.0)
        }
        if (Math.abs(before - snrOffsetDb) >= 0.1 || snrOffsetDb == 0.0) {
            prefs?.vadSnrOffsetDb = snrOffsetDb
        }
    }

    /** Precisão recente: fração dos últimos despertares que viraram ação. */
    fun recentPrecision(): Double {
        if (recent.isEmpty()) return 1.0
        return recent.count { it == Outcome.PRODUCTIVE }.toDouble() / recent.size
    }

    /** Ajuste manual do limiar (o aprendizado continua a partir deste ponto). */
    fun setOffsetDb(ctx: Context, db: Double) {
        init(ctx)
        snrOffsetDb = db.coerceIn(MIN_OFFSET_DB, MAX_OFFSET_DB)
        prefs?.vadSnrOffsetDb = snrOffsetDb
        Log.i(TAG, "🎚️ Offset do limiar definido manualmente: ${fmt(snrOffsetDb)}dB")
    }

    fun reset() {
        snrOffsetDb = 0.0; score = 0; productive = 0; wasted = 0; neutral = 0
        recent.clear(); lastOutcome = "—"
        prefs?.let {
            it.vadSnrOffsetDb = 0.0; it.vadScore = 0
            it.vadProductiveWakes = 0; it.vadWastedWakes = 0
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("offset_db", Math.round(snrOffsetDb * 10.0) / 10.0)
        .put("score", score)
        .put("acertos", productive)
        .put("neutros", neutral)
        .put("desperdicios", wasted)
        .put("precisao_recente", Math.round(recentPrecision() * 1000.0) / 10.0)
        .put("ultimo_resultado", lastOutcome)

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
