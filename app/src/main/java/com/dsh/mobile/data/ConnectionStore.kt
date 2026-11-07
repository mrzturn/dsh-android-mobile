package com.dsh.mobile.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Persisted list of connections in one SharedPreferences JSON blob
 * (excluded from cloud backup via backup_rules.xml).
 */
class ConnectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("connections", Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow<List<Connection>>(load())
    val connections: StateFlow<List<Connection>> = _connections

    private fun load(): List<Connection> =
        prefs.getString(KEY, null)?.let { raw ->
            runCatching { AppJson.decodeFromString<List<Connection>>(raw) }.getOrNull()
        } ?: emptyList()

    private fun save(list: List<Connection>) {
        prefs.edit().putString(KEY, AppJson.encodeToString(list)).apply()
        _connections.value = list
    }

    /** Insert or update by origin (one row per host origin), bumping lastUsedAt. */
    fun upsert(baseUrl: String, label: String? = null, kind: String = "lan"): Connection {
        val now = System.currentTimeMillis()
        val base = baseUrl.trimEnd('/')
        val list = _connections.value.toMutableList()
        val existing = list.indexOfFirst { it.baseUrl == base }
        val conn: Connection
        if (existing >= 0) {
            val old = list[existing]
            conn = old.copy(
                label = label?.takeIf { it.isNotBlank() } ?: old.label,
                kind = kind,
                lastUsedAt = now,
            )
            list[existing] = conn
        } else {
            conn = Connection(
                id = UUID.randomUUID().toString(),
                label = label?.takeIf { it.isNotBlank() } ?: defaultLabel(base),
                baseUrl = base,
                kind = kind,
                createdAt = now,
                lastUsedAt = now,
            )
            list.add(0, conn)
        }
        save(list.sortedByDescending { it.lastUsedAt })
        return conn
    }

    fun forget(id: String) {
        save(_connections.value.filterNot { it.id == id })
    }

    fun rename(id: String, label: String) {
        save(_connections.value.map { if (it.id == id) it.copy(label = label) else it })
    }

    fun mostRecent(): Connection? = _connections.value.maxByOrNull { it.lastUsedAt }

    private fun defaultLabel(base: String): String =
        if (base.startsWith("https://")) "Tunnel · " + base.removePrefix("https://").substringBefore('/')
        else "LAN · " + base.removePrefix("http://").substringBefore('/')

    companion object { private const val KEY = "connections_json" }
}
