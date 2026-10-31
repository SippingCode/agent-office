package com.agentoffice.core

enum class SummaryKind(val id: String) { ACTIVE("active"), IDLE("idle"), UNKNOWN("unknown"), EMPTY("empty") }

data class Summary(
    val kind: SummaryKind,
    /** Names of the active agents (including those beyond the ten desks). */
    val activeNames: List<String>,
    val unknownCount: Int,
    val text: String,
) {
    /** Shape expected by the office view (`summary.kind` is a lowercase string there). */
    fun toJsonMap(): Map<String, Any> = mapOf(
        "kind" to kind.id,
        "activeNames" to activeNames,
        "unknownCount" to unknownCount,
        "text" to text,
    )
}

/** Answer to "which agent is working right now?". */
object Summarizer {
    fun summarize(snapshot: OfficeSnapshot): Summary {
        val all = snapshot.slots.mapNotNull { it.agent } + snapshot.overflow
        val activeNames = all.filter { it.state == AgentState.ACTIVE }.map { it.name }
        val unknownCount = all.count { it.state == AgentState.UNKNOWN }

        if (all.isEmpty()) {
            val sourceDown = snapshot.health.any { it.status != HealthStatus.OK }
            return Summary(
                kind = if (sourceDown) SummaryKind.UNKNOWN else SummaryKind.EMPTY,
                activeNames = activeNames,
                unknownCount = unknownCount,
                text = if (sourceDown) "Unknown status — no agent source available" else "No agents found",
            )
        }
        if (activeNames.size == 1) {
            return Summary(SummaryKind.ACTIVE, activeNames, unknownCount, "Active agent: ${activeNames[0]}")
        }
        if (activeNames.size > 1) {
            return Summary(
                SummaryKind.ACTIVE, activeNames, unknownCount,
                "${activeNames.size} active agents: ${activeNames.joinToString(", ")}",
            )
        }
        if (unknownCount > 0) {
            val text = if (unknownCount == all.size) {
                "Unknown status — activity cannot be detected"
            } else {
                "Unknown status for $unknownCount agent${if (unknownCount > 1) "s" else ""} — cannot confirm they are idle"
            }
            return Summary(SummaryKind.UNKNOWN, activeNames, unknownCount, text)
        }
        return Summary(SummaryKind.IDLE, activeNames, unknownCount, "No active agents")
    }
}
