package com.example.callrecorder.cloud

import android.content.Context
import com.example.callrecorder.auth.AppPrefs
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Uploads finished recordings into the app-private `CallRecordings` folder of Drive
 * (the `drive.file` scope only ever sees files this app created, which is why that scope is used).
 *
 * Unlike the first cut, failures are returned with a description instead of being swallowed by
 * `catch (e: Exception) { printStackTrace(); false }` — the caller needs to know whether to retry,
 * to re-authenticate, or to give up, and the Settings screen needs to show *why*.
 */
class GoogleDriveUploader(
    private val context: Context,
    private val driveService: Drive
) {

    companion object {
        const val FOLDER_NAME = "CallRecordings"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
        private const val MIME_AUDIO = "audio/mp4"
    }

    sealed class UploadResult {
        data class Success(val fileId: String, val fileName: String, val skipped: Boolean = false) : UploadResult()
        data class Failure(val failure: DriveFailure, val fileName: String) : UploadResult()
    }

    suspend fun uploadCallAudio(localFilePath: String, phoneNumber: String, timestamp: Long): UploadResult =
        withContext(Dispatchers.IO) {
            val localFile = File(localFilePath)
            val targetName = "Recording_${sanitize(phoneNumber)}_$timestamp.m4a"
            if (!localFile.exists() || localFile.length() == 0L) {
                return@withContext UploadResult.Failure(
                    fileName = targetName,
                    failure = DriveFailure(
                        headline = "Recording file is missing or empty on disk",
                        detail = "Nothing to upload for $localFilePath - the record will be marked " +
                            "as synced so the queue can drain."
                    )
                )
            }

            try {
                val folderId = resolveFolderId()

                // WorkManager can retry after the upload succeeded but before Room was updated.
                // Check first so we never end up with "Recording_x (1).m4a" duplicates.
                findExistingFile(targetName, folderId)?.let { existingId ->
                    return@withContext UploadResult.Success(existingId, targetName, skipped = true)
                }

                val fileMetadata = DriveFile().apply {
                    name = targetName
                    parents = Collections.singletonList(folderId)
                    mimeType = MIME_AUDIO
                    description = "Call recording for $phoneNumber"
                }
                val mediaContent = FileContent(MIME_AUDIO, localFile)
                val uploaded = driveService.files()
                    .create(fileMetadata, mediaContent)
                    .setFields("id, name, size")
                    .execute()

                val id = uploaded.id
                if (id.isNullOrEmpty()) {
                    UploadResult.Failure(
                        fileName = targetName,
                        failure = DriveFailure(
                            headline = "Drive accepted the upload but returned no file id",
                            detail = "This usually means the account ran out of storage or the upload " +
                                "was blocked by policy. Check the quota on drive.google.com."
                        )
                    )
                } else {
                    UploadResult.Success(id, targetName)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // A stale cached folder id is recoverable: drop the cache and let the next run
                // search for (or re-create) the folder.
                if (t is GoogleJsonResponseException && t.statusCode == 404) {
                    AppPrefs.clearDriveFolderId(context)
                }
                UploadResult.Failure(fileName = targetName, failure = DriveErrorMapper.describe(t))
            }
        }

    /** Cached -> search -> create. The created id is cached for subsequent recordings. */
    private fun resolveFolderId(): String {
        AppPrefs.driveFolderId(context)?.let { cached ->
            return cached
        }
        val id = getOrCreateFolder(FOLDER_NAME)
        AppPrefs.setDriveFolderId(context, id)
        return id
    }

    private fun findExistingFile(name: String, folderId: String): String? {
        val query = "name = '${escape(name)}' and '$folderId' in parents and trashed = false"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .setPageSize(1)
            .execute()
        return result.files.orEmpty().firstOrNull()?.id
    }

    private fun getOrCreateFolder(folderName: String): String {
        val query = "name = '${escape(folderName)}' and mimeType = '$MIME_FOLDER' and " +
            "trashed = false and 'root' in parents"
        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .setPageSize(1)
            .execute()

        result.files.orEmpty().firstOrNull()?.let { return it.id }

        val folderMetadata = DriveFile().apply {
            name = folderName
            mimeType = MIME_FOLDER
            parents = Collections.singletonList("root")
        }
        val created = driveService.files().create(folderMetadata).setFields("id").execute()
        return created.id ?: error(
            "Drive did not return an id for the new '$folderName' folder - the upload cannot proceed."
        )
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "unknown" }
}
