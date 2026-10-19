package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixState

/** Which list the Chats tab shows: Matrix rooms, or the gateway's sessions. */
enum class ChatsSource { MATRIX, GATEWAY }

/**
 * The saved choice wins. With nothing saved yet, Matrix is the default only when a Matrix session
 * exists on this device.
 */
fun resolveChatsSource(saved: ChatsSource?, matrixHasSession: Boolean): ChatsSource =
    saved ?: if (matrixHasSession) ChatsSource.MATRIX else ChatsSource.GATEWAY

/** True in the states where a Matrix account is saved on this device (a restore may still bring it up). */
fun matrixHasSavedSession(state: MatrixState): Boolean = when (state) {
    is MatrixState.Ready, MatrixState.Restoring, is MatrixState.RestoreFailed -> true
    MatrixState.LoggedOut, MatrixState.LoggingIn, is MatrixState.Error -> false
}
