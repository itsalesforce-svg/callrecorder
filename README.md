# Android Automatic Call Recorder (Total Recall Clone)

Complete Native Android implementation based on Developer Specification (Target SDK: API 34/35).

## Prerequisites
- Android Studio Ladybug (2024.2+) or Hedgehog/Iguana
- JDK 17
- Android Device or Emulator with API 34+ (Android 14/15)

## Architecture
- **UI**: Jetpack Compose with Material 3 Design System (BOM 2024.09.00)
- **Local DB**: Room 2.6.1 with KSP (Kotlin Symbol Processing)
- **Background Engine**: Foreground Service with `FOREGROUND_SERVICE_MICROPHONE`
- **Telephony Receiver**: `CallStateReceiver` triggers on `EXTRA_STATE_OFFHOOK` and stops on `EXTRA_STATE_IDLE`
- **Cloud Sync**: WorkManager 2.9.1 uploading to Google Drive REST API v3
