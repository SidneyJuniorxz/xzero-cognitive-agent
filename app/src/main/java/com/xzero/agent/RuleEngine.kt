package com.xzero.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RuleEngine (XZERO v3.2 — Motor de Regras Configuráveis + CUA 4-Tier Confirmation Gatekeeper):
 *
 * Inspirado na arquitetura Computer Use (CUA) do ChatGPT/Codex e nos aprendizados reais dos logs do usuário:
 * 1. Permite cadastrar, alterar, ativar/desativar e remover regras permanentes por voz ou pela UI (App e Web).
 * 2. Classifica regras em 3 modos de imposição (EnforcementMode):
 *    - BLOCK_ALWAYS: Bloqueio absoluto no código antes do clique (ex: "Nunca apagar nada no WhatsApp").
 *    - CONFIRM_ALWAYS: Exige confirmação explícita via `ask_user` no momento da ação antes de tocar na tela
 *      (ex: apagar arquivos/vídeos/SMS, limpar dados, realizar pagamentos/Pix/transferências).
 *    - BEHAVIOR_PROMPT: Diretriz comportamental injetada no System Prompt e no verificador
 *      (ex: "Não repetir o nome usuário em toda frase", "Aguardar quando o usuário pedir espera",
 *       "Nunca interromper conversas com terceiros em voz alta").
 */
class RuleEngine(private val ctx: Context) {

    enum class EnforcementMode {
        BLOCK_ALWAYS,     // Impede a execução da ação no código (Hard Block)
        CONFIRM_ALWAYS,   // Intercepta a ação e exige ask_user("Confirma...?") antes de clicar
        BEHAVIOR_PROMPT   // Regra de comportamento/estilo/fluxo injetada no prompt de sistema
    }

    data class UserRule(
        val id: String,
        var title: String,
        var instruction: String,
        var mode: EnforcementMode,
        var targetPackages: List<String>, // ex: ["com.whatsapp"] ou ["*"]
        var triggerKeywords: List<String>, // ex: ["apagar", "excluir", "limpar", "lixeira"]
        var enabled: Boolean = true,
        val createdAt: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    )

    data class ActionInterceptResult(
        val allowed: Boolean,
        val requiresConfirmation: Boolean,
        val matchedRule: UserRule?,
        val message: String
    )

    private val file = File(ctx.filesDir, "user_rules.json")
    private val rules = mutableListOf<UserRule>()

    // Guarda aprovações concedidas pelo usuário durante a tarefa atual para não perguntar 2x no mesmo passo confirmado
    private val sessionApprovedSignatures = mutableSetOf<String>()

    init {
        load()
        if (rules.isEmpty()) {
            seedDefaultRulesFromLogs()
        }
    }

    @Synchronized
    private fun seedDefaultRulesFromLogs() {
        rules.add(
            UserRule(
                id = "rule_no_whatsapp_delete",
                title = "Nunca apagar nada no WhatsApp",
                instruction = "PROIBIDO apagar mensagens, conversas, mídias ou clicar em 'Limpar' / 'Limpar tudo' / 'Apagar' dentro do WhatsApp.",
                mode = EnforcementMode.BLOCK_ALWAYS,
                targetPackages = listOf("com.whatsapp"),
                triggerKeywords = listOf("apagar", "excluir", "deletar", "limpar", "limpar tudo", "remover", "lixeira", "delete", "clear"),
                enabled = true
            )
        )
        rules.add(
            UserRule(
                id = "rule_confirm_deletions",
                title = "Confirmar antes de apagar/excluir dados",
                instruction = "SEMPRE pedir confirmação explícita via ask_user antes de apagar, excluir, deletar ou mover para a lixeira qualquer vídeo, foto, SMS, e-mail ou arquivo, informando exatamente quais itens e tamanhos serão afetados.",
                mode = EnforcementMode.CONFIRM_ALWAYS,
                targetPackages = listOf("*"),
                triggerKeywords = listOf(
                    "excluir", "apagar", "deletar", "lixeira", "mover para a lixeira",
                    "mover 1 arquivo", "mover ", "esvaziar lixeira", "limpar tudo",
                    "remover permanentemente", "desinstalar", "formatar", "delete", "trash"
                ),
                enabled = true
            )
        )
        rules.add(
            UserRule(
                id = "rule_confirm_payments",
                title = "Confirmar pagamentos, Pix e transações",
                instruction = "SEMPRE pedir autorização explícita ao usuário antes de tocar em botões de pagar, transferir, confirmar Pix, comprar ou assinar qualquer serviço.",
                mode = EnforcementMode.CONFIRM_ALWAYS,
                targetPackages = listOf("*"),
                triggerKeywords = listOf(
                    "pagar", "transferir", "confirmar pagamento", "enviar pix", "fazer pix",
                    "finalizar compra", "comprar agora", "confirmar transferência", "assinar"
                ),
                enabled = true
            )
        )
        rules.add(
            UserRule(
                id = "rule_natural_voice_no_name_spam",
                title = "Fala natural sem repetir 'usuário'",
                instruction = "Fale de forma natural, direta e fluida. NÃO repita a palavra 'usuário' em todas as frases e não termine toda resposta com 'O que temos para agora?'.",
                mode = EnforcementMode.BEHAVIOR_PROMPT,
                targetPackages = listOf("*"),
                triggerKeywords = emptyList(),
                enabled = true
            )
        )
        rules.add(
            UserRule(
                id = "rule_silent_third_party_conversations",
                title = "Silêncio em conversas com outras pessoas",
                instruction = "Quando o usuário estiver conversando com outras pessoas (ex: ContatoFamiliar, equipe, reuniões) e NÃO chamar 'X0'/'XZERO', NUNCA interrompa falando em voz alta. Se captar um lembrete importante, use apenas post_notification com silent=true ou remember_fact.",
                mode = EnforcementMode.BEHAVIOR_PROMPT,
                targetPackages = listOf("*"),
                triggerKeywords = emptyList(),
                enabled = true
            )
        )
        rules.add(
            UserRule(
                id = "rule_wait_and_verify_backup_before_move",
                title = "Em backups/mover arquivos: verificar destino antes de remover origem",
                instruction = "Estilo CUA/ChatGPT: quando o pedido for mover vídeos/arquivos para o Google Drive ou nuvem, primeiro liste os arquivos exatos com tamanho (scan_storage_files), crie a pasta de destino, inicie o upload e NUNCA apague os arquivos originais antes que o upload esteja 100% confirmado e autorizado pelo usuário.",
                mode = EnforcementMode.BEHAVIOR_PROMPT,
                targetPackages = listOf("*"),
                triggerKeywords = emptyList(),
                enabled = true
            )
        )
        save()
    }

