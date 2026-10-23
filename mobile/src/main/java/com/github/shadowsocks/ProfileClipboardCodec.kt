package com.github.shadowsocks

import android.util.Base64
import com.github.shadowsocks.database.Profile
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Clipboard-only profile format. Protected payloads use PBKDF2-HMAC-SHA256 + AES-256-GCM. */
object ProfileClipboardCodec {
    private const val PROTECTED_PREFIX = "alrufaaey://protected/v1/"
    private const val AAD = "alrufaaey-profile-v1"
    private const val ITERATIONS = 200_000
    private val random = SecureRandom()

    fun exportPlain(profile: Profile, displayName: String): String =
        profile.copy(name = displayName.trim().ifEmpty { "GCP SHADOW" }).toUri().toString()

    fun isProtected(payload: String): Boolean = payload.trim().startsWith(PROTECTED_PREFIX)

    fun protect(plainUri: String, password: String): String {
        require(password.length >= 8) { "Password must be at least 8 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plainUri.toByteArray(StandardCharsets.UTF_8))
        return PROTECTED_PREFIX + encode(salt) + "/" + encode(nonce) + "/" + encode(encrypted)
    }

    /** Throws on malformed data, unsupported versions, or a wrong password. */
    fun unprotect(payload: String, password: String): String {
        val trimmed = payload.trim()
        require(trimmed.startsWith(PROTECTED_PREFIX)) { "Not a protected alrufaaey profile" }
        val parts = trimmed.removePrefix(PROTECTED_PREFIX).split('/')
        require(parts.size == 3) { "Malformed protected profile" }
        val salt = decode(parts[0])
        val nonce = decode(parts[1])
        val encrypted = decode(parts[2])
        require(salt.size == 16 && nonce.size == 12 && encrypted.size >= 16) { "Malformed protected profile" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            val block = salt + byteArrayOf(0, 0, 0, 1)
            var u = mac.doFinal(block)
            val result = u.copyOf()
            repeat(ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
            u.fill(0)
            return SecretKeySpec(result, "AES").also { result.fill(0) }
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(
        bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
