package com.example.callrecorder.cloud

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class GoogleDriveUploader(private val driveService: Drive) {

    /** Uploads recordings into a per-day folder, with sortable local date/time in each filename. */
    suspend fun uploadRecording(
        localFilePath: String,
        recordingTitle: String,
        phoneOrCategory: String,
        timestamp: Long,
        recordingType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val localFile = File(localFilePath)
            if (!localFile.exists()) return@withContext false

            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
            val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date(timestamp))
            val isVoice = recordingType.equals("VOICE", ignoreCase = true)
            val folderPrefix = if (isVoice) "Voice recordings" else "Phone call recordings"
            val folderId = getOrCreateFolder("${folderPrefix}_$date")

            val kind = if (isVoice) safeName(phoneOrCategory) else "Call"
            val title = safeName(recordingTitle)
            val fileMetadata = DriveFile().apply {
                name = "${kind}_${title}_${date}_$time.m4a"
                parents = Collections.singletonList(folderId)
                mimeType = "audio/mp4"
            }

            val uploadedFile = driveService.files()
                .create(fileMetadata, FileContent("audio/mp4", localFile))
                .setFields("id, name, size")
                .execute()

            uploadedFile.id != null
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun safeName(value: String): String = value
        .trim()
        .replace(Regex("[^A-Za-z0-9_-]+"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "Recording" }

    private fun getOrCreateFolder(folderName: String): String {
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        val existingFolder = result.files.firstOrNull()
        if (existingFolder != null) return existingFolder.id

        val folderMetadata = DriveFile().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }
        return driveService.files().create(folderMetadata)
            .setFields("id")
            .execute()
            .id
    }
}
