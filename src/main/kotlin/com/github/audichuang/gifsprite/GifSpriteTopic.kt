package com.github.audichuang.gifsprite

import com.intellij.util.messages.Topic;

interface GifSpriteTopic {
    fun tapped()
        companion object {

            val TOPIC: Topic<GifSpriteTopic> = Topic.create(
                "GifSprite Tap",
                GifSpriteTopic::class.java
            )

        }


}
