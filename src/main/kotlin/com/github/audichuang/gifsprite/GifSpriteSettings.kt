package com.github.audichuang.gifsprite

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

// ========== MessageBus Topic for Cross-Project Sync ==========

/**
 * Listener interface for settings change events.
 * 設定變更的監聽器介面，讓所有專案視窗都能收到通知並同步更新。
 */
interface GifSpriteSettingsListener {
    fun onSettingsChanged()
}

/**
 * Topic for broadcasting settings changes across all project windows.
 * 用於廣播設定變更的 Topic，讓所有開啟的專案都能同步。
 */
val GIF_SPRITE_SETTINGS_TOPIC = Topic.create(
    "GifSprite Settings Changed",
    GifSpriteSettingsListener::class.java
)

// ========== State Class ==========

private const val DEFAULT_SIZE_DIP = 128

/**
 * Persistent state for GifSprite plugin.
 * GifSprite 插件的持久化設定狀態類。
 */
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
    var opacity: Int = 100 // 0-100 opacity percentage
    
    // Idle Mode
    var enableIdleMode: Boolean = false
    var idleActiveSpritePack: String = "default"  // 活動時的 GIF（打字時）
    var idleSpritePack: String = "default"        // 休息時的 GIF（閒置時）
    var idleTimeout: Int = 10 // seconds

    // Playlist Mode
    var enablePlaylist: Boolean = false
    var playlist: MutableList<String> = mutableListOf()
    var playlistInterval: Int = 10 // minutes
}

// ========== Application-Level Settings Service ==========

/**
 * Application-level settings for GifSprite plugin.
 * 全域設定服務，讓設定在所有專案中共用，不會因為開新專案而遺失設定。
 * 
 * 存儲位置：~/Library/Application Support/JetBrains/<IDE>/options/gifSpriteSettings.xml
 */
@State(
    name = "GifSpriteSettings",
    storages = [Storage(
        value = "gifSpriteSettings.xml",
        roamingType = RoamingType.DEFAULT  // 可跨 IDE 同步
    )]
)
@Service(Service.Level.APP)
class GifSpriteSettings : PersistentStateComponent<StickerState> {
    
    private var state = StickerState()
    
    companion object {
        @JvmStatic
        fun getInstance(): GifSpriteSettings =
            ApplicationManager.getApplication().getService(GifSpriteSettings::class.java)
    }
    
    override fun getState(): StickerState = state
    
    override fun loadState(s: StickerState) {
        state = s
    }
    
    /**
     * 提供對 state 的直接存取，供其他 Service 使用
     */
    val settings: StickerState
        get() = state
    
    /**
     * Notify all project windows that settings have changed.
     * 通知所有專案視窗設定已變更，觸發它們重新載入資源。
     */
    fun notifySettingsChanged() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(GIF_SPRITE_SETTINGS_TOPIC)
            .onSettingsChanged()
    }
}
