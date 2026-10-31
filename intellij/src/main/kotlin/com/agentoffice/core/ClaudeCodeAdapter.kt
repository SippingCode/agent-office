package com.agentoffice.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Claude Code adapter — strictly read-only.
 *
 * Sources:
 *  1. `<claudeDir>/sessions/<pid>.json`: session registry kept by Claude Code
 *     (`pid`, `sessionId`, `cwd`, `status: "busy" | "idle"`, `updatedAt`, `agent?`).
 *     A session whose process no longer exists is expired and ignored.
 *  2. Main agent of a session: the `agent` field of the registry, otherwise the
 *     `{"type":"agent-setting","agentSetting":"<name>"}` entry at the head of the transcript.
 *  3. Subagents: `<claudeDir>/projects/<project>/<sessionId>/subagents/agent-*.meta.json`
 *     (`agentType`, `finished`, `spawnDepth`). An unfinished subagent of a live session is active.
 *  4. Delegations: a depth-1 subagent was launched by the main agent of its session.
 *     Launch = creation of the subagent transcript; finish = `finished: true` written.
 *
 * No conversation content is kept or forwarded: only the agent name, the state and a timestamp leave this module.
 */
class ClaudeCodeAdapter(private val options: Options) {

    data class Options(
        val claudeDir: Path,
        /** Beyond this, an unfinished and silent subagent becomes "unknown". */
        val subagentSilenceMs: Long,
        /** Beyond this, an unfinished and silent subagent is considered expired. */
        val subagentExpiryMs: Long = 6L * 60 * 60 * 1000,
        /** How long a finished delegation keeps being reported to the view. */
        val finishedWindowMs: Long = 10L * 60 * 1000,
        val now: () -> Long = System::currentTimeMillis,
        val isPidAlive: (Long) -> Boolean = ::defaultIsPidAlive,
    )

    private data class SessionRecord(
        val pid: Long,
        val sessionId: String,
        val cwd: String?,
        val status: String?,
        val agent: String?,
        val updatedAt: Long?,
        val statusUpdatedAt: Long?,
    )

    private data class SubagentInfo(
        val id: String,
        val agentType: String,
        val depth: Int,
        val startedAt: Long,
        val finishedAt: Long? = null,
        /** Absent for a finished subagent. */
        val signal: ActivitySignal? = null,
    )

    private data class MetaCacheEntry(val mtimeMs: Long, val finished: Boolean)

    private val agentSettingCache = HashMap<String, String?>()
    private val sessionDirCache = HashMap<String, Path>()
    private val metaCache = HashMap<Path, MetaCacheEntry>()

    private fun now(): Long = options.now()

    fun read(): RuntimeReport {
        val sessionsDir = options.claudeDir.resolve("sessions")
        val files: List<Path> = try {
            Files.newDirectoryStream(sessionsDir).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") } }
        } catch (err: NoSuchFileException) {
            return unavailable("Session registry not found ($sessionsDir).")
        } catch (err: IOException) {
            return unavailable("Session registry unreadable (${errorCode(err)}).")
        }

        val signals = mutableListOf<ActivitySignal>()
        val delegations = mutableListOf<Delegation>()
        var unreadable = 0
        val liveIds = HashSet<String>()

        for (file in files) {
            val session = try {
                parseSession(Files.readString(file, StandardCharsets.UTF_8))
            } catch (err: NoSuchFileException) {
                // Deleted in the meantime: nothing to conclude.
                continue
            } catch (err: Exception) {
                // File being written: flagged without concluding.
                unreadable++
                continue
            }
            if (session == null) {
                unreadable++
                continue
            }
            if (!options.isPidAlive(session.pid)) {
                continue // expired session
            }
            liveIds += session.sessionId
            val result = sessionSignals(session)
            signals += result.first
            delegations += result.second
        }

        for (key in agentSettingCache.keys.toList()) {
            if (key !in liveIds) {
                agentSettingCache.remove(key)
                sessionDirCache.remove(key)
            }
        }

        return RuntimeReport(
            health = RuntimeHealth(
                runtime = RuntimeId.CLAUDE_CODE,
                status = HealthStatus.OK,
                message = if (unreadable > 0) "$unreadable unreadable session entr${if (unreadable > 1) "ies" else "y"}." else null,
            ),
            signals = signals,
            delegations = delegations,
        )
    }

