package com.github.audichuang.zipsprite

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ToggleZipSpriteStickerAction : AnAction("Toggle ZipSprite"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val svc = project.service<ZipSpriteStickerService>()
        svc.setVisible(!svc.state.visible)
        if (svc.state.visible) svc.ensureAttached()
    }
}
