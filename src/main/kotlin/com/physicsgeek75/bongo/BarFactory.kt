/*package com.physicsgeek75.bongo

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.physicsgeek75.BongoWidget

class BarFactory : StatusBarWidgetFactory {
    override fun getId() = "BongoCatStatus"
    override fun getDisplayName() = "Bongo Cat"
    override fun isAvailable(project: Project) = true
    override fun canBeEnabledOn(statusBar: StatusBar) = true
    override fun createWidget(project: Project): StatusBarWidget = BongoWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {
        if (widget is Disposable) Disposer.dispose(widget)
    }
    override fun isEnabledByDefault() = true
}
*/