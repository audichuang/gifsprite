package com.github.audichuang.gifsprite

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.panel
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSlider

class GifSpriteSettingsConfigurable(private val project: Project) : Configurable {

    private var root: JComponent? = null
    private lateinit var enableCheck: JCheckBox
    private lateinit var sizeSlider: JSlider
    private lateinit var sizeLabel: JLabel
    private lateinit var packComboBox: JComboBox<String>
    private val svc = project.service<GifSpriteStickerService>()

    override fun getDisplayName(): String = "GifSprite"

    override fun createComponent(): JComponent {
        if (root == null) {
            root = panel {
                group("Main") {
                    row {
                        enableCheck = checkBox("Enable").component
                    }

                    row {
                        sizeLabel = label("Size: ${svc.getSizeDip()} DIP").component

                        // min 0 max 512
                        sizeSlider = slider(0, 512, 0, majorTickSpacing = 0).applyToComponent {
                            paintTicks = false
                            paintLabels = false
                            snapToTicks = false
                            addChangeListener { sizeLabel.text = "Size: $value DIP" }
                        }.component
                    }

                    row {
                        button("Reset Position & Size") {
                            project.service<GifSpriteStickerService>().resetPosition()
                            project.service<GifSpriteStickerService>().resetSize()
                            reset()
                        }
                    }
                }

                group("Sprite Pack") {
                    row("Current Pack:") {
                        packComboBox = comboBox(GifSpriteManager.getAvailablePacks())
                            .applyToComponent {
                                selectedItem = svc.getSelectedSpritePack()
                            }.component
                    }

                    row {
                        button("Import GIF...") {
                            importGif()
                        }
                        button("Delete Pack") {
                            deleteSelectedPack()
                        }
                    }
                }
            }
            reset()
        }
        return root!!
    }

    private fun importGif() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("gif")
        descriptor.title = "Select GIF Animation"
        descriptor.description = "Choose a GIF file to import as a sprite pack"

        val virtualFile = FileChooser.chooseFile(descriptor, project, null)
        virtualFile?.let { vf ->
            // Ask for pack name
            val defaultName = vf.nameWithoutExtension
            val packName = Messages.showInputDialog(
                project,
                "Enter a name for this sprite pack:",
                "Import GIF",
                null,
                defaultName,
                null
            )

            if (!packName.isNullOrBlank()) {
                val gifFile = File(vf.path)
                val frameCount = GifSpriteManager.importGif(gifFile, packName)

                if (frameCount > 0) {
                    Messages.showInfoMessage(
                        project,
                        "Successfully imported GIF with $frameCount frames.",
                        "Import Complete"
                    )
                    // Refresh combo box
                    refreshPackList()
                    // Select the newly imported pack
                    packComboBox.selectedItem = packName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
                } else {
                    Messages.showErrorDialog(
                        project,
                        "Failed to import GIF. Please check the file is a valid GIF animation.",
                        "Import Error"
                    )
                }
            }
        }
    }

    private fun deleteSelectedPack() {
        val selectedPack = packComboBox.selectedItem as? String ?: return

        if (selectedPack == "default") {
            Messages.showWarningDialog(
                project,
                "Cannot delete the default sprite pack.",
                "Delete Pack"
            )
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete the sprite pack '$selectedPack'?",
            "Delete Pack",
            Messages.getQuestionIcon()
        )

        if (confirm == Messages.YES) {
            if (GifSpriteManager.deletePack(selectedPack)) {
                Messages.showInfoMessage(
                    project,
                    "Sprite pack '$selectedPack' has been deleted.",
                    "Delete Complete"
                )
                // If we deleted the currently selected pack, switch to default
                if (svc.getSelectedSpritePack() == selectedPack) {
                    svc.changeSpritePack("default")
                }
                refreshPackList()
            } else {
                Messages.showErrorDialog(
                    project,
                    "Failed to delete the sprite pack.",
                    "Delete Error"
                )
            }
        }
    }

    private fun refreshPackList() {
        val packs = GifSpriteManager.getAvailablePacks()
        packComboBox.model = DefaultComboBoxModel(packs.toTypedArray())
        packComboBox.selectedItem = svc.getSelectedSpritePack()
    }

    override fun isModified(): Boolean {
        val selectedPack = packComboBox.selectedItem as? String ?: "default"
        return enableCheck.isSelected != svc.isVisible() ||
                sizeSlider.value != svc.getSizeDip() ||
                selectedPack != svc.getSelectedSpritePack()
    }

    override fun apply() {
        val svc = project.service<GifSpriteStickerService>()
        svc.applySize(sizeSlider.value)
        svc.setVisible(enableCheck.isSelected)

        // Apply sprite pack change
        val selectedPack = packComboBox.selectedItem as? String ?: "default"
        svc.changeSpritePack(selectedPack)

        if (svc.isVisible()) svc.ensureAttached()

        sizeLabel.text = "Size: ${svc.getSizeDip()} DIP"
    }

    override fun reset() {
        val svc = project.service<GifSpriteStickerService>()
        enableCheck.isSelected = svc.isVisible()
        sizeSlider.value = svc.getSizeDip()
        sizeLabel.text = "Size: ${svc.getSizeDip()} DIP"

        // Refresh pack list and select current
        if (::packComboBox.isInitialized) {
            refreshPackList()
        }
    }

    override fun disposeUIResources() {
        root = null
    }
}
