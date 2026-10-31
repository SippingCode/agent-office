package com.agentoffice.core

object Placement {
    private val ROLE_OFFICE = mapOf(
        AgentRole.PM to Workstation.OFFICE_PM,
        AgentRole.CHALLENGER to Workstation.OFFICE_CHALLENGER,
        AgentRole.DESIGN to Workstation.OFFICE_DESIGN,
    )

    /** A name that clearly denotes a technical profile: its description is not consulted. */
    private val ENGINEERING_NAME =
        Regex("dev|engineer|qa|test|tech|lead|architect|backend|frontend|fullstack|ops|admin|doc|secur|data|code|review")

    private val PM_NAME = Regex("product[-_ ]?manager|^pm$|^po$|product[-_ ]?owner")
    private val CHALLENGER_NAME = Regex("challenger")
    private val DESIGN_NAME = Regex("(^|[-_ ])(ux|ui)([-_ ]|$)|design")

    /** Keywords searched in the description of an agent whose name is not recognized (English and French). */
    private val DESCRIPTION_RULES: List<Pair<AgentRole, Regex>> = listOf(
        AgentRole.PM to Regex("product[-_ ]?(manager|owner)|project manager|chef de (produit|projet)|orchestrat|coordinat"),
        AgentRole.CHALLENGER to Regex("challeng|devil'?s advocate|avocat du diable|brainstorm"),
        AgentRole.DESIGN to Regex("\\bux\\b|\\bui\\b|user experience|user interface|designer|wireframe|mockup|maquette|figma"),
    )

    /**
     * Role of an agent, which decides its room:
     *  1. the "agent rooms" setting;
     *  2. the agent's name;
     *  3. for a name that says nothing (neither a known role nor a technical profile), its description:
     *     the keyword that appears first wins.
     * Anything that is neither PM, challenger nor design joins the engineering team (open space).
     */
    fun roleOf(name: String, overrides: Map<String, String> = emptyMap(), description: String = ""): AgentRole {
        AgentRole.fromId(overrides[name])?.let { return it }
        val n = name.lowercase()
        if (PM_NAME.containsMatchIn(n)) return AgentRole.PM
        if (CHALLENGER_NAME.containsMatchIn(n)) return AgentRole.CHALLENGER
        if (DESIGN_NAME.containsMatchIn(n)) return AgentRole.DESIGN
        if (ENGINEERING_NAME.containsMatchIn(n)) return AgentRole.ENGINEERING
        val d = description.lowercase()
        var best: Pair<AgentRole, Int>? = null
        for ((role, re) in DESCRIPTION_RULES) {
            val m = re.find(d)
            if (m != null && (best == null || m.range.first < best.second)) {
                best = role to m.range.first
            }
        }
        return best?.first ?: AgentRole.ENGINEERING
    }

    /**
     * Gives a workstation to each shown agent (in the stable slot order):
     *  1. PM, challenger and designer take their private office if it is free;
     *  2. the others take the open space (7 desks);
     *  3. when the open space is full, free private offices are used as overflow.
     */
    fun placeAgents(
        agents: List<AgentStatus>,
        overrides: Map<String, String> = emptyMap(),
        descriptions: Map<String, String> = emptyMap(),
    ): Map<String, Workstation> {
        val result = LinkedHashMap<String, Workstation>()
        val taken = HashSet<Workstation>()
        val pending = mutableListOf<AgentStatus>()

        for (agent in agents) {
            val role = roleOf(agent.name, overrides, descriptions[agent.id] ?: "")
            val office = ROLE_OFFICE[role]
            if (office != null && office !in taken) {
                taken += office
                result[agent.id] = office
            } else {
                pending += agent
            }
        }
        val free = (Workstation.OPEN_DESKS + Workstation.OFFICES).filter { it !in taken }.toMutableList()
        for (agent in pending) {
            val ws = free.removeFirstOrNull() ?: break
            result[agent.id] = ws
        }
        return result
    }
}
