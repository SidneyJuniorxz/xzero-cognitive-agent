package com.xzero.agent

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject

/**
 * Monitor de saúde (Healthcheck) e auto-recuperação (Watchdog / Self-healing) do AgentePhone.
 * - Verifica serviços essenciais (Acessibilidade, Notificações, Overlay, Bolha) e os reativa via WRITE_SECURE_SETTINGS.
 * - Detecta tarefas travadas (watchdog) e diálogos de erro/ANR na tela ("não está respondendo") e os fecha sozinho.
 * - Coleta telemetria do aparelho (bateria, temperatura, RAM, rede, uptime, contadores de recuperação).
 */
object HealthMonitor {

    @Volatile var lastStepAtMs: Long = 0L
    @Volatile var currentTaskStartedAtMs: Long = 0L
    @Volatile var currentStepInfo: String = "idle"
    @Volatile var lastError: String = ""
    @Volatile var lastRecovery: String = ""
    @Volatile var recoveryCount: Int = 0
    @Volatile var crashCount: Int = 0

    // Backoff exponencial para recuperação
    @Volatile var accessibilityDisableCountInWindow: Int = 0
    @Volatile var nextAccessibilityHealAllowedAtMs: Long = 0L
    private const val MAX_HEALS_PER_WINDOW = 3
    private const val WINDOW_MS = 300_000L // 5 min
    private val random = kotlin.random.Random(System.currentTimeMillis())

    /**
     * Calcula tempo de backoff exponencial com jitter.
     * Sequência: 1s, 2s, 4s, 8s, 16s, 32s (máx 2 min)
     */
    private fun calculateBackoffMs(attemptCount: Int): Long {
        val safeAttempt = attemptCount.coerceAtLeast(0)
        val exponentialBaseMs = 1000L * (1 shl minOf(safeAttempt, 7))
        val cappedMs = minOf(exponentialBaseMs, 120_000L) // máx 2 min
        val halfCap = (cappedMs / 2).coerceAtLeast(1L)
        val jitterMs = random.nextLong(0, halfCap) // ±50%
        return cappedMs + jitterMs
    }

    /**
     * Registra desabilitação de AccessibilityService e aplica backoff.
     */
    fun recordAccessibilityDisable(ctx: Context, reason: String) {
        val now = System.currentTimeMillis()
        val prefs = Prefs(ctx)

        // Atualizar persistência
        prefs.lastAccessibilityDisableAtMs = now
        accessibilityDisableCountInWindow++
        prefs.accessibilityDisableCount5Min = accessibilityDisableCountInWindow
        prefs.healAttemptCount++

        if (accessibilityDisableCountInWindow >= MAX_HEALS_PER_WINDOW) {
            prefs.healthIssuesSummary = "ALERTA: AccessibilityService desabilitado $accessibilityDisableCountInWindow vezes em 5min (motivo: $reason)"
            android.util.Log.w("AgentePhone", "⚠️ ALERTA CRÍTICO: $reason x$accessibilityDisableCountInWindow em ${WINDOW_MS/1000}s!")
        } else {
            android.util.Log.w("AgentePhone", "⚠️ AccessibilityService desabilitado: $reason ($accessibilityDisableCountInWindow/$MAX_HEALS_PER_WINDOW)")
        }
    }

    /**
     * Determina se deve tentar heal agora, respeitando backoff exponencial.
     */
    fun shouldAttemptHeal(): Boolean {
        val now = System.currentTimeMillis()

        // Ainda em período de backoff?
        if (now < nextAccessibilityHealAllowedAtMs) {
            return false
        }

        // Já atingiu limite de tentativas na janela?
        if (accessibilityDisableCountInWindow >= MAX_HEALS_PER_WINDOW) {
            return false
        }

        return true
    }

    /**
     * Retorna quantos ms faltam para a próxima tentativa de heal ser permitida.
     */
    fun getTimeUntilNextHealMs(): Long {
        val now = System.currentTimeMillis()
        return maxOf(0L, nextAccessibilityHealAllowedAtMs - now)
    }

    /**
     * Registra tentativa de heal bem-sucedida ou não.
     */
    private fun recordHealAttempt(ctx: Context, success: Boolean) {
        val prefs = Prefs(ctx)
        prefs.lastHealTimestampMs = System.currentTimeMillis()

        if (!success) {
            nextAccessibilityHealAllowedAtMs = System.currentTimeMillis() + calculateBackoffMs(accessibilityDisableCountInWindow - 1)
            android.util.Log.i("AgentePhone", "⏳ Backoff ativo por ${getTimeUntilNextHealMs()}ms (tentativa #$accessibilityDisableCountInWindow falhou)")
        }
    }

    /**
     * Limpa contadores de tentativas se passou da janela de 5 minutos.
     */
    private fun clearOldCounters(ctx: Context) {
        val now = System.currentTimeMillis()
        val prefs = Prefs(ctx)

        if ((now - prefs.lastAccessibilityDisableAtMs) > WINDOW_MS) {
            accessibilityDisableCountInWindow = 0
            prefs.accessibilityDisableCount5Min = 0
            nextAccessibilityHealAllowedAtMs = 0L
        }
    }

