package com.hermes.client.ui.webhooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebhooksScreen(onBack: () -> Unit, vm: WebhooksViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Webhooks",
                subtitle = if (state.globallyEnabled) "Enabled" else "Disabled globally",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.load() }) { Text("Retry") }
            }

            state.webhooks.isEmpty() -> Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (state.globallyEnabled) "No webhook routes configured"
                    else "The webhook platform is turned off",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.baseUrl.isNotBlank()) {
                    Text(
                        "Base URL: ${state.baseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.webhooks, key = { it.name }) { hook ->
                    ListItem(
                        headlineContent = { Text(hook.name) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    hook.description.ifBlank { null },
                                    hook.url.ifBlank { null },
                                    hook.events.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                ).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = hook.enabled,
                                onCheckedChange = { vm.toggle(hook.name, it) },
                            )
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
