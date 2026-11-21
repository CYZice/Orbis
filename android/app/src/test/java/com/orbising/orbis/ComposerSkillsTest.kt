package com.orbising.orbis

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ComposerSkillsTest {
    @Test fun skillSelectionReplacesOnlyTheTokenAtTheCursor() {
        val input = TextFieldValue("用 \$revie 检查附件", TextRange(7))
        val query = requireNotNull(composerSkillQuery(input))
        assertEquals("revi", query.text)
        assertEquals("用 \$review 检查附件", query.select(input, "review").text)
        assertNotNull(composerSkillQuery(TextFieldValue("$", TextRange(1))))
        assertNull(composerSkillQuery(TextFieldValue("/say $", TextRange(6))))
        assertNull(composerSkillQuery(TextFieldValue("price$20", TextRange(8))))
    }

    @Test fun editPayloadPreservesTextAndRejectsEmptyAndSlashInput() {
        val json = Json.parseToJsonElement(requireNotNull(messageEditPayload("entry", "  改过的正文\n第二行  "))).jsonObject
        assertEquals("entry", json.getValue("entryId").jsonPrimitive.contentOrNull)
        assertEquals("改过的正文\n第二行", json.getValue("text").jsonPrimitive.contentOrNull)
        assertNull(messageEditPayload("entry", "  "))
        assertNull(messageEditPayload("entry", "/fork"))
    }
}
