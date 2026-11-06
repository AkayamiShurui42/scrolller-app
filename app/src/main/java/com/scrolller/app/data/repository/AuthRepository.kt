package com.scrolller.app.data.repository

import android.content.Context
import android.webkit.CookieManager
import com.scrolller.app.data.api.ScrolllerApiClient
import com.scrolller.app.data.model.UserAccount
import com.scrolller.app.data.model.UserCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthRepository(
    context: Context,
    private val apiClient: ScrolllerApiClient
) {
    private val prefs = context.getSharedPreferences("scrolller_auth_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _currentUser = MutableStateFlow<UserAccount?>(null)
    val currentUser: StateFlow<UserAccount?> = _currentUser.asStateFlow()

    private val _userCollections = MutableStateFlow<List<UserCollection>>(emptyList())
    val userCollections: StateFlow<List<UserCollection>> = _userCollections.asStateFlow()

    init {
        loadSavedSession()
    }

    private fun loadSavedSession() {
        val rawAccount = prefs.getString("user_account_json", null)
        if (!rawAccount.isNullOrBlank()) {
            runCatching {
                val account = json.decodeFromString<UserAccount>(rawAccount)
                if (account.isSignedIn) {
                    _currentUser.value = account
                    apiClient.authToken = account.token
                    apiClient.cookieHeader = account.cookieHeader
                }
            }
        }

        val rawCollections = prefs.getString("user_collections_json", null)
        if (!rawCollections.isNullOrBlank()) {
            runCatching {
                _userCollections.value = json.decodeFromString<List<UserCollection>>(rawCollections)
            }
        }
    }

    suspend fun loginWithCredentials(usernameOrEmail: String, password: String): UserAccount {
        val account = apiClient.loginWithCredentials(usernameOrEmail, password)
        val verified = apiClient.fetchLoggedInUser(account.token, account.cookieHeader)
            ?: throw IllegalStateException("Scrolller did not confirm the account. Sign-in was not completed.")
        require(verified.username.isNotBlank()) { "Scrolller returned an invalid account profile." }
        saveAccount(verified)
        refreshUserCollections()
        return verified
    }

    suspend fun completeWebSignIn(extractedToken: String?, fallbackUsername: String?): UserAccount {
        val cookieMgr = runCatching { CookieManager.getInstance() }.getOrNull()
        val mainCookies = cookieMgr?.getCookie("https://scrolller.com").orEmpty()
        val apiCookies = cookieMgr?.getCookie("https://api.scrolller.com").orEmpty()
        val combinedCookies = listOf(mainCookies, apiCookies)
            .filter { it.isNotBlank() }
            .joinToString("; ")

        val cleanToken = extractedToken?.trim().orEmpty()
        apiClient.authToken = cleanToken
        apiClient.cookieHeader = combinedCookies

        val remoteUser = runCatching {
            apiClient.fetchLoggedInUser(tokenOverride = cleanToken, cookieOverride = combinedCookies)
        }.getOrNull()

        val verified = remoteUser?.takeIf { it.username.isNotBlank() }
            ?: throw IllegalStateException("Scrolller could not verify the web session. Please sign in again.")
        saveAccount(verified)
        refreshUserCollections()
        return verified
    }

    suspend fun verifyAndRefreshSession(): UserAccount? {
        val existing = _currentUser.value ?: return null
        apiClient.authToken = existing.token
        apiClient.cookieHeader = existing.cookieHeader

        val remote = runCatching {
            apiClient.fetchLoggedInUser(existing.token, existing.cookieHeader)
        }.getOrNull()

        if (remote != null && remote.isSignedIn) {
            saveAccount(remote)
        }
        refreshUserCollections()
        return _currentUser.value
    }

    suspend fun refreshUserCollections(): List<UserCollection> {
        val current = _currentUser.value
        if (current == null || !current.isSignedIn) return emptyList()

        return try {
            val cols = apiClient.fetchUserCollections()
            if (cols.isNotEmpty()) {
                _userCollections.value = cols
                prefs.edit()
                    .putString("user_collections_json", json.encodeToString(cols))
                    .apply()
            }
            _userCollections.value
        } catch (e: Exception) {
            _userCollections.value
        }
    }

    fun signOut() {
        _currentUser.value = null
        _userCollections.value = emptyList()
        apiClient.authToken = ""
        apiClient.cookieHeader = ""
        prefs.edit()
            .remove("user_account_json")
            .remove("user_collections_json")
            .apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private fun saveAccount(account: UserAccount) {
        _currentUser.value = account
        apiClient.authToken = account.token
        apiClient.cookieHeader = account.cookieHeader
        prefs.edit()
            .putString("user_account_json", json.encodeToString(account))
            .apply()
    }
}
