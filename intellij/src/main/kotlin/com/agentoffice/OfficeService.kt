package com.agentoffice

import com.agentoffice.core.AgentDefinitions
import com.agentoffice.core.ClaudeCodeAdapter
import com.agentoffice.core.Copilot
import com.agentoffice.core.HealthStatus
import com.agentoffice.core.OfficeSnapshot
import com.agentoffice.core.RuntimeHealth
import com.agentoffice.core.RuntimeId
import com.agentoffice.core.RuntimeReport
import com.agentoffice.core.SnapshotBuilder
import com.agentoffice.core.SnapshotOptions
import com.agentoffice.core.Summarizer
import com.agentoffice.core.Summary
import com.agentoffice.settings.AgentOfficeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls the local Claude Code signals for one project and keeps the latest office snapshot.
 * Everything is read-only; no process is started.
 */
@Service(Service.Level.PROJECT)
class OfficeService(private val project: Project) : Disposable {

    fun interface Listener {
        fun officeUpdated(snapshot: OfficeSnapshot, summary: Summary)
    }

    private val log = logger<OfficeService>()
    private val settings get() = AgentOfficeSettings.getInstance()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val running = AtomicBoolean(false)
    private var adapter: ClaudeCodeAdapter = makeAdapter()
    private var adapterKey = ""
    @Volatile private var future: ScheduledFuture<*>? = null

    @Volatile var snapshot: OfficeSnapshot? = null
        private set
    @Volatile var summary: Summary? = null
        private set

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgentOfficeSettings.TOPIC, AgentOfficeSettings.Listener { onConfigChanged() })
    }

    private fun makeAdapter(): ClaudeCodeAdapter {
        val s = settings
        adapterKey = "${s.claudeDir}|${s.silenceMs}"
        return ClaudeCodeAdapter(ClaudeCodeAdapter.Options(claudeDir = s.claudeDir, subagentSilenceMs = s.silenceMs))
    }

    @Synchronized
    fun start() {
        stop()
        future = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ tick() }, 0, settings.pollMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        future?.cancel(false)
        future = null
    }

    private fun onConfigChanged() {
        val s = settings
        if ("${s.claudeDir}|${s.silenceMs}" != adapterKey) {
            adapter = makeAdapter()
        }
        start()
    }

    /** Reads the signals once, off the EDT when possible. */
    fun refresh() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            AppExecutorUtil.getAppExecutorService().execute { tick() }
        } else {
            tick()
        }
    }

    fun addListener(listener: Listener, parent: Disposable) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        val snap = snapshot
        val sum = summary
        if (snap != null && sum != null) {
            listener.officeUpdated(snap, sum)
        }
    }

    private fun workspaceDirs(): List<Path> {
        if (project.isDisposed) return emptyList()
        val dirs = LinkedHashSet<String>()
        project.basePath?.let { dirs += it }
        try {
            ApplicationManager.getApplication().runReadAction {
                if (!project.isDisposed) {
                    ProjectRootManager.getInstance(project).contentRoots
                        .filter { it.isInLocalFileSystem }
                        .forEach { dirs += it.path }
                }
            }
        } catch (err: Exception) {
            log.debug("Could not list content roots", err)
        }
        return dirs.map { Paths.get(it) }
    }

    fun tick() {
        if (project.isDisposed || !running.compareAndSet(false, true)) {
            return
        }
        try {
            val s = settings
            val discovery = AgentDefinitions.discoverDefinitions(
                AgentDefinitions.definitionSources(s.claudeDir, AgentOfficeSettings.homeDir, workspaceDirs()),
            )
            val claude: RuntimeReport = try {
                adapter.read()
            } catch (err: Exception) {
                RuntimeReport(RuntimeHealth(RuntimeId.CLAUDE_CODE, HealthStatus.UNAVAILABLE, err.toString()))
            }
            val notices = discovery.errors.map { "Unreadable agents folder: ${it.dir} (${it.message})" }
            val snap = SnapshotBuilder.buildSnapshot(
                discovery.definitions,
                listOf(claude, Copilot.read()),
                System.currentTimeMillis(),
                SnapshotOptions(pinned = s.pinned, rooms = s.rooms, notices = notices),
            )
            val sum = Summarizer.summarize(snap)
            snapshot = snap
            summary = sum
            for (listener in listeners) {
                try {
                    listener.officeUpdated(snap, sum)
                } catch (err: Exception) {
                    log.warn("Agent Office listener failed", err)
                }
            }
        } catch (err: Exception) {
            log.warn("Agent Office poll failed", err)
        } finally {
            running.set(false)
        }
    }

    override fun dispose() {
        stop()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): OfficeService = project.getService(OfficeService::class.java)
    }
}
