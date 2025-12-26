package com.github.audichuang.gifsprite

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
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
import javax.swing.SwingConstants

class GifSpriteSettingsConfigurable(private val project: Project) : Configurable {

    private var root: JComponent? = null
    private lateinit var enableCheck: JCheckBox
    private lateinit var sizeSlider: JSlider
    private lateinit var sizeLabel: JLabel
    private lateinit var packComboBox: JComboBox<String>
    private lateinit var modeComboBox: JComboBox<String>
    private lateinit var speedSlider: JSlider
    private lateinit var speedLabel: JLabel
    private lateinit var opacitySlider: JSlider
    private lateinit var opacityLabel: JLabel
    private lateinit var previewLabel: JLabel
    private val svc = project.service<GifSpriteStickerService>()
    
    private var previewTimer: javax.swing.Timer? = null
    private var currentPreviewFrame = 1

    private val modeDisplayNames = mapOf(
        "TYPE_TRIGGERED" to "打字觸發 (Type Triggered)",
        "AUTO_PLAY" to "自動播放 (Auto Play)"
    )

    private val modeValues = listOf("TYPE_TRIGGERED", "AUTO_PLAY")

    override fun getDisplayName(): String = "GifSprite"

    override fun createComponent(): JComponent {
        if (root == null) {
            root = panel {
                group("Preview") {
                    row {
                        // Create a placeholder label
                        previewLabel = JLabel()
                        previewLabel.horizontalAlignment = SwingConstants.CENTER
                        previewLabel.verticalAlignment = SwingConstants.CENTER
                        
                        // We want a fixed height info panel or something, but dynamic size is fine
                        cell(previewLabel)
                    }
                }

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
                            addChangeListener { 
                                sizeLabel.text = "Size: $value DIP"
                                // Debounce: 只在放開滑桿時更新預覽
                                if (!valueIsAdjusting) {
                                    updatePreview()
                                }
                            }
                        }.component
                    }

                    row {
                        opacityLabel = label("Opacity: ${svc.getOpacity()}%").component

                        // min 0 max 100
                        opacitySlider = slider(0, 100, 0, majorTickSpacing = 0).applyToComponent {
                            paintTicks = false
                            paintLabels = false
                            snapToTicks = false
                            addChangeListener { 
                                opacityLabel.text = "Opacity: $value%"
                                // Debounce: 只在放開滑桿時更新預覽
                                if (!valueIsAdjusting) {
                                    updatePreview()
                                }
                            }
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

                group("Animation") {
                    row("Mode:") {
                        modeComboBox = comboBox(modeValues.map { modeDisplayNames[it] ?: it })
                            .applyToComponent {
                                selectedIndex = modeValues.indexOf(svc.getAnimationMode().name).coerceAtLeast(0)
                                addActionListener { updatePreview() }
                            }.component
                    }

                    row {
                        speedLabel = label("Speed: ${svc.getAnimationSpeed()} ms/frame").component

                        // min 10ms max 500ms (lower = faster)
                        speedSlider = slider(10, 500, 0, majorTickSpacing = 0).applyToComponent {
                            paintTicks = false
                            paintLabels = false
                            snapToTicks = false
                            inverted = true  // Lower value = faster, so invert for UX
                            addChangeListener { 
                                speedLabel.text = "Speed: $value ms/frame"
                                // Debounce: 只在放開滑桿時更新預覽
                                if (!valueIsAdjusting) {
                                    updatePreview()
                                }
                            }
                        }.component
                    }
                    row {
                        comment("Lower value = faster animation")
                    }
                }

                group("Sprite Pack") {
                    row("Current Pack:") {
                        packComboBox = comboBox(GifSpriteManager.getAvailablePacks())
                            .applyToComponent {
                                selectedItem = svc.getSelectedSpritePack()
                                addActionListener { updatePreview() }
                            }.component
                    }

                    row {
                        button("Import GIF...") {
                            importGif()
                        }
                        button("Import from URL...") {
                            importGifFromUrl()
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

    private fun importGifFromUrl() {
        val url = Messages.showInputDialog(
            project,
            "Enter the URL of the GIF file:",
            "Import GIF from URL",
            null
        )

        if (!url.isNullOrBlank()) {
            // Ask for pack name (on EDT)
            val defaultName = try {
                 File(java.net.URI.create(url).toURL().path).nameWithoutExtension
            } catch (e: Exception) { "downloaded_gif" }
            
            val packName = Messages.showInputDialog(
                project,
                "Enter a name for this sprite pack:",
                "Import GIF",
                null,
                defaultName,
                null
            )

            if (!packName.isNullOrBlank()) {
                 val sanitizedName = packName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(50)
                 
                 // Run import in background
                 ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Importing GIF from URL...", false) {
                     private var frameCount = -1
                     
                     override fun run(indicator: ProgressIndicator) {
                         indicator.isIndeterminate = true
                         indicator.text = "Downloading and extracting GIF..."
                         frameCount = GifSpriteManager.importGifFromUrl(url, packName)
                     }
                     
                     override fun onSuccess() {
                         if (frameCount > 0) {
                             Messages.showInfoMessage(
                                 project,
                                 "Successfully imported GIF from URL with $frameCount frames.",
                                 "Import Complete"
                             )
                             refreshPackList()
                             packComboBox.selectedItem = sanitizedName
                         } else {
                             Messages.showErrorDialog(
                                 project,
                                 "Failed to import GIF. Please check the URL is valid.",
                                 "Import Error"
                             )
                         }
                     }
                     
                     override fun onThrowable(error: Throwable) {
                         Messages.showErrorDialog(
                             project,
                             "Error importing GIF: ${error.message}",
                             "Import Error"
                         )
                     }
                 })
            }
        }
    }

    private fun importGif() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("gif")
        descriptor.title = "Select GIF Animation"
        descriptor.description = "Choose a GIF file to import as a sprite pack"

        val virtualFile = FileChooser.chooseFile(descriptor, project, null)
        virtualFile?.let { vf ->
            // Ask for pack name (on EDT)
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
                val sanitizedName = packName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(50)
                
                // Run import in background to avoid freezing UI
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Importing GIF...", false) {
                    private var frameCount = -1
                    
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        indicator.text = "Extracting frames from GIF..."
                        frameCount = GifSpriteManager.importGif(gifFile, packName)
                    }
                    
                    override fun onSuccess() {
                        if (frameCount > 0) {
                            Messages.showInfoMessage(
                                project,
                                "Successfully imported GIF with $frameCount frames.",
                                "Import Complete"
                            )
                            refreshPackList()
                            packComboBox.selectedItem = sanitizedName
                        } else {
                            Messages.showErrorDialog(
                                project,
                                "Failed to import GIF. Please check the file is a valid GIF animation.",
                                "Import Error"
                            )
                        }
                    }
                    
                    override fun onThrowable(error: Throwable) {
                        Messages.showErrorDialog(
                            project,
                            "Error importing GIF: ${error.message}",
                            "Import Error"
                        )
                    }
                })
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
        val selectedModeIndex = modeComboBox.selectedIndex
        val selectedMode = if (selectedModeIndex >= 0 && selectedModeIndex < modeValues.size) {
            modeValues[selectedModeIndex]
        } else {
            "TYPE_TRIGGERED"
        }

        return enableCheck.isSelected != svc.isVisible() ||
                sizeSlider.value != svc.getSizeDip() ||
                selectedPack != svc.getSelectedSpritePack() ||
                selectedMode != svc.getAnimationMode().name ||
                speedSlider.value != svc.getAnimationSpeed() ||
                opacitySlider.value != svc.getOpacity()
    }

    override fun apply() {
        val svc = project.service<GifSpriteStickerService>()
        svc.applySize(sizeSlider.value)
        svc.setVisible(enableCheck.isSelected)

        // Apply sprite pack change
        val selectedPack = packComboBox.selectedItem as? String ?: "default"
        svc.changeSpritePack(selectedPack)

        // Apply animation mode
        val selectedModeIndex = modeComboBox.selectedIndex
        if (selectedModeIndex >= 0 && selectedModeIndex < modeValues.size) {
            val mode = AnimationMode.valueOf(modeValues[selectedModeIndex])
            svc.setAnimationMode(mode)
        }

        // Apply animation speed
        svc.setAnimationSpeed(speedSlider.value)
        
        // Apply opacity
        svc.applyOpacity(opacitySlider.value)

        if (svc.isVisible()) svc.ensureAttached()

        sizeLabel.text = "Size: ${svc.getSizeDip()} DIP"
        speedLabel.text = "Speed: ${svc.getAnimationSpeed()} ms/frame"
        opacityLabel.text = "Opacity: ${svc.getOpacity()}%"
    }

    override fun reset() {
        val svc = project.service<GifSpriteStickerService>()
        enableCheck.isSelected = svc.isVisible()
        sizeSlider.value = svc.getSizeDip()
        sizeLabel.text = "Size: ${svc.getSizeDip()} DIP"

        // Reset animation mode
        if (::modeComboBox.isInitialized) {
            val modeIndex = modeValues.indexOf(svc.getAnimationMode().name)
            modeComboBox.selectedIndex = modeIndex.coerceAtLeast(0)
        }

        // Reset speed
        if (::speedSlider.isInitialized) {
            speedSlider.value = svc.getAnimationSpeed()
            speedLabel.text = "Speed: ${svc.getAnimationSpeed()} ms/frame"
        }
        
        // Reset opacity
        if (::opacitySlider.isInitialized) {
            opacitySlider.value = svc.getOpacity()
            opacityLabel.text = "Opacity: ${svc.getOpacity()}%"
        }

        // Refresh pack list and select current
        if (::packComboBox.isInitialized) {
            refreshPackList()
        }
        
        // Initial preview update
        if (::previewLabel.isInitialized) {
            updatePreview()
        }
    }
    
    private fun updatePreview() {
        if (!::previewLabel.isInitialized) return
        
        val packName = packComboBox.selectedItem as? String ?: "default"
        val size = sizeSlider.value
        val opacity = opacitySlider.value
        
        // Check mode for animation
        val modeIndex = modeComboBox.selectedIndex
        val isAutoPlay = modeIndex >= 0 && modeValues[modeIndex] == "AUTO_PLAY"
        
        if (isAutoPlay) {
            val speed = speedSlider.value
            
            if (previewTimer == null) {
                previewTimer = javax.swing.Timer(speed) {
                     // 檢查設定頁面是否還開著，避免競爭條件
                     if (root == null || !::packComboBox.isInitialized) return@Timer
                     val currentPack = packComboBox.selectedItem as? String ?: "default"
                     val currentSize = sizeSlider.value
                     val currentOpacity = opacitySlider.value
                     
                     val frameCount = GifSpriteManager.getFrameCount(currentPack)
                     if (frameCount > 0) {
                         // 1-based index
                         currentPreviewFrame = (currentPreviewFrame % frameCount) + 1
                     } else {
                        currentPreviewFrame = 1
                     }
                     val icon = svc.getPreviewIcon(currentPack, currentPreviewFrame, currentSize, currentOpacity)
                     previewLabel.icon = icon
                     previewLabel.repaint()
                }.apply { start() }
            } else {
                 previewTimer?.delay = speed
                 if (!previewTimer!!.isRunning) previewTimer?.start()
            }
        } else {
            previewTimer?.stop()
            currentPreviewFrame = 1
            val icon = svc.getPreviewIcon(packName, 1, size, opacity)
            previewLabel.icon = icon
            previewLabel.repaint()
        }
        previewLabel.text = null
    }

    override fun disposeUIResources() {
        previewTimer?.stop()
        previewTimer = null
        // 清理預覽緩存避免記憶體洩漏
        svc.clearPreviewCache()
        root = null
    }
}
