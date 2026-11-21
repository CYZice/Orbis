package com.orbising.orbis

import kotlinx.serialization.json.*

internal fun messageEditPayload(entryId: String, text: String, retryToken: String? = null): String? {
    val clean = text.trim()
    if (clean.isEmpty() || userMessageInputError(clean) != null) return null
    return buildJsonObject {
        put("entryId", entryId); put("text", clean)
        retryToken?.let { put("retryToken", it) }
    }.toString()
        .takeIf { it.length <= MAX_USER_MESSAGE_CHARACTERS }
}

internal fun historyEditWasRewound(result: JsonElement?): Boolean =
    ((result as? JsonObject)?.get("rewound") as? JsonPrimitive)?.booleanOrNull == true

internal data class FirstSessionSubmission(val commandId: String?)
internal const val MAX_USER_MESSAGE_CHARACTERS = 100_000

/** Same UTF-16 length limit as the deployed user_message schema. */
internal fun userMessageInputError(text: String): String? = when {
    text.trimStart().startsWith("/") -> L10n.tr("斜杠命令只能从当前会话提供的菜单中选择")
    text.trim().length > MAX_USER_MESSAGE_CHARACTERS -> L10n.tr("消息超过 {0} 个字符，请缩短后重试", MAX_USER_MESSAGE_CHARACTERS)
    else -> null
}
