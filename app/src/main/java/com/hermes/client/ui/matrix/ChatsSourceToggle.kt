package com.hermes.client.ui.matrix

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.LocalProfileAccent

/** Segmented Matrix | Gateway switch shown at the top of the Chats tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsSourceToggle(selected: ChatsSource, onSelect: (ChatsSource) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalProfileAccent.current
    val options = listOf(ChatsSource.MATRIX to "Matrix", ChatsSource.GATEWAY to "Gateway")
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        options.forEachIndexed { i, (source, label) ->
            SegmentedButton(
                selected = selected == source,
                onClick = { onSelect(source) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = accent.accent,
                    activeContentColor = accent.onAccent,
                ),
            ) { Text(label) }
        }
    }
}
