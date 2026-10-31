package com.agentoffice.core

data class SnapshotOptions(
    val pinned: List<String> = emptyList(),
    val rooms: Map<String, String> = emptyMap(),
    val notices: List<String> = emptyList(),
)

object SnapshotBuilder {
    fun buildSnapshot(
        definitions: List<AgentDefinition>,
        reports: List<RuntimeReport>,
        now: Long,
        options: SnapshotOptions = SnapshotOptions(),
    ): OfficeSnapshot {
        val statuses = Normalize.normalize(definitions, reports)
        val (slots, overflow) = Slots.assignSlots(statuses, options.pinned)
        val descriptions = definitions.filter { it.description != null }.associate { it.id to it.description!! }
        val placed = Placement.placeAgents(slots.mapNotNull { it.agent }, options.rooms, descriptions)
        for (slot in slots) {
            val agent = slot.agent ?: continue
            slot.workstation = placed[agent.id]
        }

        // Interactions limited to the agents shown in the office, and to the same runtime.
        val shown = slots.mapNotNull { it.agent }.associateBy { it.id }
        val interactions = mutableListOf<Interaction>()
        for (report in reports) {
            for (d in report.delegations) {
                val from = "${d.runtime.id}:${d.from}"
                val to = "${d.runtime.id}:${d.to}"
                if (from in shown && to in shown && from != to) {
                    interactions += Interaction(d.id, d.from, d.to, d.startedAt, d.finishedAt)
                }
            }
        }

        // Only runtimes with at least one definition matter for display.
        val runtimes = definitions.map { it.runtime }.toSet()
        val health = reports.map { it.health }
            .filter { it.runtime in runtimes || (definitions.isEmpty() && it.runtime == RuntimeId.CLAUDE_CODE) }
        return OfficeSnapshot(
            slots = slots,
            overflow = overflow,
            discoveredCount = statuses.size,
            health = health,
            notices = options.notices,
            interactions = interactions,
            generatedAt = now,
        )
    }
}
