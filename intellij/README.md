# Agent Office for JetBrains IDEs

The IntelliJ Platform port of the [Agent Office](../README.md) VS Code extension: a pixel-art office at the
bottom of your IDE that shows which of your **Claude Code custom agents** is working right now.
Read-only, 100% local.

It works in every JetBrains IDE built on platform 2025.1 or later (IntelliJ IDEA, PyCharm, WebStorm, GoLand,
PhpStorm, Rider, RustRover, CLion…).

## What you get

| VS Code extension | JetBrains plugin |
|---|---|
| *Agent Office* panel next to the Terminal | *Agent Office* tool window, anchored at the bottom |
| Status bar item “Active agent: …” | Status bar widget “● Active agent: …” (click to open the office) |
| `Agent Office: Show the agent office` | *Tools \| Agent Office: Show the Agent Office* (also in *Find Action*) |
| `Agent Office: Refresh agent status` | Refresh button in the tool window title, and *Find Action* |
| `agentOffice.*` settings | *Settings \| Tools \| Agent Office* |
| `workbench.reduceMotion` | *Reduce motion* setting (auto / on / off) |

The scene itself (`office.js`, `office.css`) is **shared with the VS Code extension**: the Gradle build copies
`../media/office.js` and `../media/office.css` into the plugin, and a tiny shim replaces `acquireVsCodeApi()`
with a JCEF bridge. The rules of the office (desks, rooms, colors, delegations, status) are a line-by-line
port of the extension's TypeScript to Kotlin, with the same test suite.

## Settings

*Settings | Tools | Agent Office*

| Setting | Default | What it does |
|---|---|---|
| Poll interval (seconds) | 2 | How often the Claude Code runtime signals are read |
| Subagent silence (minutes) | 20 | An unfinished subagent silent for this long is shown as “unknown” |
| Pinned agents | empty | Agents shown first among the ten desks, comma-separated |
| Claude Code config folder | empty | Empty = `$CLAUDE_CONFIG_DIR` or `~/.claude` |
| Agent rooms | empty | One `agent = room` per line; rooms: `pm`, `challenger`, `design`, `engineering` |
| Reduce motion | auto | `on` keeps the scene still; `auto` follows the system preference |

Settings are stored in the IDE's `agentOffice.xml`.

## Install

1. Build or download `agent-office-intellij-<version>.zip`.
2. In the IDE: *Settings | Plugins | ⚙ | Install Plugin from Disk…*, pick the zip, restart.
3. Open *View | Tool Windows | Agent Office*, or click the status bar item.

## Build from source

Requirements: JDK 21 (a JetBrains Runtime works). Gradle is downloaded by the wrapper.

```bash
cd intellij
./gradlew test            # runs the test suite
./gradlew buildPlugin     # builds build/distributions/agent-office-intellij-<version>.zip
./gradlew runIde          # starts a sandbox IDE with the plugin installed
```

By default the build downloads IntelliJ IDEA Community `platformVersion` (see `gradle.properties`).
To compile against an IDE already installed on your machine instead:

```bash
./gradlew buildPlugin -PlocalIdePath="/Applications/IntelliJ IDEA.app/Contents"
```

## How it works

Same as the VS Code extension, see the [technical docs](../docs/TECHNICAL.md). In short, every 2 seconds the
plugin reads, read-only:

1. `~/.claude/agents/**.md` and `<project>/.claude/agents/**.md` for the agent definitions
   (`name`, `color`, `description` from the frontmatter only);
2. `~/.claude/sessions/<pid>.json` for live sessions and their main agent;
3. `~/.claude/projects/<project>/<sessionId>/subagents/agent-*.meta.json` for subagents and delegations.

Only names, states, desks, colors and timestamps reach the view. No prompt, code or conversation is read
beyond the first 64 KB of a transcript header, which is scanned for the `agent-setting` line only.

### Architecture

```
intellij/src/main/kotlin/com/agentoffice/
  core/AgentDefinitions.kt   read-only agent definitions (name, color, description)
  core/ClaudeCodeAdapter.kt  Claude Code adapter → status signals + delegations
  core/Copilot.kt            Copilot adapter: “not supported”
  core/Normalize.kt          definitions + signals → working / idle / unknown
  core/Slots.kt              stable rule for the ten desks
  core/Placement.kt          role (setting, name, description) → room and desk
  core/Summary.kt            answer to “which agent is working?”
  core/Snapshot.kt           minimal snapshot sent to the view
  core/Types.kt              shared types (same JSON shape as the extension)
  OfficeService.kt           per-project polling, listeners
  OfficeStartup.kt           starts polling when a project opens
  settings/                  persistent settings + Settings page
  ui/OfficeHtml.kt           page: inline office.css + office.js + JCEF shim
  ui/OfficePanel.kt          tool window content (JCEF, text fallback without JCEF)
  ui/OfficeStatusBarWidget.kt
  ui/OfficeToolWindowFactory.kt
  actions/                   Show / Refresh
```

## Known limitations

Those of the VS Code extension, plus:

- **JCEF required** for the pixel-art scene. IDE builds without JCEF (rare) show a text list of the desks instead.
- `auto` reduce-motion follows the `prefers-reduced-motion` media query as seen by the embedded Chromium.

## License

MIT, like the rest of the project.
