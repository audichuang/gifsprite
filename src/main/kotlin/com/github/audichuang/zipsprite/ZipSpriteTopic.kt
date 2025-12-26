package com.github.audichuang.zipsprite

import com.intellij.util.messages.Topic;

interface ZipSpriteTopic {
    fun tapped()
        companion object {

            val TOPIC: Topic<ZipSpriteTopic> = Topic.create(
                "ZipSprite Tap",
                ZipSpriteTopic::class.java
            )

        }


}
