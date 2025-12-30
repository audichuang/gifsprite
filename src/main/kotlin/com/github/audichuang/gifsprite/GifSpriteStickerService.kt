package com.github.audichuang.gifsprite

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.*
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.*
import java.awt.event.ComponentListener
import java.io.File
import java.nio.file.Paths
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.ImageIcon
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


private const val DEFAULT_SIZE_DIP = 128
private const val DEFAULT_MARGIN_DIP = 0
private const val ICON_DIP = DEFAULT_SIZE_DIP

/**
 * Animation mode for the sprite.
 * 動畫模式：打字觸發或自動播放
 */
enum class AnimationMode {
    TYPE_TRIGGERED,  // Animate when typing (current behavior)
    AUTO_PLAY        // Loop animation automatically
}

// Note: StickerState 類別已移到 GifSpriteSettings.kt

@Service(Service.Level.PROJECT)
class GifSpriteStickerService(private val project: Project) : Disposable {

    // 使用全域設定 Service，讓設定在所有專案中共用
    private val state: StickerState
        get() = GifSpriteSettings.getInstance().settings
    private var isIdle = false // Runtime state tracking

    private var layeredPane: JLayeredPane? = null
    private var lpResizeListener: ComponentListener? = null
    private var panel: JPanel? = null
    private var label: JBLabel? = null
    @Volatile
    private var animationIcons: List<Icon> = emptyList()
    
    // Cache for instant switching
    @Volatile
    private var activeIcons: List<Icon> = emptyList()
    @Volatile
    private var idleIconList: List<Icon> = emptyList()
    
    private val currentFrame = AtomicInteger(0)
    private var punchTimer: Timer? = null
    private var autoPlayTimer: Timer? = null  // Timer for auto-play mode
    private var idleCheckTimer: Timer? = null // Timer to check if we should enter idle mode
    private var playlistTimer: Timer? = null  // Timer for playlist rotation

    // Performance optimizations
    private val iconCache = ConcurrentHashMap<String, Icon>()
    private var lastFrameTime = 0L
    private val MIN_FRAME_INTERVAL get() = state.animationSpeed.toLong()
    private var attachRetryCount = 0  // 重試計數器，避免無窮重試
    private val MAX_ATTACH_RETRIES = 10
    
    // Cache 簽名追蹤，用於判斷是否需要清空 Cache
    @Volatile
    private var lastCacheSignature: String = ""
    
    // 防止重複載入資源，使用 pending 機制處理設定改變
    @Volatile
    private var isLoadingResources: Boolean = false
    @Volatile
    private var pendingReload: Boolean = false

    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    private var idleIcon: Icon? = null


    init {
        // Subscribe to typing events for animation
        connection.subscribe(GifSpriteTopic.TOPIC, object : GifSpriteTopic {
            override fun tapped() = onTap()
        })
        
        // 訂閱設定變更事件，實現跨專案同步
        // 當任何一個專案視窗改了設定，所有專案的 Service 都會收到通知並重新載入資源
        connection.subscribe(GIF_SPRITE_SETTINGS_TOPIC, object : GifSpriteSettingsListener {
            override fun onSettingsChanged() {
                // 重新載入資源與 Timer，同步更新 UI
                initializeFromSettings()
            }
        })
        
        // 從全域設定初始化（資源載入 + UI 附加）
        initializeFromSettings()
    }

