package com.agentoffice.core

import com.agentoffice.core.AgentDefinitions.definitionSources
import com.agentoffice.core.AgentDefinitions.discoverDefinitions
import com.agentoffice.core.AgentDefinitions.parseFrontmatter
import com.agentoffice.core.Placement.roleOf
import com.agentoffice.core.Slots.assignSlots
import com.agentoffice.core.SnapshotBuilder.buildSnapshot
import com.agentoffice.core.Summarizer.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Port of the VS Code extension's src/test/core.test.ts. */
class CoreTest {

    private val now = 1_800_000_000_000L

    private fun tmp(): Path = Files.createTempDirectory("agent-office-")

    private fun write(file: Path, content: String, mtime: Long? = null) {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        if (mtime != null) {
            Files.setLastModifiedTime(file, FileTime.fromMillis(mtime))
        }
    }

    private fun def(name: String, runtime: RuntimeId = RuntimeId.CLAUDE_CODE, description: String? = null) =
        AgentDefinition("${runtime.id}:$name", name, runtime, Scope.USER, description = description)

    private fun okClaude(signals: List<ActivitySignal> = emptyList()) =
        RuntimeReport(RuntimeHealth(RuntimeId.CLAUDE_CODE, HealthStatus.OK), signals)

    private fun sig(name: String, state: SignalState, at: Long = now) =
        ActivitySignal(RuntimeId.CLAUDE_CODE, name, state, at)

    @Test
    fun `parseFrontmatter reads name, color and description only`() {
        assertEquals(
            Frontmatter(name = "tech-lead", color = "blue"),
            parseFrontmatter("---\nname: tech-lead\ncolor: \"blue\"\ntools: Read\n---\nBody"),
        )
        assertEquals(Frontmatter(), parseFrontmatter("no frontmatter"))
        assertEquals(
            Frontmatter(name = "helper", description = "Turns ideas into wireframes."),
            parseFrontmatter("---\nname: helper\ndescription: >\n  Turns ideas into\n  wireframes.\nmodel: opus\n---\n"),
        )
    }

    @Test
    fun `discovery - user and project, project wins, missing folders ignored`() {
        val home = tmp()
        val ws = tmp()
        write(home.resolve(".claude/agents/a.md"), "---\nname: alpha\n---\n")
        write(home.resolve(".claude/agents/b.md"), "---\nname: beta\ncolor: red\n---\n")
        write(ws.resolve(".claude/agents/b2.md"), "---\nname: beta\ncolor: green\n---\n")
        write(ws.resolve(".github/agents/reviewer.agent.md"), "no frontmatter")
        val result = discoverDefinitions(definitionSources(home.resolve(".claude"), home, listOf(ws)))
        assertEquals(0, result.errors.size)
        val byId = result.definitions.associateBy { it.id }
        assertEquals(listOf("claude-code:alpha", "claude-code:beta", "copilot:reviewer"), byId.keys.sorted())
        assertEquals(Scope.PROJECT, byId["claude-code:beta"]!!.scope)
        assertEquals("green", byId["claude-code:beta"]!!.color)
    }

