package com.xzero.agent

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Portão de desbloqueio por palavra-chave falada.
 *
 * Regra pedida pelo usuário:
 *  - o X0 SEMPRE escuta com a tela bloqueada (isso já funcionava: o VAD não depende da tela);
 *  - se a conversa COMEÇA com a tela bloqueada, para destravar a tela ele precisa ouvir a
 *    palavra-chave (padrão "powerfly") — assim ninguém que pegue o aparelho na mesa consegue
 *    mandar o X0 abrir o celular;
 *  - se a tela bloqueou NO MEIO de uma conversa que já estava rolando, ele destrava sozinho,
 *    sem pedir nada (o usuário já estava autenticado naquela conversa).
 *
 * A comparação é tolerante ao reconhecedor de voz: "powerfly" em pt-BR costuma voltar como
 * "power fly", "pauer flai", "power flay". Usa o cosseno léxico de trigramas do
 * [SemanticMatcher] em cima do texto sem espaços, então variação de grafia passa.
 */
object UnlockGate {

    private const val TAG = "UnlockGate"
    private const val AUTH_WINDOW_MS = 10 * 60_000L // 10 min após acertar a palavra-chave
    private const val FUZZY_MIN = 0.62

    /** A conversa atual começou com a tela bloqueada? */
    @Volatile private var startedLocked: Boolean = false
    /** Autorização válida até (ms epoch). */
    @Volatile private var authorizedUntilMs: Long = 0L
    @Volatile var lastDecision: String = "—"
        private set
    @Volatile var passphraseAsks: Int = 0
        private set
    @Volatile var passphraseFails: Int = 0
        private set
    @Volatile var graceUnlocks: Int = 0
        private set

    fun isKeyguardLocked(ctx: Context): Boolean =
        runCatching {
            ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)

    fun isDeviceSecure(ctx: Context): Boolean =
        runCatching {
            ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        }.getOrDefault(false)

    /**
     * Marca o início de uma tarefa/turno.
     * `continuingConversation` = já existe janela de conversa aberta (follow-up do modo Live);
     * nesse caso o estado anterior é preservado, então uma tela que bloqueou no meio do papo
     * não volta a exigir a palavra-chave.
     */
    fun onTaskStart(ctx: Context, continuingConversation: Boolean) {
        val lockedNow = isKeyguardLocked(ctx)
        if (continuingConversation && !startedLocked) {
            // Conversa já estava rolando com a tela liberada -> mantém a graça
            if (lockedNow && authorizedUntilMs < System.currentTimeMillis()) {
                authorizedUntilMs = System.currentTimeMillis() + AUTH_WINDOW_MS
                graceUnlocks++
                lastDecision = "graça: tela bloqueou durante a conversa"
                Log.i(TAG, "🔓 $lastDecision")
            }
            return
        }
        if (!continuingConversation) {
            startedLocked = lockedNow
            if (lockedNow) {
                authorizedUntilMs = 0L
                lastDecision = "conversa iniciada com tela bloqueada -> exige palavra-chave"
            } else {
                authorizedUntilMs = System.currentTimeMillis() + AUTH_WINDOW_MS
                lastDecision = "conversa iniciada com tela liberada"
            }
            Log.i(TAG, "🔐 $lastDecision")
        }
    }

    fun isAuthorized(): Boolean = System.currentTimeMillis() < authorizedUntilMs

    /** Precisa pedir a palavra-chave antes de destravar a tela? */
    fun needsPassphrase(): Boolean = startedLocked && !isAuthorized()

    fun passphraseHint(ctx: Context): String =
        Prefs(ctx).unlockPassphrase.ifBlank { "powerfly" }

    /** Compara a fala com a palavra-chave de forma tolerante ao ASR. */
    fun verify(ctx: Context, spoken: String): Boolean {
        passphraseAsks++
        val expected = compact(passphraseHint(ctx))
        val heard = compact(spoken)
        if (heard.isEmpty()) {
            passphraseFails++
            lastDecision = "palavra-chave vazia"
            return false
        }
        // 1. contém a palavra exata (sem espaços/acentos)
        if (heard.contains(expected)) {
            authorizedUntilMs = System.currentTimeMillis() + AUTH_WINDOW_MS
            lastDecision = "palavra-chave correta (exata)"
            Log.i(TAG, "🔓 $lastDecision")
            return true
        }
        // 2. parecido o suficiente (ASR trocou letras)
        val sim = SemanticMatcher.lexicalCosine(
            SemanticMatcher.lexicalVector(expected),
            SemanticMatcher.lexicalVector(heard),
        )
        val simWindow = heard.windowed(expected.length.coerceAtMost(heard.length), 1, true)
            .maxOfOrNull { w ->
                SemanticMatcher.lexicalCosine(
                    SemanticMatcher.lexicalVector(expected),
                    SemanticMatcher.lexicalVector(w),
                )
            } ?: 0.0
        val best = maxOf(sim, simWindow)
        return if (best >= FUZZY_MIN) {
            authorizedUntilMs = System.currentTimeMillis() + AUTH_WINDOW_MS
            lastDecision = "palavra-chave aceita por semelhança (${fmt(best)})"
            Log.i(TAG, "🔓 $lastDecision")
            true
        } else {
            passphraseFails++
            lastDecision = "palavra-chave errada (semelhança ${fmt(best)})"
            Log.w(TAG, "🔒 $lastDecision")
            false
        }
    }

    /** Encerra a conversa: a próxima com tela bloqueada volta a exigir a palavra-chave. */
    fun onConversationEnded() {
        startedLocked = false
        authorizedUntilMs = 0L
        lastDecision = "conversa encerrada — portão rearmado"
    }

    private fun compact(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(java.util.Locale("pt", "BR")), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]"), "")

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.2f", v)

    fun toJson(): JSONObject = JSONObject()
        .put("conversa_iniciou_bloqueada", startedLocked)
        .put("autorizado", isAuthorized())
        .put("precisa_palavra_chave", needsPassphrase())
        .put("ultima_decisao", lastDecision)
        .put("pedidos_palavra_chave", passphraseAsks)
        .put("falhas_palavra_chave", passphraseFails)
        .put("desbloqueios_por_graca", graceUnlocks)
}
