package com.xzero.agent

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Motor Proativo, Sensores Físicos, Gravador Inteligente e Vigia de Notificações/Tarefas
 * (WhatsApp, E-mail, Claude, Codex, etc.).
 */
object ProactiveWatcher : SensorEventListener {

    data class WatchRule(
        val id: String,
        val source: String,       // ex: "whatsapp", "email", "claude", "codex", "any"
        val filter: String,       // ex: "ColegaEquipe", "ContatoFamiliar", "erro", "deploy"
        val instruction: String,  // ex: "Avise o usuário e sugira resposta"
        val autoOpen: Boolean = false
    )

    private val watches = mutableListOf<WatchRule>()
    private var watchesFile: File? = null
    private var sensorManager: SensorManager? = null
    private var recorder: MediaRecorder? = null
    @Volatile var isRecordingAudio = false
    @Volatile var currentRecordingFile: String? = null
    @Volatile var ambientLux: Float = -1f
    @Volatile var deviceMoving: Boolean = false
    @Volatile var deviceFlatOnTable: Boolean = false
    @Volatile var proximityNear: Boolean = false
    @Volatile var gyroMagnitude: Float = 0f
    @Volatile var stepCount: Int = 0
    @Volatile var magneticUt: Float = -1f
    @Volatile var pressureHpa: Float = -1f
    @Volatile var azimuthDeg: Int = -1
    @Volatile var availableSensorsCount: Int = 0
    @Volatile var availableSensorsNames: String = ""
    @Volatile var sensorMode: String = "auto" // "auto", "eco", "normal", "high_alert"
    @Volatile var enableLight: Boolean = true
    @Volatile var enableAccel: Boolean = true
    @Volatile var enableProximity: Boolean = true
    @Volatile var enableGyro: Boolean = true
    @Volatile var enableSteps: Boolean = true
    @Volatile var autoSilentInDarkOrPocket: Boolean = true
    @Volatile var inConversationalPosture: Boolean = false
    private var lastShakeMs = 0L
    private var shakePeakCount = 0
    private var firstShakePeakMs = 0L
    private var lastPickupMs = 0L
    private var wasFlatOnTable = false
    private var lastProactiveAlertMs = 0L
    private var notifCounter = 200

    fun init(ctx: Context) {
        watchesFile = File(ctx.filesDir, "proactive_watches.json")
        sensorMode = Prefs(ctx).sensorMode.ifBlank { "normal" }
        loadWatches()
        setupSensors(ctx)
    }

