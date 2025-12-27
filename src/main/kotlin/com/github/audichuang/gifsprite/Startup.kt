package com.github.audichuang.gifsprite

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Startup : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        // 1. 初始化 App Level Service (Typing Handler)
        ApplicationManager.getApplication().service<GifSpriteTypingService>()

        // 2. 在 IO 執行緒處理檔案解壓/檢查，避免阻塞 UI 或啟動流程
        try {
            withContext(Dispatchers.IO) {
                GifSpriteManager.ensureDefaultPackExists()
            }
        } catch (e: Exception) {
            // Log but don't fail - Service 會處理資源不存在的情況
            thisLogger().warn("GifSprite: Failed to initialize resources", e)
        }

        // 3. 確保 Project Level Service 準備好
        project.service<GifSpriteStickerService>().ensureAttached()
    }
}
