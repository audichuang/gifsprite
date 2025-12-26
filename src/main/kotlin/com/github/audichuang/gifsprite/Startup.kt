package com.github.audichuang.gifsprite

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class Startup : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        // handler installation once app level
        ApplicationManager.getApplication().service<GifSpriteTypingService>()
        // Ensure default pack exists
        GifSpriteManager.ensureDefaultPackExists()
        project.service<GifSpriteStickerService>().ensureAttached()
    }
}
