package com.musicly.musicly.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One shared HTTP client for the whole app. */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun withTimeout(seconds: Long): OkHttpClient = client.newBuilder()
        .callTimeout(seconds, TimeUnit.SECONDS).build()

    class HttpError(val code: Int, message: String) : IOException(message)

    /**
     * Runs the call and returns its response. By default the body is read right
     * away on OkHttp's own thread: callers often run on the main thread, where
     * reading a socket is not allowed. Pass [buffer] = false to stream big downloads
     * (only from background threads).
     */
    suspend fun Call.await(buffer: Boolean = true): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!buffer) {
                    cont.resume(response)
                    return
                }
                try {
                    val body = response.body
                    val bytes = body.bytes()
                    cont.resume(response.newBuilder().body(bytes.toResponseBody(body.contentType())).build())
                } catch (e: IOException) {
                    response.close()
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): Response {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return withTimeout(timeoutSeconds).newCall(req).await()
    }

    /** GET and read the body as text; throws [HttpError] unless the answer is 200. */
    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), timeoutSeconds: Long = 15): String =
        get(url, headers, timeoutSeconds).use {
            if (it.code != 200) throw HttpError(it.code, "HTTP ${it.code}")
            it.body.string()
        }

    suspend fun postJson(url: String, json: String, timeoutSeconds: Long = 30): Response {
        val req = Request.Builder().url(url)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        return withTimeout(timeoutSeconds).newCall(req).await()
    }
}
