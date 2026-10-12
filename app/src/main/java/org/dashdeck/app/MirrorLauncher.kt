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
