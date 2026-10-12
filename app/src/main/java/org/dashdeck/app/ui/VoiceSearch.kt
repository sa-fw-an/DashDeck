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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hands-free search from DashDeck's toolbar mic. In Android Auto the audio comes
 * from the car's microphone. (The car's own mic button always opens Assistant.)
 */
class VoiceSearch(
    private val context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (noMatch: Boolean) -> Unit,
) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null

    val isListening get() = recognizer != null

    val isAvailable get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
        onListeningChanged(true)
    }

    fun stop() {
        val active = recognizer ?: return
        recognizer = null
        active.cancel()
        active.destroy()
        onListeningChanged(false)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        stop()
        if (text.isNullOrBlank()) onError(true) else onResult(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let(onPartial)
    }

    override fun onError(error: Int) {
        stop()
        onError(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
