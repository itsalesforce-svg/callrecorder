# ProGuard Minification Rules for Call Recorder
# Developer Note from Specification Section 5:
# Ensure ProGuard minification rules include keeps for Room entities,
# WorkManager workers, and Google Drive API serialization classes.

# Room Entities & DAOs
-keep class com.example.callrecorder.data.local.CallRecordEntity { *; }
-keep interface com.example.callrecorder.data.local.CallRecordDao { *; }
-keep class * extends androidx.room.RoomDatabase

# WorkManager Workers
-keep class com.example.callrecorder.worker.GoogleDriveSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Google Drive API v3 & HTTP Client Serialization
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }

# Google Play services auth (GoogleAuthUtil / AccountPicker) - google-api-client-android calls these
# reflectively through GoogleAccountCredential, and R8 otherwise strips or renames the members.
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.api.client.googleapis.extensions.android.gms.** { *; }
-keep class com.google.api.client.googleapis.extensions.android.gms.auth.** { *; }
-keep class com.google.api.client.http.javanet.** { *; }
-keep class com.google.api.client.http.** { *; }
-keep class com.google.api.client.json.** { *; }
-keep class com.google.api.client.util.** { *; }
-keep class com.google.api.client.googleapis.** { *; }

# google-http-client still references the legacy Apache HTTP classes that Android removed.
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.codec.**
-dontwarn javax.annotation.**
-dontwarn com.google.android.gms.**
