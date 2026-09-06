package com.kriyasense.app.auth

import android.content.Context
import android.util.Base64
import android.util.Patterns
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AuthErrors(
    val name: String? = null, val email: String? = null,
    val password: String? = null, val confirmation: String? = null,
    val general: String? = null
) {
    val any: Boolean get() = listOf(name, email, password, confirmation, general).any { it != null }
}

/** One device-local prototype account, separate from profile and workout preferences. */
class LocalAuth(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("kriyasense_auth_v1", Context.MODE_PRIVATE)
    val loggedIn: Boolean get() = preferences.getBoolean("logged_in", false) && preferences.contains("password_hash")
    val name: String get() = preferences.getString("name", "").orEmpty()
    val email: String get() = preferences.getString("email", "").orEmpty()

    fun signUp(name: String, email: String, password: String, confirmation: String): AuthErrors {
        val errors = AuthErrors(
            name = if (name.isBlank()) "Please enter your name." else null,
            email = emailError(email),
            password = if (password.isBlank()) "Please enter a password." else if (password.length < 8) "Use at least 8 characters." else null,
            confirmation = if (password != confirmation) "Your passwords don't match." else null
        )
        if (errors.any) return errors
        if (preferences.contains("password_hash")) return AuthErrors(general = "An account already exists on this device. Please log in instead.")
        return runCatching {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val saved = preferences.edit().putString("name", name.trim())
                .putString("email", normalize(email)).putString("salt", encode(salt))
                .putString("password_hash", encode(hash(password, salt)))
                .putBoolean("logged_in", true).commit()
            if (saved) AuthErrors() else AuthErrors(general = "Couldn't save your account. Please try again.")
        }.getOrElse { AuthErrors(general = "Couldn't create your account. Please try again.") }
    }

    fun login(email: String, password: String): AuthErrors {
        val errors = AuthErrors(email = emailError(email), password = if (password.isBlank()) "Please enter your password." else null)
        if (errors.any) return errors
        return runCatching {
            val storedHash = preferences.getString("password_hash", null)
                ?: return AuthErrors(general = "No account on this device yet. Create an account to get started.")
            val salt = Base64.decode(preferences.getString("salt", ""), Base64.NO_WRAP)
            val matches = MessageDigest.isEqual(Base64.decode(storedHash, Base64.NO_WRAP), hash(password, salt))
            if (normalize(email) != this.email || !matches) AuthErrors(general = "That email and password don't match. Please try again.")
            else if (preferences.edit().putBoolean("logged_in", true).commit()) AuthErrors()
            else AuthErrors(general = "Couldn't save your login. Please try again.")
        }.getOrElse { AuthErrors(general = "Couldn't log in. Please try again.") }
    }

    fun logout(): Boolean = preferences.edit().putBoolean("logged_in", false).commit()

    private fun emailError(email: String): String? = when {
        email.isBlank() -> "Please enter your email."
        !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Enter a valid email address."
        else -> null
    }
    private fun normalize(email: String) = email.trim().lowercase(Locale.ROOT)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
