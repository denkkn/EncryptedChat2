package com.kgapp.encryptionchat.data.sync

import android.content.Context
import android.util.Log
import com.kgapp.encryptionchat.data.ChatRepository
import com.kgapp.encryptionchat.data.api.SseChatApi
import com.kgapp.encryptionchat.util.PullMode
import com.kgapp.encryptionchat.util.UnreadCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import org.json.JSONObject
import java.time.Instant

class MessageSyncManager(
    private val repository: ChatRepository,
    private val context: Context,
    private val sseApi: SseChatApi = SseChatApi()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var currentFromUid: String? = null
    private var currentJob: Job? = null
    private var currentCall: Call? = null
    private var currentMode: PullMode = PullMode.CHAT_SSE
    private var lastActiveChatUid: String? = null
    private var activeChatUid: String? = null

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val updates: SharedFlow<String> = _updates

    suspend fun refreshOnce(fromUid: String): String? {
        val result = repository.readChat(fromUid)
        return when {
            !result.success -> result.message
            result.addedCount > 0 -> {
                _updates.tryEmit(fromUid)
                if (activeChatUid != fromUid) UnreadCounter.increment(context, fromUid)
                null
            }
            else -> null
        }
    }

    suspend fun refreshRecentChats(): String? {
        val recents = repository.getRecentChats()
        var lastError: String? = null
        for (item in recents) {
            val message = refreshOnce(item.uid)
            if (!message.isNullOrBlank()) {
                lastError = message
            }
        }
        return lastError
    }

    fun updateMode(mode: PullMode, activeChatUid: String?) {
        currentMode = mode
        this.activeChatUid = activeChatUid
        if (!activeChatUid.isNullOrBlank()) {
            lastActiveChatUid = activeChatUid
        }
        scope.launch {
            when (mode) {
                PullMode.MANUAL -> stopSse()
                PullMode.CHAT_SSE -> {
                    if (activeChatUid != null) {
                        startSse(activeChatUid)
                    } else {
                        stopSse()
                    }
                }
                PullMode.GLOBAL_SSE -> {
                    val targetUid = activeChatUid ?: lastActiveChatUid ?: repository.getRecentChats().firstOrNull()?.uid
                    if (targetUid != null) {
                        startSse(targetUid)
                    } else {
                        stopSse()
                    }
                }
            }
        }
    }

    suspend fun stopSse() {
        mutex.withLock {
            currentCall?.cancel()
            currentCall = null
            currentFromUid = null
            currentJob?.cancelAndJoin()
            currentJob = null
            Log.d(TAG, "SSE stopped")
        }
    }

    suspend fun startSse(fromUid: String) {
        mutex.withLock {
            if (currentFromUid == fromUid && currentJob?.isActive == true) {
                return
            }
            currentCall?.cancel()
            currentJob?.cancelAndJoin()
            currentFromUid = fromUid
            currentJob = scope.launch {
                val lastTs = repository.getLastTimestamp(fromUid)
                val pair = buildSsePayload(fromUid, lastTs) ?: run {
                    Log.d(TAG, "SSE skipped: missing credentials"); return@launch
                }
                val (sig, data) = pair
                val call = sseApi.openStream(sig, data)
                currentCall = call
                Log.d(TAG, "SSE start for $fromUid with lastTs=$lastTs")
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.d(TAG, "SSE response error: ${response.code}")
                            return@use
                        }
                        val source = response.body?.source() ?: return@use
                        var pendingData: String? = null
                        while (!source.exhausted() && currentCall?.isCanceled() != true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) {
                                if (!pendingData.isNullOrBlank()) {
                                    handleSseData(fromUid, pendingData)
                                    pendingData = null
                                }
                                continue
                            }
                            if (line == "hb") {
                                continue
                            }
                            if (line.startsWith("data: ")) {
                                pendingData = line.removePrefix("data: ").trim()
                            }
                        }
                    }
                } catch (ex: Exception) {
                    if (currentCall?.isCanceled() != true) {
                        Log.d(TAG, "SSE error: ${ex.message}")
                    }
                }
            }
        }
    }

    private suspend fun buildSsePayload(fromUid: String, lastTs: Long): Pair<String, org.json.JSONObject>? {
        return repository.crypto?.buildSignedRequest("SseMsg", mapOf("from" to fromUid, "last_ts" to lastTs))
    }

    private suspend fun handleSseData(fromUid: String, payload: String) {
        val json = JSONObject(payload)
        if (json.optInt("code", -1) != 0) return
        val encMsg = json.optString("msg", "")
        val encKey = json.optString("key", "")
        if (encMsg.isEmpty() || encKey.isEmpty()) return
        val ts = java.time.Instant.now().epochSecond
        val result = repository.handleIncomingCipherMessage(fromUid, ts, encMsg, encKey)
        if (result.success) {
            _updates.tryEmit(fromUid)
            if (activeChatUid != fromUid) {
                UnreadCounter.increment(context, fromUid)
            }
        }
    }

    companion object {
        private const val TAG = "MessageSyncManager"
    }
}
