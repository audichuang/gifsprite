package com.physicsgeek75.bongo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.startup.StartupActivity

class Startup : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        // handler installation once app level
        ApplicationManager.getApplication().service<BongoTypingService>()
        project.service<BongoStickerService>().ensureAttached()
    }
}