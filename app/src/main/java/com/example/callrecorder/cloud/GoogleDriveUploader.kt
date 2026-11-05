package com.example.callrecorder.cloud

import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class GoogleDriveUploader(private val driveService: Drive) {

    suspend fun uploadCallAudio(localFilePath: String, phoneNumber: String, timestamp: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val localFile = File(localFilePath)
                if (!localFile.exists()) return@withContext false

                // 1. Get or create target folder in Google Drive
                val folderId = getOrCreateFolder("CallRecordings")

                // 2. Prepare metadata
                val fileMetadata = DriveFile().apply {
                    name = "Recording_${phoneNumber}_${timestamp}.m4a"
                    parents = Collections.singletonList(folderId)
                    mimeType = "audio/mp4"
                }

                // 3. Upload content stream
                val mediaContent = FileContent("audio/mp4", localFile)
                val uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id, name, size")
                    .execute()

                uploadedFile.id != null
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun getOrCreateFolder(folderName: String): String {
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()

        val existingFolder = result.files.firstOrNull()
        if (existingFolder != null) {
            return existingFolder.id
        }

        val folderMetadata = DriveFile().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }
        val created = driveService.files().create(folderMetadata)
            .setFields("id")
            .execute()

        return created.id
    }
}