    @Synchronized
    fun clearSessionApprovals() {
        sessionApprovedSignatures.clear()
    }

    @Synchronized
    fun grantSessionApproval(signature: String) {
        sessionApprovedSignatures.add(signature.lowercase(Locale("pt", "BR")))
    }

    /**
     * Intercepta ações de UI (`tap`, `long_press`) ANTES de serem executadas no Android.
     * Inspeciona o texto/descrição do elemento alvo na árvore de acessibilidade atual.
     */
    @Synchronized
    fun evaluateUiActionBeforeExecution(
        action: String,
        targetElementDescription: String,
        currentPackage: String,
        taskText: String
    ): ActionInterceptResult {
        if (action != "tap" && action != "long_press") {
            return ActionInterceptResult(allowed = true, requiresConfirmation = false, matchedRule = null, message = "ok")
        }

        val elemLower = targetElementDescription.lowercase(Locale("pt", "BR"))
        val pkgLower = currentPackage.lowercase(Locale("pt", "BR"))

        // Exceção contextual: tecla Backspace ("Excluir" / "Apagar") em seletores numéricos de hora/calculadora
        // edita apenas um dígito e NÃO é exclusão destrutiva de arquivo/item!
        val isKeypadBackspace = (pkgLower.contains("deskclock") || pkgLower.contains("calculator")) &&
            (elemLower == "excluir" || elemLower == "apagar" || elemLower == "delete" || elemLower.endsWith("| excluir"))
        if (isKeypadBackspace) {
            return ActionInterceptResult(allowed = true, requiresConfirmation = false, matchedRule = null, message = "ok_keypad_backspace")
        }

        // 1. Verifica regras BLOCK_ALWAYS primeiro (ex: Nunca apagar nada no WhatsApp)
        for (rule in rules.filter { it.enabled && it.mode == EnforcementMode.BLOCK_ALWAYS }) {
            val pkgMatches = rule.targetPackages.any { it == "*" || pkgLower.contains(it.lowercase()) }
            if (!pkgMatches) continue
            val hitKeyword = rule.triggerKeywords.firstOrNull { kw ->
                kw.isNotBlank() && elemLower.contains(kw.lowercase(Locale("pt", "BR")))
            }
            if (hitKeyword != null) {
                return ActionInterceptResult(
                    allowed = false,
                    requiresConfirmation = false,
                    matchedRule = rule,
                    message = "🛑 BLOQUEADO PELA REGRA '${rule.title}': tentativa de tocar em '$targetElementDescription' no app '$currentPackage' (palavra-gatilho: '$hitKeyword'). Esta ação é estritamente proibida."
                )
            }
        }

        // 2. Verifica regras CONFIRM_ALWAYS (ex: Apagar/Excluir/Lixeira/Pagar/Pix)
        for (rule in rules.filter { it.enabled && it.mode == EnforcementMode.CONFIRM_ALWAYS }) {
            val pkgMatches = rule.targetPackages.any { it == "*" || pkgLower.contains(it.lowercase()) }
            if (!pkgMatches) continue
            val hitKeyword = rule.triggerKeywords.firstOrNull { kw ->
                kw.isNotBlank() && elemLower.contains(kw.lowercase(Locale("pt", "BR")))
            }
            if (hitKeyword != null) {
                val approvalKey = "${rule.id}:$pkgLower:$hitKeyword"
                if (sessionApprovedSignatures.contains(approvalKey)) {
                    return ActionInterceptResult(allowed = true, requiresConfirmation = false, matchedRule = rule, message = "aprovado_nesta_sessao")
                }
                return ActionInterceptResult(
                    allowed = false,
                    requiresConfirmation = true,
                    matchedRule = rule,
                    message = "Confirma executar '$targetElementDescription' no app ${friendlyAppName(currentPackage)}? (Regra ativa: ${rule.title})"
                )
            }
        }

        return ActionInterceptResult(allowed = true, requiresConfirmation = false, matchedRule = null, message = "ok")
    }

