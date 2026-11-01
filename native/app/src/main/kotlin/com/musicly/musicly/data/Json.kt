package com.musicly.musicly.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One shared JSON instance. Saved data uses plain objects, like the Flutter version. */
val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

fun parseJson(text: String): JsonElement = AppJson.parseToJsonElement(text)

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonElement?.int(): Int? = long()?.toInt()
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Builds a JSON object, skipping null values. */
fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associate { (k, v) -> k to toJson(v) },
)

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}
