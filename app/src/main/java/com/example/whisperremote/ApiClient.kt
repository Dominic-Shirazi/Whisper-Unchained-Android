package com.example.whisperremote

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)

        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun getBaseUrl(): String {
        val prefs = context.getSharedPreferences("WhisperRemotePrefs", Context.MODE_PRIVATE)
        return prefs.getString("api_url", "http://your-server-ip:5000/transcribe") ?: "http://your-server-ip:5000/transcribe"
    }

    fun transcribe(file: File, callback: (String?) -> Unit) {
        val baseUrl = getBaseUrl()
        Log.d("ApiClient", "Attempting to send file: ${file.absolutePath} to $baseUrl")
        
        if (!file.exists()) {
            Log.e("ApiClient", "File does not exist!")
            callback(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val request = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ApiClient", "Network failure: ${e.message}", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    Log.d("ApiClient", "Response code: ${response.code}")
                    if (!response.isSuccessful) {
                        Log.e("ApiClient", "Server returned error: ${response.message}")
                        callback(null)
                        return
                    }
                    val body = response.body?.string()
                    Log.d("ApiClient", "Response body: $body")
                    if (body != null) {
                        try {
                            val json = JSONObject(body)
                            callback(json.optString("text"))
                        } catch (e: Exception) {
                            Log.e("ApiClient", "JSON parsing error", e)
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
            }
        })
    }
}
