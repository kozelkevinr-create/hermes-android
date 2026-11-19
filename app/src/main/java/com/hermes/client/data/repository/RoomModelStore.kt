package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.roomModelDataStore by preferencesDataStore(name = "room_models")

/** Encodes a (provider, model) pick as one string; NUL never occurs in either part. */
internal fun encodeRoomModel(provider: String, model: String): String = "$provider\u0000$model"

internal fun decodeRoomModel(value: String?): Pair<String, String>? {
    val parts = value?.split('\u0000') ?: return null
    return if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
}

/**
 * Device-local record of the model last chosen in each Matrix room. The bot applies `/model` as a
 * chat command, so the app cannot read a room's real model back; this remembers the last pick so
 * the picker can mark it "current" across app restarts.
 */
class RoomModelStore(private val context: Context) {
    private fun key(roomId: String) = stringPreferencesKey("room:$roomId")

    suspend fun get(roomId: String): Pair<String, String>? =
        decodeRoomModel(context.roomModelDataStore.data.map { it[key(roomId)] }.first())

    suspend fun set(roomId: String, provider: String, model: String) {
        context.roomModelDataStore.edit { it[key(roomId)] = encodeRoomModel(provider, model) }
    }
}
