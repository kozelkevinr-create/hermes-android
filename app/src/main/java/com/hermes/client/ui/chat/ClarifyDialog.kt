package com.hermes.client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.ClarifyQuestion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** What the user has entered for one question: ticked [picked] choices and the free-text "Other" row. */
data class ClarifyDraft(
    val picked: Set<String> = emptySet(),
    val otherOn: Boolean = false,
    val other: String = "",
)

/**
 * The wire answer for [q]: a string, an array of strings (multi-select) or null when nothing was entered
 * (the agent sees that as skipped). An open-ended question answers from its text box alone.
 */
fun answerFor(q: ClarifyQuestion, d: ClarifyDraft): JsonElement {
    val typed = d.other.trim()
    if (q.choices.isEmpty()) return if (typed.isEmpty()) JsonNull else JsonPrimitive(typed)
    val picks = q.choices.filter { it in d.picked } + listOfNotNull(typed.takeIf { d.otherOn && it.isNotEmpty() })
    return when {
        picks.isEmpty() -> JsonNull
        q.multiSelect -> JsonArray(picks.map { JsonPrimitive(it) })
        else -> JsonPrimitive(picks.last())
    }
}

/** Answers for every question by qid; unanswered ones are null so the agent knows they were skipped. */
fun clarifyAnswers(questions: List<ClarifyQuestion>, drafts: Map<String, ClarifyDraft>): Map<String, JsonElement> =
    questions.associate { it.qid to answerFor(it, drafts[it.qid] ?: ClarifyDraft()) }

/** One form for all of a clarify's questions: tappable choices, multi-select, an "Other" row, free text. */
@Composable
fun ClarifyDialog(
    questions: List<ClarifyQuestion>,
    onSend: (Map<String, JsonElement>) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Close",
) {
    var drafts by remember(questions) { mutableStateOf(emptyMap<String, ClarifyDraft>()) }
    fun update(qid: String, f: (ClarifyDraft) -> ClarifyDraft) {
        drafts = drafts + (qid to f(drafts[qid] ?: ClarifyDraft()))
    }
    val answers = clarifyAnswers(questions, drafts)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (questions.size > 1) "Clarification (${questions.size} questions)" else "Clarification") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                questions.forEachIndexed { i, q ->
                    val d = drafts[q.qid] ?: ClarifyDraft()
                    Text(
                        q.question,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 16.dp),
                    )
                    if (q.multiSelect) {
                        Text("Pick any", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    q.choices.forEach { c ->
                        val on = c in d.picked
                        ChoiceRow(c, on, q.multiSelect) {
                            update(q.qid) {
                                when {
                                    q.multiSelect -> it.copy(picked = if (on) it.picked - c else it.picked + c)
                                    else -> it.copy(picked = setOf(c), otherOn = false)
                                }
                            }
                        }
                    }
                    if (q.choices.isNotEmpty()) {
                        ChoiceRow("Other (type your answer)", d.otherOn, q.multiSelect) {
                            update(q.qid) {
                                when {
                                    q.multiSelect -> it.copy(otherOn = !it.otherOn)
                                    else -> it.copy(otherOn = true, picked = emptySet())
                                }
                            }
                        }
                    }
                    if (q.choices.isEmpty() || d.otherOn) {
                        OutlinedTextField(
                            value = d.other,
                            onValueChange = { v -> update(q.qid) { it.copy(other = v) } },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = answers.values.any { it !is JsonNull }, onClick = { onSend(answers) }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        if (multi) Checkbox(checked = selected, onCheckedChange = { onClick() })
        else RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
