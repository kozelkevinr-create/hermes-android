package com.hermes.client.ui.matrix

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixRoomDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The room-management calls the settings screen needs; the manager implements them in the app. */
interface RoomAdminApi {
    suspend fun details(roomId: String): MatrixRoomDetails?
    suspend fun rename(roomId: String, name: String): Boolean
    suspend fun setTopic(roomId: String, topic: String): Boolean
    suspend fun invite(roomId: String, userId: String): Boolean
    suspend fun remove(roomId: String, userId: String): Boolean
    suspend fun leave(roomId: String): Boolean
    suspend fun setFavourite(roomId: String, favourite: Boolean): Boolean
    suspend fun setMarkedUnread(roomId: String, unread: Boolean): Boolean
}

private class ManagerRoomAdmin(private val manager: MatrixClientManager) : RoomAdminApi {
    override suspend fun details(roomId: String) = manager.roomDetails(roomId)
    override suspend fun rename(roomId: String, name: String) = manager.renameRoom(roomId, name)
    override suspend fun setTopic(roomId: String, topic: String) = manager.setRoomTopic(roomId, topic)
    override suspend fun invite(roomId: String, userId: String) = manager.inviteToRoom(roomId, userId)
    override suspend fun remove(roomId: String, userId: String) = manager.removeFromRoom(roomId, userId)
    override suspend fun leave(roomId: String) = manager.leaveRoom(roomId)
    override suspend fun setFavourite(roomId: String, favourite: Boolean) = manager.setRoomFavourite(roomId, favourite)
    override suspend fun setMarkedUnread(roomId: String, unread: Boolean) = manager.setRoomMarkedUnread(roomId, unread)
}

/** What the room settings screen shows. */
sealed interface RoomSettingsUi {
    data object Loading : RoomSettingsUi

    /** The room is unknown, or there is no live session. */
    data object Unavailable : RoomSettingsUi

    /** [notice] is a one-line result of the last action (an error, or "Invite sent"); [left] closes the screen. */
    data class Ready(
        val details: MatrixRoomDetails,
        val busy: Boolean = false,
        val notice: String? = null,
        val left: Boolean = false,
    ) : RoomSettingsUi
}

/**
 * Room name, topic, members, invites, leaving, favourite and unread flags for one room. Every
 * action reloads the details afterwards so the screen shows what the server accepted.
 */
@HiltViewModel
class MatrixRoomSettingsViewModel internal constructor(
    private val roomId: String,
    private val admin: RoomAdminApi,
) : ViewModel() {
    @Inject constructor(manager: MatrixClientManager, savedStateHandle: SavedStateHandle) : this(
        roomId = savedStateHandle.get<String>("roomId") ?: "",
        admin = ManagerRoomAdmin(manager),
    )

    private val _ui = MutableStateFlow<RoomSettingsUi>(RoomSettingsUi.Loading)
    val ui: StateFlow<RoomSettingsUi> = _ui.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val details = admin.details(roomId)
            _ui.value = if (details == null) RoomSettingsUi.Unavailable else RoomSettingsUi.Ready(details)
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return notice("Give the room a name.")
        act("Could not rename the room.", "Name saved.") { admin.rename(roomId, trimmed) }
    }

    fun setTopic(topic: String) {
        act("Could not change the topic.", "Topic saved.") { admin.setTopic(roomId, topic.trim()) }
    }

    /** Invites one person by Matrix ID; the shape is checked before anything is sent. */
    fun invite(mxid: String) {
        val id = mxid.trim()
        if (!mxidIsValid(id)) return notice("Enter a Matrix ID like @name:server.")
        act("Could not send the invite.", "Invite sent.") { admin.invite(roomId, id) }
    }

    fun remove(userId: String) {
        act("Could not remove that member.", "Member removed.") { admin.remove(roomId, userId) }
    }

    fun setFavourite(favourite: Boolean) {
        act("Could not change the favourite.", null) { admin.setFavourite(roomId, favourite) }
    }

    fun setMarkedUnread(unread: Boolean) {
        act("Could not change the unread flag.", null) { admin.setMarkedUnread(roomId, unread) }
    }

    fun leave() {
        val current = _ui.value as? RoomSettingsUi.Ready ?: return
        _ui.value = current.copy(busy = true, notice = null)
        viewModelScope.launch {
            _ui.value = if (admin.leave(roomId)) {
                current.copy(busy = false, left = true)
            } else {
                current.copy(busy = false, notice = "Could not leave the room.")
            }
        }
    }

    fun dismissNotice() {
        val current = _ui.value as? RoomSettingsUi.Ready ?: return
        _ui.value = current.copy(notice = null)
    }

    private fun notice(text: String) {
        val current = _ui.value as? RoomSettingsUi.Ready ?: return
        _ui.value = current.copy(notice = text)
    }

    /** Runs [call] with the screen marked busy, then reloads; [done] is the success notice, if any. */
    private fun act(failure: String, done: String?, call: suspend () -> Boolean) {
        val current = _ui.value as? RoomSettingsUi.Ready ?: return
        if (current.busy) return
        _ui.value = current.copy(busy = true, notice = null)
        viewModelScope.launch {
            val ok = call()
            val fresh = admin.details(roomId)
            _ui.value = RoomSettingsUi.Ready(
                details = fresh ?: current.details,
                notice = if (ok) done else failure,
            )
        }
    }
}