    fun setupSensors(ctx: Context) {
        runCatching {
            val sm = (sensorManager ?: ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager) ?: return
            sensorManager = sm
            sm.unregisterListener(this)

            val delayRate = when (sensorMode) {
                "eco" -> SensorManager.SENSOR_DELAY_NORMAL
                "high_alert" -> SensorManager.SENSOR_DELAY_GAME
                else -> SensorManager.SENSOR_DELAY_UI
            }

            if (enableAccel) {
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(this, it, delayRate)
                }
            }
            if (enableLight) {
                sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            if (enableProximity) {
                sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            if (enableGyro && sensorMode != "eco") {
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                    sm.registerListener(this, it, delayRate)
                }
            }
            if (enableSteps) {
                sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            if (sensorMode != "eco") {
                sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
                sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                    sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            val all = sm.getSensorList(Sensor.TYPE_ALL)
            availableSensorsCount = all.size
            availableSensorsNames = all.map { it.name }.distinct().take(15).joinToString(", ")
        }
    }

    /**
     * Permite que o próprio XZERO reconfigure dinamicamente quais sensores monitorar
     * e a frequência de amostragem conforme a necessidade que ele perceber no contexto.
     */
    fun configureSensors(
        ctx: Context,
        mode: String = sensorMode,
        light: Boolean = enableLight,
        accel: Boolean = enableAccel,
        proximity: Boolean = enableProximity,
        gyro: Boolean = enableGyro,
        steps: Boolean = enableSteps,
        autoSilent: Boolean = autoSilentInDarkOrPocket,
        keepCriticalOnly: Boolean = false
    ): String {
        val normMode = mode.lowercase().trim().ifBlank { "auto" }
        if (keepCriticalOnly || normMode == "smart_save") {
            sensorMode = "eco"
            enableLight = false
            enableGyro = false
            enableSteps = false
            enableAccel = true
            enableProximity = true
        } else {
            sensorMode = normMode
            enableLight = light
            enableAccel = accel
            enableProximity = proximity
            enableGyro = gyro
            enableSteps = steps
        }
        autoSilentInDarkOrPocket = autoSilent
        setupSensors(ctx)
        return "Sensores reconfigurados pelo XZERO: modo=$sensorMode [luz=$enableLight, accel=$enableAccel, prox=$enableProximity, gyro=$enableGyro, passos=$enableSteps, autoSilent=$autoSilentInDarkOrPocket]"
    }

    /**
     * Decide autonomamente se o ambiente pede silêncio (ex: quarto escuro < 8 lux ou celular no bolso/virado).
     */
    fun shouldPreferSilentNotification(): Boolean {
        if (!autoSilentInDarkOrPocket) return false
        val inDark = ambientLux in 0f..7.5f
        return proximityNear || inDark
    }

    /**
     * Cria uma notificação nativa do XZERO na barra de status do Android para deixar recados,
     * relatórios ou alertas sem precisar falar em voz alta.
     */
    fun postAgentNotification(ctx: Context, title: String, message: String, silent: Boolean = true): String {
        return try {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val chanId = if (silent) "xzero_silent_notes" else "xzero_alerts"
            val importance = if (silent) android.app.NotificationManager.IMPORTANCE_LOW else android.app.NotificationManager.IMPORTANCE_HIGH
            val chan = android.app.NotificationChannel(chanId, "X0 / X-Zero Recados & Alertas", importance)
            nm.createNotificationChannel(chan)

            val n = android.app.Notification.Builder(ctx, chanId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("⚡ X0 / X-Zero: ${title.take(60)}")
                .setContentText(message.take(180))
                .setStyle(android.app.Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()

            val id = ++notifCounter
            nm.notify(id, n)
            BubbleService.instance?.showSpeechBubble("🔔 Notificação criada: $title", 4500L)
            "Notificação criada com sucesso [id=$id, silenciosa=$silent]: '$title' — '$message'"
        } catch (e: Throwable) {
            "erro: falha ao criar notificação: ${e.message}"
        }
    }

    fun shutdown() {
        runCatching { sensorManager?.unregisterListener(this) }
        stopAudioRecording()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                ambientLux = event.values[0]
            }
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = event.sensor.maximumRange.coerceAtLeast(1f)
                proximityNear = event.values[0] < maxRange && event.values[0] < 5f
            }
            Sensor.TYPE_GYROSCOPE -> {
                val rx = event.values[0]
                val ry = event.values[1]
                val rz = event.values[2]
                gyroMagnitude = sqrt(rx * rx + ry * ry + rz * rz)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                stepCount = event.values[0].toInt()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val mx = event.values[0]; val my = event.values[1]; val mz = event.values[2]
                magneticUt = sqrt(mx * mx + my * my + mz * mz)
            }
            Sensor.TYPE_PRESSURE -> {
                pressureHpa = event.values[0]
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rot = FloatArray(9)
                val orient = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, orient)
                val deg = Math.toDegrees(orient[0].toDouble()).toInt()
                azimuthDeg = (deg + 360) % 360
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gX = x / SensorManager.GRAVITY_EARTH
                val gY = y / SensorManager.GRAVITY_EARTH
                val gZ = z / SensorManager.GRAVITY_EARTH
                val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

                deviceMoving = kotlin.math.abs(gForce - 1.0f) > 0.18f
                val nowFlat = !deviceMoving && kotlin.math.abs(z) > 9.0f
                val now = System.currentTimeMillis()

                // Postura de conversa / uso na mão (celular inclinado entre ~20° e 80°, fora do bolso)
                inConversationalPosture = !nowFlat && !proximityNear && y in 2.0f..9.3f

                // Rotina de Sensor Background: Detecta quando o usuário pega o celular da mesa (Pick-Up Gesture)
                if (wasFlatOnTable && !nowFlat && (deviceMoving || y > 2.5f) && !proximityNear) {
                    if (now - lastPickupMs > 12_000L) {
                        lastPickupMs = now
                        BubbleService.instance?.onPhonePickedUp()
                    }
                }
                wasFlatOnTable = nowFlat
                deviceFlatOnTable = nowFlat

                // Shake gesture intencional: exige 2 picos fortes (> 2.95G) em até 800ms
                // Funciona TANTO ocioso (abre escuta) QUANTO pensando (ativa /btw em paralelo!)
                if (gForce > 2.95f && !proximityNear) {
                    if (now - firstShakePeakMs > 800L) {
                        firstShakePeakMs = now
                        shakePeakCount = 1
                    } else if (now - firstShakePeakMs >= 95L) {
                        shakePeakCount++
                    }
                    val cooldown = if (BubbleService.instance?.isJobActive() == true) 2500L else 4200L
                    if (shakePeakCount >= 2 && now - lastShakeMs > cooldown) {
                        lastShakeMs = now
                        shakePeakCount = 0
                        BubbleService.instance?.onShakeDetected()
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- Gerenciamento de Vigias (Standing Watches) ----

    @Synchronized
    fun addWatch(source: String, filter: String, instruction: String, autoOpen: Boolean = false): String {
        val id = "w_${System.currentTimeMillis() % 100000}"
        val rule = WatchRule(id, source.lowercase().trim(), filter.trim(), instruction.trim(), autoOpen)
        watches.removeAll { it.filter.equals(filter, ignoreCase = true) && it.source == rule.source }
        watches.add(rule)
        saveWatches()
        return "Vigia ativa criada [$id]: monitorando '$filter' em '$source' -> $instruction"
    }

    @Synchronized
    fun removeWatch(idOrFilter: String): String {
        val removed = watches.removeAll {
            it.id.equals(idOrFilter, ignoreCase = true) || it.filter.contains(idOrFilter, ignoreCase = true)
        }
        saveWatches()
        return if (removed) "Vigia '$idOrFilter' removida." else "Nenhuma vigia encontrada para '$idOrFilter'."
    }

    @Synchronized
    fun listWatches(): String {
        if (watches.isEmpty()) return "Nenhuma vigia ativa no momento."
        return watches.joinToString("\n") {
            "• [${it.id}] fonte=${it.source} | filtro='${it.filter}' | ação='${it.instruction}' (autoOpen=${it.autoOpen})"
        }
    }

    @Synchronized
    fun watchesToJsonArray(): JSONArray {
        val arr = JSONArray()
        for (w in watches) {
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("source", w.source)
                    .put("filter", w.filter)
                    .put("instruction", w.instruction)
                    .put("autoOpen", w.autoOpen)
            )
        }
        return arr
    }

    fun toSensorConfigJson(): JSONObject {
        val sensorsList = JSONArray()
            .put(JSONObject().put("key", "accelerometer").put("label", "Acelerômetro").put("enabled", enableAccel).put("critical", true).put("purpose", "Shake /btw e pegar o celular da mesa"))
            .put(JSONObject().put("key", "proximity").put("label", "Proximidade").put("enabled", enableProximity).put("critical", true).put("purpose", "Detectar bolso/rosto e evitar toque acidental"))
            .put(JSONObject().put("key", "light").put("label", "Luz Ambiente").put("enabled", enableLight).put("critical", false).put("purpose", "Auto-silêncio em ambiente escuro"))
            .put(JSONObject().put("key", "gyroscope").put("label", "Giroscópio").put("enabled", enableGyro).put("critical", false).put("purpose", "Rotação fina / movimento"))
            .put(JSONObject().put("key", "steps").put("label", "Contador de Passos").put("enabled", enableSteps).put("critical", false).put("purpose", "Atividade física / caminhada"))

        return JSONObject()
            .put("mode", sensorMode)
            .put("light", enableLight)
            .put("accelerometer", enableAccel)
            .put("proximity", enableProximity)
            .put("gyroscope", enableGyro)
            .put("steps", enableSteps)
            .put("auto_silent", autoSilentInDarkOrPocket)
            .put("sensors_list", sensorsList)
            .put("ambient_lux", ambientLux)
            .put("device_moving", deviceMoving)
            .put("device_flat_on_table", deviceFlatOnTable)
            .put("proximity_near", proximityNear)
            .put("gyro_magnitude", gyroMagnitude)
            .put("step_count", stepCount)
            .put("in_conversational_posture", inConversationalPosture)
            .put("is_recording_audio", isRecordingAudio)
    }

    /**
     * Chamado pelo NotifListener sempre que chega uma notificação nova no celular.
     */
    fun onNewNotification(pkg: String, title: String, text: String) {
        // Observação passiva: SÓ metadado (app + remetente), nunca o corpo da mensagem.
        runCatching { InitiativeEngine.observarNotificacao(pkg, title) }
        val full = "$pkg $title $text".lowercase(Locale("pt", "BR"))
        val matchedRule = synchronized(this) {
            watches.firstOrNull { rule ->
                val srcMatch = when (rule.source) {
                    "whatsapp", "zap" -> pkg.contains("whatsapp")
                    "email", "gmail", "outlook" -> pkg.contains("gm") || pkg.contains("outlook") || pkg.contains("mail")
                    "claude", "codex", "ia" -> full.contains("claude") || full.contains("codex") || pkg.contains("anthropic") || pkg.contains("termux")
                    else -> true
                }
                val filterMatch = rule.filter.isEmpty() || full.contains(rule.filter.lowercase(Locale("pt", "BR")))
                srcMatch && filterMatch
            }
        }

        val now = System.currentTimeMillis()
        if (matchedRule != null && (now - lastProactiveAlertMs > 8000L)) {
            lastProactiveAlertMs = now
            BubbleService.instance?.triggerProactiveWatchAlert(
                rule = matchedRule,
                notifPkg = pkg,
                notifTitle = title,
                notifText = text
            )
        }
    }

    /**
     * Chamado pelo BroadcastReceiver de ACTION_USER_PRESENT sempre que o celular é desbloqueado.
     * Dispara vigias configuradas para desbloqueio (`source = "unlock"|"device"`) ou cuja instrução
     * mencione explicitamente "desbloque".
     */
    fun onDeviceUnlocked() {
        val rule = synchronized(this) {
            val matched = watches.filter { r ->
                val src = r.source.lowercase(Locale("pt", "BR"))
                val inst = r.instruction.lowercase(Locale("pt", "BR"))
                src in listOf("unlock", "device", "desbloqueio", "sistema") || inst.contains("desbloque")
            }
            if (matched.isEmpty()) return
            val now = System.currentTimeMillis()
            if (now - lastProactiveAlertMs < 60_000L) return
            lastProactiveAlertMs = now

            val chosen = matched.first()
            val instLower = chosen.instruction.lowercase(Locale("pt", "BR"))
            val isRecurring = instLower.contains("sempre") ||
                instLower.contains("toda vez") ||
                instLower.contains("todo dia") ||
                instLower.contains("diariamente")
            if (!isRecurring) {
                // Vigias pontuais ("ao desbloquear, envie X") disparam uma única vez e são removidas
                watches.removeAll { it.id == chosen.id }
                saveWatches()
            }
            chosen
        }
        BubbleService.instance?.triggerProactiveWatchAlert(
            rule = rule,
            notifPkg = "android.intent.action.USER_PRESENT",
            notifTitle = "Celular desbloqueado",
            notifText = "O usuário acabou de desbloquear o celular."
        )
    }

    // ---- Gravação de Áudio Proativa / Combinada ----

    fun startAudioRecording(ctx: Context, reason: String = "conversa"): String {
        if (isRecordingAudio) {
            return "Já estou gravando áudio em: $currentRecordingFile"
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "erro: permissão RECORD_AUDIO não concedida no Android"
        }
        return try {
            val dir = File(ctx.filesDir, "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "rec_${stamp}.m4a")

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            isRecordingAudio = true
            currentRecordingFile = outFile.absolutePath
            BubbleService.instance?.updateRecordingIndicator(true)
            BubbleService.instance?.showSpeechBubble("🔴 Gravando áudio: $reason", 4500L)
            "Gravação iniciada com indicador ativo em ${outFile.name} (motivo: $reason)"
        } catch (e: Throwable) {
            isRecordingAudio = false
            BubbleService.instance?.updateRecordingIndicator(false)
            "erro: falha ao iniciar gravação em segundo plano: ${e.message}"
        }
    }

    fun stopAudioRecording(): String {
        val mr = recorder ?: return "Nenhuma gravação ativa."
        return try {
            mr.stop()
            mr.release()
            val path = currentRecordingFile
            recorder = null
            isRecordingAudio = false
            currentRecordingFile = null
            BubbleService.instance?.updateRecordingIndicator(false)
            BubbleService.instance?.showSpeechBubble("⏹️ Áudio salvo com sucesso!", 3200L)
            "Gravação finalizada e salva em: $path"
        } catch (e: Throwable) {
            recorder = null
            isRecordingAudio = false
            BubbleService.instance?.updateRecordingIndicator(false)
            "erro: falha ao finalizar gravação: ${e.message}"
        }
    }

    // ---- Pesquisa Rápida na Web para Embasar Sugestões ----

    fun quickWebSearch(query: String): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                connectTimeout = 6000
                readTimeout = 6000
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html)
                .map {
                    it.groupValues[1]
                        .replace(Regex("<[^>]+>"), "")
                        .replace("&quot;", "\"")
                        .replace("&amp;", "&")
                        .replace("&#x27;", "'")
                        .trim()
                }
                .filter { it.isNotEmpty() }
                .take(4)
                .toList()

            if (snippets.isNotEmpty()) {
                "Resultados da pesquisa para '$query':\n" + snippets.joinToString("\n- ", prefix = "- ")
            } else {
                "Sem resultados diretos para '$query'."
            }
        } catch (e: Throwable) {
            "Falha na pesquisa web: ${e.message}"
        }
    }

    fun getSensorSummary(): String {
        val luxDesc = when {
            ambientLux < 0 -> "desconhecido"
            ambientLux < 15 -> "ambiente escuro (${ambientLux.toInt()} lux)"
            ambientLux < 250 -> "ambiente interno (${ambientLux.toInt()} lux)"
            else -> "ambiente claro/externo (${ambientLux.toInt()} lux)"
        }
        val mov = when {
            deviceMoving -> "em movimento/na mão"
            deviceFlatOnTable -> "parado/plano sobre mesa"
            else -> "parado/estável"
        }
        val prox = if (proximityNear) "coberto/no bolso/próximo" else "livre"
        val gyro = String.format(Locale.US, "%.2f rad/s", gyroMagnitude)
        val mag = if (magneticUt >= 0f) String.format(Locale.US, "%.1f µT", magneticUt) else "n/d"
        val pres = if (pressureHpa >= 0f) String.format(Locale.US, "%.1f hPa", pressureHpa) else "n/d"
        val compass = if (azimuthDeg >= 0) "${azimuthDeg}°" else "n/d"
        val rec = if (isRecordingAudio) "GRAVANDO (${currentRecordingFile?.substringAfterLast('/')})" else "inativo"
        return "ModoSensores: $sensorMode | Luz: $luxDesc | Movimento: $mov | Proximidade: $prox | Giroscópio: $gyro | Bússola: $compass | CampoMag: $mag | Pressão: $pres | Passos: $stepCount | SensoresHW: $availableSensorsCount | Gravador: $rec | Vigias ativas: ${watches.size}"
    }

    private fun saveWatches() {
        val f = watchesFile ?: return
        runCatching {
            val arr = JSONArray()
            for (w in watches) {
                arr.put(
                    JSONObject()
                        .put("id", w.id)
                        .put("source", w.source)
                        .put("filter", w.filter)
                        .put("instruction", w.instruction)
                        .put("autoOpen", w.autoOpen)
                )
            }
            f.writeText(arr.toString())
        }
    }

    private fun loadWatches() {
        val f = watchesFile ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            watches.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                watches.add(
                    WatchRule(
                        id = o.optString("id"),
                        source = o.optString("source"),
                        filter = o.optString("filter"),
                        instruction = o.optString("instruction"),
                        autoOpen = o.optBoolean("autoOpen", false)
                    )
                )
            }
        }
    }
}
