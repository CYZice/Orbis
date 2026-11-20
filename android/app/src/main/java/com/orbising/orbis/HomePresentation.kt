package com.orbising.orbis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A complete homepage card; background reads cannot alter any of its visible fields. */
data class HomeRuntimeCard(
    val runtime: RuntimeSummary,
    val title: String,
    val hasPendingInteraction: Boolean,
    val error: String? = null,
)

internal val HOME_MODEL_DOMAINS = setOf("identity", "execution", "interactions")

private fun RemoteState.homeRead(runtime: RuntimeSummary) = agentStateReads.entries.firstOrNull {
    it.value.scope == "agent" && it.value.runtimeId == runtime.runtimeId && it.value.sessionId == runtime.sessionId
}

private fun RemoteState.homeModelSnapshot(runtime: RuntimeSummary): AgentStateSnapshotView? {
    val read = homeRead(runtime)
    val canonical = agentStateSnapshots["agent|${runtime.agentKind.orEmpty()}|${runtime.sessionId.orEmpty()}"]
    return canonical?.takeIf { it.requestId == read?.key }
        ?: agentStateSnapshots.values.firstOrNull { it.scope == "agent" && it.requestId == read?.key }
        ?: canonical
}

private fun RemoteState.homeFactsComplete(runtime: RuntimeSummary): Boolean {
    if (runtime.sessionId.isNullOrBlank() || runtime.agentKind.isNullOrBlank() || runtime.cwd.isBlank()) return false
    val read = homeRead(runtime)
    if (read?.value?.pendingDomains?.any { it in HOME_MODEL_DOMAINS } == true) return false
    val snapshot = homeModelSnapshot(runtime) ?: return false
    if (snapshot.error != null || snapshot.modelEpoch != agentModelEpoch) return false
    return HOME_MODEL_DOMAINS.all { name ->
        val domain = snapshot.domains[name]
        runtime.domainAvailability[name] == "ready" && name !in snapshot.missing &&
            domain?.availability == "ready" && (domain.completeness == "complete" || domain.projectionComplete)
    }
}

internal val RemoteState.homeRuntimesLoading: Boolean get() = homeSnapshot == null
internal val RemoteState.homeRuntimesUpdating: Boolean
    get() = !e2eReady || !homeDirectoryReceived || !runtimes.values.all(::homeFactsComplete)
internal val RemoteState.homeCards: List<HomeRuntimeCard>
    get() = closingRuntimeIds.let { closing -> homeSnapshot.orEmpty().filter { it.runtime.runtimeId !in closing } }
internal val RemoteState.homeRuntimes: List<RuntimeSummary> get() = homeCards.map { it.runtime }

internal val RemoteState.homeRuntimesError: String?
    get() {
        if (!homeRuntimesUpdating) return null
        if (connection == RelayConnection.OFFLINE) return L10n.tr("电脑未连接，请重试")
        for (runtime in runtimes.values) {
            if (runtime.sessionId.isNullOrBlank()) return L10n.tr("会话身份尚未就绪，请重新加载")
            val read = homeRead(runtime)
            val snapshot = homeModelSnapshot(runtime)
            if (snapshot?.requestId == read?.key) snapshot?.error?.let { return it }
            if (read?.value?.pendingDomains?.any { it in HOME_MODEL_DOMAINS } == true) continue
            snapshot?.error?.let { return it }
            if (snapshot != null && !homeFactsComplete(runtime)) return L10n.tr("会话信息未能完整读取，请重试")
        }
        return null
    }

/** 首页同步进度。首版快照发布前由列表区域显示，发布后由连接胶囊显示，不再占用单独一行。 */
internal sealed interface HomeSyncStatus {
    data object Idle : HomeSyncStatus
    data object Syncing : HomeSyncStatus
    data class Failed(val message: String) : HomeSyncStatus
}

internal val RemoteState.homeSyncStatus: HomeSyncStatus
    get() = if (!homeRuntimesUpdating) HomeSyncStatus.Idle
        else homeRuntimesError?.let(HomeSyncStatus::Failed) ?: HomeSyncStatus.Syncing

/** Called at publication boundaries, after the whole authenticated event has been reduced. */
internal fun RemoteState.commitHomePresentation(titles: SessionTitleCache? = null, language: AppLanguage = AppLocalization.language): RemoteState {
    if (homeRuntimesUpdating) return this
    val cards = orderHomeCards(prepareHomeCards(titles, language))
    return if (homeSnapshot == cards) this else copy(homeSnapshot = cards)
}

private fun RemoteState.prepareHomeCards(titles: SessionTitleCache?, language: AppLanguage) = runtimes.values.map {
        HomeRuntimeCard(it, titles?.resolve(this, it.sessionId, it, language) ?: runtimeDisplayName(it), hasPendingInteraction(it.runtimeId), sessionOperationErrors[it.runtimeId])
    }

