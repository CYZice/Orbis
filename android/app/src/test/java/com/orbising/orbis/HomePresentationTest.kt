// Orbis 测试用途（场景与判定见本文件断言）：
// - MOT-21 / SC43（MOT-21、SC43、R55、S18、V13、V18）
package com.orbising.orbis

import org.junit.Assert.*
import org.junit.Test

class HomePresentationTest {
    @Test fun coldStartWaitsForTheDirectoryAndEveryCompleteCard() {
        val f = HomeLoadingFixture()
        assertTrue(f.state.homeRuntimesLoading)
        f.directory(f.runtime("a"), f.runtime("b"))
        f.beginRead("a"); f.beginRead("b")
        f.facts("a", setOf("identity", "execution")); f.facts("b")
        assertTrue(f.state.homeCards.isEmpty())
        f.facts("a", setOf("interactions"))
        assertEquals(2, f.state.homeCards.size)
        assertFalse(f.state.homeRuntimesUpdating)
    }

    @Test fun emptyStateRequiresAnAuthenticatedDirectory() {
        val f = HomeLoadingFixture()
        f.receive("""{"type":"device.ready","runtimes":[]}""", channel = null)
        assertTrue(f.state.homeRuntimesLoading)
        f.directory()
        assertFalse(f.state.homeRuntimesLoading)
        assertEquals(emptyList<HomeRuntimeCard>(), f.state.homeSnapshot)
    }

    @Test fun refreshPublishesAllVisibleFieldsTogether() {
        val f = HomeLoadingFixture(); f.complete("a", "b")
        val before = f.state.homeCards
        f.beginRead("a"); f.beginRead("b")
        f.facts("a", setOf("identity", "execution"), title = "更新后的完整会话", phase = "running", revision = 2)
        f.facts("b", revision = 2)
        assertEquals(before, f.state.homeCards)
        assertTrue(f.state.homeRuntimesUpdating)
        f.facts("a", setOf("interactions"), revision = 2)
        assertEquals("更新后的完整会话", f.state.homeCards.first { it.runtime.runtimeId == "a" }.title)
        assertFalse(f.state.homeRuntimesUpdating)
    }

    @Test fun duplicateTransportMetadataCannotEraseCommittedIdentity() {
        val f = HomeLoadingFixture(); f.complete("a")
        val before = f.state.homeCards
        f.directory(f.runtime("a"))
        assertEquals(before, f.state.homeCards)
        assertFalse(f.state.homeRuntimesUpdating)
        f.receive("""{"type":"runtime.online","runtime":{"runtimeId":"a","sessionId":"session-a","name":"Agent","cwd":"","status":"unknown"}}""")
        f.receive("""{"type":"runtime.event","runtimeId":"a","sequence":1,"event":{"type":"runtime.metadata","metadata":{"runtimeId":"a","sessionId":"session-a","name":"Agent","cwd":"","status":"unknown"}}}""")
        assertEquals(before, f.state.homeCards)
        assertFalse(f.state.homeRuntimesUpdating)
    }

    @Test fun replacementRetainsTheOldCardButCannotOperateOnTheNewSession() {
        val f = HomeLoadingFixture(); f.complete("a")
        val before = f.state.homeCards.single()
        f.receive("""{"type":"runtime.online","runtime":{"runtimeId":"a","sessionId":"replacement","name":"Agent","cwd":"","status":"unknown"}}""")
        assertEquals(before, f.state.homeCards.single())
        assertFalse(f.state.homeCardIsCurrent(before))
        f.beginRead("a"); f.facts("a", title = "替换完成")
        assertEquals("替换完成", f.state.homeCards.single().title)
        assertTrue(f.state.homeCardIsCurrent(f.state.homeCards.single()))
    }

    @Test fun reconnectAndHostEpochChangeKeepTheCompleteSnapshot() {
        val f = HomeLoadingFixture(); f.complete("a")
        val before = f.state.homeCards
        f.state = f.state.markReconnecting()
        assertEquals(before, f.state.homeCards)
        f.state = f.state.copy(e2eReady = true)
        assertTrue(f.state.homeRuntimesUpdating)
        f.directory(f.runtime("a"), epoch = "new-epoch")
        assertEquals(before, f.state.homeCards)
        f.beginRead("a"); f.facts("a", title = "重新连接完成")
        assertFalse(f.state.homeRuntimesUpdating)
    }