    private fun unavailable(message: String) = RuntimeReport(
        health = RuntimeHealth(RuntimeId.CLAUDE_CODE, HealthStatus.UNAVAILABLE, message),
    )

    private fun parseSession(text: String): SessionRecord? {
        val obj = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val pid = obj.get("pid")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return null
        val sessionId = obj.string("sessionId") ?: return null
        return SessionRecord(
            pid = pid,
            sessionId = sessionId,
            cwd = obj.string("cwd"),
            status = obj.string("status"),
            agent = obj.string("agent"),
            updatedAt = obj.long("updatedAt"),
            statusUpdatedAt = obj.long("statusUpdatedAt"),
        )
    }

    private fun sessionSignals(session: SessionRecord): Pair<List<ActivitySignal>, List<Delegation>> {
        val signals = mutableListOf<ActivitySignal>()
        val delegations = mutableListOf<Delegation>()
        val projectsDir = options.claudeDir.resolve("projects")
        var sessionDir = sessionDirCache[session.sessionId]
        if (sessionDir == null) {
            sessionDir = findSessionDir(projectsDir, session)
            if (sessionDir != null) {
                sessionDirCache[session.sessionId] = sessionDir
            }
        }

        val mainAgent = mainAgent(session, sessionDir)
        if (mainAgent != null) {
            val state = when (session.status) {
                "busy" -> SignalState.ACTIVE
                "idle" -> SignalState.IDLE
                else -> SignalState.UNKNOWN
            }
            signals += ActivitySignal(
                runtime = RuntimeId.CLAUDE_CODE,
                agentName = mainAgent,
                state = state,
                observedAt = session.statusUpdatedAt ?: session.updatedAt ?: now(),
            )
        }

        if (sessionDir != null) {
            val dir = sessionDir.resolve(session.sessionId).resolve("subagents")
            for (sub in subagents(dir)) {
                sub.signal?.let { signals += it }
                if (mainAgent != null && sub.depth == 1) {
                    delegations += Delegation(
                        id = "${session.sessionId}/${sub.id}",
                        from = mainAgent,
                        to = sub.agentType,
                        runtime = RuntimeId.CLAUDE_CODE,
                        startedAt = sub.startedAt,
                        finishedAt = sub.finishedAt,
                    )
                }
            }
        }
        return signals to delegations
    }

    private fun mainAgent(session: SessionRecord, sessionDir: Path?): String? {
        if (!session.agent.isNullOrEmpty()) {
            return session.agent
        }
        if (agentSettingCache.containsKey(session.sessionId)) {
            return agentSettingCache[session.sessionId]
        }
        if (sessionDir == null) {
            return null // transcript not created yet: retried on the next cycle
        }
        val setting = readAgentSetting(sessionDir.resolve("${session.sessionId}.jsonl"))
        agentSettingCache[session.sessionId] = setting
        return setting
    }

