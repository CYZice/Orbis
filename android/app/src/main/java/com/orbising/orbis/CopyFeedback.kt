package com.orbising.orbis

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private class CopyFeedback {
    var copied by mutableStateOf(false)
        private set
    var generation by mutableLongStateOf(0)
        private set

    fun perform(copy: () -> Unit) {
        copy()
        copied = true
        generation++
    }

    fun clear() { copied = false }
}

/** Each copy owner has one cancellable deadline; accessibility motion settings do not shorten it. */
@Composable
private fun rememberCopyFeedback(identity: Any?, enabled: Boolean): CopyFeedback {
    val feedback = remember(identity) { CopyFeedback() }
    LaunchedEffect(feedback, feedback.generation, feedback.copied, enabled) {
        if (!enabled) feedback.clear()
        else if (feedback.copied) {
            delay(InteractionMotion.CopyFeedback)
            feedback.clear()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, feedback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) feedback.clear()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); feedback.clear() }
    }
    return feedback
}

/** One fixed slot crossfades and scales in both directions, including the timed reset. */
@Composable
internal fun CopyFeedbackIcon(
    copied: Boolean,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        if (copied) 1f else 0f,
        tween(InteractionMotion.duration(InteractionMotion.CopyTransition), easing = InteractionMotion.GlyphEase),
        label = "copy-feedback",
    )
    Box(modifier.size(size)) {
        Icon(OrbisSymbols.ContentCopy, contentDescription = null, tint = tint,
            modifier = Modifier.size(size).graphicsLayer {
                alpha = 1f - progress
                scaleX = 1f - 0.4f * progress
                scaleY = scaleX
            })
        Icon(OrbisSymbols.Check, contentDescription = null, tint = tint,
            modifier = Modifier.size(size).graphicsLayer {
                alpha = progress
                scaleX = 0.5f + 0.5f * progress
                scaleY = scaleX
            })
    }
}

/** Copies [text] and shows a check for 2.5 s; copying again restarts the timer. */
@Composable
internal fun CopyIconButton(
    label: String,
    size: Dp = 32.dp,
    enabled: Boolean = true,
    identity: Any? = null,
    text: () -> String,
) {
    val clipboard = LocalClipboardManager.current
    val feedback = rememberCopyFeedback(identity, enabled)
    NeumorphIconButton(
        onClick = { feedback.perform { clipboard.setText(AnnotatedString(text())) } },
        icon = OrbisSymbols.ContentCopy,
        contentDescription = if (feedback.copied) L10n.tr("已复制") else label,
        size = size,
        enabled = enabled,
        copyFeedback = feedback.copied,
    )
}

/** Chat footer actions are flat and do not add a full button-height row. */
@Composable
internal fun ReplyActionButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(width = 40.dp, height = 28.dp)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant) {
            content()
        }
    }
}

@Composable
internal fun ReplyCopyButton(label: String, identity: Any, text: () -> String) {
    val clipboard = LocalClipboardManager.current
    val feedback = rememberCopyFeedback(identity, enabled = true)
    ReplyActionButton(if (feedback.copied) L10n.tr("已复制") else label,
        onClick = { feedback.perform { clipboard.setText(AnnotatedString(text())) } }) {
        CopyFeedbackIcon(feedback.copied, size = 16.dp)
    }
}

/** The label and hit area stay fixed while the copy glyph changes to a check. */
@Composable
internal fun CopyTextButton(label: String, identity: Any? = null, text: () -> String) {
    val clipboard = LocalClipboardManager.current
    val feedback = rememberCopyFeedback(identity, enabled = true)
    NeumorphTextButton(
        text = label,
        onClick = { feedback.perform { clipboard.setText(AnnotatedString(text())) } },
        icon = OrbisSymbols.ContentCopy,
        copyFeedback = feedback.copied,
        modifier = Modifier.semantics { contentDescription = if (feedback.copied) L10n.tr("已复制") else label },
    )
}


/** A full-width message-menu row with the same copy lifetime as every other copy control. */
@Composable
internal fun CopyMenuItem(label: String, identity: Any? = null, text: () -> String) {
    val clipboard = LocalClipboardManager.current
    val feedback = rememberCopyFeedback(identity, enabled = true)
    NeumorphMenuItem(
        text = { androidx.compose.material3.Text(if (feedback.copied) L10n.tr("已复制") else label) },
        onClick = { feedback.perform { clipboard.setText(AnnotatedString(text())) } },
        leadingIcon = {
            CopyFeedbackIcon(feedback.copied,
                modifier = Modifier.actionGlyphMotion(LocalGlyphActivation.current))
        },
    )
}
