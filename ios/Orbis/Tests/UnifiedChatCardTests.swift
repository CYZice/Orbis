import Foundation
import Testing
@testable import OrbisCore

struct UnifiedChatCardTests {
    private func signature(_ rows: [ChatRow]) -> [[String]] {
        rows.map { $0.messages.flatMap { $0.content.map { block in
            block.type == "tool_call" ? "tool:\(block.toolCallId ?? "")" : "\(block.type):\(block.text ?? "")"
        } } }
    }
    private func text(_ id: String) -> ChatMessage {
        ChatMessage(messageId: id, role: "assistant", content: [ChatContent(type: "text", text: id, toolName: nil, artifact: nil)], timestamp: 0, isError: nil)
    }

    // S31 / SC50: native turns and message packing stay one continuous assistant stream.
    @Test func allBackendsKeepOneContinuousAssistantStream() {
        let steps: [[ChatContent]] = [
            [ChatContent(type: "thinking", text: "plan", toolName: nil, artifact: nil), ChatContent(type: "text", text: "inspect", toolName: nil, artifact: nil), ChatContent(type: "tool_call", text: nil, toolName: "read", artifact: nil, toolCallId: "read-1"), ChatContent(type: "tool_call", text: nil, toolName: "bash", artifact: nil, toolCallId: "bash-1")],
            [ChatContent(type: "thinking", text: "review", toolName: nil, artifact: nil), ChatContent(type: "text", text: "adjust", toolName: nil, artifact: nil), ChatContent(type: "tool_call", text: nil, toolName: "edit", artifact: nil, toolCallId: "edit-1")],
            [ChatContent(type: "text", text: "done", toolName: nil, artifact: nil)],
        ]
        let expected = [["thinking:plan", "text:inspect", "tool:read-1", "tool:bash-1", "thinking:review", "text:adjust", "tool:edit-1", "text:done"]]
        func message(_ id: String, _ content: [ChatContent]) -> ChatMessage {
            ChatMessage(messageId: id, role: "assistant", content: content, timestamp: 0, isError: nil)
        }
        let perStep = steps.enumerated().map { message("step-\($0.offset)", $0.element) }
        let perBlock = steps.flatMap { $0 }.enumerated().map { message("block-\($0.offset)", [$0.element]) }
        let bundled = [message("bundled", steps.flatMap { $0 })]
        for (backend, messages) in [("pi", perStep), ("dsh", bundled), ("claude", perBlock), ("codex", perBlock), ("codexDesktop", perStep)] {
            let anchors = Dictionary(uniqueKeysWithValues: messages.map { ($0.messageId, "native-" + $0.messageId) })
            #expect(signature(chatRows(messages, turnIdsByMessageId: anchors)) == expected, "\(backend)")
            #expect(signature(chatRows(messages)) == expected, "\(backend)")
        }
        let rows = chatRows(bundled)
        #expect(rows.map(\.completedIds) == [[], [], ["bundled"]])
        #expect(assistantCardContent(rows[0].messages).tools.count == 2)
        #expect(assistantCardContent(rows[1].messages).tools.count == 1)
        let timing = SessionTurnTiming(turnId: "outer", startedAt: 0, durationMs: 100, turnIndex: nil, messageId: "bundled")
        let transcript = ChatTranscript(messages: bundled, turnTimings: ["outer": timing])
        #expect(transcript.timingsByRow.keys.sorted() == [rows[2].id])
        #expect(transcript.changeAnchorsByRow[rows[0].id]?.isEmpty == true)
        #expect(transcript.changeAnchorsByRow[rows[2].id] == ["bundled"])
    }

    // Parallel calls stay collapsed/paged and use their stable native tool ID through persistence.
    @Test func parallelToolsRetainIdentityAndUseBoundedPages() {
        func tool(_ index: Int) -> ChatContent { ChatContent(type: "tool_call", text: nil, toolName: "read", artifact: nil, toolCallId: "call-\(index)") }
        let before = chatRows([ChatMessage(messageId: "live", role: "assistant", content: [tool(0)], timestamp: 0, isError: nil)])
        let after = chatRows([ChatMessage(messageId: "stored", role: "assistant", content: (0..<24).map(tool), timestamp: 0, isError: nil)], previous: before)
        #expect(after.count == 1 && after[0].id == before[0].id)
        #expect(assistantCardContent(after[0].messages).tools.count == 24)
        #expect(chatToolPageSize == 8)
    }

    // Block budgets cannot depend on backend message packing or shift existing starts after prepend.
    @Test func proseBoundariesSurvivePrependAndNativeTurnChanges() {
        let original = (0..<9).map { text("m\($0)") }
        let before = chatRows(original)
        #expect(before.map { $0.messages.count } == [4, 4, 1])
        let after = chatRows([text("older0"), text("older1")] + original, previous: before)
        #expect(after.map(\.id) == ["older0"] + before.map(\.id))
        #expect(signature(before) == signature(chatRows(original, turnIdsByMessageId: ["m0": "first", "m8": "second"])))
        let packed = ChatMessage(messageId: "packed", role: "assistant", content: original.flatMap(\.content), timestamp: 0, isError: nil)
        #expect(signature(before) == signature(chatRows([packed])))
    }
}