    private fun friendlyAppName(pkg: String): String {
        return when {
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("messaging") -> "Mensagens (SMS)"
            pkg.contains("nbu.files") || pkg.contains("documentsui") -> "Arquivos (Files)"
            pkg.contains("photos") || pkg.contains("gallery") -> "Galeria / Fotos"
            pkg.contains("docs") -> "Google Drive"
            pkg.contains("gm") -> "Gmail"
            else -> pkg.substringAfterLast('.')
        }
    }

    @Synchronized
    fun addOrUpdateRule(
        title: String,
        instruction: String,
        modeStr: String = "BEHAVIOR_PROMPT",
        targetPkg: String = "*",
        keywordsCsv: String = "",
        ruleId: String? = null
    ): String {
        val cleanTitle = title.trim().ifEmpty { "Regra personalizada" }
        val cleanInst = instruction.trim().ifEmpty { cleanTitle }
        val mode = when (modeStr.uppercase(Locale.US)) {
            "BLOCK", "BLOCK_ALWAYS", "BLOQUEAR", "PROIBIR", "NUNCA" -> EnforcementMode.BLOCK_ALWAYS
            "CONFIRM", "CONFIRM_ALWAYS", "CONFIRMAR", "PERGUNTAR" -> EnforcementMode.CONFIRM_ALWAYS
            else -> {
                val lower = (cleanTitle + " " + cleanInst).lowercase(Locale("pt", "BR"))
                when {
                    lower.contains("nunca apag") || lower.contains("proibido apag") || lower.contains("jamais apag") ->
                        EnforcementMode.BLOCK_ALWAYS
                    lower.contains("confirmar") || lower.contains("pedir autorização") || lower.contains("perguntar antes") ->
                        EnforcementMode.CONFIRM_ALWAYS
                    else -> EnforcementMode.BEHAVIOR_PROMPT
                }
            }
        }

        val pkgs = if (targetPkg.isBlank() || targetPkg == "*") {
            val lower = (cleanTitle + " " + cleanInst).lowercase(Locale("pt", "BR"))
            when {
                lower.contains("whatsapp") || lower.contains("zap") -> listOf("com.whatsapp")
                lower.contains("sms") || lower.contains("mensagens") -> listOf("com.google.android.apps.messaging")
                else -> listOf("*")
            }
        } else {
            targetPkg.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        val kwList = if (keywordsCsv.isNotBlank()) {
            keywordsCsv.split(",").map { it.trim().lowercase(Locale("pt", "BR")) }.filter { it.isNotEmpty() }
        } else {
            val lower = (cleanTitle + " " + cleanInst).lowercase(Locale("pt", "BR"))
            buildList {
                if (lower.contains("apagar") || lower.contains("excluir") || lower.contains("deletar")) {
                    addAll(listOf("apagar", "excluir", "deletar", "lixeira", "limpar", "limpar tudo", "remover"))
                }
                if (lower.contains("pagar") || lower.contains("pix") || lower.contains("transferir")) {
                    addAll(listOf("pagar", "transferir", "pix", "confirmar pagamento", "comprar"))
                }
            }
        }

        val existing = rules.firstOrNull {
            (ruleId != null && it.id == ruleId) || it.title.equals(cleanTitle, ignoreCase = true)
        }

        return if (existing != null) {
            existing.title = cleanTitle
            existing.instruction = cleanInst
            existing.mode = mode
            existing.targetPackages = pkgs
            if (kwList.isNotEmpty()) existing.triggerKeywords = kwList
            existing.enabled = true
            save()
            "Regra atualizada com sucesso: [${existing.id}] '${existing.title}' (modo=${existing.mode})"
        } else {
            val newId = ruleId?.takeIf { it.isNotBlank() } ?: "rule_${System.currentTimeMillis() % 100000}"
            val r = UserRule(
                id = newId,
                title = cleanTitle,
                instruction = cleanInst,
                mode = mode,
                targetPackages = pkgs,
                triggerKeywords = kwList,
                enabled = true
            )
            rules.add(0, r)
            save()
            "Nova regra salva e ativada: [${r.id}] '${r.title}' (modo=${r.mode})"
        }
    }

    @Synchronized
    fun toggleRule(idOrTitle: String, enabled: Boolean? = null): String {
        val q = idOrTitle.trim().lowercase(Locale("pt", "BR"))
        val r = rules.firstOrNull {
            it.id.lowercase() == q || it.title.lowercase(Locale("pt", "BR")).contains(q)
        } ?: return "Regra '$idOrTitle' não encontrada."
        r.enabled = enabled ?: !r.enabled
        save()
        return "Regra '${r.title}' agora está ${if (r.enabled) "ATIVA" else "DESATIVADA"}."
    }

    @Synchronized
    fun removeRule(idOrTitle: String): String {
        val q = idOrTitle.trim().lowercase(Locale("pt", "BR"))
        val idx = rules.indexOfFirst {
            it.id.lowercase() == q || it.title.lowercase(Locale("pt", "BR")).contains(q)
        }
        if (idx < 0) return "Regra '$idOrTitle' não encontrada."
        val removed = rules.removeAt(idx)
        save()
        return "Regra '${removed.title}' removida com sucesso."
    }

    @Synchronized
    fun listRulesSummary(): String {
        if (rules.isEmpty()) return "Nenhuma regra cadastrada."
        return rules.joinToString("\n") { r ->
            val st = if (r.enabled) "✅ ATIVA" else "⏸️ INATIVA"
            val badge = when (r.mode) {
                EnforcementMode.BLOCK_ALWAYS -> "🛑 BLOQUEIO"
                EnforcementMode.CONFIRM_ALWAYS -> "⚠️ CONFIRMAR"
                EnforcementMode.BEHAVIOR_PROMPT -> "🧠 COMPORTAMENTO"
            }
            "• [${r.id}] $st ($badge) ${r.title}: ${r.instruction}"
        }
    }

    @Synchronized
    fun getPromptRulesBlock(): String {
        val active = rules.filter { it.enabled }
        if (active.isEmpty()) return ""
        return buildString {
            append("REGRAS PERMANENTES E GUARDRAILS DO USUÁRIO (OBRIGATÓRIO CUMPRIR 100%):\n")
            for (r in active) {
                val tag = when (r.mode) {
                    EnforcementMode.BLOCK_ALWAYS -> "[PROIBIÇÃO ABSOLUTA]"
                    EnforcementMode.CONFIRM_ALWAYS -> "[CONFIRMAÇÃO OBRIGATÓRIA ANTES DE AGIR]"
                    EnforcementMode.BEHAVIOR_PROMPT -> "[DIRETRIZ DE COMPORTAMENTO]"
                }
                append("- ").append(tag).append(' ').append(r.title).append(": ").append(r.instruction).append('\n')
            }
        }.trim()
    }

    @Synchronized
    fun getAllRules(): List<UserRule> = rules.toList()

    @Synchronized
    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", r.title)
                    .put("instruction", r.instruction)
                    .put("mode", r.mode.name)
                    .put("targetPackages", JSONArray(r.targetPackages))
                    .put("triggerKeywords", JSONArray(r.triggerKeywords))
                    .put("enabled", r.enabled)
                    .put("createdAt", r.createdAt)
            )
        }
        return arr
    }

    @Synchronized
    private fun save() {
        runCatching {
            file.writeText(toJsonArray().toString(2))
        }
    }

    @Synchronized
    private fun load() {
        rules.clear()
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkgs = mutableListOf<String>()
                val pArr = o.optJSONArray("targetPackages") ?: JSONArray()
                for (j in 0 until pArr.length()) pkgs.add(pArr.getString(j))
                val kws = mutableListOf<String>()
                val kArr = o.optJSONArray("triggerKeywords") ?: JSONArray()
                for (j in 0 until kArr.length()) kws.add(kArr.getString(j))
                val mode = runCatching {
                    EnforcementMode.valueOf(o.optString("mode", "BEHAVIOR_PROMPT"))
                }.getOrDefault(EnforcementMode.BEHAVIOR_PROMPT)
                rules.add(
                    UserRule(
                        id = o.optString("id", "rule_$i"),
                        title = o.optString("title", "Regra $i"),
                        instruction = o.optString("instruction", ""),
                        mode = mode,
                        targetPackages = if (pkgs.isEmpty()) listOf("*") else pkgs,
                        triggerKeywords = kws,
                        enabled = o.optBoolean("enabled", true),
                        createdAt = o.optString("createdAt", "")
                    )
                )
            }
        }
    }
}
