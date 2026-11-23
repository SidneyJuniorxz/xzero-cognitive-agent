package com.xzero.agent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Modo Demo / X-Zero Studio (Android 14):
 * Grava em um único .mp4 na Galeria (Movies/XZeroDemo):
 *  1. Tela inteira do aparelho (com Xenon flutuante e apps em execução)
 *  2. Janela PiP da câmera com alternância rápida Frontal (você falando) <-> Traseira (TV/ambiente)
 *  3. Áudio duplo sem conflito de microfone:
 *     - Sua voz via hook PCM direto do VoiceActivityDetector
 *     - Voz do X-Zero (TTS) via AudioPlaybackCaptureConfiguration
 */
class DemoRecorderService : Service() {

    companion object {
        private const val TAG = "DemoRecorder"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "xzero_demo_studio"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_FRONT_CAM = "front_cam"

        @Volatile
        var instance: DemoRecorderService? = null
            private set

        @Volatile
        var isRecording: Boolean = false
            private set

        fun start(ctx: Context, resultCode: Int, data: Intent, startFrontCam: Boolean = true) {
            val i = Intent(ctx, DemoRecorderService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_FRONT_CAM, startFrontCam)
            }
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DemoRecorderService::class.java))
        }

        fun switchCamera(front: Boolean? = null): String {
            val svc = instance ?: return "gravação demo não está ativa"
            val target = front ?: !svc.useFrontCamera
            svc.mainHandler.post { svc.setCameraFacing(target) }
            return if (target) "câmera demo alternada para frontal" else "câmera demo alternada para traseira"
        }

        fun pauseCameraForAgent() {
            instance?.pauseCameraInternal()
        }

        fun resumeCameraAfterAgent() {
            instance?.resumeCameraInternal()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var pipContainer: LinearLayout? = null
    private var textureView: TextureView? = null
    private var timerBadge: TextView? = null
    private var switchBtn: TextView? = null

    @Volatile private var useFrontCamera = true
    @Volatile private var cameraPausedForAgent = false
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var tempFile: File? = null

    @Volatile private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val muxerLock = Any()

    @Volatile private var encodingRunning = false
    private var startedAtMs = 0L
    private val micPcmQueue = ArrayBlockingQueue<ShortArray>(64)
    private var playbackRecord: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRecording) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        useFrontCamera = intent?.getBooleanExtra(EXTRA_FRONT_CAM, true) ?: true

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startDemoForeground()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, resultData)
        if (proj == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSelf() }
            }
        }, mainHandler)

        isRecording = true
        startedAtMs = System.currentTimeMillis()
        buildPipOverlay()
        startRecordingPipeline(proj)
        scheduleTimerTick()
        Toast.makeText(this, "🎬 Gravação Demo iniciada!", Toast.LENGTH_SHORT).show()
        return START_NOT_STICKY
    }

    private fun startDemoForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "X-Zero Studio Demo", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("🎬 X-Zero Studio • Gravando Demo")
            .setContentText("Tela + Câmera PiP + Sua voz + Voz do X-Zero")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        val hasCam = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        var types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        if (hasCam && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, types)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPipOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0F172A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
        }

        val tv = TextureView(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            clipToOutline = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    openCameraOnTexture(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    closeCameraInternal()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        textureView = tv
        root.addView(tv, LinearLayout.LayoutParams(dp(128), dp(170)))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }

        val recTv = TextView(this).apply {
            text = "🔴 00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(6), 0)
        }
        timerBadge = recTv

        val swTv = TextView(this).apply {
            text = if (useFrontCamera) "📷 Traseira" else "🤳 Frontal"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { setCameraFacing(!useFrontCamera) }
        }
        switchBtn = swTv

        val stopTv = TextView(this).apply {
            text = "⏹️"
            textSize = 13f
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { stopSelf() }
        }

        bar.addView(recTv)
        bar.addView(swTv)
        bar.addView(stopTv)
        root.addView(bar)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(90)
        }

        var downX = 0f
        var downY = 0f
        var initX = 0
        var initY = 0
        root.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    initX = lp.x; initY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initX - (ev.rawX - downX).toInt()
                    lp.y = initY + (ev.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(root, lp) }
                    true
                }
                else -> false
            }
        }

        pipContainer = root
        wm.addView(root, lp)
    }

    private fun scheduleTimerTick() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isRecording) return
                val sec = ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt()
                timerBadge?.text = String.format(Locale.US, "🔴 %02d:%02d", sec / 60, sec % 60)
                mainHandler.postDelayed(this, 1000L)
            }
        }, 1000L)
    }

    fun setCameraFacing(front: Boolean) {
        if (useFrontCamera == front && cameraDevice != null) return
        useFrontCamera = front
        switchBtn?.text = if (front) "📷 Traseira" else "🤳 Frontal"
        closeCameraInternal()
        val st = textureView?.surfaceTexture
        if (st != null && !cameraPausedForAgent) {
            openCameraOnTexture(st)
        }
    }

    private fun pauseCameraInternal() {
        cameraPausedForAgent = true
        mainHandler.post { closeCameraInternal() }
    }

    private fun resumeCameraInternal() {
        cameraPausedForAgent = false
        mainHandler.post {
            val st = textureView?.surfaceTexture
            if (st != null && isRecording) openCameraOnTexture(st)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraOnTexture(st: SurfaceTexture) {
        if (cameraPausedForAgent) return
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: cm.cameraIdList.firstOrNull() ?: return

        if (cameraThread == null) {
            cameraThread = HandlerThread("DemoPipCamera").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }

        st.setDefaultBufferSize(640, 480)
        val previewSurface = Surface(st)

        runCatching {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    runCatching {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                runCatching { session.setRepeatingRequest(req.build(), null, cameraHandler) }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        }, cameraHandler)
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
            }, cameraHandler)
        }
    }

    private fun closeCameraInternal() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
    }

    @SuppressLint("MissingPermission")
    private fun startRecordingPipeline(projection: MediaProjection) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val rawW = (dm.widthPixels / 2).coerceAtLeast(540)
        val rawH = (dm.heightPixels / 2).coerceAtLeast(960)
        val width = (rawW / 16) * 16
        val height = (rawH / 16) * 16
        val density = dm.densityDpi

        val dir = File(cacheDir, "demo_rec").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "xzero_demo_$stamp.mp4")
        tempFile = outFile

        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = vCodec.createInputSurface()
        vCodec.start()
        videoEncoder = vCodec

        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aCodec.start()
        audioEncoder = aCodec

        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1

        virtualDisplay = projection.createVirtualDisplay(
            "XZeroDemoDisplay", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        // Hook direto no PCM do VoiceActivityDetector para capturar a voz do usuário sem disputar o microfone
        VoiceActivityDetector.onPcmFrameListener = { samples, len ->
            if (encodingRunning && len > 0) {
                micPcmQueue.offer(samples.copyOf(len))
            }
        }

        // Captura áudio interno (TTS do X-Zero) via AudioPlaybackCapture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                playbackRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(16_000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 4096))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build().apply { startRecording() }
            }.onFailure {
                Log.w(TAG, "AudioPlaybackCapture indisponível: ${it.message}")
            }
        }

        encodingRunning = true
        val startNs = System.nanoTime()

        // Thread de Vídeo
        thread(name = "DemoVideoEnc", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            while (encodingRunning) {
                val idx = runCatching { vCodec.dequeueOutputBuffer(info, 15_000) }.getOrElse { -1 }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer!!.addTrack(vCodec.outputFormat)
                        if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !muxerStarted) {
                            muxer!!.start()
                            muxerStarted = true
                        }
                    }
                } else if (idx >= 0) {
                    val buf = vCodec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        synchronized(muxerLock) {
                            if (muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(videoTrackIndex, buf, info)
                            }
                        }
                    }
                    vCodec.releaseOutputBuffer(idx, false)
                }
            }
        }

        // Thread de Áudio (Mixer: Voz do usuário via VAD + TTS do X-Zero via AudioPlaybackCapture)
        // Sempre alimenta frames (inclusive silêncio quando não houver áudio) para garantir que
        // audioTrackIndex seja adicionado ao MediaMuxer imediatamente no início da gravação.
        thread(name = "DemoAudioEnc", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            val frameSamples = 1024
            val internalBuf = ShortArray(frameSamples)
            val mixedBytes = ByteArray(frameSamples * 2)
            var currentMicChunk: ShortArray? = null
            var micOffset = 0
            var fallbackMic: AudioRecord? = null
            var lastVadFrameMs = System.currentTimeMillis()

            while (encodingRunning) {
                val pbCount = playbackRecord?.read(internalBuf, 0, frameSamples, AudioRecord.READ_NON_BLOCKING) ?: 0
                if (currentMicChunk == null || micOffset >= currentMicChunk.size) {
                    currentMicChunk = micPcmQueue.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                    micOffset = 0
                    if (currentMicChunk != null) {
                        lastVadFrameMs = System.currentTimeMillis()
                        if (fallbackMic != null) {
                            runCatching { fallbackMic?.stop(); fallbackMic?.release() }
                            fallbackMic = null
                        }
                    }
                }

                // Se o Modo Live/VAD estiver desligado (>400ms sem frames do VAD), abre fallback MIC leve
                var fallbackCount = 0
                val fallbackBuf = if (currentMicChunk == null && System.currentTimeMillis() - lastVadFrameMs > 400L) {
                    if (fallbackMic == null && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        runCatching {
                            val mb = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                            fallbackMic = AudioRecord(
                                android.media.MediaRecorder.AudioSource.MIC,
                                16_000,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(mb, 4096)
                            ).apply { startRecording() }
                        }
                    }
                    val fb = ShortArray(frameSamples)
                    fallbackCount = fallbackMic?.read(fb, 0, frameSamples, AudioRecord.READ_NON_BLOCKING) ?: 0
                    fb
                } else null

                for (i in 0 until frameSamples) {
                    val internalSample = if (i < pbCount) internalBuf[i].toInt() else 0
                    val micSample = when {
                        currentMicChunk != null && micOffset < currentMicChunk.size -> currentMicChunk[micOffset++].toInt()
                        fallbackBuf != null && i < fallbackCount -> fallbackBuf[i].toInt()
                        else -> 0
                    }
                    val sum = (internalSample + micSample).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    mixedBytes[i * 2] = (sum and 0xFF).toByte()
                    mixedBytes[i * 2 + 1] = ((sum ushr 8) and 0xFF).toByte()
                }

                val inIdx = runCatching { aCodec.dequeueInputBuffer(10_000) }.getOrElse { -1 }
                if (inIdx >= 0) {
                    val inBuf = aCodec.getInputBuffer(inIdx)
                    inBuf?.clear()
                    inBuf?.put(mixedBytes)
                    val ptsUs = (System.nanoTime() - startNs) / 1000L
                    aCodec.queueInputBuffer(inIdx, 0, mixedBytes.size, ptsUs, 0)
                }

                while (true) {
                    val outIdx = runCatching { aCodec.dequeueOutputBuffer(info, 0) }.getOrElse { -1 }
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(muxerLock) {
                            audioTrackIndex = muxer!!.addTrack(aCodec.outputFormat)
                            if (videoTrackIndex >= 0 && audioTrackIndex >= 0 && !muxerStarted) {
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                    } else if (outIdx >= 0) {
                        val outBuf = aCodec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            synchronized(muxerLock) {
                                if (muxerStarted) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(audioTrackIndex, outBuf, info)
                                }
                            }
                        }
                        aCodec.releaseOutputBuffer(outIdx, false)
                    } else {
                        break
                    }
                }
            }
            runCatching { fallbackMic?.stop(); fallbackMic?.release() }
        }
    }

    private fun saveToGallery(src: File): Uri? {
        if (!src.exists() || src.length() < 1024) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/XZeroDemo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    override fun onDestroy() {
        isRecording = false
        runCatching { videoEncoder?.signalEndOfInputStream() }
        runCatching { Thread.sleep(150) }
        encodingRunning = false
        VoiceActivityDetector.onPcmFrameListener = null
        instance = null

        closeCameraInternal()
        runCatching { cameraThread?.quitSafely() }
        pipContainer?.let { runCatching { wm.removeView(it) } }
        pipContainer = null

        runCatching { playbackRecord?.stop() }
        runCatching { playbackRecord?.release() }
        runCatching { virtualDisplay?.release() }
        runCatching { mediaProjection?.stop() }
        runCatching { videoEncoder?.stop(); videoEncoder?.release() }
        runCatching { audioEncoder?.stop(); audioEncoder?.release() }
        synchronized(muxerLock) {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxerStarted = false
        }

        val savedUri = tempFile?.let { runCatching { saveToGallery(it) }.getOrNull() }
        runCatching { tempFile?.delete() }
        if (savedUri != null) {
            mainHandler.post {
                Toast.makeText(applicationContext, "✅ Demo salva em Movies/XZeroDemo!", Toast.LENGTH_LONG).show()
            }
        }
        super.onDestroy()
    }
}
