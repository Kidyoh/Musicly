package com.musicly.musicly.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Small key-value storage. On first run it also copies everything the
 * Flutter version saved (library, playlists, Telegram, settings), so
 * updating from 1.x keeps all data.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("musicly", Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean("_migrated", false)) {
            try {
                val old = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                val edit = prefs.edit()
                for ((k, v) in old.all) {
                    if (!k.startsWith("flutter.")) continue
                    val key = k.removePrefix("flutter.")
                    when (v) {
                        is String -> edit.putString(key, v)
                        is Boolean -> edit.putBoolean(key, v)
                        is Int -> edit.putLong(key, v.toLong())
                        is Long -> edit.putLong(key, v)
                        is Float -> edit.putFloat(key, v)
                        is Double -> edit.putString(key, v.toString())
                    }
                }
                edit.putBoolean("_migrated", true).apply()
            } catch (_: Exception) {
            }
        }
    }

    fun string(key: String): String? = prefs.getString(key, null)
    fun bool(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    fun long(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

    fun put(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    fun put(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun put(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun remove(key: String) = prefs.edit().remove(key).apply()

    /** Every saved setting as text, for backups. */
    fun dump(): Map<String, Any?> = prefs.all.filterKeys { !it.startsWith("_") }

    fun putAll(values: Map<String, Any?>) {
        val e = prefs.edit()
        for ((k, v) in values) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Long -> e.putLong(k, v)
            is Int -> e.putLong(k, v.toLong())
        }
        e.apply()
    }
}
