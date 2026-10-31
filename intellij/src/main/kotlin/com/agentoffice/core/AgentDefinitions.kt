package com.agentoffice.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

data class DefinitionSource(val runtime: RuntimeId, val scope: Scope, val dir: Path)

data class DiscoveryError(val dir: String, val message: String)

data class DiscoveryResult(
    val definitions: List<AgentDefinition>,
    /** Folders that exist but cannot be read (access errors). */
    val errors: List<DiscoveryError>,
)

data class Frontmatter(val name: String? = null, val color: String? = null, val description: String? = null)

/**
 * Read-only discovery of agent definitions.
 * Claude Code: ~/.claude/agents (user) and <workspace>/.claude/agents (project).
 * Copilot: ~/.copilot/agents (user) and <workspace>/.github/agents (project).
 */
object AgentDefinitions {

    fun definitionSources(claudeDir: Path, homeDir: Path, workspaceDirs: List<Path>): List<DefinitionSource> {
        val sources = mutableListOf(
            DefinitionSource(RuntimeId.CLAUDE_CODE, Scope.USER, claudeDir.resolve("agents")),
            DefinitionSource(RuntimeId.COPILOT, Scope.USER, homeDir.resolve(".copilot").resolve("agents")),
        )
        for (ws in workspaceDirs) {
            sources += DefinitionSource(RuntimeId.CLAUDE_CODE, Scope.PROJECT, ws.resolve(".claude").resolve("agents"))
            sources += DefinitionSource(RuntimeId.COPILOT, Scope.PROJECT, ws.resolve(".github").resolve("agents"))
        }
        return sources
    }

    private val FRONTMATTER = Regex("^﻿?---\r?\n([\\s\\S]*?)\r?\n---")
    private val KEY_VALUE = Regex("^(name|color|description):\\s*(.*?)\\s*$")
    private val BLOCK_START = Regex("^[|>][-+]?$")
    private val BLOCK_LINE = Regex("^\\s+\\S|^\\s*$")
    private val QUOTED = Regex("^(['\"])(.*)\\1$")

    /**
     * Extracts the simple `name`, `color` and `description` fields of a YAML frontmatter, interpreting nothing else.
     * `description` also accepts a multi-line block (`|` or `>`), whose indented lines are joined.
     */
    fun parseFrontmatter(text: String): Frontmatter {
        val match = FRONTMATTER.find(text) ?: return Frontmatter()
        val lines = match.groupValues[1].split(Regex("\r?\n"))
        var name: String? = null
        var color: String? = null
        var description: String? = null
        var i = 0
        while (i < lines.size) {
            val kv = KEY_VALUE.find(lines[i])
            if (kv == null) {
                i++
                continue
            }
            val key = kv.groupValues[1]
            var value = kv.groupValues[2]
            if (key == "description" && BLOCK_START.matches(value)) {
                val block = mutableListOf<String>()
                while (i + 1 < lines.size && BLOCK_LINE.containsMatchIn(lines[i + 1])) {
                    i++
                    block += lines[i].trim()
                }
                value = block.filter { it.isNotEmpty() }.joinToString(" ")
            }
            value = QUOTED.replace(value, "$2").trim()
            if (value.isNotEmpty()) {
                when (key) {
                    "name" -> name = value
                    "color" -> color = value
                    "description" -> description = value
                }
            }
            i++
        }
        return Frontmatter(name, color, description)
    }

    private fun fallbackName(file: Path): String =
        file.fileName.toString()
            .replace(Regex("\\.agent\\.md$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.md$", RegexOption.IGNORE_CASE), "")

    private fun listMarkdown(dir: Path, depth: Int = 0): List<Path> {
        val files = mutableListOf<Path>()
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                val fileName = entry.fileName.toString()
                if (Files.isRegularFile(entry) && fileName.lowercase().endsWith(".md")) {
                    files.add(entry)
                } else if (Files.isDirectory(entry) && depth < 2 && !fileName.startsWith(".")) {
                    files.addAll(listMarkdown(entry, depth + 1))
                }
            }
        }
        return files
    }

    private fun errorCode(err: Exception): String = when (err) {
        is AccessDeniedException -> "EACCES"
        is NoSuchFileException -> "ENOENT"
        is NotDirectoryException -> "ENOTDIR"
        else -> err.message ?: err.javaClass.simpleName
    }

    /** Read-only: only enumerates and reads the .md files of the given locations. */
    fun discoverDefinitions(sources: List<DefinitionSource>): DiscoveryResult {
        val byId = LinkedHashMap<String, AgentDefinition>()
        val errors = mutableListOf<DiscoveryError>()

        for (source in sources) {
            val files: List<Path> = try {
                listMarkdown(source.dir)
            } catch (err: NoSuchFileException) {
                continue
            } catch (err: NotDirectoryException) {
                continue
            } catch (err: IOException) {
                errors += DiscoveryError(source.dir.toString(), errorCode(err))
                continue
            }
            for (file in files.sortedBy { it.toString() }) {
                val text = try {
                    Files.readString(file, StandardCharsets.UTF_8)
                } catch (err: IOException) {
                    errors += DiscoveryError(file.toString(), errorCode(err))
                    continue
                } catch (err: RuntimeException) {
                    // Malformed UTF-8 or similar: skip the file, as the extension does.
                    errors += DiscoveryError(file.toString(), errorCode(err))
                    continue
                }
                val fm = parseFrontmatter(text)
                val name = fm.name ?: fallbackName(file)
                val id = "${source.runtime.id}:$name"
                val existing = byId[id]
                // A project definition replaces a user definition with the same name.
                if (existing != null && existing.scope == Scope.PROJECT && source.scope == Scope.USER) {
                    continue
                }
                byId[id] = AgentDefinition(id, name, source.runtime, source.scope, fm.color, fm.description)
            }
        }
        return DiscoveryResult(byId.values.toList(), errors)
    }
}