    private fun subagents(dir: Path): List<SubagentInfo> {
        val entries: List<Path> = try {
            Files.newDirectoryStream(dir).use { stream -> stream.toList() }
        } catch (err: IOException) {
            return emptyList()
        }
        val now = now()
        val expiry = options.subagentExpiryMs
        val finishedWindow = options.finishedWindowMs
        val out = mutableListOf<SubagentInfo>()
        for (metaPath in entries) {
            val entry = metaPath.fileName.toString()
            if (!entry.endsWith(".meta.json")) {
                continue
            }
            val metaStat = try {
                Files.readAttributes(metaPath, BasicFileAttributes::class.java)
            } catch (err: IOException) {
                continue
            }
            val metaMtime = metaStat.lastModifiedTime().toMillis()
            val cached = metaCache[metaPath]
            // A subagent finished long ago no longer changes: no need to re-read its file.
            if (cached != null && cached.finished && cached.mtimeMs == metaMtime && now - metaMtime > finishedWindow) {
                continue
            }
            val meta: JsonObject = try {
                JsonParser.parseString(Files.readString(metaPath, StandardCharsets.UTF_8)).asJsonObject
            } catch (err: Exception) {
                continue
            }
            val finished = meta.get("finished")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } ?: false
            metaCache[metaPath] = MetaCacheEntry(metaMtime, finished)
            val agentType = meta.string("agentType") ?: continue
            val transcript: BasicFileAttributes? = try {
                Files.readAttributes(dir.resolve(entry.removeSuffix(".meta.json") + ".jsonl"), BasicFileAttributes::class.java)
            } catch (err: IOException) {
                null
            }
            val transcriptMtime = transcript?.lastModifiedTime()?.toMillis() ?: 0L
            val lastWrite = maxOf(transcriptMtime, if (finished) 0L else metaMtime)
            val startedAt = if (transcript != null) {
                transcript.creationTime().toMillis().takeIf { it > 0 } ?: transcriptMtime
            } else {
                metaStat.creationTime().toMillis().takeIf { it > 0 } ?: lastWrite
            }
            val id = entry.removeSuffix(".meta.json")
            val depth = meta.get("spawnDepth")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 1

            if (finished) {
                if (now - metaMtime <= finishedWindow) {
                    out += SubagentInfo(id, agentType, depth, startedAt, finishedAt = metaMtime)
                }
                continue
            }
            val silence = now - lastWrite
            if (silence > expiry) {
                continue
            }
            out += SubagentInfo(
                id, agentType, depth, startedAt,
                signal = ActivitySignal(
                    runtime = RuntimeId.CLAUDE_CODE,
                    agentName = agentType,
                    state = if (silence > options.subagentSilenceMs) SignalState.UNKNOWN else SignalState.ACTIVE,
                    observedAt = lastWrite,
                ),
            )
        }
        return out
    }

    companion object {
        private const val HEAD_BYTES = 64 * 1024

        /** The signal 0 equivalent: only checks that the process exists. */
        fun defaultIsPidAlive(pid: Long): Boolean =
            try {
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            } catch (err: Exception) {
                false
            }

        /** Claude Code's convention for naming the project folder from the cwd. */
        fun projectDirName(cwd: String): String = cwd.replace(Regex("[^a-zA-Z0-9]"), "-")

        /** Reads only the head of the transcript to find the main agent setting. */
        fun readAgentSetting(transcript: Path): String? {
            return try {
                FileChannel.open(transcript, StandardOpenOption.READ).use { channel ->
                    val buffer = ByteBuffer.allocate(HEAD_BYTES)
                    var read = 0
                    while (buffer.hasRemaining()) {
                        val n = channel.read(buffer)
                        if (n <= 0) break
                        read += n
                    }
                    val head = String(buffer.array(), 0, read, StandardCharsets.UTF_8)
                    var found: String? = null
                    for (line in head.split('\n')) {
                        if (!line.contains("\"agent-setting\"")) {
                            continue
                        }
                        try {
                            val entry = JsonParser.parseString(line)
                            if (entry.isJsonObject) {
                                val obj = entry.asJsonObject
                                if (obj.string("type") == "agent-setting") {
                                    obj.string("agentSetting")?.let { found = it }
                                }
                            }
                        } catch (err: Exception) {
                            // Line truncated at the read limit: ignored.
                        }
                    }
                    found
                }
            } catch (err: Exception) {
                null
            }
        }

        private fun findSessionDir(projectsDir: Path, session: SessionRecord): Path? {
            val candidates = mutableListOf<Path>()
            if (session.cwd != null) {
                candidates += projectsDir.resolve(projectDirName(session.cwd))
            }
            try {
                Files.newDirectoryStream(projectsDir).use { stream -> stream.forEach { candidates += it } }
            } catch (err: IOException) {
                return null
            }
            for (dir in candidates) {
                if (Files.exists(dir.resolve("${session.sessionId}.jsonl"))) {
                    return dir
                }
            }
            return null
        }

        private fun errorCode(err: Exception): String = when (err) {
            is AccessDeniedException -> "EACCES"
            is NoSuchFileException -> "ENOENT"
            else -> err.javaClass.simpleName
        }

        private fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.long(key: String): Long? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    }
}
