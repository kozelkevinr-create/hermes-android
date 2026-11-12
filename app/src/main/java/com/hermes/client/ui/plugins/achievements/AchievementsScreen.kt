package com.hermes.client.ui.plugins.achievements

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AchievementsUiState(
    val snapshot: AchievementsSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class AchievementsViewModel @Inject constructor(
    private val api: AchievementsApi,
) : ViewModel() {
    private val _state = MutableStateFlow(AchievementsUiState())
    val state: StateFlow<AchievementsUiState> = _state.asStateFlow()

    init { load() }

    /** Load, and while the server reports a running scan poll a few times (first run is slow). */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            var attempts = 0
            while (true) {
                val result = runCatching { api.load() }
                result.onFailure {
                    _state.value = AchievementsUiState(loading = false, error = it.message ?: "Failed to load")
                    return@launch
                }
                val snap = result.getOrThrow()
                _state.value = AchievementsUiState(snapshot = snap, loading = false)
                if (!snap.scanning || ++attempts >= 10) return@launch
                delay(3_000)
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { api.rescan() }.fold(
                { _state.value = AchievementsUiState(snapshot = it, loading = false) },
                { _state.value = _state.value.copy(loading = false, error = it.message ?: "Rescan failed") },
            )
        }
    }
}

private fun tierColor(tier: String?): Color = when (tier) {
    "Copper" -> Color(0xFFB87333)
    "Silver" -> Color(0xFFB0B7C3)
    "Gold" -> Color(0xFFE6B422)
    "Diamond" -> Color(0xFF4FC3F7)
    "Olympian" -> Color(0xFFC77DFF)
    else -> Color(0xFF6B7280)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    onBack: () -> Unit,
    vm: AchievementsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var category by remember { mutableStateOf<String?>(null) }
    var showLocked by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Achievements",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.rescan() }, enabled = !state.loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val snap = state.snapshot
            when {
                snap == null && state.loading -> com.hermes.client.ui.components.LoadingState()
                snap == null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error ?: "Failed to load", onRetry = { vm.load() },
                )
                else -> {
                    val categories = snap.achievements.map { it.category }.distinct().sorted()
                    val shown = snap.achievements
                        .filter { category == null || it.category == category }
                        .filter { showLocked || it.unlocked }
                        .sortedWith(
                            compareByDescending<Achievement> { it.unlocked }
                                .thenByDescending { TIER_ORDER.indexOf(it.tier) }
                                .thenByDescending { it.progressPct }
                                .thenBy { it.name },
                        )
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "${snap.unlockedCount} / ${snap.totalCount} unlocked",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        if (snap.totalCount == 0) 0f
                                        else snap.unlockedCount.toFloat() / snap.totalCount
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                if (snap.scanning || snap.isStale || state.loading) {
                                    Text(
                                        if (snap.scanning || state.loading) "Scanning sessions…" else "Results may be out of date",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                                state.error?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
                                }
                                items(categories) { c ->
                                    FilterChip(
                                        selected = category == c,
                                        onClick = { category = if (category == c) null else c },
                                        label = { Text(c) },
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = !showLocked,
                                        onClick = { showLocked = !showLocked },
                                        label = { Text("Unlocked only") },
                                    )
                                }
                            }
                        }
                        items(shown, key = { it.id }) { a ->
                            AchievementRow(a)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementRow(a: Achievement) {
    var expanded by remember { mutableStateOf(false) }
    val color = if (a.unlocked) tierColor(a.tier) else MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).background(color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Star, contentDescription = null, tint = color) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (a.unlocked && a.tier != null) {
                    Text(a.tier, color = color, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(a.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(a.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (a.nextTier != null || !a.unlocked) {
                LinearProgressIndicator(
                    progress = { a.progressPct / 100f },
                    color = color,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    buildString {
                        append("${a.progressPct}%")
                        if (a.nextTier != null) append(" to ${a.nextTier}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (expanded && a.criteria.isNotBlank()) {
                Text(a.criteria, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
