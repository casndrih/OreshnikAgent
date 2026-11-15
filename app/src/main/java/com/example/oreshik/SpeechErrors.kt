package com.example.oreshik

import android.speech.SpeechRecognizer

/**
 * Human-readable explanations for [SpeechRecognizer] error codes, shared by the
 * in-chat mic (MainActivity), the Voice screen, and wake-word logging.
 *
 * The classic constants (1-9) exist since API 8 and are referenced by name. The
 * newer codes (10+) are referenced by literal with a comment because they were
 * added in API 31+ while this app's minSdk is 26.
 */
object SpeechErrors {

    fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout. Check your internet connection."
        SpeechRecognizer.ERROR_NETWORK -> "Network error. Check your internet connection."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error. Another app may be using the mic."
        SpeechRecognizer.ERROR_SERVER -> "Speech server error. Check your internet connection and try again."
        SpeechRecognizer.ERROR_CLIENT -> "Speech service glitch. Try again in a moment."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap the mic and speak."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try speaking a bit slower."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again in a moment."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        13 -> "Speech service is rate-limiting requests. Wait a moment and try again." // ERROR_TOO_MANY_REQUESTS (API 31)
        14 -> "Speech service disconnected. Try again in a moment."                    // ERROR_SERVER_DISCONNECTED (API 32)
        else -> "Speech error $error"
    }
}
