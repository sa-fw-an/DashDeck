/*
 * Copyright (c) 2026 BluDood (https://github.com/BluDood/BluMirror)
 * Copyright (C) 2026 Safwan Sayeed (https://github.com/sa-fw-an/DashDeck)
 *
 * Derived from BluMirror by BluDood (https://github.com/BluDood/BluMirror),
 * MIT License; see licenses/BluMirror-MIT.txt.
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
