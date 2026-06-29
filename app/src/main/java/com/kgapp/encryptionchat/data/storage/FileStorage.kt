package com.kgapp.encryptionchat.data.storage

import android.content.Context
import com.kgapp.encryptionchat.data.model.ChatMessage
import com.kgapp.encryptionchat.data.model.ContactConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class FileStorage(private val context: Context) {
    private val jsonPretty2 = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
    private val jsonPretty4 = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
    }
    private val jsonCompact = Json { encodeDefaults = true }

    val baseDir: File get() = context.filesDir

    private fun resolve(relative: String): File = File(baseDir, relative)

    fun ensureKeyDirs(): File {
        val dir = resolve("config/key")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun privateKeyFile(): File = resolve("config/key/private.pem")

    fun publicKeyFile(): File = resolve("config/key/public.pem")

    fun contactsConfigFile(): File = resolve("contacts/config.json")

    fun readContactsConfigRaw(): String {
        val file = contactsConfigFile()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("{}", Charsets.UTF_8)
        }
        return file.readText(Charsets.UTF_8)
    }

    fun chatFile(uid: String): File = resolve("contacts/chats/$uid.json")

    fun listChatFiles(): List<File> {
        val dir = resolve("contacts/chats")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension == "json" }?.toList().orEmpty()
    }

    fun deleteChatHistory(uid: String) {
        val file = chatFile(uid)
        if (file.exists()) {
            file.delete()
        }
    }

    fun readContactsConfig(): MutableMap<String, ContactConfig> {
        val file = contactsConfigFile()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("{}", Charsets.UTF_8)
            return mutableMapOf()
        }
        val content = file.readText(Charsets.UTF_8)
        if (content.isBlank()) {
            return mutableMapOf()
        }
        return runCatching {
            jsonCompact.decodeFromString<Map<String, ContactConfig>>(content).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    fun writeContactsConfig(config: Map<String, ContactConfig>) {
        val file = contactsConfigFile()
        file.parentFile?.mkdirs()
        val content = jsonPretty4.encodeToString(config)
        file.writeText(content, Charsets.UTF_8)
    }

    fun updateContactRemark(uid: String, remark: String): Boolean {
        val config = readContactsConfig()
        val existing = config[uid] ?: return false
        config[uid] = existing.copy(Remark = remark)
        writeContactsConfig(config)
        return true
    }

    fun ensureChatFile(uid: String) {
        val file = chatFile(uid)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val defaultHistory = mapOf("0" to ChatMessage(Spokesman = 0, text = "暂无记录"))
            val content = jsonPretty2.encodeToString(defaultHistory)
            file.writeText(content, Charsets.UTF_8)
        }
    }

    fun readChatHistory(uid: String): MutableMap<String, ChatMessage> {
        ensureChatFile(uid)
        val file = chatFile(uid)
        val content = file.readText(Charsets.UTF_8)
        if (content.isBlank()) {
            return mutableMapOf()
        }
        return runCatching {
            jsonCompact.decodeFromString<Map<String, ChatMessage>>(content).toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    fun writeChatHistory(uid: String, history: Map<String, ChatMessage>) {
        val file = chatFile(uid)
        file.parentFile?.mkdirs()
        val content = jsonPretty2.encodeToString(history)
        file.writeText(content, Charsets.UTF_8)
    }

    fun upsertChatMessage(uid: String, ts: String, message: ChatMessage) {
        val history = readChatHistory(uid)
        history[ts] = message
        writeChatHistory(uid, history)
    }

    fun replaceChatTimestamp(uid: String, oldTs: String, newTs: String, message: ChatMessage) {
        val history = readChatHistory(uid)
        history.remove(oldTs)
        history[newTs] = message
        writeChatHistory(uid, history)
    }

    fun readPublicPemText(): String? {
        val file = publicKeyFile()
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    fun readPrivatePemBytes(): ByteArray? {
        val file = privateKeyFile()
        return if (file.exists()) file.readBytes() else null
    }

    fun hasPrivateKey() = privateKeyFile().exists()
    fun hasPublicKey() = publicKeyFile().exists()
    fun readPublicPemBytes(): ByteArray? {
        val file = publicKeyFile()
        return if (file.exists()) file.readBytes() else null
    }
    fun writePrivatePem(bytes: ByteArray) { ensureKeyDirs(); privateKeyFile().writeBytes(bytes) }
    fun writePublicPem(bytes: ByteArray) { ensureKeyDirs(); publicKeyFile().writeBytes(bytes) }

    fun wipeSensitiveData() {
        privateKeyFile().delete()
        publicKeyFile().delete()
        contactsConfigFile().delete()
        val chatsDir = resolve("contacts/chats")
        if (chatsDir.exists()) {
            chatsDir.deleteRecursively()
        }
    }
}