    private class ClaudeFixture(val dir: Path) {
        val cwd = "/Users/x/projet"
        val proj: Path = dir.resolve("projects").resolve(ClaudeCodeAdapter.projectDirName(cwd))
        fun session(pid: Long, id: String, status: String) {
            val file = dir.resolve("sessions").resolve("$pid.json")
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                """{"pid":$pid,"sessionId":"$id","cwd":"$cwd","status":"$status","updatedAt":${1_800_000_000_000L - 1000}}""",
            )
        }
    }

    @Test
    fun `Claude Code - live busy session with main agent is active, dead session ignored`() {
        val f = ClaudeFixture(tmp())
        f.session(100, "s-live", "busy")
        f.session(200, "s-dead", "busy")
        f.session(300, "s-idle", "idle")
        write(f.proj.resolve("s-live.jsonl"), "{\"type\":\"agent-setting\",\"agentSetting\":\"product-manager\"}\n{\"type\":\"user\"}\n")
        write(f.proj.resolve("s-dead.jsonl"), "{\"type\":\"agent-setting\",\"agentSetting\":\"qa-engineer\"}\n")
        write(f.proj.resolve("s-idle.jsonl"), "{\"type\":\"agent-setting\",\"agentSetting\":\"tech-lead\"}\n")
        val adapter = ClaudeCodeAdapter(
            ClaudeCodeAdapter.Options(f.dir, subagentSilenceMs = 60_000, now = { now }, isPidAlive = { it != 200L }),
        )
        val report = adapter.read()
        assertEquals(HealthStatus.OK, report.health.status)
        val states = report.signals.associate { it.agentName to it.state }
        assertEquals(mapOf("product-manager" to SignalState.ACTIVE, "tech-lead" to SignalState.IDLE), states)
    }

    @Test
    fun `Claude Code - unfinished subagents active, finished ignored, silent unknown`() {
        val f = ClaudeFixture(tmp())
        f.session(100, "s1", "idle")
        write(f.proj.resolve("s1.jsonl"), "{\"type\":\"user\"}\n")
        val sub = f.proj.resolve("s1/subagents")
        write(sub.resolve("agent-a.meta.json"), """{"agentType":"tech-lead"}""")
        write(sub.resolve("agent-a.jsonl"), "{}", now - 5_000)
        write(sub.resolve("agent-b.meta.json"), """{"agentType":"qa-engineer","finished":true}""")
        write(sub.resolve("agent-b.jsonl"), "{}", now - 5_000)
        write(sub.resolve("agent-c.meta.json"), """{"agentType":"ux-ui-designer"}""")
        write(sub.resolve("agent-c.jsonl"), "{}", now - 30 * 60_000)
        write(sub.resolve("agent-d.meta.json"), """{"agentType":"developer-backend"}""")
        write(sub.resolve("agent-d.jsonl"), "{}", now - 24 * 3600_000)
        // The meta files were just written: pretend they are as old as their transcripts.
        for (name in listOf("a", "c", "d")) {
            val t = Files.getLastModifiedTime(sub.resolve("agent-$name.jsonl"))
            Files.setLastModifiedTime(sub.resolve("agent-$name.meta.json"), t)
        }
        val adapter = ClaudeCodeAdapter(
            ClaudeCodeAdapter.Options(f.dir, subagentSilenceMs = 20 * 60_000, now = { now }, isPidAlive = { true }),
        )
        val states = adapter.read().signals.associate { it.agentName to it.state }
        assertEquals(mapOf("tech-lead" to SignalState.ACTIVE, "ux-ui-designer" to SignalState.UNKNOWN), states)
    }

    @Test
    fun `Claude Code - missing session registry means unavailable source`() {
        val adapter = ClaudeCodeAdapter(ClaudeCodeAdapter.Options(tmp(), subagentSilenceMs = 1))
        assertEquals(HealthStatus.UNAVAILABLE, adapter.read().health.status)
    }

    @Test
    fun `normalize - active over unknown over inactive, unavailable source means unknown`() {
        val defs = listOf(def("a"), def("b"), def("c"), def("cop", RuntimeId.COPILOT))
        val statuses = Normalize.normalize(
            defs,
            listOf(
                okClaude(
                    listOf(
                        sig("a", SignalState.IDLE, 1), sig("a", SignalState.ACTIVE, 2),
                        sig("b", SignalState.UNKNOWN, 3), sig("Explore", SignalState.ACTIVE, 4),
                    ),
                ),
                Copilot.read(),
            ),
        )
        assertEquals(
            listOf(
                Triple("a", AgentState.ACTIVE, 2L), Triple("b", AgentState.UNKNOWN, 3L),
                Triple("c", AgentState.INACTIVE, null), Triple("cop", AgentState.UNKNOWN, null),
            ),
            statuses.map { Triple(it.name, it.state, it.updatedAt) },
        )
        val down = Normalize.normalize(
            listOf(def("a")),
            listOf(RuntimeReport(RuntimeHealth(RuntimeId.CLAUDE_CODE, HealthStatus.UNAVAILABLE))),
        )
        assertEquals(AgentState.UNKNOWN, down[0].state)
    }

    @Test
    fun `slots - exactly ten, stable alphabetical order, pinned first, overflow`() {
        val names = listOf("zeta", "alpha", "Beta", "gamma", "delta", "eps", "eta", "theta", "iota", "kappa", "lambda")
        val statuses = Normalize.normalize(names.map { def(it) }, listOf(okClaude()))
        val (slots, overflow) = assignSlots(statuses)
        assertEquals(10, slots.size)
        assertEquals(
            listOf("alpha", "Beta", "delta", "eps", "eta", "gamma", "iota", "kappa", "lambda", "theta"),
            slots.map { it.agent?.name },
        )
        assertEquals(listOf("zeta"), overflow.map { it.name })
        val pinned = assignSlots(statuses, listOf("zeta"))
        assertEquals("zeta", pinned.slots[0].agent?.name)
        assertEquals(listOf("theta"), pinned.overflow.map { it.name })

        val few = assignSlots(Normalize.normalize(listOf(def("solo")), listOf(okClaude())))
        assertEquals(10, few.slots.size)
        assertEquals(1, few.slots.count { it.agent != null })
    }

    @Test
    fun `rooms - roles from name, private offices, open space, setting wins`() {
        assertEquals(AgentRole.PM, roleOf("product-manager"))
        assertEquals(AgentRole.CHALLENGER, roleOf("product-challenger"))
        assertEquals(AgentRole.DESIGN, roleOf("ux-ui-designer"))
        assertEquals(AgentRole.ENGINEERING, roleOf("developer-frontend"))
        assertEquals(AgentRole.ENGINEERING, roleOf("qa-engineer"))
        assertEquals(AgentRole.PM, roleOf("administrateur", mapOf("administrateur" to "pm")))

        // Mute name: the description decides; the first keyword met wins.
        assertEquals(AgentRole.PM, roleOf("alice", emptyMap(), "Acts as the product manager and talks to the designer."))
        assertEquals(AgentRole.CHALLENGER, roleOf("bob", emptyMap(), "Plays devil's advocate on every product idea."))
        assertEquals(AgentRole.DESIGN, roleOf("carol", emptyMap(), "Draws wireframes and checks the UX of each screen."))
        assertEquals(AgentRole.ENGINEERING, roleOf("dave", emptyMap(), "Writes migrations."))
        assertEquals(AgentRole.ENGINEERING, roleOf("dave"))
        // Technical name: the description is not consulted.
        assertEquals(AgentRole.ENGINEERING, roleOf("developer-frontend", emptyMap(), "Builds the UI from the mockups."))

        val team = listOf(
            "administrateur", "developer-backend", "developer-frontend", "documentation-engineer",
            "product-challenger", "product-manager", "qa-engineer", "tech-lead", "ux-ui-designer",
        )
        val snap = buildSnapshot(team.map { def(it) }, listOf(okClaude()), now)
        val ws = snap.slots.filter { it.agent != null }.associate { it.agent!!.name to it.workstation }
        assertEquals(0, snap.overflow.size)
        assertEquals(Workstation.OFFICE_PM, ws["product-manager"])
        assertEquals(Workstation.OFFICE_CHALLENGER, ws["product-challenger"])
        assertEquals(Workstation.OFFICE_DESIGN, ws["ux-ui-designer"])
        for (n in listOf("administrateur", "developer-backend", "developer-frontend", "documentation-engineer", "qa-engineer", "tech-lead")) {
            assertTrue(ws[n]!!.id.startsWith("open-"))
        }
        assertEquals(team.size, ws.values.toSet().size)

        // Agents with mute names: the description gives them a private office.
        val generic = buildSnapshot(
            listOf(
                def("alice", description = "Product owner of the team."),
                def("bob", description = "Challenges every idea."),
                def("carol", description = "UX designer."),
                def("dave"),
            ),
            listOf(okClaude()),
            now,
        )
        val gws = generic.slots.filter { it.agent != null }.associate { it.agent!!.name to it.workstation }
        assertEquals(
            mapOf(
                "alice" to Workstation.OFFICE_PM, "bob" to Workstation.OFFICE_CHALLENGER,
                "carol" to Workstation.OFFICE_DESIGN, "dave" to Workstation.OPEN_1,
            ),
            gws,
        )
        // The description never reaches the view.
        assertFalse(Json.gson.toJson(generic).contains("Product owner"))

        // Ten engineers: seven open-space desks then the free offices as overflow.
        val many = buildSnapshot((0 until 10).map { def("dev-$it") }, listOf(okClaude()), now)
        val used = many.slots.map { it.workstation }
        assertEquals(10, used.toSet().size)
        assertTrue(used.contains(Workstation.OFFICE_PM))
    }

    @Test
    fun `delegations - real launch and finish, only between shown agents`() {
        val f = ClaudeFixture(tmp())
        f.session(100, "s1", "idle")
        write(f.proj.resolve("s1.jsonl"), "{\"type\":\"agent-setting\",\"agentSetting\":\"product-manager\"}\n")
        val sub = f.proj.resolve("s1/subagents")
        write(sub.resolve("agent-a.meta.json"), """{"agentType":"tech-lead","spawnDepth":1}""")
        write(sub.resolve("agent-a.jsonl"), "{}", now - 5_000)
        write(sub.resolve("agent-b.meta.json"), """{"agentType":"qa-engineer","spawnDepth":1,"finished":true}""", now - 60_000)
        write(sub.resolve("agent-b.jsonl"), "{}", now - 60_000)
        write(sub.resolve("agent-c.meta.json"), """{"agentType":"qa-engineer","finished":true}""", now - 3600_000)
        write(sub.resolve("agent-c.jsonl"), "{}", now - 3600_000)
        write(sub.resolve("agent-d.meta.json"), """{"agentType":"Explore","spawnDepth":1}""")
        write(sub.resolve("agent-d.jsonl"), "{}", now - 5_000)
        Files.setLastModifiedTime(sub.resolve("agent-a.meta.json"), FileTime.fromMillis(now - 5_000))
        Files.setLastModifiedTime(sub.resolve("agent-d.meta.json"), FileTime.fromMillis(now - 5_000))
        val adapter = ClaudeCodeAdapter(
            ClaudeCodeAdapter.Options(f.dir, subagentSilenceMs = 60_000, now = { now }, isPidAlive = { true }),
        )
        val report = adapter.read()
        val dl = report.delegations.map { Triple(it.from, it.to, it.finishedAt != null) }.sortedBy { it.second }
        assertEquals(
            listOf(
                Triple("product-manager", "Explore", false),
                Triple("product-manager", "qa-engineer", true),
                Triple("product-manager", "tech-lead", false),
            ),
            dl,
        )
        val snap = buildSnapshot(listOf("product-manager", "tech-lead", "qa-engineer").map { def(it) }, listOf(report), now)
        assertEquals(listOf("qa-engineer", "tech-lead"), snap.interactions.map { it.to }.sorted())
        val pm = snap.slots.firstOrNull { it.agent?.name == "product-manager" }
        assertEquals(AgentState.INACTIVE, pm?.agent?.state)
    }

    @Test
    fun `summary - one, several, none, unknown, unavailable source`() {
        val defs = listOf(def("pm"), def("qa"))
        val one = summarize(buildSnapshot(defs, listOf(okClaude(listOf(sig("pm", SignalState.ACTIVE)))), now))
        assertEquals("Active agent: pm", one.text)
        val two = summarize(buildSnapshot(defs, listOf(okClaude(listOf(sig("pm", SignalState.ACTIVE), sig("qa", SignalState.ACTIVE)))), now))
        assertEquals("2 active agents: pm, qa", two.text)
        val none = summarize(buildSnapshot(defs, listOf(okClaude()), now))
        assertEquals("No active agents", none.text)
        val unknown = summarize(buildSnapshot(defs, listOf(okClaude(listOf(sig("qa", SignalState.UNKNOWN)))), now))
        assertEquals(SummaryKind.UNKNOWN, unknown.kind)
        assertNotEquals("No active agents", unknown.text)
        val down = summarize(
            buildSnapshot(defs, listOf(RuntimeReport(RuntimeHealth(RuntimeId.CLAUDE_CODE, HealthStatus.UNAVAILABLE))), now),
        )
        assertEquals(SummaryKind.UNKNOWN, down.kind)
        val empty = summarize(buildSnapshot(emptyList(), listOf(okClaude()), now))
        assertEquals("No agents found", empty.text)
    }

    @Test
    fun `snapshot JSON has the shape the office view expects`() {
        val snap = buildSnapshot(
            listOf(def("product-manager"), def("qa")),
            listOf(okClaude(listOf(sig("qa", SignalState.ACTIVE)))),
            now,
        )
        val json = Json.gson.toJson(snap)
        assertTrue(json.contains("\"workstation\":\"office-pm\""))
        assertTrue(json.contains("\"state\":\"active\""))
        assertTrue(json.contains("\"runtime\":\"claude-code\""))
        assertTrue(json.contains("\"status\":\"ok\""))
        assertFalse(json.contains("\"updatedAt\":null"))
    }
}
