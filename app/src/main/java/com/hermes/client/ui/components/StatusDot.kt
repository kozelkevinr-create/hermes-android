package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.ConnectionState
import com.hermes.client.ui.theme.Neon

fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Reconnecting -> "Reconnecting…"
    ConnectionState.Disconnected -> "Offline"
    is ConnectionState.Error -> "Error: ${state.reason}"
}

/** Friendlier, sentence-form copy for the chat offline/error banner (vs the terse [connectionLabel]). */
fun bannerLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Disconnected -> "You're offline — new messages send when you reconnect."
    is ConnectionState.Error -> "Connection error — tap Retry."
    else -> connectionLabel(state)
}

@Composable
fun StatusDot(state: ConnectionState, modifier: Modifier = Modifier, showLabel: Boolean = false) {
    val color = when (state) {
        ConnectionState.Connected -> Neon.Phosphor
        ConnectionState.Connecting, ConnectionState.Reconnecting -> Neon.Amber
        else -> Neon.Red
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, LocalContentColor.current, CircleShape),
        )
        if (showLabel) {
            Text(
                text = connectionLabel(state),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
