package com.example.callrecorder.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Database Entity representing an automatic call recording.
 * Specification compliant with API 34/35.
 */
@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val contactName: String,
    val phoneNumber: String,
    val callType: String, // "INCOMING", "OUTGOING", "MISSED"
    val timestamp: Long,
    val durationSeconds: Long,
    val filePath: String,
    val isSynced: Boolean = false,
    /** CALL for telephony recordings; VOICE for meetings, interviews, and other mic recordings. */
    @ColumnInfo(defaultValue = "'CALL'")
    val recordingType: String = "CALL"
)
