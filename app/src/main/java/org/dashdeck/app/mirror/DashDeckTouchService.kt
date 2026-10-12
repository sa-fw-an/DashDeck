/*
 * Copyright (C) 2026 Safwan Sayeed (https://github.com/sa-fw-an/DashDeck)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package org.dashdeck.app.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, user-enabled bridge that replays touches made on the mirrored car
 * display as live gestures on the phone. Android only allows this through an
 * accessibility service the user turns on.
 *
 * Touches are streamed: the phone's pointer goes down as soon as the car is
 * touched and follows the finger in short chained segments
 * ([GestureDescription.StrokeDescription.continueStroke]), so drags, scrolls
 * and long presses track the finger instead of replaying after release.
 */
class DashDeckTouchService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        Log.i(TAG, "Car touch control enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        session.reset()
        super.onDestroy()
    }

    data class TouchPoint(val x: Float, val y: Float, val timeMs: Long)

    /** One finger on the car screen, mirrored as one continued stroke on the phone. */
    private inner class StrokeSession {
        private var current: GestureDescription.StrokeDescription? = null
        private var last: TouchPoint? = null
        private val pending = ArrayList<TouchPoint>()
        private var inFlight = false
        private var lifted = false

        fun down(p: TouchPoint) {
            reset()
            last = p
            val path = Path().apply { moveTo(p.x, p.y) }
            send(GestureDescription.StrokeDescription(path, 0, 1, true))
        }

        fun move(p: TouchPoint) {
            if (last == null) return
            pending += p
            if (!inFlight) flush()
        }

        fun up(p: TouchPoint?) {
            if (last == null) return
            p?.let { pending += it }
            lifted = true
            if (!inFlight) flush()
        }

        fun reset() {
            current = null
            last = null
            pending.clear()
            inFlight = false
            lifted = false
        }

        private fun flush() {
            val from = last ?: return
            val previous = current ?: return
            if (pending.isEmpty() && !lifted) return
            val to = pending.lastOrNull() ?: from
            val path = Path().apply {
                moveTo(from.x, from.y)
                if (pending.isEmpty()) lineTo(from.x, from.y) else pending.forEach { lineTo(it.x, it.y) }
            }
            val duration = (to.timeMs - from.timeMs).coerceIn(1L, MAX_SEGMENT_MS)
            pending.clear()
            last = to
            val finishing = lifted
            send(previous.continueStroke(path, 0, duration, !finishing))
            if (finishing) {
                current = null
                last = null
                lifted = false
            }
        }

        private fun send(stroke: GestureDescription.StrokeDescription) {
            current = stroke
            inFlight = true
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                    flush()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Phone cancelled the car gesture")
                    reset()
                }
            }, main)
            if (!accepted) {
                Log.w(TAG, "Phone rejected the car gesture")
                reset()
            }
        }
    }

    private val session = StrokeSession()

    companion object {
        private const val TAG = "DashDeckMirror"
        private const val MAX_SEGMENT_MS = 250L
        @Volatile private var active: DashDeckTouchService? = null
        private val main = Handler(Looper.getMainLooper())

        fun isEnabled(): Boolean = active != null

        /** True when the user has turned the service on in system settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.endsWith(DashDeckTouchService::class.java.name) }
        }

        // All calls arrive on the main thread from the car surface's touch listener.
        fun down(p: TouchPoint) {
            active?.session?.down(p) ?: Log.i(TAG, "Car touch ignored; touch control is not enabled")
        }

        fun move(p: TouchPoint) { active?.session?.move(p) }
        fun up(p: TouchPoint?) { active?.session?.up(p) }
        fun cancel() { active?.session?.up(null) }
    }
}
