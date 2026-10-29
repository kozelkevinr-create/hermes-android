package com.hermes.client.ui.chat

import com.hermes.client.data.network.ClarifyQuestion
import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.clarifyQuestions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ClarifyAnswersTest {
    private val single = ClarifyQuestion("q0", "Which repo?", listOf("a (Recommended)", "b"))
    private val multi = ClarifyQuestion("q1", "Which envs?", listOf("dev", "prod"), multiSelect = true)
    private val open = ClarifyQuestion("q2", "Anything else?")

    @Test fun single_select_answers_with_the_picked_choice() {
        assertEquals(JsonPrimitive("b"), answerFor(single, ClarifyDraft(picked = setOf("b"))))
    }

    @Test fun single_select_other_uses_the_typed_text() {
        assertEquals(JsonPrimitive("c"), answerFor(single, ClarifyDraft(otherOn = true, other = " c ")))
    }

    @Test fun multi_select_answers_with_an_array_in_choice_order_plus_other() {
        val a = answerFor(multi, ClarifyDraft(picked = setOf("prod", "dev"), otherOn = true, other = "qa"))
        assertEquals(JsonArray(listOf("dev", "prod", "qa").map { JsonPrimitive(it) }), a)
    }

    @Test fun untouched_questions_are_skipped_with_null() {
        val all = clarifyAnswers(listOf(single, multi, open), mapOf("q2" to ClarifyDraft(other = "no")))
        assertEquals(JsonNull, all["q0"])
        assertEquals(JsonNull, all["q1"])
        assertEquals(JsonPrimitive("no"), all["q2"])
    }

    @Test fun parses_every_question_with_choices_and_multi_select() {
        val payload = Json.parseToJsonElement(
            """{"questions":[{"qid":"q0","question":"A?","choices":["x","y"],"multi_select":true},{"qid":"q1","question":"B?"}]}""",
        ) as JsonObject
        val qs = ServerEvent("clarify.request", "s1", payload).clarifyQuestions()
        assertEquals(listOf(ClarifyQuestion("q0", "A?", listOf("x", "y"), true), ClarifyQuestion("q1", "B?")), qs)
    }

    @Test fun falls_back_to_the_single_question_field() {
        val payload = Json.parseToJsonElement("""{"question":"Which?","qid":"q7"}""") as JsonObject
        assertEquals(listOf(ClarifyQuestion("q7", "Which?")), ServerEvent("clarify.request", "s1", payload).clarifyQuestions())
    }
}
