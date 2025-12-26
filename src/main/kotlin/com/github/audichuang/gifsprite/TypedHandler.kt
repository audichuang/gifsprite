package com.github.audichuang.gifsprite

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.application.ApplicationManager

class TypedHandler(
    private val delegate: TypedActionHandler
) : TypedActionHandler {

    override fun execute(editor: Editor, c: Char, dataContext: DataContext) {
        delegate.execute(editor, c, dataContext)

        // if (!c.isLetterOrDigit()) return

        ApplicationManager.getApplication()
            .messageBus.syncPublisher(GifSpriteTopic.TOPIC)
            .tapped()
    }
}
