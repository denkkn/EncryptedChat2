package com.kgapp.encryptionchat.data.api

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SseChatApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS).build()
) {
    fun openStream(sig: String, data: JSONObject): Call {
        val body = JSONObject().apply { put("sig", sig); put("data", data) }
        val request = Request.Builder()
            .url(ChatApi.SERVER_API)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream").build()
        return client.newCall(request)
    }
}
