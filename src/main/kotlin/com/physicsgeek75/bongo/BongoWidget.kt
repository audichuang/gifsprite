/*package com.physicsgeek75

import com.physicsgeek75.bongo.BongoTopic

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer
import com.intellij.openapi.wm.impl.status.widget.*
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.util.*

private const val ICON_DIP = 16

class BongoWidget(private val project: com.intellij.openapi.project.Project)
    : CustomStatusBarWidget, StatusBarWidget.Multiframe, Disposable {

    private fun loadScaledIcon(path: String, dip: Int = ICON_DIP): Icon {
        val raw = IconLoader.getIcon(path, javaClass)
        val targetPx = JBUI.scale(dip)
        val h = raw.iconHeight.coerceAtLeast(1)
        val factor = targetPx.toFloat() / h
        return IconUtil.scale(raw, null, factor)
    }

    private var statusBar: StatusBar? = null




    private val icon1: Icon = loadScaledIcon("icons/icon1.svg")
    private val icon2: Icon = loadScaledIcon("icons/icon2.svg")

    private val label = JBLabel(icon1).apply {
        border = JBUI.Borders.empty(0, 8)
        toolTipText = "Bongo Cat — toggles on each keypress"
        horizontalAlignment = JBLabel.CENTER
        verticalAlignment = JBLabel.CENTER
    }

    private var toggle = false
    private val busConnection = ApplicationManager.getApplication().messageBus.connect(this)

    init {
        busConnection.subscribe(BongoTopic.TOPIC, object : BongoTopic {
            override fun tapped() {
                toggle = !toggle
                ApplicationManager.getApplication().invokeLater {
                    label.icon = if (toggle) icon2 else icon1
                    punch()
                }
            }
        })
    }

    override fun ID(): String = "BongoCatStatus"

    override fun install(statusBar: StatusBar) { this.statusBar = statusBar }

    override fun getComponent(): JComponent = label

    override fun dispose() { busConnection.dispose() }

    override fun copy(): StatusBarWidget = BongoWidget(project)


    private fun safeIcon(path: String): Icon =
        try { IconLoader.getIcon(path, javaClass) }
        catch (_: Throwable) { com.intellij.icons.AllIcons.General.Information }

    private fun punch() {
        val orig = label.border
        label.border = JBUI.Borders.empty(0, 8, 2, 8)
        Timer(90) {
            label.border = orig
        }.apply { isRepeats = false; start() }
    }
}
*/