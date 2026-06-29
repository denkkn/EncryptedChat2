package com.kgapp.encryptionchat.data.crypto

import android.util.Base64
import com.kgapp.encryptionchat.data.storage.FileStorage
import org.json.JSONObject
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** main..py 完全一致的加密层 */
class CryptoManager(private val storage: FileStorage) {

    fun hasKeyPair() = storage.hasPrivateKey() && storage.hasPublicKey()
    fun computeSelfName() = computeUid(computePemBase64() ?: "")

    fun generateKeyPair(): Boolean {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        saveKeys(kp.private, kp.public)
        return true
    }

    fun importPrivatePem(pem: String): Boolean = try {
        val b64 = pem.replace("-----BEGIN PRIVATE KEY-----\n", "").replace("-----END PRIVATE KEY-----\n", "").replace("\n", "").trim()
        val priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)))
        val spec = KeyFactory.getInstance("RSA").getKeySpec(priv, RSAPrivateCrtKeySpec::class.java)
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(spec.modulus, spec.publicExponent))
        saveKeys(priv, pub)
        true
    } catch (_: Exception) { false }

    fun importPublicPem(pem: String): Boolean = try {
        val b64 = pem.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("-----END PUBLIC KEY-----\n", "").replace("\n", "").trim()
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.DEFAULT)))
        storage.writePublicPem(publicToPem(pub).toByteArray())
        true
    } catch (_: Exception) { false }

    fun computePemBase64(): String? {
        if (!hasKeyPair()) return null
        return Base64.encodeToString(loadPublicKey().encoded, Base64.NO_WRAP)
    }

    fun signData(data: Map<String, Any?>): String {
        val json = canonicalize(data)
        val sig = Signature.getInstance("SHA256withRSA"); sig.initSign(loadPrivateKey())
        sig.update(json.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    /** 构建请求并签名 → (sig, dataJson) */
    fun buildSignedRequest(type: String, extra: Map<String, Any?>? = null): Pair<String, JSONObject>? {
        val pubB64 = computePemBase64() ?: return null
        val data = mutableMapOf<String, Any?>("pub" to pubB64, "ts" to System.currentTimeMillis() / 1000, "type" to type)
        extra?.let { data.putAll(it) }
        val sig = signData(data)
        return sig to JSONObject(data)
    }

    /** AES-256-GCM 加密 JSON */
    fun aesEncrypt(json: JSONObject, key: ByteArray): String {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12); SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(bytes), Base64.NO_WRAP)
    }

    /** AES-256-GCM 解密 */
    fun aesDecrypt(b64: String, key: ByteArray): JSONObject {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return JSONObject(String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
    }

    /** RSA-OAEP 加密 AES 密钥 */
    fun rsaEncryptKey(key: ByteArray, pubB64: String): String {
        val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        c.init(Cipher.ENCRYPT_MODE, loadPublicKeyFromBase64(pubB64))
        return Base64.encodeToString(c.doFinal(key), Base64.NO_WRAP)
    }

    /** RSA-OAEP 解密 AES 密钥 */
    fun rsaDecryptKey(b64: String): ByteArray {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        c.init(Cipher.DECRYPT_MODE, loadPrivateKey())
        return c.doFinal(raw)
    }

    fun generateAesKey() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** 加密给好友的完整消息 → (encMsg, encKey) */
    fun encryptForFriend(json: JSONObject, friendPubB64: String): Pair<String, String> {
        val aes = generateAesKey()
        return aesEncrypt(json, aes) to rsaEncryptKey(aes, friendPubB64)
    }

    /** 解密收到的消息 → (json, aesKeyB64) */
    fun decryptReceived(encMsg: String, encKey: String): Pair<JSONObject, String> {
        val aes = rsaDecryptKey(encKey)
        return aesDecrypt(encMsg, aes) to Base64.encodeToString(aes, Base64.NO_WRAP)
    }

    /** 加密文件 → (encryptedData, fileHash) */
    fun encryptFile(data: ByteArray, key: ByteArray): Pair<ByteArray, String> {
        val iv = ByteArray(12); SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val enc = iv + c.doFinal(data)
        return enc to MessageDigest.getInstance("SHA-256").digest(enc).joinToString("") { "%02x".format(it) }
    }

    fun decryptFile(enc: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, enc.copyOfRange(0, 12)))
        return c.doFinal(enc.copyOfRange(12, enc.size))
    }

    // ---- helpers ----
    fun loadPublicKeyFromBase64(b64: String) = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)))

    fun computeUid(b64: String) = MessageDigest.getInstance("SHA-256")
        .digest(b64.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    private fun loadPrivateKey() = KeyFactory.getInstance("RSA")
        .generatePrivate(PKCS8EncodedKeySpec(storage.readPrivatePemBytes()!!))

    private fun loadPublicKey() = KeyFactory.getInstance("RSA")
        .generatePublic(X509EncodedKeySpec(storage.readPublicPemBytes()!!))

    private fun saveKeys(priv: PrivateKey, pub: PublicKey) {
        storage.writePrivatePem(privToPem(priv).toByteArray())
        storage.writePublicPem(publicToPem(pub).toByteArray())
    }

    private fun privToPem(k: PrivateKey) = "-----BEGIN PRIVATE KEY-----\n${Base64.encodeToString(k.encoded, Base64.DEFAULT)}-----END PRIVATE KEY-----\n"
    private fun publicToPem(k: PublicKey) = "-----BEGIN PUBLIC KEY-----\n${Base64.encodeToString(k.encoded, Base64.DEFAULT)}-----END PUBLIC KEY-----\n"

    // ---- 签名规范化 (与 Python normalize_data_for_signing + json.dumps 完全一致) ----
    private fun canonicalize(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is Number -> v.toString()
        is String -> "\"${esc(v)}\""
        is Map<*, *> -> "{${v.entries.sortedBy { it.key.toString() }.joinToString(",") { "\"${esc(it.key.toString())}\":${canonicalize(it.value)}" }}}"
        is List<*> -> "[${v.joinToString(",") { canonicalize(it) }}]"
        else -> "\"${esc(v.toString())}\""
    }

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
        }
    }
}
