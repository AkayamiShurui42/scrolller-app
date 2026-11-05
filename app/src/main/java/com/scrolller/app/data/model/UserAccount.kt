package com.scrolller.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserAccount(
    val id: String = "",
    val username: String = "",
    val email: String = "",
    val token: String = "",
    val cookieHeader: String = "",
    val isWebSession: Boolean = false,
    val signedInAt: Long = System.currentTimeMillis()
) {
    val isSignedIn: Boolean
        get() = username.isNotBlank() || token.isNotBlank() || cookieHeader.isNotBlank()
}

@Serializable
data class UserCollection(
    val id: Long,
    val title: String,
    val url: String = "",
    val description: String = "",
    val isNsfw: Boolean = false,
    val isPrivate: Boolean = false,
    val postCount: Int = 0
)
