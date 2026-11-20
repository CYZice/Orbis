package com.orbising.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatExperienceInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val imageReference = MessageFileReference(
        fileName = "photo.png",
        referenceId = "orbis-message-file:${"a".repeat(64)}",
    )

    @Test
    fun continuousAssistantReplyKeepsThinkingCollapsedAndExpandsToolImage() {
        val messages = listOf(
            ChatMessage(
                messageId = "assistant",
                role = "assistant",
                timestamp = 1,
                content = listOf(
                    RemoteContent(type = "thinking", text = "private reasoning summary"),
                    RemoteContent(type = "text", text = "The answer stays in one assistant card."),
                    RemoteContent(type = "tool_call", toolCallId = "view-1", toolName = "查看图片", file = imageReference),
                ),
            ),
        )

        compose.setContent {
            OrbisTheme {
                AssistantTurnCard(
                    runtimeId = "r",
                    messages = messages,
                    turnTiming = null,
                    nowMs = 0,
                    toolActivities = emptyMap(),
                    toolResults = emptyMap(),
                    downloads = emptyMap(),
                    downloadArtifact = {},
                    downloadFile = {},
                )
            }
        }

        compose.onNodeWithText("The answer stays in one assistant card.").assertIsDisplayed()
        compose.onNodeWithText("思考过程").assertIsDisplayed()
        compose.onNodeWithText("private reasoning summary").assertDoesNotExist()

        compose.onNodeWithText("思考过程").performClick()
        compose.onNodeWithText("private reasoning summary").assertIsDisplayed()

        compose.onNodeWithText("查看图片").performClick()
        compose.onNodeWithTag("message-image-preview").assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
    }

    @Test
    fun userAndGeneratedImagesUseTheSamePreviewSurface() {
        val user = ChatMessage(
            messageId = "user-image",
            role = "user",
            timestamp = 1,
            content = listOf(RemoteContent(type = "render", renderKind = "file_reference", text = "photo.png", file = imageReference)),
        )
        val generated = ChatMessage(
            messageId = "generated-image",
            role = "assistant",
            timestamp = 2,
            content = listOf(RemoteContent(type = "render", renderKind = "file_reference", text = "photo.png", file = imageReference)),
        )

        compose.setContent {
            OrbisTheme {
                Column {
                    UserMessageCard("r", user, emptyMap(), {}, {})
                    AssistantTurnCard("r", listOf(generated), null, 0, emptyMap(), emptyMap(), emptyMap(), {}, {})
                }
            }
        }

        assertEquals(2, compose.onAllNodesWithTag("message-image-preview").fetchSemanticsNodes().size)
        compose.onAllNodesWithTag("message-image-preview")[0].assertIsDisplayed()
        compose.onAllNodesWithTag("message-image-preview")[1].assertIsDisplayed()
    }
}