    /**
     * Reload animation icons from the current sprite pack.
     */
    /**
     * Reload animation icons from the current sprite pack.
     */
    /**
     * Load resources appropriately (async if called from UI thread, sync if necessary/safe).
     * Populates activeIcons and idleIconList, then updates animationIcons.
     * @param initTimers 如果為 true，在資源載入完成後初始化 timers（用於 loadState 恢復設定）
     */
    private fun loadResourcesAsync(initTimers: Boolean = false) {
        // 如果正在載入中，設定 pending 標記，等當前載入完成後會自動重新載入
        if (isLoadingResources) {
            pendingReload = true
            return
        }
        isLoadingResources = true
        pendingReload = false
        
        // 1. Snapshot state variables needed for loading to avoid race conditions
        val packName = state.selectedSpritePack
        val idlePackName = state.idleSpritePack
        val useIdle = state.enableIdleMode
        val size = state.sizeDip
        val alpha = state.opacity
        val shouldAttach = state.visible && panel == null
        
        // 生成當前設定的唯一簽名
        val currentSignature = "$packName|$idlePackName|$useIdle|$size|$alpha"
        
        // 2. Run on background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 記憶體管理：如果設定改變（例如切換了 Pack），清空舊的 Cache
                // 這是防止記憶體洩漏的最重要步驟
                if (lastCacheSignature != currentSignature) {
                    iconCache.clear()
                    lastCacheSignature = currentSignature
                }
                
                val newActive = loadPackIcons(packName, size, alpha)
                val newIdle = if (useIdle) loadPackIcons(idlePackName, size, alpha) else emptyList()
                
                // 3. Update state on EDT
                SwingUtilities.invokeLater {
                    isLoadingResources = false
                    
                    // 檢查是否已經 dispose，避免在關閉專案後更新 UI
                    if (project.isDisposed) return@invokeLater
                    
                    activeIcons = newActive
                    idleIconList = newIdle
                    
                    // Update current display
                    if (isIdle && useIdle) {
                        animationIcons = idleIconList
                    } else {
                        animationIcons = activeIcons
                    }
                    
                    // Update idleIcon (single icon) for display
                    idleIcon = if (animationIcons.isNotEmpty()) animationIcons[0] else com.intellij.icons.AllIcons.General.Information
                    
                    // Refresh UI
                    currentFrame.set(0)
                    label?.apply {
                        icon = idleIcon
                        repaint()
                    }
                    
                    // 資源載入完成後初始化 timers（如果需要）
                    // 這解決了 loadState() 恢復設定後 idle timer 沒有啟動的問題
                    if (initTimers) {
                        initializeTimersFromState()
                    }
                    
                    // 資源載入完成後，如果 visible 且 panel 還沒創建，觸發 attach
                    if (shouldAttach && panel == null && animationIcons.isNotEmpty()) {
                        ensureAttached()
                    }
                    
                    // 如果有 pending reload 請求，重新載入（設定在載入過程中改變了）
                    if (pendingReload) {
                        pendingReload = false
                        loadResourcesAsync()
                    }
                }
            } catch (e: Exception) {
                isLoadingResources = false
                pendingReload = false
            }
        }
    }
    
    private fun loadPackIcons(packName: String, sizeDip: Int, opacity: Int): List<Icon> {
        val count = GifSpriteManager.getFrameCount(packName)
        val list = mutableListOf<Icon>()
        val validPack = if (count > 0) packName else "default"
        val validCount = if (count > 0) count else GifSpriteManager.getFrameCount("default")
        
        for (i in 1..validCount) {
             val path = GifSpriteManager.getFramePath(validPack, i)
             val icon = loadScaledIcon(path, sizeDip, opacity, GifSpriteManager.isCustomPack(validPack))
             list.add(icon)
        }
        return list
    }

    // Deprecated sync method, redirect to async or keep empty
    private fun reloadAnimationIcons() {
        loadResourcesAsync()
    }

    // ---------- 初始化（使用全域設定） ----------
    /**
     * 從全域設定初始化 Service。
     * 這會在 Service 建立時自動呼叫。
     */
    fun initializeFromSettings() {
        // Reload resources with current settings, initTimers=true 確保 timers 在資源載入後初始化
        loadResourcesAsync(initTimers = true)
        // Re-attach if visible
        if (state.visible) {
            ApplicationManager.getApplication().invokeLater { 
                ensureAttached()
            }
        }
    }
    
    /**
     * 根據當前 state 初始化所有 timers。
     * 這會在 loadState() 恢復設定後被調用。
     * 注意：Idle 和 Playlist 模式是互斥的，只會啟動其中一個。
     */
    private fun initializeTimersFromState() {
        // 停止現有的 timers
        idleCheckTimer?.stop()
        playlistTimer?.stop()
        autoPlayTimer?.stop()
        
        // 根據動畫模式初始化
        if (getAnimationMode() == AnimationMode.AUTO_PLAY) {
            startAutoPlay()
        } else {
            // TYPE_TRIGGERED 模式：根據行為模式設定 Timer（Idle 和 Playlist 互斥）
            when {
                state.enablePlaylist -> setupPlaylistTimer()
                state.enableIdleMode -> setupIdleTimer()
                // Single 模式：不需要特殊 Timer
            }
        }
    }

    // ---------- Lifecycle ----------
    fun ensureAttached() {
        if (!state.visible || panel != null) return
        if (project.isDisposed) return

        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater({ ensureAttached() }, ModalityState.any())
            return
        }

        // 確保資源已載入，如果還沒載入則先載入
        // loadResourcesAsync 完成後會自動呼叫 ensureAttached（如果 visible && panel == null）
        if (animationIcons.isEmpty() || idleIcon == null) {
            loadResourcesAsync()
            // 不需要額外排程重試，loadResourcesAsync 完成後會自動觸發
            return
        }

        // 檢查重試次數，避免無窮重試
        if (attachRetryCount >= MAX_ATTACH_RETRIES) {
            // 放棄重試，避免事件佇列擁塞
            attachRetryCount = 0
            return
        }

        val frame = WindowManager.getInstance().getFrame(project) as? JFrame ?: return
        if (frame.rootPane == null) {
            // schedule one more try shortly
            attachRetryCount++
            app.invokeLater({ ensureAttached() }, ModalityState.any())
            return
        }


        val lp = frame.rootPane.layeredPane ?: run {
            attachRetryCount++
            app.invokeLater({ ensureAttached() }, ModalityState.any())
            return
        }

        // 成功附加，重置重試計數器
        attachRetryCount = 0

        if (layeredPane != null && layeredPane !== lp) {
            lpResizeListener?.let { old -> layeredPane!!.removeComponentListener(old) }
            lpResizeListener = null
        }

        layeredPane = lp

        val resize = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                clampIntoBounds()
            }
        }

        lp.addComponentListener(resize)
        lpResizeListener = resize


        // Icons are already loaded in init block

        // Initialize timer based on animation mode
        if (getAnimationMode() == AnimationMode.AUTO_PLAY) {
            startAutoPlay()
        } else {
            setupIdleTimer()
        }
        
        setupPlaylistTimer()

        // idleIcon 已確保不為 null（上面有檢查）
        val currentIcon = idleIcon ?: com.intellij.icons.AllIcons.General.Information
        label = JBLabel(currentIcon).apply {
            horizontalAlignment = JBLabel.CENTER
            verticalAlignment = JBLabel.CENTER
            toolTipText = "GifSprite"
            preferredSize = JBDimensionDip(state.sizeDip, state.sizeDip)
            minimumSize   = preferredSize
            maximumSize   = preferredSize
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }

        // Transparent container we can position freely
        panel = object : JPanel(null) {
            override fun isOpaque() = false
        }.apply {
            val sizePx = JBUI.scale(state.sizeDip)
            setSize(sizePx, sizePx)
            add(label)
            label!!.setBounds(0, 0, sizePx, sizePx)
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            addDragHandlers(this,label!! ,lp)
        }

        // Compute starting spot
        val w = panel!!.width.toInt()
        val h = panel!!.height
        val start = computeStartPoint(lp, w, h)
        panel!!.setLocation(start)

        // Add to a high layer so it floats above editor
        layeredPane!!.add(panel, JLayeredPane.POPUP_LAYER,0)
        layeredPane!!.revalidate()
        layeredPane!!.repaint()



        // Removed duplicate listener - already added above at line 112
    }

    fun setVisible(visible: Boolean) {
        if (state.visible == visible) {
            if (visible && panel == null) ensureAttached()
            return
        }
        state.visible = visible
        if (visible) ensureAttached() else detach()
    }

    fun isVisible(): Boolean = state.visible

    fun getSizeDip(): Int = state.sizeDip

    private fun detach() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            // 使用 invokeLater 避免死鎖風險
            app.invokeLater({ detach() }, ModalityState.any())
            return
        }

        // Store references locally to avoid race conditions
        val lp = layeredPane
        val p = panel
        val listener = lpResizeListener

        // Clear references immediately
        layeredPane = null
        panel = null
        label = null
        lpResizeListener = null

        // Stop timers
        // Stop timers
        resetAnimationTimer?.stop()
        resetAnimationTimer = null
        idleCheckTimer?.stop()
        idleCheckTimer = null
        punchTimer?.stop()
        punchTimer = null
        autoPlayTimer?.stop()
        autoPlayTimer = null

        // Remove UI components
        listener?.let { lp?.removeComponentListener(it) }
        p?.let {
            lp?.remove(it)
            lp?.revalidate()
            lp?.repaint()
        }

        // Clear resources (replace with empty list instead of clear)
        animationIcons = emptyList()
        currentFrame.set(0)
        iconCache.clear()
        lastCacheSignature = ""
    }

    fun applySize(newDip: Int) {
        state.sizeDip = newDip.coerceIn(0, 512)

        if (state.visible && panel == null) {
            ensureAttached()
        }

        // 清除舊尺寸的快取，避免記憶體洩漏
        iconCache.clear()
        lastCacheSignature = ""
        // Reload all icons with new size
        loadResourcesAsync()

        val sizePx = JBUI.scale(state.sizeDip)

        label?.apply {
            preferredSize = JBDimensionDip(state.sizeDip, state.sizeDip)
            minimumSize   = preferredSize
            maximumSize   = preferredSize
            setBounds(0, 0, sizePx.toInt(), sizePx)
            val frame = currentFrame.get()
            icon = if (animationIcons.isNotEmpty() && frame < animationIcons.size) {
                animationIcons[frame]
            } else {
                idleIcon ?: com.intellij.icons.AllIcons.General.Information
            }
            revalidate()
            repaint()

        }

        panel?.apply {
            setSize(sizePx, sizePx)
            revalidate()
            repaint()
        }

        layeredPane?.apply {
            revalidate()
            repaint()
        }

        clampIntoBounds()
    }

    fun applyOpacity(newOpacity: Int) {
        state.opacity = newOpacity.coerceIn(0, 100)
        
        // 清除舊透明度的快取，避免記憶體洩漏
        iconCache.clear()
        lastCacheSignature = ""
        // Reload all icons with new opacity
        loadResourcesAsync()
        
        // Update current frame immediately
        label?.apply {
            val frame = currentFrame.get()
            icon = if (animationIcons.isNotEmpty() && frame < animationIcons.size) {
                animationIcons[frame]
            } else {
                idleIcon ?: com.intellij.icons.AllIcons.General.Information
            }
            repaint()
        }
    }

    fun resetPosition() {
        state.xDip = -1; state.yDip = -1
        layeredPane?.let { lp ->
            panel?.let { p ->
                val start = computeStartPoint(lp, p.width, p.height)
                p.setLocation(start)
                lp.revalidate(); lp.repaint()
            }
        }
    }

    fun resetSize() {
        applySize(DEFAULT_SIZE_DIP)
        applyOpacity(100)
    }

    /**
     * Change the current sprite pack.
     * Reloads all animation icons from the new pack.
     */
    fun changeSpritePack(packName: String) {
        if (state.selectedSpritePack == packName) return

        state.selectedSpritePack = packName
        loadResourcesAsync()

        // Update the current label if attached
        label?.apply {
            icon = idleIcon ?: com.intellij.icons.AllIcons.General.Information
            revalidate()
            repaint()
        }
    }

    /**
     * Get the current sprite pack name.
     */
    fun getSelectedSpritePack(): String = state.selectedSpritePack

    /**
     * Get the current animation mode.
     */
    fun getAnimationMode(): AnimationMode {
        return try {
            AnimationMode.valueOf(state.animationMode)
        } catch (e: Exception) {
            AnimationMode.TYPE_TRIGGERED
        }
    }

    /**
     * Set the animation mode.
     */
    fun setAnimationMode(mode: AnimationMode) {
        if (state.animationMode == mode.name) return

        state.animationMode = mode.name

        when (mode) {
            AnimationMode.AUTO_PLAY -> {
                // Stop idle timer and start auto-play
                resetAnimationTimer?.stop()
                startAutoPlay()
            }
            AnimationMode.TYPE_TRIGGERED -> {
                // Stop auto-play and setup idle timer
                stopAutoPlay()
                setupIdleTimer()
                // Reset to first frame
                currentFrame.set(0)
                label?.icon = idleIcon ?: com.intellij.icons.AllIcons.General.Information
            }
        }
    }

    /**
     * Get the animation speed (ms per frame).
     */
    fun getAnimationSpeed(): Int = state.animationSpeed

    /**
     * Set the animation speed (ms per frame).
     */
    /**
     * Set the animation speed (ms per frame).
     */
    fun setAnimationSpeed(speed: Int) {
        state.animationSpeed = speed.coerceIn(10, 500)
        // Restart auto-play timer if in auto-play mode
        if (getAnimationMode() == AnimationMode.AUTO_PLAY) {
            startAutoPlay()
        }
    }

    fun getOpacity(): Int = state.opacity
    
    fun setOpacity(opacity: Int) {
        state.opacity = opacity.coerceIn(0, 100)
    }

    // ---------- Idle Mode ----------
    fun setIdleMode(enable: Boolean, activePack: String, idlePack: String, timeoutSec: Int) {
        state.enableIdleMode = enable
        state.idleActiveSpritePack = activePack  // 活動時的 GIF
        state.idleSpritePack = idlePack          // 休息時的 GIF
        state.idleTimeout = timeoutSec
        
        if (enable) {
            // 停止 playlist timer（模式互斥）
            playlistTimer?.stop()
            state.enablePlaylist = false
            
            // Idle 模式下，主要顯示的是活動時的 GIF
            state.selectedSpritePack = activePack
            
            // 重新載入 icons（活動時和休息時的 GIF）
            loadResourcesAsync()
            setupIdleTimer()
        } else {
            idleCheckTimer?.stop()
            if (isIdle) wakeUp()
        }
    }
    
    fun getIdleActiveSpritePack(): String = state.idleActiveSpritePack
    
    private fun enterIdleMode() {
        if (isIdle || !state.enableIdleMode) return
        
        isIdle = true
        // Instant swap
        animationIcons = if (idleIconList.isNotEmpty()) idleIconList else activeIcons
        
        // Update UI to show idle icon
        currentFrame.set(0)
        label?.apply {
            icon = idleIcon ?: com.intellij.icons.AllIcons.General.Information
            repaint()
        }
    }
    
    private fun wakeUp() {
        if (!isIdle) return
        
        isIdle = false
        // Instant swap
        animationIcons = activeIcons
        
        // Reset to active icon
        currentFrame.set(0)
        label?.apply {
             icon = idleIcon ?: com.intellij.icons.AllIcons.General.Information
             repaint()
        }
        
        // Restart playlist timer if needed (to ensure full duration)
        if (state.enablePlaylist && !state.enableIdleMode) {
             setupPlaylistTimer()
        }
    }

    // ---------- Playlist Mode ----------
    fun setPlaylistMode(enable: Boolean, newPlaylist: List<String>, intervalMin: Int) {
        state.enablePlaylist = enable
        state.playlist = newPlaylist.toMutableList()
        state.playlistInterval = intervalMin
        
        if (enable) {
            // 停止 idle timer（模式互斥）
            idleCheckTimer?.stop()
            state.enableIdleMode = false
            if (isIdle) wakeUp()
        }
        
        setupPlaylistTimer()
    }

    private fun setupPlaylistTimer() {
        playlistTimer?.stop()
        if (state.enablePlaylist && state.playlist.isNotEmpty()) {
            // 確保當前選擇的 GIF 在輪播清單中
            // 如果不在清單中，立即切換到清單的第一個 GIF
            if (!state.playlist.contains(state.selectedSpritePack)) {
                val firstPack = state.playlist.first()
                changeSpritePack(firstPack)
            }
            
            val intervalMs = (state.playlistInterval * 60 * 1000).coerceAtLeast(10000) // Min 10 sec
            playlistTimer = Timer(intervalMs) {
                if (project.isDisposed) {
                    playlistTimer?.stop()
                    return@Timer
                }
                rotatePlaylist()
            }.apply { isRepeats = true; start() }
        }
    }

    private fun rotatePlaylist() {
        // Don't rotate if we are in idle mode (wait until wakeup)
        if (isIdle) return
        
        if (state.playlist.isEmpty()) return

        val currentIndex = state.playlist.indexOf(state.selectedSpritePack)
        val nextIndex = (currentIndex + 1) % state.playlist.size
        val nextPack = state.playlist[nextIndex]

        // Only change if different (and valid)
        if (nextPack != state.selectedSpritePack) {
            changeSpritePack(nextPack)
        }
    }

    /**
     * Get a preview icon for the settings page.
     */
    fun getPreviewIcon(packName: String, frameIndex: Int, sizeDip: Int, opacity: Int): Icon {
        val path = GifSpriteManager.getFramePath(packName, frameIndex)
        return loadScaledIcon(path, sizeDip, opacity, GifSpriteManager.isCustomPack(packName))
    }

    /**
     * Clear preview cache to free memory when settings page is closed.
     * 設定頁面關閉時呼叫，清理預覽緩存避免記憶體洩漏。
     */
    fun clearPreviewCache() {
        iconCache.clear()
    }

    /**
     * Start auto-play animation loop.
     */
    private fun startAutoPlay() {
        stopAutoPlay()

        if (animationIcons.isEmpty()) return

        autoPlayTimer = Timer(state.animationSpeed) {
            // 專案關閉時停止 Timer
            if (project.isDisposed) {
                autoPlayTimer?.stop()
                return@Timer
            }
            // 使用本地快照避免並發問題
            val icons = animationIcons
            if (icons.isNotEmpty()) {
                val frame = currentFrame.updateAndGet { (it + 1) % icons.size }
                val nextIcon = icons[frame]
                SwingUtilities.invokeLater {
                    if (!project.isDisposed) {
                        label?.icon = nextIcon
                    }
                }
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    /**
     * Stop auto-play animation.
     */
    private fun stopAutoPlay() {
        autoPlayTimer?.stop()
        autoPlayTimer = null
    }


    override fun dispose() {
        try {
            // Disconnect message bus first
            connection.disconnect()

            // Stop all timers
            resetAnimationTimer?.stop()
            resetAnimationTimer = null
            idleCheckTimer?.stop()
            idleCheckTimer = null
            playlistTimer?.stop()
            playlistTimer = null
            punchTimer?.stop()
            punchTimer = null
            autoPlayTimer?.stop()
            autoPlayTimer = null

            // Clean up UI components
            val app = ApplicationManager.getApplication()
            if (!app.isDispatchThread) {
                // 使用 invokeLater 避免死鎖風險
                app.invokeLater({ detach() }, ModalityState.any())
            } else {
                detach()
            }

            // Clear cache to prevent memory leaks
            iconCache.clear()
        } catch (e: Exception) {
            // Ensure dispose doesn't throw
        }
    }




    // ---------- Behavior ----------
    // ---------- Behavior ----------
    private var resetAnimationTimer: Timer? = null // Resets frame to 0 after typing stops

    private fun setupIdleTimer() {
        // 1. Timer to reset animation to frame 0 (short delay)
        resetAnimationTimer?.stop()
        resetAnimationTimer = Timer(2000) {
            if (project.isDisposed) return@Timer
            if (!isIdle) { // Only reset if not already in idle mode
                currentFrame.set(0)
                val icon = idleIcon
                if (label != null && icon != null) {
                    SwingUtilities.invokeLater {
                        if (!project.isDisposed) {
                            label?.icon = icon
                        }
                    }
                }
            }
        }.apply { isRepeats = false }
        
        // 2. Timer to check for Idle Mode (long delay)
        idleCheckTimer?.stop()
        if (state.enableIdleMode) {
            val timeoutMs = (state.idleTimeout * 1000).coerceAtLeast(1000)
            idleCheckTimer = Timer(timeoutMs) {
                if (project.isDisposed) return@Timer
                enterIdleMode()
            }.apply { isRepeats = false; start() }
        }
    }

    private fun onTap() {
        val p = panel ?: return
        val lbl = label ?: return

        // Throttle to prevent performance issues
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < MIN_FRAME_INTERVAL) {
            return
        }
        lastFrameTime = currentTime

        // Always handle Idle mode wake-up on typing (regardless of animation mode)
        if (state.enableIdleMode) {
            wakeUp()
            setupIdleTimer()
            resetAnimationTimer?.restart()
            idleCheckTimer?.restart()
        }

        // Only advance animation frames in TYPE_TRIGGERED mode
        if (getAnimationMode() == AnimationMode.TYPE_TRIGGERED) {
            val icons = animationIcons
            if (icons.isNotEmpty()) {
                val frame = currentFrame.updateAndGet { (it + 1) % icons.size }
                val nextIcon = icons[frame]
                SwingUtilities.invokeLater {
                    if (!project.isDisposed) {
                        lbl.icon = nextIcon
                    }
                }
            }
        }
    }

    private fun punch(p: JPanel) {
        // Cancel any existing punch animation
        punchTimer?.stop()

        // Store the original position before moving
        val originalY = p.y
        val dy = JBUI.scale(2)

        // Move down slightly
        p.setLocation(p.x, originalY + dy)

        // Create new timer to move back to original position
        punchTimer = Timer(90) {
            p.setLocation(p.x, originalY)
            punchTimer = null
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ---------- Helpers ----------

    /**
     * Load and scale an icon from either bundled resources or filesystem.
     *
     * @param path Resource path (for default) or file path (for custom packs)
     * @param dip Target size in DIP
     * @param opacity Opacity percentage (0-100)
     * @param isCustomPack If true, load from filesystem; if false, load from bundled resources
     */
    private fun loadScaledIcon(path: String, dip: Int, opacity: Int, isCustomPack: Boolean = false): Icon {
        // Use cache to avoid reloading same icons
        val cacheKey = "$path:$dip:$opacity:$isCustomPack"
        return iconCache.getOrPut(cacheKey) {
            val targetPx = JBUI.scale(dip)
            
            val raw: Icon = if (isCustomPack) {
                // Load from filesystem
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val image = ImageIO.read(file)
                        try {
                            // 直接縮放並創建新的 ImageIcon，然後釋放原始 image
                            val baseH = max(1, image.height)
                            val scale = targetPx.toFloat() / baseH
                            val scaledWidth = (image.width * scale).toInt().coerceAtLeast(1)
                            val scaledHeight = (image.height * scale).toInt().coerceAtLeast(1)
                            val scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, java.awt.Image.SCALE_SMOOTH)
                            ImageIcon(scaledImage)
                        } finally {
                            image.flush()  // 釋放原始 BufferedImage 的原生資源
                        }
                    } else {
                        com.intellij.icons.AllIcons.General.Information
                    }
                } catch (_: Throwable) {
                    com.intellij.icons.AllIcons.General.Information
                }
            } else {
                // Load from bundled resources - 使用 IconLoader 並縮放
                try {
                    val bundled = IconLoader.getIcon(path, javaClass)
                    val baseH = max(1, bundled.iconHeight)
                    val scale = targetPx.toFloat() / baseH
                    IconUtil.scale(bundled, null, scale)
                } catch (_: Throwable) {
                    com.intellij.icons.AllIcons.General.Information
                }
            }
            
            // Apply opacity if needed
            if (opacity < 100) {
                 TransparentIcon(raw, opacity / 100f)
            } else {
                raw
            }
        }
    }

    private class TransparentIcon(private val delegate: Icon, private val alpha: Float) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                delegate.paintIcon(c, g2, x, y)
            } finally {
                g2.dispose()
            }
        }
        override fun getIconWidth(): Int = delegate.iconWidth
        override fun getIconHeight(): Int = delegate.iconHeight
    }


    private fun JBDimensionDip(wDip: Int, hDip: Int) =
        com.intellij.util.ui.JBDimension(JBUI.scale(wDip), JBUI.scale(hDip))

    private fun computeStartPoint(lp: JLayeredPane, w: Int, h: Int): Point {
        val margin = JBUI.scale(DEFAULT_MARGIN_DIP)
        val x = if (state.xDip >= 0) JBUI.scale(state.xDip) else lp.width - w - margin
        val y = if (state.yDip >= 0) JBUI.scale(state.yDip) else lp.height - h - margin
        return Point(x.coerceIn(0, max(0, lp.width - w)),
            y.coerceIn(0, max(0, lp.height - h)))
    }

    private fun clampIntoBounds() {
        val lp = layeredPane ?: return
        val p = panel ?: return
        val maxX = max(0, lp.width - p.width)
        val maxY = max(0, lp.height - p.height)
        val nx = p.x.coerceIn(0, maxX)
        val ny = p.y.coerceIn(0, maxY)
        if (nx != p.x || ny != p.y) p.setLocation(nx, ny)
    }

    private fun addDragHandlers(panel: JPanel, label: JComponent, lp: JLayeredPane) {
        val ma = object : java.awt.event.MouseAdapter() {
            private var dx = 0
            private var dy = 0
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                val pt = SwingUtilities.convertPoint(e.component, e.point, lp)
                dx = pt.x - panel.x
                dy = pt.y - panel.y
            }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                val pt = SwingUtilities.convertPoint(e.component, e.point, lp)
                val nx = (pt.x - dx).coerceIn(0, kotlin.math.max(0, lp.width - panel.width))
                val ny = (pt.y - dy).coerceIn(0, kotlin.math.max(0, lp.height - panel.height))

                // Direct update for responsive dragging
                panel.setLocation(nx, ny)
                // persist as DIP
                state.xDip = JBUI.unscale(nx)
                state.yDip = JBUI.unscale(ny)
            }
        }
        panel.addMouseListener(ma); panel.addMouseMotionListener(ma)
        label.addMouseListener(ma); label.addMouseMotionListener(ma)
    }

    private fun Int.coerceIn(minVal: Int, maxVal: Int) = min(max(this, minVal), maxVal)
}
