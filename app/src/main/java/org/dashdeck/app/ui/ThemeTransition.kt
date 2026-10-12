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

package org.dashdeck.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import kotlin.math.hypot

/**
 * Theme change with a circular reveal from the control that was tapped: the new
 * theme grows out of it when going dark, and the old one shrinks into it when
 * going light.
 *
 * Applying a theme recreates the activity, so the old screen is snapshotted
 * first and the reveal plays over the recreated one.
 */
object ThemeTransition {
    private const val DURATION_MS = 620L

    private class Pending(val snapshot: Bitmap, val x: Float, val y: Float, val expand: Boolean)

    private var pending: Pending? = null

    /** Snapshots [activity], then recreates it. [toDark] picks grow vs. shrink. */
    fun start(activity: Activity, origin: View?, toDark: Boolean) {
        val window = activity.window
        val decor = window.decorView
        if (!animationsEnabled(activity) || origin == null || decor.width == 0 || decor.height == 0) {
            activity.recreate()
            return
        }
        val location = IntArray(2).also { origin.getLocationInWindow(it) }
        val x = location[0] + origin.width / 2f
        val y = location[1] + origin.height / 2f
        val snapshot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        // PixelCopy captures what is actually on screen, including WebView content.
        PixelCopy.request(window, snapshot, { result ->
            if (result == PixelCopy.SUCCESS) {
                pending = Pending(snapshot, x, y, expand = toDark)
            } else {
                snapshot.recycle()
            }
            activity.recreate()
        }, Handler(Looper.getMainLooper()))
    }

    /** Call from onCreate after setContentView: plays a pending reveal, if any. */
    fun onCreated(activity: Activity) {
        val reveal = pending ?: return
        pending = null
        val decor = activity.window.decorView as ViewGroup
        val overlay = RevealOverlay(activity, reveal)
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.post { overlay.play { decor.removeView(overlay) } }
    }

    private fun animationsEnabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private class RevealOverlay(context: Context, private val reveal: Pending) : View(context) {
        private val circle = Path()

        // Start fully covering the new screen until the animator's first frame:
        // no hole when growing, a circle larger than the screen when shrinking.
        private var radius = if (reveal.expand) 0f else 100_000f

        init {
            isClickable = true // swallow touches until the reveal finishes
        }

        fun play(onEnd: () -> Unit) {
            val full = listOf(0f to 0f, width.toFloat() to 0f, 0f to height.toFloat(), width.toFloat() to height.toFloat())
                .maxOf { (cx, cy) -> hypot(cx - reveal.x, cy - reveal.y) }
            val (from, to) = if (reveal.expand) 0f to full else full to 0f
            radius = from
            ValueAnimator.ofFloat(from, to).apply {
                duration = DURATION_MS
                interpolator = PathInterpolator(0.33f, 0f, 0.2f, 1f)
                addUpdateListener {
                    radius = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        reveal.snapshot.recycle()
                        onEnd()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            circle.rewind()
            circle.addCircle(reveal.x, reveal.y, radius, Path.Direction.CW)
            canvas.save()
            // Expand: the old screen with a growing hole. Contract: the old screen inside a shrinking circle.
            if (reveal.expand) canvas.clipOutPath(circle) else canvas.clipPath(circle)
            canvas.drawBitmap(reveal.snapshot, 0f, 0f, null)
            canvas.restore()
        }
    }
}
