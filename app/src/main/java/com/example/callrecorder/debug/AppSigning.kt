package com.example.callrecorder.debug

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * The app's signing certificate, exposed as SHA-1 / SHA-256 the way Google Cloud Console wants it.
 *
 * This matters more than it looks: Google Play services only hands out a Drive token when the
 * requesting app's **package name + signing SHA-1** are registered as an *Android OAuth client* in
 * the Cloud project. With the legacy `GoogleAuthUtil` flow there is no client id in the code, the
 * lookup is implicit, and when it fails the error is the unhelpful
 * `GoogleAuthException: UnregisteredOnApiConsole`. Showing the fingerprint on screen turns that into
 * "paste these two values into Cloud Console".
 */
object AppSigning {

    fun sha1(context: Context): String = digest(context, "SHA-1")

    fun sha256(context: Context): String = digest(context, "SHA-256")

    private fun digest(context: Context, algorithm: String): String {
        val bytes = signatureBytes(context) ?: return "unavailable (${algorithm})"
        val hash = runCatching { MessageDigest.getInstance(algorithm).digest(bytes) }.getOrNull()
            ?: return "unavailable (${algorithm})"
        return hash.joinToString(":") { String.format("%02X", it) }
    }

    private fun signatureBytes(context: Context): ByteArray? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            // Signature.toByteArray() exists; it is the *Signature* object we need, not a lambda.
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.let { sig -> sig.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.let { sig -> sig.toByteArray() }
        }
    }.getOrNull()

    /**
     * True when this APK carries the well-known AOSP debug certificate. Builds signed with a
     * throwaway debug key are exactly the ones whose SHA-1 changes on every CI run, so it is worth
     * saying so next to the fingerprint.
     */
    fun isDebugKeystore(context: Context): Boolean {
        val value = sha1(context)
        return DEBUG_SHA1_PREFIXES.any { value.startsWith(it) }
    }

    private val DEBUG_SHA1_PREFIXES = listOf("A4:0D:A8:C1", "61:ED:37:7E", "8C:5E:9E:4C")
}
