package com.agentoffice.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import java.nio.file.Path
import java.nio.file.Paths

/** Reduced motion: `on`, `off`, or `auto` (follows the OS "prefers-reduced-motion" setting). */
enum class ReduceMotion(val id: String) { AUTO("auto"), ON("on"), OFF("off") }

/** Agent Office settings (application level), the equivalent of the `agentOffice.*` VS Code settings. */
@Service(Service.Level.APP)
@State(name = "AgentOfficeSettings", storages = [Storage("agentOffice.xml")])
class AgentOfficeSettings : SimplePersistentStateComponent<AgentOfficeSettings.State>(State()) {

    class State : BaseState() {
        /** How often (in seconds) the runtime signals are read. */
        var pollIntervalSeconds by property(2)

        /** An unfinished subagent whose transcript has not changed for this long is shown as "unknown". */
        var subagentSilenceMinutes by property(20)

        /** Agent names to place first among the ten desks, in this order. The others follow alphabetically. */
        var pinnedAgents by list<String>()

        /** Claude Code configuration folder. Empty = $CLAUDE_CONFIG_DIR or ~/.claude. */
        var claudeConfigDir by string("")

        /** Forces an agent's room: `pm`, `challenger`, `design` or `engineering`. */
        var agentRooms by map<String, String>()

        /** `auto`, `on` or `off`. */
        var reduceMotion by string("auto")
    }

    fun interface Listener {
        fun settingsChanged()
    }

    val pollMs: Long get() = maxOf(1, state.pollIntervalSeconds) * 1000L
    val silenceMs: Long get() = maxOf(1, state.subagentSilenceMinutes) * 60_000L
    val pinned: List<String> get() = state.pinnedAgents.toList()
    val rooms: Map<String, String> get() = state.agentRooms.toMap()
    val reduceMotion: ReduceMotion
        get() = ReduceMotion.entries.firstOrNull { it.id == state.reduceMotion } ?: ReduceMotion.AUTO

    val claudeDir: Path
        get() {
            val configured = state.claudeConfigDir?.trim().orEmpty()
            if (configured.isNotEmpty()) return Paths.get(expandHome(configured))
            val env = System.getenv("CLAUDE_CONFIG_DIR")?.trim().orEmpty()
            if (env.isNotEmpty()) return Paths.get(env)
            return homeDir.resolve(".claude")
        }

    fun fireChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("Agent Office settings", Listener::class.java)

        val homeDir: Path get() = Paths.get(System.getProperty("user.home"))

        fun getInstance(): AgentOfficeSettings =
            ApplicationManager.getApplication().getService(AgentOfficeSettings::class.java)

        private fun expandHome(path: String): String =
            if (path == "~" || path.startsWith("~/")) homeDir.toString() + path.substring(1) else path
    }
}
