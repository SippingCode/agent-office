package com.agentoffice.ui

import com.agentoffice.OfficeService
import com.agentoffice.core.SummaryKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/** Status bar text: "Active agent: …", "N active agents: …", "No active agents" or an unknown status. */
class OfficeStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    @Volatile private var text: String = "◌ Agent Office"

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        OfficeService.getInstance(project).addListener({ _, summary ->
            val glyph = when (summary.kind) {
                SummaryKind.ACTIVE -> "●"
                SummaryKind.IDLE -> "○"
                SummaryKind.UNKNOWN -> "?"
                SummaryKind.EMPTY -> "⊘"
            }
            text = "$glyph ${summary.text}"
            statusBar.updateWidget(ID)
        }, this)
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = text

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = "Agent Office — which agent is working right now? (click to open the office)"

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow(OfficeToolWindowFactory.ID)?.activate(null)
    }

    override fun dispose() {}

    companion object {
        const val ID = "AgentOffice.Status"
    }
}

class OfficeStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = OfficeStatusBarWidget.ID
    override fun getDisplayName(): String = "Agent Office"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = OfficeStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
