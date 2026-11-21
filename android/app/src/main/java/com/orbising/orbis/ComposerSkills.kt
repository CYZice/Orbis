package com.orbising.orbis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class ComposerSkillQuery(val start: Int, val end: Int, val text: String) {
    fun select(input: TextFieldValue, name: String): TextFieldValue {
        val replacement = "$$name"
        val suffix = input.text.substring(end)
        val space = if (suffix.firstOrNull()?.isWhitespace() == true) "" else " "
        return TextFieldValue(input.text.replaceRange(start, end, replacement + space),
            TextRange(start + replacement.length + 1))
    }
}

private val composerSkillPattern = Regex("(?:^|\\s)\\$([\\p{L}\\p{N}_-]*)$")

internal fun composerSkillQuery(input: TextFieldValue): ComposerSkillQuery? {
    if (!input.selection.collapsed || input.text.trimStart().startsWith('/')) return null
    val cursor = input.selection.start
    val match = composerSkillPattern.find(input.text.substring(0, cursor)) ?: return null
    val query = match.groupValues[1]
    var end = cursor
    while (end < input.text.length && input.text[end].let { it.isLetterOrDigit() || it == '_' || it == '-' }) end++
    return ComposerSkillQuery(cursor - query.length - 1, end, query)
}

@Composable
internal fun ComposerSkillPicker(input: TextFieldValue, commands: List<RuntimeSlashCommand>, onSelect: (TextFieldValue) -> Unit) {
    val query = composerSkillQuery(input) ?: return
    val matches = commands.filter { it.source == "skill" &&
        (it.name.contains(query.text, ignoreCase = true) || it.description?.contains(query.text, ignoreCase = true) == true) }
    if (matches.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).testTag("composer-skills"),
        contentPadding = PaddingValues(horizontal = RemoteUi.PagePadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(matches, key = RuntimeSlashCommand::name) { skill ->
            NeumorphSurface(onClick = { onSelect(query.select(input, skill.name)) },
                modifier = Modifier.fillMaxWidth(), shape = RemoteUi.ControlShape) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("$${skill.name}", style = MaterialTheme.typography.labelLarge)
                    skill.description?.takeIf(String::isNotBlank)?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
