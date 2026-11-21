package com.orbising.orbis

import org.junit.Assert.*
import org.junit.Test

class UnifiedChatCardTest {
    private fun rows(messages: List<ChatMessage>, anchors: Map<String, String> = emptyMap(), previous: List<ChatListItem> = emptyList()) =
        chatViewportItems(buildChatListItems(messages, anchors, previous)).filterIsInstance<ChatListItem.AssistantTurn>()
    private fun signature(rows: List<ChatListItem.AssistantTurn>) = rows.map { row -> row.messages.flatMap { message -> message.content.map { block ->
        if (block.type == "tool_call") "tool:${block.toolCallId}" else "${block.type}:${block.text}"
    } } }
    private fun text(id: String, value: String = id) = ChatMessage(id, "assistant", listOf(RemoteContent("text", value)), 0)

    // S31 / SC50: native turns and message packing stay one continuous assistant stream.
    @Test fun `all backends keep one continuous assistant stream regardless of packing`() {
        val steps = listOf(
            listOf(RemoteContent("thinking", "plan"), RemoteContent("text", "inspect"), RemoteContent("tool_call", toolCallId = "read-1", toolName = "read"), RemoteContent("tool_call", toolCallId = "bash-1", toolName = "bash")),
            listOf(RemoteContent("thinking", "review"), RemoteContent("text", "adjust"), RemoteContent("tool_call", toolCallId = "edit-1", toolName = "edit")),
            listOf(RemoteContent("text", "done")),
        )
        val expected = listOf(listOf("thinking:plan", "text:inspect", "tool:read-1", "tool:bash-1", "thinking:review", "text:adjust", "tool:edit-1", "text:done"))
        val perStep = steps.mapIndexed { index, blocks -> ChatMessage("step-$index", "assistant", blocks, 0) }
        val perBlock = steps.flatten().mapIndexed { index, block -> ChatMessage("block-$index", "assistant", listOf(block), 0) }
        val bundled = listOf(ChatMessage("bundled", "assistant", steps.flatten(), 0))
        for ((backend, messages) in listOf("pi" to perStep, "dsh" to bundled, "claude" to perBlock,
                "codex" to perBlock, "codexDesktop" to perStep)) {
            val anchors = messages.associate { it.messageId to "native-${it.messageId}" }
            assertEquals(backend, expected, signature(rows(messages, anchors)))
            assertEquals(backend, expected, signature(rows(messages)))
        }
        val result = rows(bundled)
        assertEquals(setOf("bundled"), result.single().completedMessageIds)
        assertEquals(listOf(2, 1), assistantCardContent(result.single().messages).items
            .filterIsInstance<AssistantCardItem.ToolGroup>().map { it.calls.size })
    }

    // A large parallel batch stays one collapsed group with bounded local paging, not dozens of frames.
    @Test fun `large tool batches and appended tools retain card identity`() {
        fun tool(index: Int) = RemoteContent("tool_call", toolCallId = "call-$index", toolName = "read")
        val initial = ChatMessage("live", "assistant", listOf(tool(0)), 0)
        val before = rows(listOf(initial))
        val packed = initial.copy(messageId = "persisted", content = (0 until 24).map(::tool))
        val after = rows(listOf(packed), previous = before)
        assertEquals(1, after.size)
        assertEquals(before.single().key, after.single().key)
        assertEquals(24, assistantCardContent(after.single().messages).items.filterIsInstance<AssistantCardItem.ToolGroup>().sumOf { it.calls.size })
        assertEquals(8, CHAT_TOOL_PAGE_SIZE)
    }

    @Test fun `content order survives packing and native boundary changes`() {
        val original = (0 until 9).map { text("m$it") }
        val before = rows(original)
        assertEquals(listOf(9), before.map { it.messages.size })
        val after = rows(listOf(text("older0"), text("older1")) + original, previous = before)
        assertEquals(listOf("older0"), after.map { it.key })
        assertEquals(signature(before), signature(rows(original, original.associate { it.messageId to "turn" })))
        val oneMessage = ChatMessage("packed", "assistant", original.flatMap { it.content }, 0)
        assertEquals(signature(before), signature(rows(listOf(oneMessage))))
    }
}
