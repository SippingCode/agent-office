package com.agentoffice.ui

import com.agentoffice.OfficeService
import com.agentoffice.core.AgentState
import com.agentoffice.core.Json
import com.agentoffice.core.OfficeSnapshot
import com.agentoffice.core.Summary
import com.agentoffice.settings.AgentOfficeSettings
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JPanel

/** The tool window content: the pixel-art office in a JCEF browser, or a text roster when JCEF is unavailable. */
class OfficePanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val log = logger<OfficePanel>()
    private val service = OfficeService.getInstance(project)
    private var browser: JBCefBrowser? = null
    @Volatile private var pageReady = false
    private var fallback: JBTextArea? = null

    init {
        Disposer.register(parent, this)
        if (JBCefApp.isSupported()) {
            add(createBrowser(), BorderLayout.CENTER)
        } else {
            add(createFallback(), BorderLayout.CENTER)
        }
        service.addListener({ snapshot, summary -> render(snapshot, summary) }, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AgentOfficeSettings.TOPIC, AgentOfficeSettings.Listener { postMotion() })
    }

    private fun createBrowser(): JComponent {
        val browser = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
        Disposer.register(this, browser)
        this.browser = browser
        val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
        Disposer.register(browser, query)
        query.addHandler { request ->
            onPageMessage(request)
            null
        }
        browser.loadHTML(OfficeHtml.page(query.inject("msg")))
        return browser.component
    }

    private fun createFallback(): JComponent {
        val area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8, 12)
            text = "Reading agent status…"
        }
        fallback = area
        return JBScrollPane(area)
    }

    private fun onPageMessage(request: String) {
        try {
            val type = Json.gson.fromJson(request, Map::class.java)?.get("type")
            if (type == "ready") {
                pageReady = true
                postMotion()
                val snapshot = service.snapshot
                val summary = service.summary
                if (snapshot != null && summary != null) {
                    render(snapshot, summary)
                } else {
                    service.refresh()
                }
            }
        } catch (err: Exception) {
            log.debug("Ignored message from the office page: $request", err)
        }
    }

    private fun execute(js: String) {
        val b = browser ?: return
        if (Disposer.isDisposed(b)) return
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url, 0)
    }

    private fun postMotion() {
        if (!pageReady) return
        val mode = AgentOfficeSettings.getInstance().reduceMotion.id
        execute("window.postMessage({type: 'motion', reduceMotion: '$mode'}, '*');")
    }

    private fun applyTheme() {
        if (!pageReady) return
        execute(OfficeHtml.themeScript())
    }

    private fun render(snapshot: OfficeSnapshot, summary: Summary) {
        val area = fallback
        if (area != null) {
            val text = fallbackText(snapshot, summary)
            ApplicationManager.getApplication().invokeLater { area.text = text }
            return
        }
        if (!pageReady) return
        val payload = Json.gson.toJson(
            mapOf("type" to "snapshot", "snapshot" to snapshot, "summary" to summary.toJsonMap()),
        )
        execute("window.postMessage($payload, '*');")
    }

    private fun fallbackText(snapshot: OfficeSnapshot, summary: Summary): String {
        val time = SimpleDateFormat("HH:mm:ss")
        val sb = StringBuilder()
        sb.appendLine(summary.text)
        sb.appendLine()
        sb.appendLine("The pixel-art office needs JCEF, which this IDE build does not provide. Desks:")
        for (slot in snapshot.slots) {
            val agent = slot.agent
            if (agent == null) {
                sb.appendLine("${slot.index + 1}. Free desk")
            } else {
                val state = when (agent.state) {
                    AgentState.ACTIVE -> "working"
                    AgentState.INACTIVE -> "free"
                    AgentState.UNKNOWN -> "unknown"
                }
                val at = agent.updatedAt?.let { " · ${time.format(Date(it))}" } ?: ""
                sb.appendLine("${slot.index + 1}. ${agent.name} — $state$at (${slot.workstation ?: "no desk"})")
            }
        }
        if (snapshot.overflow.isNotEmpty()) {
            sb.appendLine("${snapshot.overflow.size} more agent(s) beyond the ten desks: " + snapshot.overflow.joinToString { it.name })
        }
        snapshot.health.mapNotNull { it.message }.forEach { sb.appendLine(it) }
        snapshot.notices.forEach { sb.appendLine(it) }
        sb.appendLine("Last update: ${time.format(Date(snapshot.generatedAt))}")
        return sb.toString()
    }

    override fun dispose() {
        pageReady = false
        browser = null
        fallback = null
    }
}