    @Test fun failureAndTimeoutKeepCardsAndRetryCanRecover() {
        val f = HomeLoadingFixture(); f.complete("a", "b")
        val before = f.state.homeCards
        val request = f.beginRead("b")
        f.receive("""{"type":"protocol.error","code":"session_request_failed","requestId":"$request","message":"读取失败"}""")
        assertEquals("读取失败", f.state.homeRuntimesError)
        assertEquals(before, f.state.homeCards)
        val retry = f.beginRead("b")
        assertNull(f.state.homeRuntimesError)
        f.state = f.state.failHomeRead(retry, "会话读取超时，请重试")
        assertEquals("会话读取超时，请重试", f.state.homeRuntimesError)
        f.facts("b", revision = 2)
        assertNull(f.state.homeRuntimesError)
        assertFalse(f.state.homeRuntimesUpdating)
    }

    @Test fun syncStatusFollowsProgressAndFailureWithoutAnotherSource() {
        val f = HomeLoadingFixture()
        assertEquals(HomeSyncStatus.Syncing, f.state.homeSyncStatus)
        f.complete("a")
        assertEquals(HomeSyncStatus.Idle, f.state.homeSyncStatus)
        val request = f.beginRead("a")
        assertEquals(HomeSyncStatus.Syncing, f.state.homeSyncStatus)
        f.state = f.state.failHomeRead(request, "会话读取超时，请重试")
        assertEquals(HomeSyncStatus.Failed("会话读取超时，请重试"), f.state.homeSyncStatus)
        assertFalse(f.state.homeRuntimesLoading)
        f.beginRead("a"); f.facts("a", revision = 2)
        assertEquals(HomeSyncStatus.Idle, f.state.homeSyncStatus)
    }

    @Test fun unknownOrPartialDomainsNeverProduceHalfLoadedCards() {
        val f = HomeLoadingFixture(); f.directory(f.runtime("a")); f.beginRead("a")
        f.facts("a", setOf("identity", "execution"))
        f.facts("a", setOf("interactions"), completeness = "partial", projectionComplete = false)
        assertTrue(f.state.homeCards.isEmpty())
        assertNotNull(f.state.homeRuntimesError)
        f.facts("a", setOf("interactions"), revision = 2, availability = "unknown")
        assertTrue(f.state.homeCards.isEmpty())
        f.facts("a", setOf("interactions"), revision = 3)
        assertEquals(1, f.state.homeCards.size)
    }

