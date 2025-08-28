package com.physicsgeek75.bongo

import com.intellij.util.messages.Topic;

interface BongoTopic {
    fun tapped()
        companion object {

            val TOPIC: Topic<BongoTopic> = Topic.create(
                "Bongo Tap",
                BongoTopic::class.java
            )

        }


}