/** Short project label for compact cards; full paths remain available to accessibility. */
internal fun homeDirectoryLabel(cwd: String): String {
    val trimmed = cwd.trimEnd('/', '\\')
    return trimmed.substringAfterLast('\\').substringAfterLast('/').ifBlank { cwd }
}

/**
 * Last meaningful activity, rather than the catalog scan timestamp alone.
 * Tool progress/deltas intentionally do not move cards on every update.
 */
internal fun RemoteState.homeActivityAt(runtime: RuntimeSummary): Long {
    val entry = sessions[runtime.sessionId]
    val catalogTime = entry?.modifiedAt ?: 0L
    val conversation = conversations[runtime.runtimeId]
    val latestTurnStart = conversation?.turnTimings?.values?.maxOfOrNull { it.startedAt } ?: 0L
    // Follow Codex's native recent-activity order. Live turn starts compensate for
    // catalog caching; tool output alone must not move a session up the list.
    if (runtime.agentKind in setOf("codex", "codexDesktop") && entry?.recencyAt != null) {
        return maxOf(entry.recencyAt, latestTurnStart)
    }
    if (conversation == null) return catalogTime
    // Streaming assistant text and tool deltas must not cause home-list resorting.
    // The user's next message, a turn-start event, or the catalog timestamp is the activity boundary.
    val messageTime = conversation.messages.asReversed().firstOrNull { message ->
        message.role == "user"
    }?.timestamp ?: 0L
    val turnTime = conversation.turnTimings.values.maxOfOrNull { turn ->
        turn.startedAt + (turn.durationMs ?: 0L).coerceAtLeast(0L)
    } ?: 0L
    return maxOf(catalogTime, messageTime, turnTime)
}

internal data class RecentHomeProject(
    val cwd: String,
    val label: String,
    val lastActivityAt: Long,
    val sessionCount: Int,
)

/** Prefer Codex's native named projects; fill gaps with known working directories. */
internal fun RemoteState.homeProjectPickerEntries(): List<RecentHomeProject> {
    val native = nativeCodexProjects.filter { it.cwd.isNotBlank() && it.name.isNotBlank() }.map {
        RecentHomeProject(it.cwd, it.name, it.recencyAt ?: 0L, 0)
    }
    val inferred = recentHomeProjects(limit = Int.MAX_VALUE)
    return (native + inferred).distinctBy {
        it.cwd.trimEnd('/', '\\').lowercase(java.util.Locale.ROOT)
    }
}

/** Projects are Host-scoped, reused from known sessions/runtimes without browsing the filesystem. */
internal fun RemoteState.recentHomeProjects(limit: Int = 6): List<RecentHomeProject> {
    val known = sessions.values.filter { it.archived != true && it.cwd.isNotBlank() }
    val active = runtimes.values.filter { it.cwd.isNotBlank() }
    val paths = (known.map { it.cwd } + active.map { it.cwd }).distinctBy { it.trimEnd('/', '\\').lowercase(java.util.Locale.ROOT) }
    return paths.map { cwd ->
        val normalized = cwd.trimEnd('/', '\\')
        val projectSessions = known.filter { it.cwd.trimEnd('/', '\\').equals(normalized, ignoreCase = true) }
        val projectRuntimes = active.filter { it.cwd.trimEnd('/', '\\').equals(normalized, ignoreCase = true) }
        RecentHomeProject(
            cwd = cwd,
            label = homeDirectoryLabel(cwd),
            lastActivityAt = maxOf(
                projectSessions.maxOfOrNull { it.recencyAt ?: it.modifiedAt } ?: 0L,
                projectRuntimes.maxOfOrNull { homeActivityAt(it) } ?: 0L,
            ),
            sessionCount = projectSessions.size,
        )
    }.sortedWith(compareByDescending<RecentHomeProject> { it.lastActivityAt }.thenBy { it.label })
        .take(limit.coerceAtLeast(0))
}

internal fun RemoteState.orderHomeCards(cards: List<HomeRuntimeCard>): List<HomeRuntimeCard> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return cards.sortedWith(compareByDescending<HomeRuntimeCard> { homeActivityAt(it.runtime) }
        .thenBy(collator) { it.title }.thenBy { it.runtime.runtimeId })
}

