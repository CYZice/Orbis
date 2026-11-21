package com.orbising.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                CompositionLocalProvider(LocalAgentBrand provides AgentBrand.Codex) {
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
                    onFork = {},
                )
                }
            }
        }

        val answer = compose.onNodeWithText("The answer stays in one assistant card.").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertDoesNotExist()
        val footer = compose.onNodeWithTag("reply-footer").assertIsDisplayed()
        assertTrue(footer.fetchSemanticsNode().boundsInRoot.top >= answer.fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithContentDescription("复制回复").assertIsDisplayed()
        compose.onNodeWithContentDescription("从这条回复创建分支").assertIsDisplayed()
        compose.onNodeWithText("思考过程").assertIsDisplayed()
        compose.onNodeWithText("private reasoning summary").assertDoesNotExist()

        compose.onNodeWithText("思考过程").performClick()
        compose.onNodeWithText("private reasoning summary").assertIsDisplayed()

        compose.onNodeWithText("查看图片").performClick()
        compose.onNodeWithTag("message-image-preview").assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertIsDisplayed()
    }

    @Test
    fun sentUserMessageOffersEdit() {
        var opened = false
        val user = ChatMessage("user", "user", listOf(RemoteContent("text", "Original")), 1)
        compose.setContent {
            OrbisTheme { UserMessageCard("r", user, emptyMap(), {}, {}, onEdit = { opened = true }) }
        }
        compose.onNodeWithText("Original").performTouchInput { longClick() }
        compose.onNodeWithText("编辑并重发").performClick()
        compose.runOnIdle { assertTrue(opened) }
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
