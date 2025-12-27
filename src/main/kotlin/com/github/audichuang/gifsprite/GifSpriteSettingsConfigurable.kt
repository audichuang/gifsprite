package com.github.audichuang.gifsprite

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*

/**
 * Settings UI for GifSprite plugin.
 * Layout: Left = Settings panels, Right = Preview (fixed position)
 */
class GifSpriteSettingsConfigurable(private val project: Project) : Configurable {

    private var root: JComponent? = null
    
    // Main settings
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
    
    // Behavior Mode
    private lateinit var behaviorModeComboBox: JComboBox<String>
    private val behaviorModeDisplayNames = arrayOf(
        "Single (單一圖片)",
        "Idle (閒置休息)",
        "Playlist (自動輪播)"
    )
    
    // Dynamic Settings Panels - will show/hide based on mode
    private lateinit var idleSettingsPanel: JPanel
    private lateinit var playlistSettingsPanel: JPanel
    
    // Idle Mode UI
    private lateinit var idleActivePackComboBox: JComboBox<String>  // Active GIF for idle mode
    private lateinit var idlePackComboBox: JComboBox<String>        // Idle/rest GIF
    private lateinit var idleTimeoutSlider: JSlider
    private lateinit var idleTimeoutLabel: JLabel
    
    // Playlist UI
    private lateinit var playlistList: JBList<String>
    private lateinit var playlistModel: DefaultListModel<String>
    private lateinit var playlistAddComboBox: JComboBox<String>
    private lateinit var playlistIntervalSlider: JSlider
    private lateinit var playlistIntervalLabel: JLabel
    
    private val svc by lazy { project.service<GifSpriteStickerService>() }
    
    private var previewTimer: Timer? = null
    private var currentPreviewFrame = 1

    private val animModeDisplayNames = mapOf(
        "TYPE_TRIGGERED" to "打字觸發 (Type Triggered)",
        "AUTO_PLAY" to "自動播放 (Auto Play)"
    )
    private val animModeValues = listOf("TYPE_TRIGGERED", "AUTO_PLAY")

    override fun getDisplayName(): String = "GifSprite"

    override fun createComponent(): JComponent {
        if (root != null) return root!!
        
        // ========== LEFT PANEL: All settings ==========
        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }
        
        // --- 1. Enable/Disable (Top) ---
        leftPanel.add(createEnablePanel())
        leftPanel.add(Box.createVerticalStrut(10))
        
        // --- 2. GIF Management ---
        leftPanel.add(createGifManagementPanel())
        leftPanel.add(Box.createVerticalStrut(10))
        
        // --- 3. Behavior Mode ---
        leftPanel.add(createBehaviorModePanel())
        leftPanel.add(Box.createVerticalStrut(10))
        
        // --- 4. Dynamic: Mode-specific Settings ---
        idleSettingsPanel = createIdleSettingsPanel()
        leftPanel.add(idleSettingsPanel)
        leftPanel.add(Box.createVerticalStrut(10))
        
        playlistSettingsPanel = createPlaylistSettingsPanel()
        leftPanel.add(playlistSettingsPanel)
        leftPanel.add(Box.createVerticalStrut(10))
        
        // --- 5. Customization (Bottom) ---
        leftPanel.add(createCustomizationPanel())
        leftPanel.add(Box.createVerticalStrut(10))
        
        leftPanel.add(createAnimationSettingsPanel())
        
        // Add glue to push content to top
        leftPanel.add(Box.createVerticalGlue())
        
        val leftScrollPane = JBScrollPane(leftPanel).apply {
            border = null
            preferredSize = Dimension(400, 600)
        }
        
