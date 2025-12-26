package com.github.audichuang.zipsprite

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler

@Service(Service.Level.APP)
class ZipSpriteTypingService : Disposable {

    private val typed: TypedAction = TypedAction.getInstance()

    private val previous: TypedActionHandler = typed.rawHandler

    private val installed: TypedActionHandler


    init {
        val wrapper = TypedHandler(previous)
        installed = wrapper
        typed.setupRawHandler(wrapper)
    }

    override fun dispose() {
        if (typed.rawHandler === installed) {
            typed.setupRawHandler(previous)
        }
    }
}
