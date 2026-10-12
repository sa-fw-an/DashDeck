package org.dashdeck.app

import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
import android.view.Display
import org.dashdeck.app.mirror.DashDeckProjectionPermissionActivity
/** Launches DashDeck's built-in Android Auto mirror flow. */
object MirrorLauncher {
    fun launch(context: Context) {
        val intent = Intent(context, DashDeckProjectionPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
        context.startActivity(intent, options.toBundle())
    }
}
