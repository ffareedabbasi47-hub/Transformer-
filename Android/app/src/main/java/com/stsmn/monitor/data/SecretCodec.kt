package com.stsmn.monitor.data

/** Protects small secrets (server viewer key, device token) before they are written to disk. */
interface SecretCodec {
    /** Returns the protected text, or null when protection is not possible (secret is then NOT stored). */
    fun encrypt(plain: String): String?
    /** Returns the secret, or null when the stored value is unreadable (e.g. key was wiped). */
    fun decrypt(stored: String): String?
}
