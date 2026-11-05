package com.example.callrecorder.auth

import android.content.Context
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.util.Collections

class GoogleAuthManager(private val context: Context) {

    companion object {
        val SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE)
    }

    fun getGoogleDriveService(): Drive? {
        val sharedPref = context.getSharedPreferences("call_recorder_prefs", Context.MODE_PRIVATE)
        val selectedAccount = sharedPref.getString("google_account_name", null) ?: return null

        val credential = GoogleAccountCredential.usingOAuth2(context, SCOPES).apply {
            this.selectedAccountName = selectedAccount
        }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("CallRecorderApp").build()
    }

    fun saveAccountName(accountName: String) {
        context.getSharedPreferences("call_recorder_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("google_account_name", accountName)
            .apply()
    }
}
