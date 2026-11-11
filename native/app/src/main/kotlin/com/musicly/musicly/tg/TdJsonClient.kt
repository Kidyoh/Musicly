package com.musicly.musicly.tg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.drinkless.tdlib.JsonClient
import org.drinkless.tdlib.TdLibInitResult
import org.drinkless.tdlib.TdLibInitializer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A tiny TDLib client over the JSON interface (the JNI part of td-android).
 * Requests are matched to answers with `@extra`; everything else (updates) goes to [onUpdate]
 * on the receiving thread, so keep that quick. Nothing is ever logged here: requests carry
 * phone numbers, codes and passwords.
 */
internal class TdJsonClient(private val onUpdate: (type: String, json: String) -> Unit) {
    private val clientId = JsonClient.create()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val counter = AtomicLong()

    @Volatile
    private var running = true

    private val receiver = thread(name = "tdlib-receive", isDaemon = true) {
        while (running) {
            val text = try {
                JsonClient.receive(clientId, 1.0)
            } catch (_: Throwable) {
                break
            } ?: continue
            val extra = extraOf(text)
            if (extra != null) {
                pending.remove(extra)?.complete(text)
                continue
            }
            val type = typeOf(text)
            try {
                onUpdate(type, text)
            } catch (_: Throwable) {
                // A broken handler must never stop the receiver.
            }
            if (type == "updateAuthorizationState" && text.contains("\"authorizationStateClosed\"")) running = false
        }
        pending.values.forEach { it.completeExceptionally(IOException("Telegram connection closed")) }
        pending.clear()
    }

    /** Sends [request] (a JSON object with an `@type`) and returns the raw answer, which may be an `error` object. */
    suspend fun request(request: JsonObject, timeoutMs: Long = 60_000): String {
        val extra = "m" + counter.incrementAndGet()
        val deferred = CompletableDeferred<String>()
        pending[extra] = deferred
        if (!running) {
            pending.remove(extra)
            throw IOException("Telegram connection closed")
        }
        try {
            JsonClient.send(clientId, JsonObject(request + ("@extra" to JsonPrimitive(extra))).toString())
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("Telegram did not answer in time")
        } finally {
            pending.remove(extra)
        }
    }

    fun close() {
        running = false
    }

    companion object {
        /** Loads the native library (first use only). Returns null when it works, else a short reason. */
        fun load(): String? = when (val r = TdLibInitializer.init()) {
            is TdLibInitResult.Error -> r.message ?: "native library missing"
            else -> null
        }

        internal fun typeOf(json: String): String {
            val key = "\"@type\":\""
            val i = json.indexOf(key)
            if (i < 0) return ""
            val s = i + key.length
            return json.substring(s, json.indexOf('"', s).let { if (it < 0) json.length else it })
        }

        internal fun extraOf(json: String): String? {
            val key = "\"@extra\":\""
            val i = json.lastIndexOf(key)
            if (i < 0) return null
            val s = i + key.length
            val e = json.indexOf('"', s)
            return if (e < 0) null else json.substring(s, e).takeIf { it.startsWith("m") }
        }
    }
}
