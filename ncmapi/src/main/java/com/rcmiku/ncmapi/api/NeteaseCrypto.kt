package com.rcmiku.ncmapi.api

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Request formats used by the NetEase web and desktop clients. */
internal object NeteaseCrypto {
    private val random = SecureRandom()
    private val iv = "0102030405060708".toByteArray(Charsets.UTF_8)
    private val presetKey = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
    private val eapiKey = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
    private const val base62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(
            Base64.getDecoder().decode(
                "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
            )
        )
    )

    fun weapi(json: String): Map<String, String> {
        val secret = CharArray(16) { base62[random.nextInt(base62.length)] }.concatToString()
        val first = Base64.getEncoder().encodeToString(aes(json.toByteArray(Charsets.UTF_8), presetKey, "CBC", iv))
        val params = Base64.getEncoder().encodeToString(aes(first.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8), "CBC", iv))
        val rsa = Cipher.getInstance("RSA/ECB/NoPadding")
        rsa.init(Cipher.ENCRYPT_MODE, publicKey)
        val encSecKey = rsa.doFinal(secret.reversed().toByteArray(Charsets.UTF_8)).toHex()
        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    fun eapi(path: String, json: String): Map<String, String> {
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${path}use${json}md5forencrypt".toByteArray(Charsets.UTF_8))
            .toHex()
        val plain = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
        return mapOf("params" to aes(plain.toByteArray(Charsets.UTF_8), eapiKey, "ECB").toHex().uppercase())
    }

    private fun aes(input: ByteArray, key: ByteArray, mode: String, iv: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/$mode/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            if (iv == null) null else IvParameterSpec(iv)
        )
        return cipher.doFinal(input)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
