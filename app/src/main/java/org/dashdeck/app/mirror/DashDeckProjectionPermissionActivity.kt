/* Derived from BluMirror by BluDood (MIT License). */
package org.dashdeck.app.mirror

import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class DashDeckProjectionPermissionActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.takeIf { result.resultCode == RESULT_OK }?.let {
            Log.i("DashDeckMirror", "Phone approved screen capture")
            DashDeckProjectionService.start(this, result.resultCode, it)
        } ?: run {
            Log.i("DashDeckMirror", "Phone declined screen capture")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(
            MediaProjectionConfig.createConfigForDefaultDisplay()
        ))
    }
}
