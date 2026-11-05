package com.example.callrecorder.data

/**
 * The kinds of recording the app makes.
 *
 * Calls were the only thing the database knew about, so anything that is not a phone call had nowhere
 * to live - which is why a meeting or podcast could never show up in the list. Voice recordings are
 * stored in the same table (and therefore use the same player, delete and Drive upload paths) with one
 * of the ids below in `callType`.
 */
object VoiceCategory {

    const val MEETING = "MEETING"
    const val PODCAST = "PODCAST"
    const val NOTE = "VOICE_NOTE"

    val all = listOf(MEETING, PODCAST, NOTE)

    /** True when the record was captured by the in-app recorder rather than by the call service. */
    fun isVoice(callType: String): Boolean = callType in all

    fun label(callType: String): String = when (callType) {
        MEETING -> "Meeting"
        PODCAST -> "Podcast"
        NOTE -> "Voice note"
        else -> "Recording"
    }

    /** Prefix used in the file name so recordings stay identifiable outside the app. */
    fun filePrefix(callType: String): String = when (callType) {
        MEETING -> "MEETING"
        PODCAST -> "PODCAST"
        NOTE -> "VOICE_NOTE"
        else -> "VOICE"
    }
}
