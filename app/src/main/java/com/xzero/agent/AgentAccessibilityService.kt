package com.xzero.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Lê a tela como lista numerada de elementos e executa ações neles. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }

    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        instance = this
        PassiveHabitTracker.init(applicationContext)
    }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return
        val isAgentActing = BubbleService.instance?.isJobActive() == true

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                PassiveHabitTracker.onAppWindowChanged(applicationContext, pkg, isAgentActing)
                if (!isAgentActing) {
                    InitiativeEngine.observarAcaoDeTelaParaContinuacao(pkg, "")
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = event.source
                val isPwd = event.isPassword || (src?.isPassword == true)
                val rawLabel = src?.contentDescription?.toString()?.trim().orEmpty()
                    .ifEmpty { src?.text?.toString()?.trim().orEmpty() }
                    .ifEmpty { event.text?.firstOrNull()?.toString()?.trim().orEmpty() }
                    .ifEmpty { src?.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty() }
                runCatching { src?.recycle() }
                if (rawLabel.isNotEmpty()) {
                    PassiveHabitTracker.onUserClick(applicationContext, pkg, rawLabel, isPwd, isAgentActing)
                    if (!isAgentActing && !isPwd) {
                        InitiativeEngine.observarAcaoDeTelaParaContinuacao(pkg, rawLabel)
                    }
                }
            }
        }
    }
    override fun onInterrupt() {}

    /** Snapshot textual da tela. Cada elemento útil recebe um id [n]. */
    fun dumpScreen(): String {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        val roots = windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() != packageName } // ignora a própria bolha
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        val pkg = rootInActiveWindow?.packageName ?: "?"
        sb.append("App em foco: ").append(pkg).append('\n')
        for (r in roots) walk(r, list, sb, 0)
        nodes = list
        if (list.isEmpty()) sb.append("(tela sem elementos legíveis)\n")
        return sb.toString().take(12000)
    }

    private fun walk(n: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>, sb: StringBuilder, depth: Int) {
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val useful = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || text.isNotEmpty() || desc.isNotEmpty()
        if (useful) {
            val id = list.size
            list += n
            val r = Rect(); n.getBoundsInScreen(r)
            val flags = buildList {
                if (n.isClickable) add("clic")
                if (n.isEditable) add("editável")
                if (n.isScrollable) add("rolável")
                if (n.isCheckable) add(if (n.isChecked) "marcado" else "desmarcado")
                if (!n.isEnabled) add("desativado")
            }.joinToString(",")
            val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
            sb.append("[").append(id).append("] ").append(cls)
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(80)).append('"')
            if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(60)).append('"')
            if (hint.isNotEmpty()) sb.append(" hint=\"").append(hint.take(40)).append('"')
            if (flags.isNotEmpty()) sb.append(" {").append(flags).append('}')
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, list, sb, depth + 1) }
    }

    private fun node(id: Int): AccessibilityNodeInfo =
        nodes.getOrNull(id) ?: throw IllegalArgumentException("id $id não existe na tela atual (total=${nodes.size} elementos; releia a TELA ATUAL antes de clicar)")

    /**
     * Retorna a descrição textual completa de um elemento [id] (texto + contentDescription + classe)
     * para o Gatekeeper de Segurança (RuleEngine) inspecionar ANTES de tocar na tela.
     */
    fun describeNode(id: Int, labelHint: String = ""): String {
        val n = nodes.getOrNull(id) ?: findNodeByLabel(labelHint) ?: return labelHint
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        val pText = n.parent?.text?.toString()?.trim().orEmpty()
        val pDesc = n.parent?.contentDescription?.toString()?.trim().orEmpty()
        return listOf(t, d, pText, pDesc, labelHint).filter { it.isNotBlank() }.distinct().joinToString(" | ")
    }

    private fun findNodeByLabel(labelHint: String): AccessibilityNodeInfo? {
        val q = labelHint.trim().lowercase()
        if (q.isEmpty()) return null
        return nodes.firstOrNull {
            val t = it.text?.toString()?.trim()?.lowercase().orEmpty()
            val d = it.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            (t == q || d == q) && (it.isClickable || it.parent?.isClickable == true)
        } ?: nodes.firstOrNull {
            val t = it.text?.toString()?.trim()?.lowercase().orEmpty()
            val d = it.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            (t.contains(q) || d.contains(q)) && (it.isClickable || it.parent?.isClickable == true)
        }
    }

    /**
     * CUA-style AX Tree Diff: extrai apenas as linhas que entraram na tela (ex: pop-ups de confirmação,
     * novos botões ou resultados de busca) para evitar que o LLM reutilize IDs obsoletos.
     */
    fun computeScreenDiff(prevScreen: String, currScreen: String): String {
        if (prevScreen.isBlank() || currScreen.isBlank()) return ""
        defStrip(prevScreen)
        val prevSignatures = prevScreen.lineSequence()
            .drop(1)
            .map { defStrip(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        val addedLines = currScreen.lineSequence()
            .drop(1)
            .filter { line ->
                val sig = defStrip(line)
                sig.isNotEmpty() && sig !in prevSignatures
            }
            .take(18)
            .toList()

        if (addedLines.isEmpty()) return ""
        return "🆕 ELEMENTOS NOVOS NESTE PASSO (use estes [id] atualizados!):\n" + addedLines.joinToString("\n")
    }

    private fun defStrip(line: String): String {
        // Remove o prefixo "[id] " e as coordenadas " @x,y" para comparar o elemento semântico
        return line.replace(Regex("^\\[\\d+]\\s*"), "")
            .replace(Regex("\\s*@\\d+,\\d+$"), "")
            .trim()
    }

    fun tap(id: Int, labelHint: String = ""): String {
        var n: AccessibilityNodeInfo? = nodes.getOrNull(id)
        if (n != null && labelHint.isNotBlank()) {
            val desc = describeNode(id).lowercase()
            if (!desc.contains(labelHint.trim().lowercase())) {
                findNodeByLabel(labelHint)?.let { n = it }
            }
        }
        if (n == null && labelHint.isNotBlank()) {
            n = findNodeByLabel(labelHint)
        }
        val targetNode = n ?: node(id)
        var cur: AccessibilityNodeInfo? = targetNode
        while (cur != null && !cur.isClickable) cur = cur.parent
        if (cur != null && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ok"
        val r = Rect(); targetNode.getBoundsInScreen(r)
        return tapXY(r.centerX(), r.centerY())
    }

    fun longPress(id: Int): String {
        val r = Rect(); node(id).getBoundsInScreen(r)
        return gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 800)
    }

    fun tapXY(x: Int, y: Int) = gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60)

    fun type(id: Int, text: String): String {
        val n = node(id)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "ok"

        // Fallback para editores ricos / WebViews (ex.: Google Docs) que bloqueiam ACTION_SET_TEXT:
        // copia para o Clipboard e executa ACTION_PASTE no nó focado.
        val copied = runCatching {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("ada_input", text))
            cm != null
        }.getOrDefault(false)
        if (copied && n.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return "ok (colado via clipboard)"
        }
        return "falhou ao digitar"
    }

    fun scroll(direction: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (direction) {
            "down" -> gesture(w / 2, h * 0.75f, w / 2, h * 0.25f, 350)
            "up" -> gesture(w / 2, h * 0.25f, w / 2, h * 0.75f, 350)
            "left" -> gesture(w * 0.85f, h / 2, w * 0.15f, h / 2, 300)
            "right" -> gesture(w * 0.15f, h / 2, w * 0.85f, h / 2, 300)
            else -> "direção inválida"
        }
    }

    fun global(action: Int) = if (performGlobalAction(action)) "ok" else "falhou"

    /**
     * Detecta diálogos do sistema Android de app travado/erro ("não está respondendo", "continua apresentando falhas")
     * e clica automaticamente em "Aguardar" ou "Fechar app" para desbloquear a tela.
     */
    fun dismissSystemErrorDialogIfAny(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg != "android" && pkg != "com.android.systemui") return null
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo) {
            allNodes += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it) }
        }
        collect(root)
        val fullText = allNodes.joinToString(" ") { (it.text ?: "").toString() }.lowercase()
        val isAnrOrCrash = fullText.contains("não está respondendo") ||
            fullText.contains("isn't responding") ||
            fullText.contains("apresentando falhas") ||
            fullText.contains("keeps stopping")
        if (!isAnrOrCrash) return null

        val targetLabels = listOf("aguardar", "wait", "fechar app", "close app", "ok")
        for (label in targetLabels) {
            val btn = allNodes.firstOrNull {
                it.isClickable && (it.text?.toString()?.trim()?.lowercase() == label)
            }
            if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "clicou '$label'"
            }
        }
        return null
    }

    /**
     * Procura o botão de envio ativo (WhatsApp, SMS, Telegram) e clica automaticamente nele.
     */
    fun clickSendButton(): String {
        val root = rootInActiveWindow ?: return "sem janela ativa"
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(n: AccessibilityNodeInfo) {
            allNodes += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it) }
        }
        collect(root)
        val sendBtn = allNodes.firstOrNull {
            val desc = it.contentDescription?.toString()?.lowercase().orEmpty()
            val text = it.text?.toString()?.lowercase().orEmpty()
            val viewId = it.viewIdResourceName?.lowercase().orEmpty()
            (desc.contains("enviar") || desc.contains("send") || text == "enviar" || text == "send" || viewId.contains("send")) &&
                (it.isClickable || it.parent?.isClickable == true)
        }
        if (sendBtn != null) {
            var target: AccessibilityNodeInfo? = sendBtn
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return "enviado"
            val r = Rect()
            sendBtn.getBoundsInScreen(r)
            return tapXY(r.centerX(), r.centerY())
        }
        return "botão enviar não encontrado"
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): String {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return if (dispatchGesture(g, null, null)) "ok" else "gesto recusado"
    }

    /**
     * Captura um screenshot real da tela (API 30+ / Android 11+) redimensionado e codificado em JPEG Base64.
     * Fornece evidência visual observável (OBSERVED) para o LLM multimodal, evitando alucinações baseadas
     * apenas na árvore XML de acessibilidade.
     */
    suspend fun captureScreenBase64(maxWidth: Int = 720, quality: Int = 74): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val deferred = CompletableDeferred<String?>()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hwBuf = screenshot.hardwareBuffer
                        try {
                            val rawBmp = Bitmap.wrapHardwareBuffer(hwBuf, screenshot.colorSpace)
                            if (rawBmp == null) {
                                deferred.complete(null)
                                return
                            }
                            val softBmp = rawBmp.copy(Bitmap.Config.ARGB_8888, false)
                            rawBmp.recycle()
                            if (softBmp == null) {
                                deferred.complete(null)
                                return
                            }
                            val finalBmp = if (softBmp.width > maxWidth && softBmp.width > 0) {
                                val ratio = maxWidth.toFloat() / softBmp.width.toFloat()
                                val targetH = (softBmp.height * ratio).toInt().coerceAtLeast(1)
                                val scaled = Bitmap.createScaledBitmap(softBmp, maxWidth, targetH, true)
                                if (scaled !== softBmp) softBmp.recycle()
                                scaled
                            } else {
                                softBmp
                            }
                            val b64 = CameraVision.bitmapToBase64(finalBmp, quality)
                            finalBmp.recycle()
                            deferred.complete(b64)
                        } catch (_: Throwable) {
                            deferred.complete(null)
                        } finally {
                            runCatching { hwBuf.close() }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        deferred.complete(null)
                    }
                },
            )
        } catch (_: Throwable) {
            return null
        }
        return withTimeoutOrNull(3500L) { deferred.await() }
    }
}