        // ========== RIGHT PANEL: Preview ==========
        val rightPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            preferredSize = Dimension(300, 600)
        }
        
        previewLabel = JLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }
        
        val previewTitleLabel = JBLabel("Preview").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
            border = JBUI.Borders.emptyBottom(10)
        }
        
        rightPanel.add(previewTitleLabel, BorderLayout.NORTH)
        rightPanel.add(previewLabel, BorderLayout.CENTER)
        
        // ========== MAIN SPLIT: Left + Right ==========
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightPanel).apply {
            dividerLocation = 450
            resizeWeight = 0.6
            border = null
        }
        
        root = splitPane
        
        // Initialize visibility based on current mode
        updateBehaviorModeUI()
        reset()
        
        return root!!
    }
    
    // ========== Panel Creation Methods ==========
    
    private fun createEnablePanel(): JPanel {
        return panel {
            group("GifSprite") {
                row {
                    enableCheck = checkBox("啟用 GifSprite").component
                }
            }
        }
    }
    
    private fun createGifManagementPanel(): JPanel {
        return panel {
            group("GIF 管理") {
                row {
                    button("匯入 GIF 檔案...") { importGif() }
                    button("從 URL 匯入...") { importGifFromUrl() }
                }
                row {
                    label("已匯入的圖片:")
                }
                row {
                    // Show available packs as a list for reference
                    packComboBox = comboBox(GifSpriteManager.getAvailablePacks())
                        .applyToComponent {
                            selectedItem = svc.getSelectedSpritePack()
                            addActionListener { updatePreview() }
                        }.component
                    button("刪除選取") { deleteSelectedPack() }
                }
                row {
                    comment("在下方選擇行為模式，再選擇要使用的 GIF")
                }
            }
        }
    }
    
    private fun createCustomizationPanel(): JPanel {
        return panel {
            group("外觀調整") {
                row {
                    sizeLabel = label("大小: ${svc.getSizeDip()} DIP").component
                    sizeSlider = slider(0, 512, 0, 0).applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        addChangeListener {
                            sizeLabel.text = "大小: $value DIP"
                            if (!valueIsAdjusting) updatePreview()
                        }
                    }.component
                }
                row {
                    opacityLabel = label("透明度: ${svc.getOpacity()}%").component
                    opacitySlider = slider(0, 100, 0, 0).applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        addChangeListener {
                            opacityLabel.text = "透明度: $value%"
                            if (!valueIsAdjusting) updatePreview()
                        }
                    }.component
                }
                row {
                    button("重置位置與大小") {
                        svc.resetPosition()
                        svc.resetSize()
                        reset()
                    }
                }
            }
        }
    }
    
    private fun createAnimationSettingsPanel(): JPanel {
        return panel {
            group("Animation (動畫設定)") {
                row("模式:") {
                    modeComboBox = comboBox(animModeValues.map { animModeDisplayNames[it] ?: it })
                        .applyToComponent {
                            selectedIndex = animModeValues.indexOf(svc.getAnimationMode().name).coerceAtLeast(0)
                            addActionListener { updatePreview() }
                        }.component
                }
                row {
                    speedLabel = label("速度: ${svc.getAnimationSpeed()} ms/frame").component
                    speedSlider = slider(10, 500, 0, 0).applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        inverted = true
                        addChangeListener {
                            speedLabel.text = "速度: $value ms/frame"
                            if (!valueIsAdjusting) updatePreview()
                        }
                    }.component
                }
                row {
                    comment("數值越小動畫越快")
                }
            }
        }
    }
    
    private fun createBehaviorModePanel(): JPanel {
        return panel {
            group("Behavior Mode (行為模式)") {
                row("模式:") {
                    behaviorModeComboBox = comboBox(behaviorModeDisplayNames.toList())
                        .applyToComponent {
                            selectedIndex = getCurrentBehaviorModeIndex()
                            addActionListener { updateBehaviorModeUI() }
                        }.component
                }
                row {
                    comment("根據選擇的模式，下方會顯示對應的設定")
                }
            }
        }
    }
    
    private fun createIdleSettingsPanel(): JPanel {
        return panel {
            group("Idle Settings (閒置設定)") {
                row("活動時的 GIF:") {
                    idleActivePackComboBox = comboBox(GifSpriteManager.getAvailablePacks())
                        .applyToComponent {
                            selectedItem = svc.getSelectedSpritePack()
                            addActionListener { 
                                // Sync with main packComboBox
                                packComboBox.selectedItem = selectedItem
                                updatePreview()
                            }
                        }.component
                }
                row("休息時的 GIF:") {
                    idlePackComboBox = comboBox(GifSpriteManager.getAvailablePacks()).component
                }
                row {
                    idleTimeoutLabel = label("等待時間: ${svc.state.idleTimeout} 秒").component
                    idleTimeoutSlider = slider(5, 300, 0, 0).applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        addChangeListener {
                            idleTimeoutLabel.text = "等待時間: $value 秒"
                        }
                    }.component
                }
                row {
                    comment("停止打字後，等待這麼久會切換成休息狀態")
                }
            }
        }
    }
    
    private fun createPlaylistSettingsPanel(): JPanel {
        return panel {
            group("Playlist Settings (輪播設定)") {
                row("加入輪播:") {
                    playlistAddComboBox = comboBox(GifSpriteManager.getAvailablePacks()).component
                    button("+") { addToPlaylist() }
                }
                row {
                    label("輪播清單:")
                }
                row {
                    playlistModel = DefaultListModel<String>()
                    playlistList = JBList(playlistModel)
                    
                    val scrollPane = JBScrollPane(playlistList).apply {
                        preferredSize = Dimension(250, 80)
                    }
                    cell(scrollPane)
                    
                    panel {
                        row { button("移除選取") { removeFromPlaylist() } }
                        row { button("清空全部") { playlistModel.clear() } }
                    }
                }
                row {
                    playlistIntervalLabel = label("輪播間隔: ${svc.state.playlistInterval} 分鐘").component
                    playlistIntervalSlider = slider(1, 60, 0, 0).applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        addChangeListener {
                            playlistIntervalLabel.text = "輪播間隔: $value 分鐘"
                        }
                    }.component
                }
                row {
                    comment("每隔這麼久會自動切換到下一個 GIF")
                }
            }
        }
    }
    
    // ========== Behavior Mode Logic ==========
    
    private fun getCurrentBehaviorModeIndex(): Int {
        val enableIdle = svc.state.enableIdleMode
        val enablePlaylist = svc.state.enablePlaylist
        return when {
            enablePlaylist -> 2  // Playlist
            enableIdle -> 1      // Idle
            else -> 0            // Normal
        }
    }
    
    private fun getBehaviorModeSettings(): Pair<Boolean, Boolean> {
        val index = if (::behaviorModeComboBox.isInitialized) behaviorModeComboBox.selectedIndex else 0
        return when (index) {
            0 -> Pair(false, false)  // Normal
            1 -> Pair(true, false)   // Idle
            2 -> Pair(false, true)   // Playlist
            else -> Pair(false, false)
        }
    }
    
    private fun updateBehaviorModeUI() {
        if (!::behaviorModeComboBox.isInitialized) return
        
        val (showIdle, showPlaylist) = getBehaviorModeSettings()
        
        if (::idleSettingsPanel.isInitialized) {
            idleSettingsPanel.isVisible = showIdle
        }
        if (::playlistSettingsPanel.isInitialized) {
            playlistSettingsPanel.isVisible = showPlaylist
        }
        
        // Refresh layout
        root?.revalidate()
        root?.repaint()
    }
    
    // ========== Import/Delete Logic ==========
    
    private fun importGifFromUrl() {
        val url = Messages.showInputDialog(project, "請輸入 GIF 的 URL:", "Import GIF from URL", null)
        if (!url.isNullOrBlank()) {
            val defaultName = try {
                File(java.net.URI.create(url).toURL().path).nameWithoutExtension
            } catch (e: Exception) { "downloaded_gif" }
            
            val packName = Messages.showInputDialog(project, "為這個 Sprite Pack 命名:", "Import GIF", null, defaultName, null)
            if (!packName.isNullOrBlank()) {
                val sanitizedName = packName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(50)
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Importing GIF from URL...", false) {
                    private var frameCount = -1
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        frameCount = GifSpriteManager.importGifFromUrl(url, packName)
                    }
                    override fun onSuccess() {
                        if (frameCount > 0) {
                            Messages.showInfoMessage(project, "成功匯入 $frameCount 幀!", "Import Complete")
                            refreshPackList()
                            packComboBox.selectedItem = sanitizedName
                        } else {
                            Messages.showErrorDialog(project, "匯入失敗，請檢查 URL 是否正確", "Import Error")
                        }
                    }
                    override fun onThrowable(error: Throwable) {
                        Messages.showErrorDialog(project, "錯誤: ${error.message}", "Import Error")
                    }
                })
            }
        }
    }

    private fun importGif() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("gif")
        descriptor.title = "選擇 GIF 檔案"
        val virtualFile = FileChooser.chooseFile(descriptor, project, null)
        virtualFile?.let { vf ->
            val defaultName = vf.nameWithoutExtension
            val packName = Messages.showInputDialog(project, "為這個 Sprite Pack 命名:", "Import GIF", null, defaultName, null)
            if (!packName.isNullOrBlank()) {
                val gifFile = File(vf.path)
                val sanitizedName = packName.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(50)
                ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Importing GIF...", false) {
                    private var frameCount = -1
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        frameCount = GifSpriteManager.importGif(gifFile, packName)
                    }
                    override fun onSuccess() {
                        if (frameCount > 0) {
                            Messages.showInfoMessage(project, "成功匯入 $frameCount 幀!", "Import Complete")
                            refreshPackList()
                            packComboBox.selectedItem = sanitizedName
                        } else {
                            Messages.showErrorDialog(project, "匯入失敗，請確認是有效的 GIF 動畫", "Import Error")
                        }
                    }
                    override fun onThrowable(error: Throwable) {
                        Messages.showErrorDialog(project, "錯誤: ${error.message}", "Import Error")
                    }
                })
            }
        }
    }

    private fun deleteSelectedPack() {
        val selectedPack = packComboBox.selectedItem as? String ?: return
        if (selectedPack == "default") {
            Messages.showWarningDialog(project, "無法刪除預設的 Sprite Pack", "Delete Pack")
            return
        }
        val confirm = Messages.showYesNoDialog(project, "確定要刪除 '$selectedPack' 嗎?", "Delete Pack", Messages.getQuestionIcon())
        if (confirm == Messages.YES) {
            if (GifSpriteManager.deletePack(selectedPack)) {
                Messages.showInfoMessage(project, "'$selectedPack' 已刪除", "Delete Complete")
                if (svc.getSelectedSpritePack() == selectedPack) {
                    svc.changeSpritePack("default")
                }
                refreshPackList()
            } else {
                Messages.showErrorDialog(project, "刪除失敗", "Delete Error")
            }
        }
    }

    private fun refreshPackList() {
        val packs = GifSpriteManager.getAvailablePacks()
        val prevSelected = packComboBox.selectedItem
        packComboBox.model = DefaultComboBoxModel(packs.toTypedArray())
        packComboBox.selectedItem = prevSelected ?: svc.getSelectedSpritePack()
        
        if (::idleActivePackComboBox.isInitialized) {
            idleActivePackComboBox.model = DefaultComboBoxModel(packs.toTypedArray())
            idleActivePackComboBox.selectedItem = prevSelected ?: svc.getSelectedSpritePack()
        }
        if (::idlePackComboBox.isInitialized) {
            val prevIdle = idlePackComboBox.selectedItem
            idlePackComboBox.model = DefaultComboBoxModel(packs.toTypedArray())
            idlePackComboBox.selectedItem = prevIdle ?: svc.state.idleSpritePack
        }
        if (::playlistAddComboBox.isInitialized) {
            playlistAddComboBox.model = DefaultComboBoxModel(packs.toTypedArray())
        }
    }

    private fun addToPlaylist() {
        val selected = playlistAddComboBox.selectedItem as? String ?: return
        if (!playlistModel.contains(selected)) {
            playlistModel.addElement(selected)
        }
    }
    
    private fun removeFromPlaylist() {
        val index = playlistList.selectedIndex
        if (index >= 0) {
            playlistModel.remove(index)
        }
    }

    // ========== Configurable Interface ==========

    override fun isModified(): Boolean {
        val selectedPack = packComboBox.selectedItem as? String ?: "default"
        val selectedModeIndex = modeComboBox.selectedIndex
        val selectedMode = if (selectedModeIndex >= 0 && selectedModeIndex < animModeValues.size) {
            animModeValues[selectedModeIndex]
        } else { "TYPE_TRIGGERED" }
        
        val (enableIdle, enablePlaylist) = getBehaviorModeSettings()
        val behaviorModified = enableIdle != svc.state.enableIdleMode || enablePlaylist != svc.state.enablePlaylist
        
        val idlePack = if (::idlePackComboBox.isInitialized) idlePackComboBox.selectedItem as? String ?: "default" else "default"
        val idleModified = idlePack != svc.state.idleSpritePack ||
                           (if (::idleTimeoutSlider.isInitialized) idleTimeoutSlider.value else svc.state.idleTimeout) != svc.state.idleTimeout
                           
        val currentPlaylist = svc.state.playlist
        val uiPlaylist = if (::playlistModel.isInitialized) playlistModel.elements().toList() else emptyList()
        val playlistModified = (if (::playlistIntervalSlider.isInitialized) playlistIntervalSlider.value else svc.state.playlistInterval) != svc.state.playlistInterval ||
                               currentPlaylist != uiPlaylist

        return enableCheck.isSelected != svc.isVisible() ||
                sizeSlider.value != svc.getSizeDip() ||
                selectedPack != svc.getSelectedSpritePack() ||
                selectedMode != svc.getAnimationMode().name ||
                speedSlider.value != svc.getAnimationSpeed() ||
                opacitySlider.value != svc.getOpacity() ||
                behaviorModified ||
                idleModified ||
                playlistModified
    }

    override fun apply() {
        svc.applySize(sizeSlider.value)
        svc.setVisible(enableCheck.isSelected)
        
        val (enableIdle, enablePlaylist) = getBehaviorModeSettings()
        
        // 根據行為模式決定要設定的 GIF
        val selectedPack = when {
            enableIdle -> {
                // Idle 模式：使用「活動時的 GIF」
                if (::idleActivePackComboBox.isInitialized) {
                    idleActivePackComboBox.selectedItem as? String ?: "default"
                } else {
                    packComboBox.selectedItem as? String ?: "default"
                }
            }
            enablePlaylist -> {
                // Playlist 模式：使用清單中的第一個（或當前選擇的項目如果在清單中）
                val uiPlaylist = if (::playlistModel.isInitialized) playlistModel.elements().toList() else emptyList()
                val currentPack = packComboBox.selectedItem as? String ?: "default"
                if (uiPlaylist.isNotEmpty() && !uiPlaylist.contains(currentPack)) {
                    uiPlaylist.first()
                } else {
                    currentPack
                }
            }
            else -> {
                // Single 模式：使用 packComboBox 的值
                packComboBox.selectedItem as? String ?: "default"
            }
        }
        svc.changeSpritePack(selectedPack)
        
        val selectedModeIndex = modeComboBox.selectedIndex
        if (selectedModeIndex >= 0 && selectedModeIndex < animModeValues.size) {
            val mode = AnimationMode.valueOf(animModeValues[selectedModeIndex])
            svc.setAnimationMode(mode)
        }
        
        svc.setAnimationSpeed(speedSlider.value)
        svc.applyOpacity(opacitySlider.value)
        
        val idlePack = if (::idlePackComboBox.isInitialized) idlePackComboBox.selectedItem as? String ?: "default" else "default"
        val idleTimeout = if (::idleTimeoutSlider.isInitialized) idleTimeoutSlider.value else svc.state.idleTimeout
        svc.setIdleMode(enableIdle, idlePack, idleTimeout)
        
        val uiPlaylist = if (::playlistModel.isInitialized) playlistModel.elements().toList() else emptyList()
        val playlistInterval = if (::playlistIntervalSlider.isInitialized) playlistIntervalSlider.value else svc.state.playlistInterval
        svc.setPlaylistMode(enablePlaylist, uiPlaylist, playlistInterval)

        if (svc.isVisible()) svc.ensureAttached()
    }

    override fun reset() {
        enableCheck.isSelected = svc.isVisible()
        sizeSlider.value = svc.getSizeDip()
        sizeLabel.text = "大小: ${svc.getSizeDip()} DIP"
        
        if (::modeComboBox.isInitialized) {
            val modeIndex = animModeValues.indexOf(svc.getAnimationMode().name)
            modeComboBox.selectedIndex = modeIndex.coerceAtLeast(0)
        }
        
        if (::speedSlider.isInitialized) {
            speedSlider.value = svc.getAnimationSpeed()
            speedLabel.text = "速度: ${svc.getAnimationSpeed()} ms/frame"
        }
        
        if (::opacitySlider.isInitialized) {
            opacitySlider.value = svc.getOpacity()
            opacityLabel.text = "透明度: ${svc.getOpacity()}%"
        }
        
        if (::idlePackComboBox.isInitialized) {
            idlePackComboBox.selectedItem = svc.state.idleSpritePack
            idleTimeoutSlider.value = svc.state.idleTimeout
            idleTimeoutLabel.text = "等待時間: ${svc.state.idleTimeout} 秒"
        }
        
        if (::playlistModel.isInitialized) {
            playlistIntervalSlider.value = svc.state.playlistInterval
            playlistIntervalLabel.text = "輪播間隔: ${svc.state.playlistInterval} 分鐘"
            playlistModel.clear()
            svc.state.playlist.forEach { playlistModel.addElement(it) }
        }
        
        if (::behaviorModeComboBox.isInitialized) {
            behaviorModeComboBox.selectedIndex = getCurrentBehaviorModeIndex()
            updateBehaviorModeUI()
        }
        
        refreshPackList()
        updatePreview()
    }
    
    private fun updatePreview() {
        if (!::previewLabel.isInitialized) return
        
        val packName = packComboBox.selectedItem as? String ?: "default"
        val size = sizeSlider.value
        val opacity = opacitySlider.value
        
        val modeIndex = if (::modeComboBox.isInitialized) modeComboBox.selectedIndex else 0
        val isAutoPlay = modeIndex >= 0 && animModeValues.getOrNull(modeIndex) == "AUTO_PLAY"
        
        if (isAutoPlay) {
            val speed = speedSlider.value
            if (previewTimer == null) {
                previewTimer = Timer(speed) {
                    if (root == null || !::packComboBox.isInitialized) return@Timer
                    val currentPack = packComboBox.selectedItem as? String ?: "default"
                    val currentSize = sizeSlider.value
                    val currentOpacity = opacitySlider.value
                    
                    val frameCount = GifSpriteManager.getFrameCount(currentPack)
                    if (frameCount > 0) {
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
        svc.clearPreviewCache()
        root = null
    }
}
