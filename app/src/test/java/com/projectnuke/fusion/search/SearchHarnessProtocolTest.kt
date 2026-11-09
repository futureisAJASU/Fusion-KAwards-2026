package com.projectnuke.fusion.search

import com.projectnuke.fusion.ai.model.AiProviderType
import com.projectnuke.fusion.ai.model.AiToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHarnessProtocolTest {
    @Test
    fun structuredIntentAcceptsBoundedSearchAndTerminalActions() {
        assertEquals(
            SearchToolAction.Search(listOf("삼성전자 주가 현재")),
            SearchHarnessProtocol.parseStructuredIntent("""{"action":"SEARCH","queries":["삼성전자 주가 현재"]}""").action,
        )
        assertEquals(
            SearchToolAction.NoMoreSearch,
            SearchHarnessProtocol.parseStructuredIntent("""{"action":"NO_MORE_SEARCH"}""").action,
        )
        assertEquals(
            SearchToolAction.Final("정리"),
            SearchHarnessProtocol.parseStructuredIntent("""{"action":"FINAL","answer":"정리"}""").action,
        )
    }

    @Test
    fun malformedEmptyTruncatedAndOversizedStructuredOutputsFailClosed() {
        assertEquals("malformed_json", SearchHarnessProtocol.parseStructuredIntent("not json").errorCode)
        assertEquals("empty", SearchHarnessProtocol.parseStructuredIntent(" ").errorCode)
        assertEquals("truncated", SearchHarnessProtocol.parseStructuredIntent("{}", truncated = true).errorCode)
        assertEquals(
            "oversized",
            SearchHarnessProtocol.parseStructuredIntent("x".repeat(SearchHarnessProtocol.MAX_ACTION_CHARS + 1)).errorCode,
        )
    }

    @Test
    fun unknownArgumentsUnsupportedActionsAndInvalidQueryShapesAreRejected() {
        val rejected = listOf(
            """{"action":"SEARCH","queries":["safe"],"shell":"ignored"}""",
            """{"action":"RUN_COMMAND"}""",
            """{'action':'SEARCH','queries':['permissive json']}""",
            """{"action":"SEARCH","queries":["trailing",]}""",
            """{"action":"SEARCH","queries":["safe"]} trailing""",
            """{"action":"SEARCH","queries":[]}""",
            """{"action":"SEARCH","queries":["one","two","three"]}""",
            """{"action":"SEARCH","queries":[12]}""",
            """{"action":"SEARCH","queries":["\u0000"]}""",
            """{"action":"NO_MORE_SEARCH","answer":"unexpected"}""",
        )
        rejected.forEach { raw ->
            assertEquals(raw, SearchToolAction.InvalidToolCall, SearchHarnessProtocol.parseStructuredIntent(raw).action)
        }
        assertEquals(
            SearchToolAction.Unsupported,
            SearchHarnessProtocol.parseStructuredIntent("""{"action":"UNSUPPORTED"}""").action,
        )
    }

    @Test
    fun nativeToolCallsRequireKnownToolValidIdAndExactArguments() {
        val valid = SearchHarnessProtocol.parseNativeToolCall(
            AiToolCall("call-1", "web_search", """{"queries":["public query"]}"""),
        )
        assertEquals(SearchToolAction.Search(listOf("public query")), valid.action)
        assertEquals("missing_tool_call_id", SearchHarnessProtocol.parseNativeToolCall(
            AiToolCall("", "web_search", """{"queries":["q"]}"""),
        ).errorCode)
        assertEquals("unsupported_tool", SearchHarnessProtocol.parseNativeToolCall(
            AiToolCall("call-2", "shell", "{}"),
        ).errorCode)
        assertEquals("unknown_arguments", SearchHarnessProtocol.parseNativeToolCall(
            AiToolCall("call-3", "web_search", """{"queries":["q"],"path":"/private"}"""),
        ).errorCode)
        assertEquals("malformed_arguments", SearchHarnessProtocol.parseNativeToolCall(
            AiToolCall("call-4", "web_search", "{"),
        ).errorCode)
    }

    @Test
    fun providerNativeSupportIsExplicitAndConservative() {
        assertTrue(ExternalSearchToolCallingSupport.supports(
            AiProviderType.OPENAI,
            "https://api.openai.com/v1",
        ))
        assertFalse(ExternalSearchToolCallingSupport.supports(
            AiProviderType.CUSTOM_OPENAI_COMPATIBLE,
            "https://api.openai.com/v1",
        ))
        assertFalse(ExternalSearchToolCallingSupport.supports(
            AiProviderType.NVIDIA_NIM,
            "https://integrate.api.nvidia.com/v1",
        ))
        assertFalse(ExternalSearchToolCallingSupport.supports(
            AiProviderType.OPENAI,
            "https://proxy.example.com/v1",
        ))
        assertFalse(ExternalSearchToolCallingSupport.supports(
            AiProviderType.OPENAI,
            "http://api.openai.com/v1",
        ))
    }
}
