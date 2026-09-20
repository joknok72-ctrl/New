package com.upscaler.ai.util

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Tiny persistence layer (SharedPreferences + JSON). No DB needed for this app. */
object Prefs {
    private const val FILE = "upscaler_prefs"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 30

    data class HistoryItem(
        val outputUri: Uri,
        val name: String,
        val outputRes: String,
        val elapsedSec: Long,
        val sizeBytes: Long,
        val timestamp: Long,
        val preset: String,
        val model: String,
    )

    fun saveSettings(ctx: Context, json: JSONObject) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SETTINGS, json.toString()).apply()
    }

    fun loadSettings(ctx: Context): JSONObject? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SETTINGS, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun addHistory(ctx: Context, item: HistoryItem) {
        val list = loadHistory(ctx).toMutableList()
        list.add(0, item)
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(JSONObject().apply {
                put("uri", h.outputUri.toString()); put("name", h.name); put("res", h.outputRes)
                put("el", h.elapsedSec); put("size", h.sizeBytes); put("ts", h.timestamp)
                put("preset", h.preset); put("model", h.model)
            })
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun loadHistory(ctx: Context): List<HistoryItem> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(Uri.parse(o.getString("uri")), o.getString("name"), o.getString("res"),
                    o.getLong("el"), o.getLong("size"), o.getLong("ts"), o.optString("preset"), o.optString("model"))
            }
        }.getOrDefault(emptyList())
    }

    fun clearHistory(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }
}
