package com.github.audichuang.gifsprite

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ToggleGifSpriteStickerAction : AnAction("Toggle GifSprite"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val svc = project.service<GifSpriteStickerService>()
        svc.setVisible(!svc.isVisible())
        if (svc.isVisible()) svc.ensureAttached()
    }
}
