package com.relaypony.desktop

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a QR code straight to the terminal. Each module is drawn as two spaces with an explicit
 * ANSI background colour (black for dark modules, white for light), so it scans correctly
 * regardless of the terminal's own background. Passing width/height 0 yields one cell per module.
 */
object TerminalQr {

    private const val DARK = "\u001b[40m  \u001b[0m"     // black background
    private const val LIGHT = "\u001b[107m  \u001b[0m"   // bright white background

    fun print(text: String) {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2),
        )
        val sb = StringBuilder("\n")
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) sb.append(if (matrix.get(x, y)) DARK else LIGHT)
            sb.append('\n')
        }
        println(sb)
    }
}
