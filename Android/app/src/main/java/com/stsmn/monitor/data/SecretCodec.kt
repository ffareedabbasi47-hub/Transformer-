package com.stsmn.monitor.data

interface SecretCodec {

    fun encrypt(plain: String): String?

    fun decrypt(stored: String): String?
}
