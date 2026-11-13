package com.relaypony.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Ways a desktop reads the other device's pairing QR without a camera: a screenshot or copied
 * text on the clipboard, or an image file. Each returns the QR's text, or null when there is none.
 */
object QrInput {

    /** A pairing code from the clipboard: an image holding a QR, or the code's text itself. */
    fun fromClipboard(): String? {
        val clip = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return null
        if (runCatching { clip.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false)) {
            val image = runCatching { clip.getData(DataFlavor.imageFlavor) as? Image }.getOrNull()
            image?.let { decode(toBuffered(it)) }?.let { return it }
        }
        if (runCatching { clip.isDataFlavorAvailable(DataFlavor.stringFlavor) }.getOrDefault(false)) {
            return runCatching { clip.getData(DataFlavor.stringFlavor) as? String }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun fromImageFile(file: File): String? = runCatching { ImageIO.read(file) }.getOrNull()?.let { decode(it) }

    private fun decode(image: BufferedImage): String? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        image.getRGB(0, 0, w, h, pixels, 0, w)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        return runCatching {
            QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
        }.getOrNull()
    }

    private fun toBuffered(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val w = image.getWidth(null).coerceAtLeast(1)
        val h = image.getHeight(null).coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return out
    }

    /** Put [text] on the clipboard (a received text drop's Copy). */
    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }
}
