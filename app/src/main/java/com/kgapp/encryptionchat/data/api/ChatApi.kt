package com.kgapp.encryptionchat.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.UnknownServiceException

class ChatApi(private val client: OkHttpClient = OkHttpClient()) {
    companion object {
        const val SERVER_API = "http://47.113.126.123:8891/api/api.php"
        const val UPLOAD_URL = "http://47.113.126.123:8891/api/upload.php"
        const val DOWNLOAD_BASE = "http://47.113.126.123:8891/uploads"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    /** POST JSON envelope: {"sig":"...", "data":{...}} */
    suspend fun postJson(sig: String, data: JSONObject): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply { put("sig", sig); put("data", data) }
            val request = Request.Builder().url(SERVER_API)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext ApiResult.Failure("HTTP ${response.code}")
                val text = response.body?.string() ?: return@withContext ApiResult.Failure("响应为空")
                return@withContext ApiResult.Success(JSONObject(text))
            }
        } catch (ex: UnknownServiceException) { ApiResult.Failure("不允许明文连接")
        } catch (ex: Exception) { ApiResult.Failure(ex.message ?: "网络错误") }
    }
}
