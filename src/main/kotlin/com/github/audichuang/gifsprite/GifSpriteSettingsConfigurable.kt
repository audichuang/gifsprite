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
    
    // Main settings - these are always initialized in createComponent()
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
    
    // Behavior Mode (nullable to avoid lateinit complexity)
    private var behaviorModeComboBox: JComboBox<String>? = null
    private val behaviorModeDisplayNames = arrayOf(
        "Single (單一圖片)",
        "Idle (閒置休息)",
        "Playlist (自動輪播)"
    )
    
    // Dynamic Settings Panels - will show/hide based on mode (nullable to avoid lateinit complexity)
    private var singleSettingsPanel: JPanel? = null
    private var idleSettingsPanel: JPanel? = null
    private var playlistSettingsPanel: JPanel? = null
    
    // Single Mode UI
    private var singlePackComboBox: JComboBox<String>? = null
    
    // Idle Mode UI (nullable to avoid lateinit complexity)
    private var idleActivePackComboBox: JComboBox<String>? = null  // Active GIF for idle mode
    private var idlePackComboBox: JComboBox<String>? = null        // Idle/rest GIF
    private var idleTimeoutSlider: JSlider? = null
    private var idleTimeoutLabel: JLabel? = null
    
    // Playlist UI (nullable to avoid lateinit complexity)
    private var playlistList: JBList<String>? = null
    private var playlistModel: DefaultListModel<String>? = null
    private var playlistAddComboBox: JComboBox<String>? = null
    private var playlistIntervalSlider: JSlider? = null
    private var playlistIntervalLabel: JLabel? = null
    
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
        val singlePanel = createSingleSettingsPanel()
        singleSettingsPanel = singlePanel
        leftPanel.add(singlePanel)
        leftPanel.add(Box.createVerticalStrut(10))
        
        val idlePanel = createIdleSettingsPanel()
        idleSettingsPanel = idlePanel
        leftPanel.add(idlePanel)
        leftPanel.add(Box.createVerticalStrut(10))
        
        val playlistPanel = createPlaylistSettingsPanel()
        playlistSettingsPanel = playlistPanel
        leftPanel.add(playlistPanel)
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
                    // 這個下拉選單只用於管理（配合刪除按鈕），不影響任何模式設定
                    packComboBox = comboBox(GifSpriteManager.getAvailablePacks())
                        .applyToComponent {
                            // 不設定 selectedItem，因為這只是管理用途
                            // 不添加 actionListener，避免影響預覽或設定
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
    
    private fun createSingleSettingsPanel(): JPanel {
        return panel {
            group("Single Settings (單一圖片設定)") {
                row("使用的 GIF:") {
                    singlePackComboBox = comboBox(GifSpriteManager.getAvailablePacks())
                        .applyToComponent {
                            selectedItem = svc.getSelectedSpritePack()
                            addActionListener { updatePreview() }
                        }.component
                }
                row {
                    comment("選擇要顯示的 GIF 圖片")
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
                            selectedItem = svc.getIdleActiveSpritePack()
                            addActionListener { updatePreview() }
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
                            idleTimeoutLabel?.text = "等待時間: $value 秒"
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
                    val model = DefaultListModel<String>()
                    playlistModel = model
                    playlistList = JBList(model)
                    
                    val scrollPane = JBScrollPane(playlistList).apply {
                        preferredSize = Dimension(250, 80)
                    }
                    cell(scrollPane)
                    
                    panel {
                        row { button("移除選取") { removeFromPlaylist() } }
                        row { button("清空全部") { playlistModel?.clear() } }
                    }
                }
                row {
                    playlistIntervalLabel = label("輪播間隔: ${svc.state.playlistInterval} 分鐘").component
                    playlistIntervalSlider = slider(1, 60, 0, 0).applyToComponent {
                        paintTicks = false
                        paintLabels = false
                        addChangeListener {
                            playlistIntervalLabel?.text = "輪播間隔: $value 分鐘"
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
        val index = behaviorModeComboBox?.selectedIndex ?: 0
        return when (index) {
            0 -> Pair(false, false)  // Normal
            1 -> Pair(true, false)   // Idle
            2 -> Pair(false, true)   // Playlist
            else -> Pair(false, false)
        }
    }
    
    private fun updateBehaviorModeUI() {
        val combo = behaviorModeComboBox ?: return
        val index = combo.selectedIndex
        
        // Show/hide panels based on mode
        // index 0 = Single, 1 = Idle, 2 = Playlist
        singleSettingsPanel?.isVisible = (index == 0)
        idleSettingsPanel?.isVisible = (index == 1)
        playlistSettingsPanel?.isVisible = (index == 2)
        
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
        
        // GIF 管理區域的下拉選單（只用於管理，不影響設定）
        val prevManageSelected = packComboBox.selectedItem
        packComboBox.model = DefaultComboBoxModel(packs.toTypedArray())
        // 如果之前選擇的還在列表中，保持選擇；否則選第一個
        packComboBox.selectedItem = if (packs.contains(prevManageSelected)) prevManageSelected else packs.firstOrNull()
        
        // Single 模式 GIF 選擇（獨立的選擇）
        singlePackComboBox?.let {
            val prevSingle = it.selectedItem
            it.model = DefaultComboBoxModel(packs.toTypedArray())
            it.selectedItem = if (packs.contains(prevSingle)) prevSingle else svc.getSelectedSpritePack()
        }
        
        // Idle 模式：活動時的 GIF（獨立的選擇）
        idleActivePackComboBox?.let {
            val prevActive = it.selectedItem
            it.model = DefaultComboBoxModel(packs.toTypedArray())
            it.selectedItem = if (packs.contains(prevActive)) prevActive else svc.getIdleActiveSpritePack()
        }
        
        // Idle 模式：休息時的 GIF（獨立的選擇）
        idlePackComboBox?.let {
            val prevIdle = it.selectedItem
            it.model = DefaultComboBoxModel(packs.toTypedArray())
            it.selectedItem = if (packs.contains(prevIdle)) prevIdle else svc.state.idleSpritePack
        }
        
        // Playlist 模式：添加用的下拉選單
        playlistAddComboBox?.let {
            it.model = DefaultComboBoxModel(packs.toTypedArray())
        }
    }

    private fun addToPlaylist() {
        val selected = playlistAddComboBox?.selectedItem as? String ?: return
        val model = playlistModel ?: return
        if (!model.contains(selected)) {
            model.addElement(selected)
        }
    }
    
    private fun removeFromPlaylist() {
        val index = playlistList?.selectedIndex ?: return
        if (index >= 0) {
            playlistModel?.remove(index)
        }
    }

    // ========== Configurable Interface ==========

    override fun isModified(): Boolean {
        // 優先使用 singlePackComboBox 的值
        val selectedPack = singlePackComboBox?.selectedItem as? String 
            ?: packComboBox.selectedItem as? String ?: "default"
        val selectedModeIndex = modeComboBox.selectedIndex
        val selectedMode = if (selectedModeIndex >= 0 && selectedModeIndex < animModeValues.size) {
            animModeValues[selectedModeIndex]
        } else { "TYPE_TRIGGERED" }
        
        val (enableIdle, enablePlaylist) = getBehaviorModeSettings()
        val behaviorModified = enableIdle != svc.state.enableIdleMode || enablePlaylist != svc.state.enablePlaylist
        
        // Idle 模式的修改檢查：活動時的 GIF + 休息時的 GIF + 等待時間
        val idleActivePack = idleActivePackComboBox?.selectedItem as? String ?: "default"
        val idlePack = idlePackComboBox?.selectedItem as? String ?: "default"
        val idleModified = idleActivePack != svc.getIdleActiveSpritePack() ||
                           idlePack != svc.state.idleSpritePack ||
                           (idleTimeoutSlider?.value ?: svc.state.idleTimeout) != svc.state.idleTimeout
                           
        val currentPlaylist = svc.state.playlist
        val uiPlaylist = playlistModel?.elements()?.toList() ?: emptyList()
        val playlistModified = (playlistIntervalSlider?.value ?: svc.state.playlistInterval) != svc.state.playlistInterval ||
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
                idleActivePackComboBox?.selectedItem as? String
                    ?: singlePackComboBox?.selectedItem as? String ?: "default"
            }
            enablePlaylist -> {
                // Playlist 模式：使用清單中的第一個（或當前選擇的項目如果在清單中）
                val uiPlaylist = playlistModel?.elements()?.toList() ?: emptyList()
                val currentPack = singlePackComboBox?.selectedItem as? String ?: "default"
                if (uiPlaylist.isNotEmpty() && !uiPlaylist.contains(currentPack)) {
                    uiPlaylist.first()
                } else {
                    currentPack
                }
            }
            else -> {
                // Single 模式：使用 singlePackComboBox 的值
                singlePackComboBox?.selectedItem as? String ?: "default"
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
        
        val idleActivePack = idleActivePackComboBox?.selectedItem as? String ?: "default"
        val idlePack = idlePackComboBox?.selectedItem as? String ?: "default"
        val idleTimeout = idleTimeoutSlider?.value ?: svc.state.idleTimeout
        svc.setIdleMode(enableIdle, idleActivePack, idlePack, idleTimeout)
        
        val uiPlaylist = playlistModel?.elements()?.toList() ?: emptyList()
        val playlistInterval = playlistIntervalSlider?.value ?: svc.state.playlistInterval
        svc.setPlaylistMode(enablePlaylist, uiPlaylist, playlistInterval)

        if (svc.isVisible()) svc.ensureAttached()
    }

    override fun reset() {
        enableCheck.isSelected = svc.isVisible()
        sizeSlider.value = svc.getSizeDip()
        sizeLabel.text = "大小: ${svc.getSizeDip()} DIP"
        
        modeComboBox.selectedIndex = animModeValues.indexOf(svc.getAnimationMode().name).coerceAtLeast(0)
        
        speedSlider.value = svc.getAnimationSpeed()
        speedLabel.text = "速度: ${svc.getAnimationSpeed()} ms/frame"
        
        opacitySlider.value = svc.getOpacity()
        opacityLabel.text = "透明度: ${svc.getOpacity()}%"
        
        // Single 模式 GIF 選擇器
        singlePackComboBox?.selectedItem = svc.getSelectedSpritePack()
        
        // Idle 模式：活動時的 GIF（獨立儲存）
        idleActivePackComboBox?.selectedItem = svc.getIdleActiveSpritePack()
        
        // Idle 模式：休息時的 GIF
        idlePackComboBox?.let {
            it.selectedItem = svc.state.idleSpritePack
            idleTimeoutSlider?.value = svc.state.idleTimeout
            idleTimeoutLabel?.text = "等待時間: ${svc.state.idleTimeout} 秒"
        }
        
        playlistModel?.let { model ->
            playlistIntervalSlider?.value = svc.state.playlistInterval
            playlistIntervalLabel?.text = "輪播間隔: ${svc.state.playlistInterval} 分鐘"
            model.clear()
            svc.state.playlist.forEach { model.addElement(it) }
        }
        
        behaviorModeComboBox?.let {
            it.selectedIndex = getCurrentBehaviorModeIndex()
            updateBehaviorModeUI()
        }
        
        refreshPackList()
        updatePreview()
    }
    
    private fun updatePreview() {
        if (!::previewLabel.isInitialized) return
        
        // 優先使用 singlePackComboBox 的值，如果為 null 則使用 packComboBox
        val packName = singlePackComboBox?.selectedItem as? String 
            ?: packComboBox.selectedItem as? String ?: "default"
        val size = sizeSlider.value
        val opacity = opacitySlider.value
        
        val modeIndex = modeComboBox.selectedIndex
        val isAutoPlay = modeIndex >= 0 && animModeValues.getOrNull(modeIndex) == "AUTO_PLAY"
        
        if (isAutoPlay) {
            val speed = speedSlider.value
            if (previewTimer == null) {
                previewTimer = Timer(speed) {
                    if (root == null) return@Timer
                    // 優先使用 singlePackComboBox 的值
                    val currentPack = singlePackComboBox?.selectedItem as? String 
                        ?: packComboBox.selectedItem as? String ?: "default"
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
