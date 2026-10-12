/* Derived from BluMirror by BluDood (MIT License). */
package org.dashdeck.app.mirror

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.dashdeck.app.MainActivity
import org.dashdeck.app.R

class DashDeckProjectionService : Service() {
    private var projection: android.media.projection.MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "DashDeck Mirror", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("DashDeck Mirror is active")
            .setContentText("Tap Stop in DashDeck or Android Auto to end it")
            .setContentIntent(openApp).setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        if (intent == null) {
            Log.w(TAG, "Projection service restarted without a fresh capture grant")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(RESULT, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Projection service started without an accepted capture grant")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        projection?.registerCallback(object : android.media.projection.MediaProjection.Callback() {
            override fun onCapturedContentResize(width: Int, height: Int) {
                DashDeckMirrorState.setCapturedContentSize(width, height)
            }

            override fun onStop() {
                Log.i(TAG, "Android stopped the screen capture session")
                DashDeckMirrorState.stop()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection?.let { DashDeckMirrorState.setProjection(it, this) } ?: stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DashDeckMirrorState.stop()
        projection = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DashDeckMirror"
        private const val CHANNEL = "dashdeck_mirror"
        private const val NOTIFICATION = 77
        private const val RESULT = "result"
        private const val DATA = "data"
        fun start(context: android.content.Context, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, DashDeckProjectionService::class.java).putExtra(RESULT, resultCode).putExtra(DATA, data))
        }
    }
}
