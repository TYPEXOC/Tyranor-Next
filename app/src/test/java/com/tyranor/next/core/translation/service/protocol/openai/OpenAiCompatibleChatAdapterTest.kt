package com.tyranor.next.core.translation.service.protocol.openai

import com.tyranor.next.core.translation.service.TranslationInput
import com.tyranor.next.core.translation.service.ProofreadInput
import com.tyranor.next.core.translation.service.TranslationItemFailureCode
import com.tyranor.next.core.translation.service.TranslationServiceErrorCode
import com.tyranor.next.core.translation.service.TranslationServiceException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleChatAdapterTest {
    @Test
    fun parsesStableIdsAndCommitsValidItemsWhenOtherItemsFail() {
        val inputs = listOf(
            input("entry-1", """\V[2] Hello %s
"""),
            input("entry-2", "Missing"),
            input("entry-3", "Duplicate"),
        )
        val results = JSONArray()
            .put(result("entry-1", """\V[2] こんにちは %s
"""))
            .put(result("entry-3", "First"))
            .put(result("entry-3", "Second"))
            .put(result("unknown-id", "Ignored"))
        val parsed = OpenAiCompatibleChatAdapter.parseBatchContent(
            JSONObject().put("results", results).toString(),
            inputs,
        )

        assertEquals(listOf("entry-1"), parsed.successes.map { it.entryId })
        assertEquals(TranslationItemFailureCode.MISSING_RESPONSE, parsed.itemFailures["entry-2"])
        assertEquals(TranslationItemFailureCode.DUPLICATE_RESPONSE, parsed.itemFailures["entry-3"])
        assertEquals(setOf("unknown-id"), parsed.unknownResponseIds)
    }

    @Test
    fun rejectsControlCodePlaceholderAndNewlineDamagePerEntry() {
        val inputs = listOf(
            input("control", """\C[3] Red"""),
            input("placeholder", "Hello {player}"),
            input("newline", "Line one\nLine two"),
        )
        val parsed = OpenAiCompatibleChatAdapter.parseBatchContent(
            JSONObject().put(
                "results",
                JSONArray()
                    .put(result("control", "赤"))
                    .put(result("placeholder", "こんにちは"))
                    .put(result("newline", "一行目 二行目")),
            ).toString(),
            inputs,
        )

        assertTrue(parsed.successes.isEmpty())
        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["control"])
        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["placeholder"])
        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["newline"])
    }

    @Test
    fun invalidJsonResponseFailsWithTypedError() {
        val failure = assertThrows(TranslationServiceException::class.java) {
            OpenAiCompatibleChatAdapter.parseBatchContent("not-json", listOf(input("entry", "source")))
        }
        assertEquals(TranslationServiceErrorCode.INVALID_RESPONSE, failure.code)
    }

    @Test
    fun parsesProofreadSuggestionsByStableIdWithoutOverwritingValidItems() {
        val inputs = listOf(
            ProofreadInput("entry-1", "\\V[1] Hello", "\\V[1] 你好"),
            ProofreadInput("entry-2", "Hello {player}", "你好"),
        )
        val parsed = OpenAiCompatibleChatAdapter.parseProofreadContent(
            JSONObject().put(
                "results",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "entry-1")
                            .put("suggestedTranslation", "\\V[1] 您好")
                            .put("issues", JSONArray().put("TONE")),
                    )
                    .put(
                        JSONObject()
                            .put("id", "entry-2")
                            .put("suggestedTranslation", "玩家您好 {player}")
                            .put("issues", JSONArray()),
                    ),
            ).toString(),
            inputs,
        )

        assertEquals(listOf("entry-1", "entry-2"), parsed.successes.map { it.entryId })
        assertEquals(setOf("TONE"), parsed.successes.first().issueCodes)
        assertEquals(emptyMap<String, com.tyranor.next.core.translation.service.TranslationItemFailureCode>(), parsed.itemFailures)
    }

    @Test
    fun rejectsProofreadSuggestionThatDamagesFormatTokens() {
        val parsed = OpenAiCompatibleChatAdapter.parseProofreadContent(
            JSONObject().put(
                "results",
                JSONArray().put(
                    JSONObject()
                        .put("id", "entry")
                        .put("suggestedTranslation", "こんにちは")
                        .put("issues", JSONArray()),
                ),
            ).toString(),
            listOf(ProofreadInput("entry", "\\C[3] Red", "\\C[3] 赤")),
        )

        assertEquals(TranslationItemFailureCode.PLACEHOLDER_MISMATCH, parsed.itemFailures["entry"])
        assertTrue(parsed.successes.isEmpty())
    }

    private fun input(id: String, text: String) = TranslationInput(id, text, "ja", "zh-Hans")
    private fun result(id: String, translated: String) =
        JSONObject().put("id", id).put("translation", translated)
}
