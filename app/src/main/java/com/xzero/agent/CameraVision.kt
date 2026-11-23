package com.xzero.agent

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Módulo de visão computacional e captura de câmera em segundo plano.
 * Permite que o XZERO capture fotos da câmera frontal ou traseira para alimentar o LLM multimodal.
 */
object CameraVision {

    @SuppressLint("MissingPermission")
    suspend fun captureSnapshot(context: Context, facingBack: Boolean = true): Bitmap? {
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("CameraVision", "Permissão CAMERA não concedida")
            return null
        }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val wasDemoRec = DemoRecorderService.isRecording
        if (wasDemoRec) {
            DemoRecorderService.pauseCameraForAgent()
            kotlinx.coroutines.delay(250)
        }
        val targetFacing = if (facingBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT

        val cameraId = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == targetFacing
        } ?: cm.cameraIdList.firstOrNull() ?: return null

        val thread = HandlerThread("CameraSnapshotThread").apply { start() }
        val handler = Handler(thread.looper)

        val deferredBitmap = CompletableDeferred<Bitmap?>()
        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
        var openedCamera: CameraDevice? = null

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    deferredBitmap.complete(bitmap)
                } catch (e: Throwable) {
                    android.util.Log.w("CameraVision", "Erro decodificando frame: ${e.message}")
                    deferredBitmap.complete(null)
                } finally {
                    image.close()
                }
            }
        }, handler)

        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openedCamera = camera
                    try {
                        val surface = imageReader.surface
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(surface)
                            set(
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                        }
                        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    session.capture(captureBuilder.build(), null, handler)
                                } catch (e: Throwable) {
                                    android.util.Log.w("CameraVision", "Falha capture(): ${e.message}")
                                    deferredBitmap.complete(null)
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                android.util.Log.w("CameraVision", "onConfigureFailed na sessão de câmera")
                                deferredBitmap.complete(null)
                            }
                        }, handler)
                    } catch (e: Throwable) {
                        android.util.Log.w("CameraVision", "Erro em onOpened: ${e.message}")
                        deferredBitmap.complete(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (openedCamera === camera) openedCamera = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    android.util.Log.w("CameraVision", "CameraDevice onError=$error")
                    camera.close()
                    if (openedCamera === camera) openedCamera = null
                    deferredBitmap.complete(null)
                }
            }, handler)

            return withTimeoutOrNull(6000) { deferredBitmap.await() }
        } catch (e: Throwable) {
            android.util.Log.w("CameraVision", "openCamera falhou (background/permissão): ${e.message}")
            return null
        } finally {
            runCatching { openedCamera?.close() }
            runCatching { imageReader.close() }
            thread.quitSafely()
            if (wasDemoRec) {
                DemoRecorderService.resumeCameraAfterAgent()
            }
        }
    }

    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    fun probeConcurrentCameras(context: Context): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "{\"error\":\"CameraManager unavailable\"}"
        val cams = cm.cameraIdList.map { id ->
            val chars = cm.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_BACK -> "back"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "unknown"
            }
            "\"$id\":\"$facing\""
        }
        val concurrent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            cm.concurrentCameraIds.map { set -> "[${set.joinToString(",") { "\"$it\"" }}]" }
        } else {
            emptyList()
        }
        return "{\"cameras\":{${cams.joinToString(",")}},\"concurrentSets\":[${concurrent.joinToString(",")}]}"
    }
}
