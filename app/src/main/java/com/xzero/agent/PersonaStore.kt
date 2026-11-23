package com.xzero.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Identidade e perfil da ADA em arquivos Markdown editáveis em tempo de execução.
 *
 * Antes, quem a ADA é e como ela fala estava cravado na string `system` do [Agent] — mudar
 * o tom exigia recompilar e reinstalar o APK. Agora:
 *
 *  - `files/persona/PERSONALITY.md` — identidade, voz, postura;
 *  - `files/persona/USER_PROFILE.md` — quem é o usuário, preferências, privacidade.
 *
 * Na primeira execução os arquivos são semeados a partir dos assets. Depois disso o
 * arquivo do aparelho manda, e o app NUNCA sobrescreve o que o usuário editou.
 *
 * Recarga a quente: o `lastModified` é conferido a cada leitura, então salvar o arquivo
 * muda o comportamento na frase seguinte — sem reinstalar nada.
 *
 * O que NÃO entra aqui: catálogo de ações, formato JSON e protocolo de execução. Isso é
 * contrato de máquina e continua no código, onde pode ser verificado pelo compilador.
 */
object PersonaStore {

    private const val TAG = "PersonaStore"
    private const val DIR = "persona"
    const val PERSONALITY = "PERSONALITY.md"
    const val USER_PROFILE = "USER_PROFILE.md"

    /** Teto de segurança: um arquivo gigante estouraria a janela de contexto do LLM. */
    private const val MAX_CHARS_EACH = 6000

    private data class Cached(val text: String, val mtime: Long, val loadedAtMs: Long)

    private val cache = HashMap<String, Cached>()
    @Volatile private var ctxRef: Context? = null
    @Volatile var lastReloadMs: Long = 0L
        private set
    @Volatile var reloads: Int = 0
        private set

    fun init(ctx: Context) {
        if (ctxRef != null) return
        ctxRef = ctx.applicationContext
        seedIfMissing(PERSONALITY)
        seedIfMissing(USER_PROFILE)
        Log.i(TAG, "📝 Persona em ${dir()?.absolutePath}")
    }

    private fun dir(): File? = ctxRef?.let { File(it.filesDir, DIR).apply { mkdirs() } }

    fun fileFor(name: String): File? = dir()?.let { File(it, name) }

    /** Copia o seed dos assets se o arquivo ainda não existir ou se for uma versão legada sem a identidade expandida. */
    private fun seedIfMissing(name: String) {
        val f = fileFor(name) ?: return
        if (f.exists() && f.length() > 0) {
            val existing = runCatching { f.readText() }.getOrDefault("")
            val isUpToDate = when (name) {
                PERSONALITY -> existing.contains("Mega Man X", ignoreCase = true) && existing.contains("OBSERVED", ignoreCase = true)
                USER_PROFILE -> existing.contains("ContatoFamiliar", ignoreCase = true)
                else -> true
            }
            if (isUpToDate) return
        }
        runCatching {
            ctxRef?.assets?.open("$DIR/$name")?.bufferedReader()?.use { r ->
                f.writeText(r.readText())
            }
            Log.i(TAG, "📝 $name semeado/atualizado a partir dos assets")
        }.onFailure { Log.w(TAG, "falha ao semear $name: ${it.message}") }
    }

    /** Lê o arquivo com cache invalidado por data de modificação (recarga a quente). */
    fun read(name: String): String {
        val f = fileFor(name) ?: return ""
        if (!f.exists()) return ""
        val mtime = f.lastModified()
        cache[name]?.let { if (it.mtime == mtime) return it.text }
        val text = runCatching { f.readText() }.getOrDefault("")
        val trimmed = if (text.length > MAX_CHARS_EACH) {
            Log.w(TAG, "$name tem ${text.length} chars; cortando em $MAX_CHARS_EACH")
            text.take(MAX_CHARS_EACH)
        } else {
            text
        }
        cache[name] = Cached(trimmed, mtime, System.currentTimeMillis())
        lastReloadMs = System.currentTimeMillis()
        reloads++
        Log.i(TAG, "📝 $name recarregado (${trimmed.length} chars)")
        return trimmed
    }

    /**
     * Bloco pronto para o topo do prompt do sistema.
     * Remove as linhas de citação (`>`) e os cabeçalhos de nível 1, que são para o humano
     * ler no editor e só gastariam tokens no prompt.
     */
    fun promptBlock(): String {
        val personality = strip(read(PERSONALITY))
        val profile = strip(read(USER_PROFILE))
        if (personality.isBlank() && profile.isBlank()) return ""
        return buildString {
            if (personality.isNotBlank()) append(personality).append('\n')
            if (profile.isNotBlank()) {
                append("\n# QUEM É O USUÁRIO (perfil editável, declarado por ele)\n")
                append(profile).append('\n')
            }
        }
    }

    private fun strip(md: String): String =
        md.lineSequence()
            .filterNot { it.trimStart().startsWith(">") }
            .filterNot { it.trimStart().startsWith("# ") }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Restaura um arquivo para o seed de fábrica. */
    fun resetToSeed(name: String): String {
        val f = fileFor(name) ?: return "sem contexto"
        runCatching { f.delete() }
        cache.remove(name)
        seedIfMissing(name)
        return "$name restaurado para o padrão de fábrica"
    }

    fun forceReload() {
        cache.clear()
        reloads++
        lastReloadMs = System.currentTimeMillis()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("diretorio", dir()?.absolutePath ?: "")
        .put("personality_chars", read(PERSONALITY).length)
        .put("user_profile_chars", read(USER_PROFILE).length)
        .put("bloco_prompt_chars", promptBlock().length)
        .put("recargas", reloads)
        .put("ultima_recarga_ms", lastReloadMs)
}
