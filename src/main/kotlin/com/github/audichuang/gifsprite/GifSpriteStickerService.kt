package com.github.audichuang.gifsprite

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
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


private const val DEFAULT_SIZE_DIP = 128
private const val DEFAULT_MARGIN_DIP = 0
private const val ICON_DIP = DEFAULT_SIZE_DIP

/**
 * Animation mode for the sprite
 */
enum class AnimationMode {
    TYPE_TRIGGERED,  // Animate when typing (current behavior)
    AUTO_PLAY        // Loop animation automatically
}

class StickerState {
    var visible: Boolean = true
    var xDip: Int = -1
    var yDip: Int = -1
    var sizeDip: Int = DEFAULT_SIZE_DIP
    var animationSpeed: Int = 50 // ms between frames for auto-play (lower = faster)
    var enableSmoothAnimation: Boolean = true
    var selectedSpritePack: String = "default"  // "default" or custom pack name
    var frameCount: Int = 24  // dynamic frame count
    var animationMode: String = "TYPE_TRIGGERED"  // "TYPE_TRIGGERED" or "AUTO_PLAY"
}

@State(name = "GifSpriteStickerState", storages = [Storage("gifSpriteState.xml")])
@Service(Service.Level.PROJECT)
class GifSpriteStickerService(private val project: Project)
    : PersistentStateComponent<StickerState>, Disposable {

    private var state = StickerState()

    private var layeredPane: JLayeredPane? = null
    private var lpResizeListener: ComponentListener? = null
    private var panel: JPanel? = null
    private var label: JBLabel? = null
    private var animationIcons: MutableList<Icon> = mutableListOf()
    private var currentFrame = 0
    private var punchTimer: Timer? = null
    private var autoPlayTimer: Timer? = null  // Timer for auto-play mode

    // Performance optimizations
    private val iconCache = ConcurrentHashMap<String, Icon>()
    private var lastFrameTime = 0L
    private val MIN_FRAME_INTERVAL get() = state.animationSpeed.toLong()

    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    private lateinit var idleIcon: Icon


    init {
        // Load icons for selected sprite pack
        reloadAnimationIcons()

        connection.subscribe(GifSpriteTopic.TOPIC, object : GifSpriteTopic {
            override fun tapped() = onTap()
        })
    }

    /**
     * Reload animation icons from the current sprite pack.
     */
    private fun reloadAnimationIcons() {
        iconCache.clear()
        animationIcons.clear()

        val packName = state.selectedSpritePack
        state.frameCount = GifSpriteManager.getFrameCount(packName)

        for (i in 1..state.frameCount) {
            val path = GifSpriteManager.getFramePath(packName, i)
            val icon = loadScaledIcon(path, state.sizeDip, GifSpriteManager.isCustomPack(packName))
            animationIcons.add(icon)
        }

        idleIcon = if (animationIcons.isNotEmpty()) animationIcons[0] else {
            com.intellij.icons.AllIcons.General.Information
        }
    }

    // ---------- PersistentStateComponent ----------
    override fun getState(): StickerState = state
    override fun loadState(s: StickerState) { state = s }

    // ---------- Lifecycle ----------
    fun ensureAttached() {
        if (!state.visible || panel != null) return

        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) { // why was that there lol
            // talking to myself is actually crazy
            app.invokeLater({ ensureAttached() }, ModalityState.any())
            return
        }


        val frame = WindowManager.getInstance().getFrame(project) as? JFrame ?: return
        if (frame.rootPane == null) {
            // schedule one more try shortly
            app.invokeLater({ ensureAttached() }, ModalityState.any())
            return
        }


        val lp = frame.rootPane.layeredPane ?: run {
            app.invokeLater({ ensureAttached() }, ModalityState.any())
            return
        }

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

        label = JBLabel(idleIcon).apply {
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
            app.invokeAndWait({ detach() }, ModalityState.any())
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
        idleTimer?.stop()
        idleTimer = null
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

        // Clear resources
        animationIcons.clear()
        currentFrame = 0
        iconCache.clear()
    }

    fun applySize(newDip: Int) {
        state.sizeDip = newDip.coerceIn(0, 512)

        if (state.visible && panel == null) {
            ensureAttached()
        }

        // Reload all icons with new size
        reloadAnimationIcons()

        val sizePx = JBUI.scale(state.sizeDip)

        label?.apply {
            preferredSize = JBDimensionDip(state.sizeDip, state.sizeDip)
            minimumSize   = preferredSize
            maximumSize   = preferredSize
            setBounds(0, 0, sizePx.toInt(), sizePx)
            icon = if (animationIcons.isNotEmpty() && currentFrame < animationIcons.size) {
                animationIcons[currentFrame]
            } else {
                idleIcon
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
    }

    /**
     * Change the current sprite pack.
     * Reloads all animation icons from the new pack.
     */
    fun changeSpritePack(packName: String) {
        if (state.selectedSpritePack == packName) return

        state.selectedSpritePack = packName
        reloadAnimationIcons()

        // Update the current label if attached
        label?.apply {
            icon = idleIcon
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
                idleTimer?.stop()
                startAutoPlay()
            }
            AnimationMode.TYPE_TRIGGERED -> {
                // Stop auto-play and setup idle timer
                stopAutoPlay()
                setupIdleTimer()
                // Reset to first frame
                currentFrame = 0
                label?.icon = idleIcon
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
    fun setAnimationSpeed(speed: Int) {
        state.animationSpeed = speed.coerceIn(10, 500)
        // Restart auto-play timer if in auto-play mode
        if (getAnimationMode() == AnimationMode.AUTO_PLAY) {
            startAutoPlay()
        }
    }

    /**
     * Start auto-play animation loop.
     */
    private fun startAutoPlay() {
        stopAutoPlay()

        if (animationIcons.isEmpty()) return

        autoPlayTimer = Timer(state.animationSpeed) {
            if (animationIcons.isNotEmpty()) {
                currentFrame = (currentFrame + 1) % animationIcons.size
                val nextIcon = animationIcons[currentFrame]
                SwingUtilities.invokeLater {
                    label?.icon = nextIcon
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
            idleTimer?.stop()
            idleTimer = null
            punchTimer?.stop()
            punchTimer = null
            autoPlayTimer?.stop()
            autoPlayTimer = null

            // Clean up UI components
            val app = ApplicationManager.getApplication()
            if (!app.isDispatchThread) {
                app.invokeAndWait({ detach() }, ModalityState.any())
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
    private var idleTimer: Timer? = null

    private fun setupIdleTimer() {
        idleTimer?.stop()
        idleTimer = Timer(2000) {
            currentFrame = 0
            val icon = idleIcon
            if (label != null && icon != null) {
                SwingUtilities.invokeLater {
                    label?.icon = icon
                }
            }
        }.apply { isRepeats = false }
    }

    private fun onTap() {
        // Only respond to typing in TYPE_TRIGGERED mode
        if (getAnimationMode() != AnimationMode.TYPE_TRIGGERED) return

        val p = panel ?: return
        val lbl = label ?: return

        // Throttle animation to prevent performance issues
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < MIN_FRAME_INTERVAL) {
            return
        }
        lastFrameTime = currentTime

        setupIdleTimer()
        idleTimer?.restart()

        // Advance to next frame in animation sequence (dynamic frame count)
        if (animationIcons.isNotEmpty()) {
            currentFrame = (currentFrame + 1) % animationIcons.size
            val nextIcon = animationIcons[currentFrame]
            SwingUtilities.invokeLater {
                lbl.icon = nextIcon
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
     * @param isCustomPack If true, load from filesystem; if false, load from bundled resources
     */
    private fun loadScaledIcon(path: String, dip: Int, isCustomPack: Boolean = false): Icon {
        // Use cache to avoid reloading same icons
        val cacheKey = "$path:$dip:$isCustomPack"
        return iconCache.getOrPut(cacheKey) {
            val raw: Icon = if (isCustomPack) {
                // Load from filesystem
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val image = ImageIO.read(file)
                        ImageIcon(image)
                    } else {
                        com.intellij.icons.AllIcons.General.Information
                    }
                } catch (_: Throwable) {
                    com.intellij.icons.AllIcons.General.Information
                }
            } else {
                // Load from bundled resources
                try {
                    IconLoader.getIcon(path, javaClass)
                } catch (_: Throwable) {
                    com.intellij.icons.AllIcons.General.Information
                }
            }
            val targetPx = JBUI.scale(dip)
            val baseH = max(1, raw.iconHeight)
            val scale = targetPx.toFloat() / baseH
            IconUtil.scale(raw, null, scale)
        }
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
