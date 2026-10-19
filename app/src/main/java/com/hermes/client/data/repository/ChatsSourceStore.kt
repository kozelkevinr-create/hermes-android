package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.ui.matrix.ChatsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.chatsSourceDataStore by preferencesDataStore(name = "chats_source")

/** Device-local Chats source (Matrix | Gateway). Emits null until the user has picked one. */
class ChatsSourceStore(private val context: Context) {
    private val key = stringPreferencesKey("source")

    val saved: Flow<ChatsSource?> = context.chatsSourceDataStore.data
        // A corrupt/unreadable DataStore throws IOException on read; fall back to "not chosen".
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[key]?.let { name -> runCatching { ChatsSource.valueOf(name) }.getOrNull() } }

    suspend fun set(source: ChatsSource) {
        context.chatsSourceDataStore.edit { it[key] = source.name }
    }
}
