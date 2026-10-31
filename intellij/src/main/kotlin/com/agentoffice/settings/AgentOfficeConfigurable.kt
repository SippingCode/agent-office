package com.agentoffice.settings

import com.agentoffice.core.AgentRole
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

/** Settings | Tools | Agent Office. */
class AgentOfficeConfigurable : BoundConfigurable("Agent Office") {

    private val settings get() = AgentOfficeSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val state = settings.state
        return panel {
            row("Poll interval (seconds):") {
                intTextField(1..60)
                    .bindIntText(state::pollIntervalSeconds)
                    .comment("How often the Claude Code runtime signals are read.")
            }
            row("Subagent silence (minutes):") {
                intTextField(1..24 * 60)
                    .bindIntText(state::subagentSilenceMinutes)
                    .comment("An unfinished subagent whose transcript has not changed for this long is shown as \"unknown\" instead of working.")
            }
            row("Pinned agents:") {
                textField()
                    .columns(40)
                    .bindText(
                        { state.pinnedAgents.joinToString(", ") },
                        { value -> state.pinnedAgents = splitList(value).toMutableList() },
                    )
                    .comment("Agent names placed first among the ten desks, in this order, separated by commas. The others follow in alphabetical order.")
            }
            row("Claude Code config folder:") {
                textField()
                    .columns(40)
                    .bindText({ state.claudeConfigDir ?: "" }, { value -> state.claudeConfigDir = value.trim() })
                    .comment("Empty = \$CLAUDE_CONFIG_DIR or ~/.claude.")
            }
            row("Agent rooms:") {
                textArea()
                    .rows(4)
                    .columns(40)
                    .align(AlignX.FILL)
                    .bindText(
                        { state.agentRooms.entries.joinToString("\n") { "${it.key} = ${it.value}" } },
                        { value -> state.agentRooms = parseRooms(value).toMutableMap() },
                    )
                    .comment(
                        "Forces an agent's room, one <code>agent = room</code> per line. Rooms: " +
                            AgentRole.entries.joinToString(", ") { "<code>${it.id}</code>" } +
                            ". By default, the room is guessed from the agent's name, then from its description.",
                    )
            }
            row("Reduce motion:") {
                comboBox(ReduceMotion.entries.toList())
                    .bindItem({ settings.reduceMotion }, { value -> state.reduceMotion = (value ?: ReduceMotion.AUTO).id })
                    .comment("When on, the scene stays still. Auto follows the system \"reduce motion\" preference.")
            }
        }
    }

    override fun apply() {
        super.apply()
        settings.fireChanged()
    }

    private fun splitList(value: String): List<String> =
        value.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    private fun parseRooms(value: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in value.split('\n', ',', ';')) {
            val parts = line.split('=', ':', limit = 2)
            if (parts.size != 2) continue
            val name = parts[0].trim()
            val room = parts[1].trim().lowercase()
            if (name.isNotEmpty() && AgentRole.fromId(room) != null) {
                out[name] = room
            }
        }
        return out
    }
}