    @Test fun newDesktopCodexSessionCompletesSplitHomeReadsAndRetryWithoutRestarting() {
        val f = HomeLoadingFixture(); f.complete("existing")
        val before = f.state.homeCards
        val id = "codex-desktop:new-thread"
        f.receive("""{"type":"runtime.online","runtime":{"runtimeId":"$id","sessionId":"$id","name":"Agent","cwd":"","status":"unknown"}}""")
        f.beginRead(id, agentKind = null)
        assertEquals(before, f.state.homeCards)
        assertEquals(HomeSyncStatus.Syncing, f.state.homeSyncStatus)
        f.facts(id, setOf("execution"), agentKind = "codexDesktop")
        assertEquals(before, f.state.homeCards)
        f.facts(id, setOf("identity"), agentKind = "codexDesktop")
        f.facts(id, setOf("interactions"), availability = "unknown", agentKind = "codexDesktop")
        assertTrue(f.state.homeSyncStatus is HomeSyncStatus.Failed)
        assertEquals(before, f.state.homeCards)
        f.facts(id, setOf("interactions"), revision = 2, agentKind = "codexDesktop")
        assertEquals(HomeSyncStatus.Idle, f.state.homeSyncStatus)
        assertEquals(2, f.state.homeCards.size)
        assertEquals("codexDesktop", f.state.homeCards.single { it.runtime.runtimeId == id }.runtime.agentKind)

        f.beginRead(id, agentKind = "codexDesktop")
        f.facts(id, revision = 2, agentKind = "codexDesktop")
        assertEquals(HomeSyncStatus.Idle, f.state.homeSyncStatus)
        assertEquals(2, f.state.homeCards.size)
    }
    @Test fun retainedCardsRemainInteractiveDuringReadsButRejectReplacementAndDisconnect() {
        val f = HomeLoadingFixture(); f.complete("a", "b")
        val card = f.state.homeCards.first { it.runtime.runtimeId == "a" }
        f.state = f.state.copy(homeDirectoryReceived = false)
        f.beginRead("a")
        assertEquals(HomeSyncStatus.Syncing, f.state.homeSyncStatus)
        assertTrue(f.state.homeCardIsCurrent(card))
        assertFalse(f.state.copy(e2eReady = false).homeCardIsCurrent(card))
        assertFalse(f.state.copy(runtimes = f.state.runtimes - "a").homeCardIsCurrent(card))
        val replacement = f.state.runtimes.getValue("a").copy(sessionId = "replacement")
        assertFalse(f.state.copy(runtimes = f.state.runtimes + ("a" to replacement)).homeCardIsCurrent(card))
    }

    @Test fun meaningfulActivityUsesRealTurnTimesButNotToolOnlyDeltas() {
        val runtime = RuntimeSummary("runtime", "Codex", "C:/Projects/Orbis", "running", sessionId = "session")
        val state = RemoteState(
            sessions = mapOf("session" to SessionCatalogEntry("session", modifiedAt = 1_000L)),
            conversations = mapOf("runtime" to RuntimeConversation(
                messages = listOf(
                    ChatMessage("user", "user", listOf(RemoteContent("text", "prompt")), 2_000L),
                    ChatMessage("tool", "assistant", listOf(RemoteContent("tool_call", toolCallId = "call")),
                        90_000L),
                ),
                turnTimings = mapOf("turn" to TurnTiming("turn", startedAt = 3_000L)),
            )),
        )
        assertEquals(3_000L, state.homeActivityAt(runtime))
        val completed = state.copy(conversations = mapOf("runtime" to
            state.conversations.getValue("runtime").copy(turnTimings =
                mapOf("turn" to TurnTiming("turn", startedAt = 3_000L, durationMs = 1_000L)))))
        assertEquals(4_000L, completed.homeActivityAt(runtime))
    }

    @Test fun recentProjectPickerDeduplicatesFoldersAndUsesActiveSessions() {
        val optics = """C:\\Work\\Optics"""
        val zotero = """C:\\Work\\Zotero"""
        val state = RemoteState(
            sessions = mapOf(
                "a" to SessionCatalogEntry("a", name = "Research", cwd = zotero, modifiedAt = 1_000L),
                "b" to SessionCatalogEntry("b", name = "Reading", cwd = zotero, modifiedAt = 2_000L),
                "c" to SessionCatalogEntry("c", name = "Orbis UI", cwd = optics, modifiedAt = 100L),
                "archived" to SessionCatalogEntry("archived", cwd = "C:/Old", modifiedAt = 9_000L, archived = true),
            ),
            runtimes = mapOf("running" to RuntimeSummary(
                "running", "Codex", optics, "running", sessionId = "c")),
            conversations = mapOf("running" to RuntimeConversation(
                turnTimings = mapOf("recent" to TurnTiming("recent", startedAt = 3_000L)))),
        )
        val projects = state.recentHomeProjects()
        assertEquals(listOf("Optics", "Zotero"), projects.map { it.label })
        assertEquals(1, projects.first().sessionCount)
        assertEquals(2, projects.last().sessionCount)
        assertEquals(listOf("c", "b", "a"), state.recentHomeSessions().map { it.sessionId })
        assertEquals("Orbis", homeDirectoryLabel("C:/Projects/Orbis/"))
        assertEquals("Zotero", homeDirectoryLabel(zotero))
    }

}
