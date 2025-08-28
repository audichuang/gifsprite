/*package com.physicsgeek75.bongo

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import com.intellij.openapi.application.ApplicationManager

class ToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val icon1 = IconLoader.getIcon("resources/icons/icon1.svg", javaClass)
        val icon2 = IconLoader.getIcon("resources/icons/icon2.svg", javaClass)

        val label = JBLabel(icon1)
        label.horizontalAlignment = JBLabel.CENTER
        label.verticalAlignment = JBLabel.CENTER

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            background=JBColor.PanelBackground
            add(label, BorderLayout.CENTER)
            }

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        var toggle = false

        val connection = ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
        connection.subscribe(BongoTopic.TOPIC, object : BongoTopic {
            override fun tapped() {
                toggle = !toggle
                ApplicationManager.getApplication().invokeLater {
                    label.icon = if (toggle) icon2 else icon1
                    panel.revalidate()
                    panel.repaint()
                }
            }
        })
    }
}*/