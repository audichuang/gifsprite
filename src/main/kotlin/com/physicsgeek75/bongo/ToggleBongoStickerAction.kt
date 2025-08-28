package com.physicsgeek75.bongo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ToggleBongoStickerAction : AnAction("Toggle Bongo Sticker"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val svc = project.service<BongoStickerService>()
        svc.setVisible(!svc.state.visible)
        if (svc.state.visible) svc.ensureAttached()
    }
}