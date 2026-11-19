package com.example

import com.example.agent.tools.ToolCallRecoveryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallRecoveryParserTest {

    @Test
    fun recoversExactLiteRtStatusCode3WebSearchError() {
        val rawErr = """
            Status Code: 3. Message: Failed to parse tool calls from code block:
            call:web_search{query:"popolazione Cardito (na)"}
            full response: <|tool_call>call:web_search{query:"popolazione Cardito (na)"}<tool_call|>
            error: Failed to parse FC tool calls:
            FailThroughParseCancelledError(NoAltError(NoViableAltError(base: BaseRecognitionError(message: "", offending_token: GenericToken(token_type: 13, text: "popolazione")))))
        """.trimIndent()

        assertTrue(ToolCallRecoveryParser.isToolParseFailure(RuntimeException(rawErr)))
        val calls = ToolCallRecoveryParser.extractToolCallsFromError(rawErr)
        assertEquals(1, calls.size)
        assertEquals("web_search", calls[0].name)
        assertEquals("popolazione Cardito (na)", calls[0].args["query"])
    }

    @Test
    fun parsesGemmaQuotesAndMultipleParameters() {
        val text = """
            <|tool_call>call:default_api:save_memory{category:<|"|>USER_PROFILE<|"|>, keyName:<|"|>citta<|"|>, content:<|"|>Cardito (NA), Italia<|"|>, importance:4}<tool_call|>
            <|tool_call>call:launch_app{appName:"YouTube"}<tool_call|>
        """.trimIndent()

        val calls = ToolCallRecoveryParser.extractToolCallsFromText(text)
        assertEquals(2, calls.size)
        assertEquals("save_memory", calls[0].name)
        assertEquals("USER_PROFILE", calls[0].args["category"])
        assertEquals("citta", calls[0].args["keyName"])
        assertEquals("Cardito (NA), Italia", calls[0].args["content"])
        assertEquals(4L, calls[0].args["importance"])

        assertEquals("launch_app", calls[1].name)
        assertEquals("YouTube", calls[1].args["appName"])
    }

    @Test
    fun stripsRawToolCallMarkupFromVisibleText() {
        val dirty = "Cerco subito.<|tool_call>call:web_search{query:\"Cardito\"}<tool_call|> Fatto!"
        assertEquals("Cerco subito. Fatto!", ToolCallRecoveryParser.stripRawToolCallMarkup(dirty))
    }
}
