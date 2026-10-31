package com.agentoffice.core

/**
 * GitHub Copilot: agent definitions (~/.copilot/agents, .github/agents) are discovered,
 * but no documented local interface exposes the current activity of an agent.
 * Their state stays "unknown" rather than being guessed.
 */
object Copilot {
    fun read(): RuntimeReport = RuntimeReport(
        health = RuntimeHealth(
            runtime = RuntimeId.COPILOT,
            status = HealthStatus.UNSUPPORTED,
            message = "Copilot exposes no local activity signal: status unknown.",
        ),
    )
}
