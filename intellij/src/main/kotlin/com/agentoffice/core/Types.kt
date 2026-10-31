package com.agentoffice.core

import com.google.gson.annotations.SerializedName

/** Runtime that hosts an agent definition. */
enum class RuntimeId(val id: String) {
    @SerializedName("claude-code") CLAUDE_CODE("claude-code"),
    @SerializedName("copilot") COPILOT("copilot");

    override fun toString(): String = id
}

enum class Scope {
    @SerializedName("user") USER,
    @SerializedName("project") PROJECT,
}

/** An agent definition found on disk (only the fields the office needs). */
data class AgentDefinition(
    /** Normalized identifier: runtime + name, e.g. "claude-code:tech-lead". */
    val id: String,
    val name: String,
    val runtime: RuntimeId,
    val scope: Scope,
    /** Color declared in the frontmatter (`color:`), if any. */
    val color: String? = null,
    /**
     * Frontmatter `description:`, only used to guess the room of an agent whose name says nothing.
     * It stays on the plugin side: it is never sent to the view.
     */
    val description: String? = null,
)

/** active: working; idle: alive but waiting; unknown: a signal exists but is inconclusive. */
enum class SignalState {
    @SerializedName("active") ACTIVE,
    @SerializedName("idle") IDLE,
    @SerializedName("unknown") UNKNOWN,
}

/** Raw signal emitted by a runtime adapter for one agent instance. */
data class ActivitySignal(
    val runtime: RuntimeId,
    val agentName: String,
    val state: SignalState,
    /** Timestamp (ms) of the last observed update of this signal. */
    val observedAt: Long,
)

/** ok: readable source; unavailable: access error; unsupported: no known activity signal. */
enum class HealthStatus {
    @SerializedName("ok") OK,
    @SerializedName("unavailable") UNAVAILABLE,
    @SerializedName("unsupported") UNSUPPORTED,
}

data class RuntimeHealth(
    val runtime: RuntimeId,
    val status: HealthStatus,
    val message: String? = null,
)

/**
 * A real delegation: the main agent of a session launched a subagent.
 * Only names and timestamps are kept (never the prompt itself).
 */
data class Delegation(
    val id: String,
    val from: String,
    val to: String,
    val runtime: RuntimeId,
    val startedAt: Long,
    /** Present when the subagent is marked finished. */
    val finishedAt: Long? = null,
)

data class RuntimeReport(
    val health: RuntimeHealth,
    val signals: List<ActivitySignal> = emptyList(),
    val delegations: List<Delegation> = emptyList(),
)

enum class AgentState {
    @SerializedName("active") ACTIVE,
    @SerializedName("inactive") INACTIVE,
    @SerializedName("unknown") UNKNOWN,
}

data class AgentStatus(
    val id: String,
    val name: String,
    val runtime: RuntimeId,
    val color: String? = null,
    val state: AgentState,
    /** Last known update of the state (ms), or null. */
    val updatedAt: Long? = null,
)

/** Workstations: three private offices and the engineering open space. */
enum class Workstation(val id: String) {
    @SerializedName("office-pm") OFFICE_PM("office-pm"),
    @SerializedName("office-challenger") OFFICE_CHALLENGER("office-challenger"),
    @SerializedName("office-design") OFFICE_DESIGN("office-design"),
    @SerializedName("open-1") OPEN_1("open-1"),
    @SerializedName("open-2") OPEN_2("open-2"),
    @SerializedName("open-3") OPEN_3("open-3"),
    @SerializedName("open-4") OPEN_4("open-4"),
    @SerializedName("open-5") OPEN_5("open-5"),
    @SerializedName("open-6") OPEN_6("open-6"),
    @SerializedName("open-7") OPEN_7("open-7");

    override fun toString(): String = id

    companion object {
        val OFFICES: List<Workstation> = listOf(OFFICE_PM, OFFICE_CHALLENGER, OFFICE_DESIGN)
        val OPEN_DESKS: List<Workstation> = listOf(OPEN_1, OPEN_2, OPEN_3, OPEN_4, OPEN_5, OPEN_6, OPEN_7)
    }
}

/** Role of an agent, which decides its room. */
enum class AgentRole(val id: String) {
    PM("pm"), CHALLENGER("challenger"), DESIGN("design"), ENGINEERING("engineering");

    companion object {
        fun fromId(id: String?): AgentRole? = entries.firstOrNull { it.id == id }
    }
}

data class Slot(
    val index: Int,
    val agent: AgentStatus? = null,
    var workstation: Workstation? = null,
)

/** Interaction sent to the view: who delegated to whom, and when. */
data class Interaction(
    val id: String,
    val from: String,
    val to: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

/** Snapshot sent to the view: nothing but names, states and times. */
data class OfficeSnapshot(
    val slots: List<Slot>,
    /** Agents discovered but not shown (beyond ten). */
    val overflow: List<AgentStatus>,
    val discoveredCount: Int,
    val health: List<RuntimeHealth>,
    /** Discovery warnings (unreadable folders, etc.). */
    val notices: List<String>,
    /** Recent or ongoing delegations between shown agents. */
    val interactions: List<Interaction>,
    val generatedAt: Long,
)

const val SLOT_COUNT = 10