    fun markTaskStart(task: String) {
        val now = SystemClock.elapsedRealtime()
        currentTaskStartedAtMs = now
        lastStepAtMs = now
        currentStepInfo = "iniciando: ${task.take(40)}"
    }

    fun markStep(step: Int, action: String) {
        lastStepAtMs = SystemClock.elapsedRealtime()
        currentStepInfo = "passo $step ($action)"
    }

    fun markTaskEnd() {
        currentTaskStartedAtMs = 0L
        lastStepAtMs = 0L
        currentStepInfo = "idle"
    }

    fun getTelemetry(ctx: Context): JSONObject {
        val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val netOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        val netType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados Móveis"
            else -> "Outro"
        }

        return JSONObject()
            .put("battery_pct", level)
            .put("charging", charging)
            .put("temp_c", tempC)
            .put("ram_avail_mb", availMb)
            .put("ram_total_mb", totalMb)
            .put("network", netType)
            .put("online", netOnline)
    }

    fun recordError(err: String) {
        lastError = err.take(160)
    }

    /** Instala handler global para capturar exceções fatais, registrar e reiniciar a bolha automaticamente. */
    fun installCrashHandler(ctx: Context) {
        val appCtx = ctx.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashCount++
                lastError = "CRASH(${thread.name}): ${throwable.javaClass.simpleName}: ${throwable.message?.take(100)}"
                android.util.Log.e("AgentePhone", "Uncaught exception, auto-recovering", throwable)
                if (Settings.canDrawOverlays(appCtx)) {
                    BubbleService.start(appCtx)
                }
            } catch (_: Exception) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    /**
     * Executa diagnóstico completo e, se `heal = true`, corrige automaticamente:
     * 1. Reativa o AccessibilityService em Settings.Secure se o sistema o tiver matado.
     * 2. Dispensa diálogos de travamento do Android (ANR: "Aguardar" / "Fechar app") se estiverem bloqueando a tela.
     * 3. Cancela tarefa do agente se um passo estiver travado há mais de 75s.
     * 4. Reinicia a escuta de wake word ("Ok agente") se deveria estar ativa.
     */
    fun checkAndHeal(ctx: Context, heal: Boolean = true): JSONObject {
        val actions = mutableListOf<String>()
        val healLog = mutableListOf<String>()
        val pkg = ctx.packageName

        clearOldCounters(ctx)

        // 1. Verificar e reativar Acessibilidade (inclusive se Android marcou como crashed pós-update)
        val a11yRunning = AgentAccessibilityService.instance != null
        if (!a11yRunning && heal) {
            val startTime = System.currentTimeMillis()
            val curBefore = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            healLog += "a11y_was_disabled: cur_len=${curBefore.length}"

            if (!shouldAttemptHeal()) {
                healLog += "a11y_heal_skipped: backoff_active_ms=${getTimeUntilNextHealMs()}"
                recordHealAttempt(ctx, false)
            } else {
                try {
                    val comp = "$pkg/$pkg.AgentAccessibilityService"
                    if (curBefore.contains(comp)) {
                        val cleaned = curBefore.split(':').filter { it.isNotBlank() && it != comp }.joinToString(":")
                        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cleaned)
                        android.os.SystemClock.sleep(80)
                        healLog += "a11y_removed_from_list"
                    }
                    val refreshed = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    val next = if (refreshed.isBlank()) comp else if (!refreshed.contains(comp)) "$refreshed:$comp" else refreshed
                    Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
                    Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

                    val timeMs = System.currentTimeMillis() - startTime
                    val curAfter = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                    val verified = AgentAccessibilityService.instance != null
                    healLog += "a11y_heal_done: time_ms=$timeMs, verified=$verified, settings_contains=${curAfter.contains(comp)}"

                    if (verified) {
                        actions += "accessibility_reenabled"
                        recordHealAttempt(ctx, true)
                    } else {
                        healLog += "a11y_verification_failed: settings ok but instance null"
                        recordError("a11y_reactivation_failed: settings ok but instance null")
                        recordHealAttempt(ctx, false)
                    }
                } catch (e: Exception) {
                    recordError("heal_a11y: ${e.message}")
                    healLog += "a11y_heal_error: ${e.javaClass.simpleName}: ${e.message?.take(60)}"
                    recordHealAttempt(ctx, false)
                }
            }
        }

        // 2. Detectar e fechar diálogo de ANR / App travado na tela
        if (heal && AgentAccessibilityService.instance != null) {
            val anr = AgentAccessibilityService.instance?.dismissSystemErrorDialogIfAny()
            if (!anr.isNullOrEmpty()) actions += "anr_dialog:$anr"
        }

        // 3. Watchdog de tarefa travada (> 75s no mesmo passo ou > 4 min no total)
        val now = SystemClock.elapsedRealtime()
        val stepAgeSec = if (lastStepAtMs > 0) (now - lastStepAtMs) / 1000 else 0
        val taskAgeSec = if (currentTaskStartedAtMs > 0) (now - currentTaskStartedAtMs) / 1000 else 0
        if (heal && BubbleService.instance?.isJobActive() == true && (stepAgeSec > 75 || taskAgeSec > 240)) {
            BubbleService.instance?.abortStuckJob("Watchdog: tarefa travada (${stepAgeSec}s no passo / ${taskAgeSec}s total)")
            actions += "stuck_job_aborted"
        }

        // 4. Garantir Bolha e Wake Word
        val overlayOk = Settings.canDrawOverlays(ctx)
        if (heal && overlayOk && BubbleService.instance == null) {
            runCatching { BubbleService.start(ctx); actions += "bubble_restarted" }
        }
        if (heal && BubbleService.instance != null) {
            if (BubbleService.instance?.ensureWakeRecognizerHealthy() == true) {
                actions += "wake_recognizer_restarted"
            }
        }

        if (actions.isNotEmpty()) {
            recoveryCount += actions.size
            lastRecovery = actions.joinToString(",")
        }

        // Telemetria do sistema
        val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val netOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
        val netType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }

        val pm = ctx.getSystemService(PowerManager::class.java)
        val notifOk = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(ctx, NotifListener::class.java).flattenToString()) == true
        val p = Prefs(ctx)

        val healthy = (AgentAccessibilityService.instance != null) &&
            (BubbleService.instance != null) &&
            overlayOk && netOnline && !mi.lowMemory

        return JSONObject()
            .put("healthy", healthy)
            .put("healed_actions", actions.joinToString(","))
            .put("recovery_count", recoveryCount)
            .put("crash_count", crashCount)
            .put("heal_log", healLog.joinToString(" | "))
            .put("a11y_disable_count_5min", accessibilityDisableCountInWindow)
            .put("next_heal_allowed_in_ms", getTimeUntilNextHealMs())
            .put("services", JSONObject()
                .put("bubble", BubbleService.instance != null)
                .put("accessibility", AgentAccessibilityService.instance != null)
                .put("notifications", notifOk && NotifListener.instance != null)
                .put("overlay_permission", overlayOk)
                .put("write_settings", Settings.System.canWrite(ctx))
                .put("wake_word_enabled", p.wakeWord)
                .put("speak_enabled", p.speak)
                .put("subtle_sound_enabled", p.subtleSound))
            .put("agent", JSONObject()
                .put("busy", BubbleService.instance?.isJobActive() == true)
                .put("step", currentStepInfo)
                .put("step_age_sec", stepAgeSec)
                .put("task_age_sec", taskAgeSec)
                .put("primary_llm", p.primary)
                .put("gemini_model", p.geminiModel)
                .put("claude_model", p.claudeModel)
                .put("openai_model", p.openaiModel)
                .put("gemini_configured", p.geminiKey.isNotBlank())
                .put("claude_configured", p.claudeKey.isNotBlank())
                .put("openai_configured", p.openaiKey.isNotBlank())
                .put("last_error", lastError)
                .put("last_recovery", lastRecovery))
            .put("sensors", JSONObject()
                .put("ambient_lux", ProactiveWatcher.ambientLux)
                .put("motion_state", if (ProactiveWatcher.deviceMoving) "em movimento / na mão" else "parado / sobre superfície")
                .put("is_recording", ProactiveWatcher.isRecordingAudio)
                .put("summary", ProactiveWatcher.getSensorSummary()))
            .put("device", JSONObject()
                .put("battery_pct", level)
                .put("charging", charging)
                .put("temp_c", tempC)
                .put("ram_avail_mb", availMb)
                .put("ram_total_mb", totalMb)
                .put("low_memory", mi.lowMemory)
                .put("network", netType)
                .put("online", netOnline)
                .put("screen_interactive", pm.isInteractive)
                .put("uptime_min", SystemClock.elapsedRealtime() / 60000))
    }

    /** Executa recuperação forçada de UI (fecha diálogos, volta para Home, limpa tarefa travada). */
    fun forceRecoverUi(ctx: Context): String {
        val steps = mutableListOf<String>()
        BubbleService.instance?.let {
            if (it.isJobActive()) {
                it.abortStuckJob("Recuperação manual solicitada")
                steps += "tarefa_cancelada"
            }
        }
        AgentAccessibilityService.instance?.let { svc ->
            svc.dismissSystemErrorDialogIfAny()?.let { steps += "dialog:$it" }
            svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
            svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            steps += "home"
        }
        checkAndHeal(ctx, heal = true)
        recoveryCount++
        lastRecovery = "force:${steps.joinToString("+")}"
        return "Recuperação executada (${steps.joinToString(", ")})"
    }
}
