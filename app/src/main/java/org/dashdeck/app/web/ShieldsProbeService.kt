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

package org.dashdeck.app.web

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/**
 * Loads a candidate engine plugin in a separate, disposable process
 * (`:shieldsprobe`) and runs the self-test suite there. A plugin that crashes
 * only takes this process down; the browser keeps running.
 */
class ShieldsProbeService : Service() {
    private lateinit var thread: HandlerThread

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ShieldsProbe").apply { start() }
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(object : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_PROBE) return
            val path = msg.data.getString(EXTRA_PATH).orEmpty()
            val result = runCatching { BraveAdblockNative.probe(applicationContext, path) }
                .getOrElse { failure(it.message ?: it.javaClass.simpleName) }
            runCatching {
                msg.replyTo.send(Message.obtain(null, MSG_RESULT).apply {
                    data = Bundle().apply { putString(EXTRA_RESULT, result) }
                })
            }
            // Never keep a probed library mapped; this process exists for one test.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 200)
        }
    }).binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val MSG_PROBE = 1
        private const val MSG_RESULT = 2
        private const val EXTRA_PATH = "path"
        private const val EXTRA_RESULT = "result"
        private const val TIMEOUT_SECONDS = 90L

        internal fun failure(reason: String): String =
            JSONObject().put("passed", false).put("error", reason).put("tests", org.json.JSONArray()).toString()

        /** Runs the probe and waits for its report. Blocks; call off the main thread. */
        fun probe(context: Context, pluginPath: String): String {
            val app = context.applicationContext
            val result = AtomicReference<String?>(null)
            val done = CountDownLatch(1)
            val replies = HandlerThread("ShieldsProbeReply").apply { start() }
            val replyTo = Messenger(object : Handler(replies.looper) {
                override fun handleMessage(msg: Message) {
                    if (msg.what == MSG_RESULT) {
                        result.compareAndSet(null, msg.data.getString(EXTRA_RESULT))
                        done.countDown()
                    }
                }
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                    runCatching {
                        service.linkToDeath({
                            result.compareAndSet(null, failure("The engine crashed during testing."))
                            done.countDown()
                        }, 0)
                        Messenger(service).send(Message.obtain(null, MSG_PROBE).apply {
                            data = Bundle().apply { putString(EXTRA_PATH, pluginPath) }
                            this.replyTo = replyTo
                        })
                    }.onFailure {
                        result.compareAndSet(null, failure("Could not start the engine test."))
                        done.countDown()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    result.compareAndSet(null, failure("The engine crashed during testing."))
                    done.countDown()
                }
            }
            val bound = app.bindService(Intent(app, ShieldsProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                replies.quitSafely()
                return failure("Could not start the engine test.")
            }
            val finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            runCatching { app.unbindService(connection) }
            replies.quitSafely()
            return if (finished) result.get() ?: failure("The engine test returned no result.")
            else failure("The engine test timed out.")
        }
    }
}
