package com.github.audichuang.gifsprite

import com.intellij.openapi.application.PathManager
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode
import org.w3c.dom.NodeList

/**
 * Manages sprite packs for GifSprite plugin.
 * Handles GIF import, frame extraction, and sprite pack listing.
 */
object GifSpriteManager {

    private const val PACKS_DIR_NAME = "gifsprite-packs"
    private const val DEFAULT_PACK_NAME = "default"

    /**
     * Get the directory where sprite packs are stored.
     */
    fun getPacksDirectory(): Path {
        val configPath = PathManager.getConfigPath()
        return Paths.get(configPath, PACKS_DIR_NAME)
    }

    /**
     * Ensure the packs directory exists.
     */
    fun ensurePacksDirectoryExists(): Path {
        val packsDir = getPacksDirectory()
        if (!Files.exists(packsDir)) {
            Files.createDirectories(packsDir)
        }
        return packsDir
    }

    /**
     * Ensure the default pack (cute_dog) exists in the filesystem.
     * If not, it extracts it from the bundled resource.
     */
    fun ensureDefaultPackExists() {
        val packsDir = ensurePacksDirectoryExists()
        val defaultPackDir = packsDir.resolve(DEFAULT_PACK_NAME)

        // If default pack doesn't exist or is empty, re-extract it
        val isEmpty = !Files.exists(defaultPackDir) || Files.list(defaultPackDir).use { it.count() == 0L }
        
        if (isEmpty) {
            try {
                // Load bundled GIF resource
                val resourceStream = javaClass.getResourceAsStream("/icons/cute_dog.gif")
                if (resourceStream != null) {
                    val tempFile = Files.createTempFile("default_gif", ".gif")
                    Files.copy(resourceStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
                    
                    importGif(tempFile.toFile(), DEFAULT_PACK_NAME)
                    
                    Files.deleteIfExists(tempFile)
                } 
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Get list of available sprite pack names.
     * Returns "default" plus any custom packs.
     */
    fun getAvailablePacks(): List<String> {
        val packs = mutableListOf<String>()
        val packsDir = getPacksDirectory()

        // Ensure default is always in the list if it exists (or we'll create it)
        ensureDefaultPackExists()
        packs.add(DEFAULT_PACK_NAME)

        if (Files.exists(packsDir)) {
            Files.list(packsDir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .map { it.fileName.toString() }
                    .filter { it != DEFAULT_PACK_NAME } // avoid duplicate "default"
                    .forEach { packs.add(it) }
            }
        }

        return packs
    }

    /**
     * Import a GIF file from a URL.
     */
    fun importGifFromUrl(urlString: String, packName: String): Int {
        try {
            val url = java.net.URI.create(urlString).toURL()
            val tempFile = Files.createTempFile("import_url", ".gif")
            
            url.openStream().use { input ->
                Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
            }
            
            val count = importGif(tempFile.toFile(), packName)
            Files.deleteIfExists(tempFile)
            return count
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }
    }

    /**
     * Import a GIF file and extract its frames to a new sprite pack.
     *
     * @param gifFile The GIF file to import
     * @param packName The name for the new sprite pack
     * @return The number of frames extracted, or -1 on error
     */
    fun importGif(gifFile: File, packName: String): Int {
        try {
            val packsDir = ensurePacksDirectoryExists()
            val packDir = packsDir.resolve(sanitizePackName(packName))

            // Create pack directory
            if (Files.exists(packDir)) {
                // Delete existing pack with same name
                packDir.toFile().deleteRecursively()
            }
            Files.createDirectories(packDir)

            // Extract GIF frames
            return extractGifFrames(gifFile, packDir)
        } catch (e: Exception) {
            e.printStackTrace()
            return -1
        }
    }

    /**
     * Data class to hold GIF frame metadata
     */
    private data class FrameMetadata(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val disposalMethod: String
    )

    /**
     * Extract metadata from a GIF frame
     */
    private fun getFrameMetadata(reader: ImageReader, frameIndex: Int): FrameMetadata {
        try {
            val metadata = reader.getImageMetadata(frameIndex)
            val tree = metadata.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode

            val imgDescr = getNode(tree, "ImageDescriptor")
            val left = imgDescr?.getAttribute("imageLeftPosition")?.toIntOrNull() ?: 0
            val top = imgDescr?.getAttribute("imageTopPosition")?.toIntOrNull() ?: 0
            val width = imgDescr?.getAttribute("imageWidth")?.toIntOrNull() ?: reader.getWidth(frameIndex)
            val height = imgDescr?.getAttribute("imageHeight")?.toIntOrNull() ?: reader.getHeight(frameIndex)

            val gce = getNode(tree, "GraphicControlExtension")
            val disposalMethod = gce?.getAttribute("disposalMethod") ?: "none"

            return FrameMetadata(left, top, width, height, disposalMethod)
        } catch (e: Exception) {
            // Fallback to basic values
            return FrameMetadata(0, 0, reader.getWidth(frameIndex), reader.getHeight(frameIndex), "none")
        }
    }

    /**
     * Get a child node by name from the metadata tree
     */
    private fun getNode(root: IIOMetadataNode, nodeName: String): IIOMetadataNode? {
        val nodes: NodeList = root.getElementsByTagName(nodeName)
        return if (nodes.length > 0) nodes.item(0) as IIOMetadataNode else null
    }

    /**
     * Extract frames from a GIF file and save as numbered PNGs.
     * Properly handles GIF disposal methods and frame positioning.
     */
    private fun extractGifFrames(gifFile: File, destDir: Path): Int {
        val readers = ImageIO.getImageReadersByFormatName("gif")
        if (!readers.hasNext()) {
            throw IllegalStateException("No GIF reader available")
        }

        val reader: ImageReader = readers.next()

        ImageIO.createImageInputStream(gifFile).use { inputStream ->
            reader.input = inputStream

            val frameCount = reader.getNumImages(true)
            if (frameCount <= 0) {
                throw IllegalStateException("No frames found in GIF")
            }

            // Get the logical screen size from the first frame
            val firstFrame = reader.read(0)
            val canvasWidth = firstFrame.width
            val canvasHeight = firstFrame.height

            // Create the main canvas and a backup for "restoreToPrevious"
            var canvas = BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB)
            var previousCanvas: BufferedImage? = null

            for (i in 0 until frameCount) {
                val frame = reader.read(i)
                val metadata = getFrameMetadata(reader, i)

                // Save canvas state before drawing (for restoreToPrevious)
                if (metadata.disposalMethod == "restoreToPrevious") {
                    previousCanvas = copyImage(canvas)
                }

                // Draw the current frame onto the canvas at the correct position
                val g = canvas.createGraphics()
                g.drawImage(frame, metadata.left, metadata.top, null)
                g.dispose()

                // Save the composed frame as PNG
                val outputFile = destDir.resolve("${i + 1}.png").toFile()
                ImageIO.write(canvas, "png", outputFile)

                // Handle disposal method for the NEXT frame
                when (metadata.disposalMethod) {
                    "restoreToBackgroundColor" -> {
                        // Clear the frame area to transparent
                        val g2 = canvas.createGraphics()
                        g2.composite = AlphaComposite.Clear
                        g2.fillRect(metadata.left, metadata.top, metadata.width, metadata.height)
                        g2.dispose()
                    }
                    "restoreToPrevious" -> {
                        // Restore to the saved state
                        previousCanvas?.let { canvas = copyImage(it) }
                    }
                    // "none" or "doNotDispose" - leave canvas as is
                }
            }

            reader.dispose()
            return frameCount
        }
    }

    /**
     * Create a deep copy of a BufferedImage
     */
    private fun copyImage(source: BufferedImage): BufferedImage {
        val copy = BufferedImage(source.width, source.height, source.type)
        val g = copy.createGraphics()
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return copy
    }

    /**
     * Get the frame count for a sprite pack.
     */
    fun getFrameCount(packName: String): Int {
        val packDir = getPacksDirectory().resolve(packName)
        if (!Files.exists(packDir)) {
             // Try to ensure default pack if that's what we are looking for
             if (packName == DEFAULT_PACK_NAME) {
                 ensureDefaultPackExists()
                 if (Files.exists(packDir)) {
                     // Recurse once
                     return getFrameCount(packName)
                 }
             }
             return 0
        }

        var count = 0
        while (Files.exists(packDir.resolve("${count + 1}.png"))) {
            count++
        }
        return count
    }

    /**
     * Get the path to a specific frame in a sprite pack.
     *
     * @param packName The sprite pack name
     * @param frameNumber The frame number (1-based)
     * @return The absolute file path string
     */
    fun getFramePath(packName: String, frameNumber: Int): String {
       return getPacksDirectory().resolve(packName).resolve("$frameNumber.png").toString()
    }

    /**
     * Check if a sprite pack uses file system (custom) or resources (default).
     * Now everything is file system based.
     */
    fun isCustomPack(packName: String): Boolean {
        return true
    }

    /**
     * Delete a sprite pack.
     * Cannot delete "default" pack.
     */
    fun deletePack(packName: String): Boolean {
        if (packName == DEFAULT_PACK_NAME) {
            return false
        }

        val packDir = getPacksDirectory().resolve(packName)
        return if (Files.exists(packDir)) {
            packDir.toFile().deleteRecursively()
        } else {
            false
        }
    }

    /**
     * Sanitize pack name to be filesystem-safe.
     * Allows Unicode characters (Chinese, Japanese, etc.) but removes unsafe filesystem characters.
     */
    private fun sanitizePackName(name: String): String {
        // Only remove characters that are unsafe for filesystems: / \ : * ? " < > |
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
            .take(50)
            .ifEmpty { "unnamed" }
    }
}
