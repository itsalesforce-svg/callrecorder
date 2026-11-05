package com.example.callrecorder.auth

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Finds and resolves the Google account this app is allowed to use.
 *
 * Two facts drive the design (both were the cause of "account is null" in this app):
 *
 * 1. `android.permission.GET_ACCOUNTS` is a **dangerous** permission (AOSP
 *    `core/res/AndroidManifest.xml`: `android:protectionLevel="dangerous"`, and since Android 6.0
 *    only apps sharing the authenticator's signature are exempt). It is declared in the manifest
 *    but was never *requested at runtime*, so `AccountManager.getAccountsByType("com.google")`
 *    returned an **empty array** and every lookup failed.
 *
 * 2. `GoogleAccountCredential.setSelectedAccountName()` resolves the name with
 *    `getAccountsByType("com.google")` + `String.equals()` (case-sensitive, no trimming) and, when
 *    nothing matches, **silently sets its account to null**. The token request then fails with
 *    "No chosen account found, or the chosen account is no longer installed on the device."
 *    We therefore resolve the account ourselves, tolerantly, and hand the library a real
 *    [Account] object via `setSelectedAccount()`.
 */
class GoogleAccountRepository(private val context: Context) {

    companion object {
        /** Account type used by Google Play Services / the Drive API client library. */
        const val GOOGLE_ACCOUNT_TYPE = "com.google"

        /** Legacy Gmail authenticator type; some OEM ROMs only register accounts under this one. */
        const val GMAIL_ACCOUNT_TYPE = "com.google.android.gm"

        /**
         * Extra key returned by the Play Services account picker
         * (`com.google.android.gms.auth.AccountPicker.KEY_SELECTED_ACCOUNT`). Inlined as a literal
         * so this class still compiles/works if the Play Services SDK is not on the classpath.
         */
        const val PICKER_SELECTED_ACCOUNT_EXTRA = "selected_account"
    }

    /** An account as far as this app can see it. */
    data class VisibleAccount(val name: String, val type: String) {
        val isGoogle: Boolean get() = type == GOOGLE_ACCOUNT_TYPE || type == GMAIL_ACCOUNT_TYPE
    }

    /** Outcome of resolving the saved e-mail address against the device. */
    sealed class Resolution {
        /** A real [Account] object we can hand to `GoogleAccountCredential.setSelectedAccount()`. */
        data class Exact(val account: Account) : Resolution()

        /**
         * The address is not visible through AccountManager (missing GET_ACCOUNTS, OEM account
         * visibility filter, work profile, …) but the user typed/saved it, so we build a synthetic
         * Account and let the `com.google` authenticator validate it when a token is requested.
         */
        data class Synthetic(val account: Account) : Resolution()

        /** Nothing saved at all. */
        object NoneSaved : Resolution()

        /** The account is gone from the phone (or was never there). */
        data class NotFound(val savedName: String, val visible: List<VisibleAccount>) : Resolution()
    }

    private val accountManager: AccountManager get() = AccountManager.get(context)

    fun hasAccountsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.GET_ACCOUNTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * All accounts this app can see. Never throws: without GET_ACCOUNTS some OEM implementations
     * return an empty array, others raise SecurityException — both end up as an empty list here.
     */
    fun visibleAccounts(): List<VisibleAccount> = runCatching {
        accountManager.accounts.orEmpty()
            .map { VisibleAccount(it.name, it.type) }
            .sortedWith(compareBy({ !it.isGoogle }, { it.name.lowercase() }))
    }.getOrElse { emptyList() }

    /** Google accounts only, de-duplicated by e-mail address. */
    fun googleAccounts(): List<VisibleAccount> {
        val byName = LinkedHashMap<String, VisibleAccount>()
        for (type in listOf(GOOGLE_ACCOUNT_TYPE, GMAIL_ACCOUNT_TYPE)) {
            val accounts = runCatching { accountManager.getAccountsByType(type).orEmpty() }
                .getOrDefault(emptyArray())
            for (account in accounts) {
                byName.putIfAbsent(account.name.trim().lowercase(), VisibleAccount(account.name, account.type))
            }
        }
        if (byName.isEmpty()) {
            visibleAccounts().forEach { if (it.isGoogle) byName.putIfAbsent(it.name.trim().lowercase(), it) }
        }
        return byName.values.toList()
    }

    /** Number of accounts present on the phone at all (as far as we are allowed to know). */
    fun totalAccountCount(): Int = visibleAccounts().size

    /**
     * Resolves the saved address, tolerantly. Match order: exact name -> trimmed/case-insensitive
     * name -> same address on either Google account type -> synthetic fallback.
     */
    fun resolve(savedName: String?): Resolution {
        val query = savedName?.trim().orEmpty()
        if (query.isEmpty()) return Resolution.NoneSaved

        val accounts = runCatching { accountManager.accounts.orEmpty() }.getOrDefault(emptyArray())

        accounts.firstOrNull { it.name == query }?.let { return Resolution.Exact(it) }

        accounts.firstOrNull { it.name.trim().equals(query, ignoreCase = true) }
            ?.let { return Resolution.Exact(it) }

        // Same mailbox, but registered under the other Google account type.
        accounts.firstOrNull {
            (it.type == GOOGLE_ACCOUNT_TYPE || it.type == GMAIL_ACCOUNT_TYPE) &&
                normalizeAddress(it.name) == normalizeAddress(query)
        }?.let { return Resolution.Exact(it) }

        val googleVisible = accounts.filter {
            it.type == GOOGLE_ACCOUNT_TYPE || it.type == GMAIL_ACCOUNT_TYPE
        }

        if (googleVisible.isNotEmpty()) {
            // The phone has Google accounts, just not this address -> a real user-facing problem.
            return Resolution.NotFound(
                query,
                googleVisible.map { VisibleAccount(it.name, it.type) }
            )
        }

        // Nothing visible at all (permission denied / OEM visibility filter): do NOT hard-fail,
        // because the com.google authenticator can still issue a token for a known address.
        return Resolution.Synthetic(Account(query, GOOGLE_ACCOUNT_TYPE))
    }

    /** Best guess for the account the user means, used when they save just a bare address. */
    fun onlyGoogleAccount(): VisibleAccount? = googleAccounts().singleOrNull()

    fun looksLikeEmail(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        val at = v.indexOf('@')
        if (at <= 0 || at != v.lastIndexOf('@')) return false
        val domain = v.substring(at + 1)
        return domain.length >= 3 && domain.contains('.') && !domain.startsWith(".") &&
            !domain.endsWith(".") && v.none { it.isWhitespace() }
    }

    private fun normalizeAddress(value: String): String {
        val trimmed = value.trim().lowercase()
        val at = trimmed.indexOf('@')
        if (at <= 0) return trimmed
        val user = trimmed.substring(0, at).removeSuffix(".")
        val domain = trimmed.substring(at + 1)
        // gmail treats dots in the local part as insignificant.
        return if (domain == "gmail.com" || domain == "googlemail.com") {
            "$user@gmail.com"
        } else {
            "$user@$domain"
        }
    }
}
