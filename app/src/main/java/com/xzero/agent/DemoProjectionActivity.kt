package com.xzero.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Activity transparente usada apenas para obter a permissão de captura de tela (MediaProjection)
 * no Android 14 e repassar imediatamente ao DemoRecorderService.
 */
class DemoProjectionActivity : Activity() {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 4041
        const val EXTRA_START_FRONT_CAM = "start_front_cam"

        fun launch(ctx: Context, startFrontCam: Boolean = true) {
            val i = Intent(ctx, DemoProjectionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_START_FRONT_CAM, startFrontCam)
            }
            ctx.startActivity(i)
        }
    }

    private var startFrontCam = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startFrontCam = intent?.getBooleanExtra(EXTRA_START_FRONT_CAM, true) ?: true
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            DemoRecorderService.start(this, resultCode, data, startFrontCam)
        }
        finish()
    }
}
