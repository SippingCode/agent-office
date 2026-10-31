package com.agentoffice.core

import java.text.Collator
import java.util.Locale

/**
 * Stable placement rule:
 *  1. agents listed in the "pinned agents" setting, in that order;
 *  2. then the others, sorted by name (alphabetical, case-insensitive),
 *     then by runtime (claude-code before copilot) on ties.
 * The first ten take desks 1 to 10; the rest are listed as overflow.
 * The activity state never affects placement, so each agent keeps its desk.
 */
object Slots {
    private val collator: Collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }

    fun orderAgents(agents: List<AgentStatus>, pinned: List<String>): List<AgentStatus> {
        fun rank(a: AgentStatus): Int {
            val i = pinned.indexOf(a.name)
            return if (i == -1) Int.MAX_VALUE else i
        }
        return agents.sortedWith(
            compareBy<AgentStatus> { rank(it) }
                .then { a, b -> collator.compare(a.name, b.name) }
                .thenBy { it.runtime.id },
        )
    }

    data class Assignment(val slots: List<Slot>, val overflow: List<AgentStatus>)

    fun assignSlots(agents: List<AgentStatus>, pinned: List<String> = emptyList()): Assignment {
        val ordered = orderAgents(agents, pinned)
        val slots = List(SLOT_COUNT) { index -> Slot(index, ordered.getOrNull(index)) }
        return Assignment(slots, ordered.drop(SLOT_COUNT))
    }
}