/** Constant-time admission/ownership checks; immutable state is only traversed by the worker. */
internal class HomePresentationRequest(val state: RemoteState, val language: AppLanguage) {
    fun matches(current: RemoteState): Boolean = state.hostId == current.hostId &&
        state.connection == current.connection && state.e2eReady == current.e2eReady &&
        state.homeDirectoryReceived == current.homeDirectoryReceived && state.agentModelEpoch == current.agentModelEpoch &&
        state.runtimes === current.runtimes && state.sessions === current.sessions &&
        state.agentStateReads === current.agentStateReads && state.agentStateSnapshots === current.agentStateSnapshots &&
        state.conversations === current.conversations && state.sessionGraphs === current.sessionGraphs
    override fun equals(other: Any?): Boolean = other is HomePresentationRequest && language == other.language && matches(other.state)
    override fun hashCode(): Int = state.hostId.hashCode()
}

internal class HomePresentationWorker(private val beforeFacts: (() -> Unit)? = null,
                                     private val beforeSort: (() -> Unit)? = null) {
    private val titles = SessionTitleCache()
    private var previous: List<HomeRuntimeCard>? = null
    private var previousFacts: List<HomeRuntimeCard>? = null
    private var previousTimes: List<Long>? = null
    private var previousInput: HomePresentationRequest? = null
    suspend fun resolve(request: HomePresentationRequest): List<HomeRuntimeCard>? = withContext(Dispatchers.Default) {
        val old = previousInput
        previousInput = request
        if (old != null && old.language == request.language && old.state.hostId == request.state.hostId &&
            old.state.connection == request.state.connection && old.state.e2eReady == request.state.e2eReady &&
            old.state.homeDirectoryReceived == request.state.homeDirectoryReceived && old.state.agentModelEpoch == request.state.agentModelEpoch &&
            old.state.runtimes === request.state.runtimes && old.state.sessions === request.state.sessions &&
            old.state.agentStateReads === request.state.agentStateReads && old.state.agentStateSnapshots === request.state.agentStateSnapshots &&
            old.state.runtimes.keys.all { id ->
                old.state.conversations[id]?.interactions.orEmpty() == request.state.conversations[id]?.interactions.orEmpty()
            } &&
            !catalogTranscriptChanged(old.state, request.state, titles, counts = false) &&
            request.state.runtimes.values.all { runtime ->
                old.state.homeActivityAt(runtime) == request.state.homeActivityAt(runtime)
            }) return@withContext previous ?: request.state.homeSnapshot
        titles.retain(request.state)
        val state = request.state
        if (state.homeRuntimesUpdating) return@withContext state.homeSnapshot
        beforeFacts?.invoke()
        val facts = state.prepareHomeCards(titles, request.language)
        val times = facts.map { state.homeActivityAt(it.runtime) }
        if (facts == previousFacts && times == previousTimes) return@withContext previous
        beforeSort?.invoke()
        val next = state.orderHomeCards(facts)
        previousFacts = facts; previousTimes = times
        if (next == previous) previous else next.also { previous = it }
    }
}

internal fun RemoteState.homeCardIsCurrent(card: HomeRuntimeCard): Boolean =
    e2eReady && !card.runtime.sessionId.isNullOrBlank() && runtimes[card.runtime.runtimeId]?.let { current ->
        current.sessionId == card.runtime.sessionId && current.agentKind == card.runtime.agentKind
    } == true

internal fun RemoteState.failHomeRead(requestId: String, message: String): RemoteState {
    val read = agentStateReads[requestId]?.takeIf { it.scope == "agent" } ?: return this
    val previous = agentStateSnapshots[read.key]
    val failed = previous?.copy(requestId = requestId, error = message)
        ?: AgentStateSnapshotView(read.key, "agent", requestId, error = message)
    return copy(agentStateRequests = agentStateRequests - requestId,
        agentStateSnapshots = agentStateSnapshots + (read.key to failed))
}

/**
 * Where the home list should sit after its cards change (ADR-0046 §6, V21); null keeps the
 * list's default, which holds the first visible card's screen position by key.
 *
 * - At the top the newest card stays visible instead of landing above the viewport.
 * - When the first visible card itself moved up past cards that were above it (it just had
 *   activity), following it would jump the viewport, so the pixel position is kept instead.
 * - A removed anchor also keeps the pixel position; the next card fills the slot.
 */
internal fun homeViewportTarget(
    previousKeys: List<String>,
    keys: List<String>,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    topSlop: Int,
): Pair<Int, Int>? {
    if (previousKeys.isEmpty() || keys.isEmpty() || previousKeys == keys) return null
    if (firstVisibleIndex == 0 && firstVisibleOffset <= topSlop) return 0 to 0
    val anchor = previousKeys.getOrNull(firstVisibleIndex) ?: return null
    val keep = firstVisibleIndex.coerceAtMost(keys.lastIndex) to firstVisibleOffset
    val position = keys.withIndex().associate { it.value to it.index }
    val anchorIndex = position[anchor] ?: return keep
    val movedUpPast = previousKeys.subList(0, firstVisibleIndex).any { key -> (position[key] ?: -1) > anchorIndex }
    return if (movedUpPast) keep else null
}
