package com.agentoffice

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts polling as soon as a project opens, so the status bar is always up to date. */
class OfficeStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        OfficeService.getInstance(project).start()
    }
}
