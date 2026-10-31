package com.agentoffice.core

/**
 * Combines definitions and signals into one state per agent.
 * - active if at least one live instance is active;
 * - otherwise unknown if an instance is inconclusive or the runtime source is unreliable;
 * - otherwise inactive (readable source and no active instance).
 * Signals of undefined agents (built-in agents, other types) are ignored.
 */
object Normalize {
    fun normalize(definitions: List<AgentDefinition>, reports: List<RuntimeReport>): List<AgentStatus> =
        definitions.map { def ->
            val report = reports.firstOrNull { it.health.runtime == def.runtime }
            val signals = report?.signals?.filter { it.agentName == def.name } ?: emptyList()
            val state = when {
                signals.any { it.state == SignalState.ACTIVE } -> AgentState.ACTIVE
                report == null || report.health.status != HealthStatus.OK ||
                    signals.any { it.state == SignalState.UNKNOWN } -> AgentState.UNKNOWN
                else -> AgentState.INACTIVE
            }
            val relevant = if (state == AgentState.ACTIVE) signals.filter { it.state == SignalState.ACTIVE } else signals
            val updatedAt = relevant.maxOfOrNull { it.observedAt }
            AgentStatus(def.id, def.name, def.runtime, def.color, state, updatedAt)
        }
}
