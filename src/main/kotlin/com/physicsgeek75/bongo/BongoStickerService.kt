package com.physicsgeek75.bongo

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
import javax.swing.*
import kotlin.math.max
import kotlin.math.min
import java.util.concurrent.ConcurrentHashMap


private const val DEFAULT_SIZE_DIP = 128
private const val DEFAULT_MARGIN_DIP = 0
private const val ICON_DIP = DEFAULT_SIZE_DIP


class StickerState {
    var visible: Boolean = true
    var xDip: Int = -1
    var yDip: Int = -1
    var sizeDip: Int = DEFAULT_SIZE_DIP
    var animationSpeed: Int = 30 // ms between frames (lower = faster)
    var enableSmoothAnimation: Boolean = true
}

@State(name = "BongoStickerState", storages = [Storage("bongoSticker.xml")])
@Service(Service.Level.PROJECT)
class BongoStickerService(private val project: Project)
    : PersistentStateComponent<StickerState>, Disposable {

    private var state = StickerState()

    private var layeredPane: JLayeredPane? = null
    private var lpResizeListener: ComponentListener? = null
    private var panel: JPanel? = null
    private var label: JBLabel? = null
    private var animationIcons: Array<Icon?> = arrayOfNulls(24)
    private var currentFrame = 0
    private var punchTimer: Timer? = null
    
    // Performance optimizations
    private val iconCache = ConcurrentHashMap<String, Icon>()
    private var lastFrameTime = 0L
    private val MIN_FRAME_INTERVAL get() = state.animationSpeed.toLong()

    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    private lateinit var idleIcon: Icon


    init {
        // Pre-load all icons at startup for better performance
        for (i in 1..24) {
            animationIcons[i - 1] = loadScaledIcon("/icons/usagi-butt/$i.svg", state.sizeDip)
        }
        idleIcon = animationIcons[0]!!
        
        connection.subscribe(BongoTopic.TOPIC, object : BongoTopic {
            override fun tapped() = onTap()
        })
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

        // Initialize idle timer
        setupIdleTimer()
        
        label = JBLabel(idleIcon).apply {
            horizontalAlignment = JBLabel.CENTER
            verticalAlignment = JBLabel.CENTER
            toolTipText = "Usagi Butt"
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
        
        // Remove UI components
        listener?.let { lp?.removeComponentListener(it) }
        p?.let { 
            lp?.remove(it)
            lp?.revalidate()
            lp?.repaint()
        }
        
        // Clear resources
        animationIcons = arrayOfNulls(24)
        currentFrame = 0
        iconCache.clear()
    }

    fun applySize(newDip: Int) {
        state.sizeDip = newDip.coerceIn(0, 512)

        if (state.visible && panel == null) {
            ensureAttached()
        }

        // Reload all icons with new size
        iconCache.clear()
        for (i in 1..24) {
            animationIcons[i - 1] = loadScaledIcon("/icons/usagi-butt/$i.svg", state.sizeDip)
        }
        idleIcon = animationIcons[0]!!

        val sizePx = JBUI.scale(state.sizeDip)

        label?.apply {
            preferredSize = JBDimensionDip(state.sizeDip, state.sizeDip)
            minimumSize   = preferredSize
            maximumSize   = preferredSize
            setBounds(0, 0, sizePx.toInt(), sizePx)
            icon = animationIcons[currentFrame % 24]
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


    override fun dispose() {
        try {
            // Disconnect message bus first
            connection.disconnect()
            
            // Stop all timers
            idleTimer?.stop()
            idleTimer = null
            punchTimer?.stop()
            punchTimer = null
            
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
        
        // Advance to next frame in animation sequence
        currentFrame = (currentFrame + 1) % 24
        val nextIcon = animationIcons[currentFrame]
        if (nextIcon != null) {
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

    private fun loadScaledIcon(path: String, dip: Int): Icon {
        // Use cache to avoid reloading same icons
        val cacheKey = "$path:$dip"
        return iconCache.getOrPut(cacheKey) {
            val raw = try { 
                IconLoader.getIcon(path, javaClass) 
            } catch (_: Throwable) { 
                com.intellij.icons.AllIcons.General.Information 
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
