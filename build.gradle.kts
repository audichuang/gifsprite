plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.github.audichuang"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }  // No upper limit for future compatibility
        }

        changeNotes = """
            <h3>Version 1.0.0</h3>
            <ul>
                <li><b>Two Animation Modes</b> - Auto Play (always moving) or Type Triggered (moves when you type)</li>
                <li><b>Speed Control</b> - Make it faster or slower (10-500)</li>
                <li><b>GIF Import</b> - Use your own GIF files</li>
                <li><b>Any Language Names</b> - Chinese, Japanese, and more</li>
                <li><b>Drag & Drop</b> - Move it anywhere you want</li>
                <li><b>Resize</b> - Make it bigger or smaller (0-512)</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(21)
}
