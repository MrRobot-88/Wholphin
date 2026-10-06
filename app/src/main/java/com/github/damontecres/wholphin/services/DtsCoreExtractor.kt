package com.github.damontecres.wholphin.services

import java.nio.ByteBuffer

/*
 * Adapted from Moonfin-Client/Moonfin-Core (GPL-2.0), DtsCoreExtractor.kt
 * and the DTS core framing helper, donor commit
 * 36ed696f1d02ba240b459e953d98965ae514648c.
 */
internal class DtsCoreExtractor {
    private var input = ByteArray(0)
    private var output: ByteBuffer = ByteBuffer.allocateDirect(0)

    fun extract(buffer: ByteBuffer): ByteBuffer? {
        val length = buffer.remaining()
        if (input.size < length) input = ByteArray(length)
        buffer.duplicate().get(input, 0, length)
        if (output.capacity() < length) output = ByteBuffer.allocateDirect(length)
        output.clear()

        var pos = 0
        while (pos < length) {
            val coreSize = parseCoreSize(input, pos, length - pos) ?: return null
            val auLength = accessUnitLength(input, pos, length, coreSize)
            if (coreSize > auLength) return null
            output.put(input, pos, coreSize)
            pos += auLength
        }
        output.flip()
        return output
    }

    private fun parseCoreSize(data: ByteArray, offset: Int, available: Int): Int? {
        if (available < 9 || read32(data, offset) != SYNCWORD_CORE_BE) return null
        return ((read24(data, offset + 5) shr 4) and 0x3FFF) + 1
    }

    private fun accessUnitLength(
        data: ByteArray,
        offset: Int,
        end: Int,
        coreSize: Int,
    ): Int {
        var pos = offset + coreSize
        while (pos + 4 <= end) {
            if (read32(data, pos) == SYNCWORD_CORE_BE) return pos - offset
            pos++
        }
        return end - offset
    }

    private fun read24(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)

    private fun read32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    companion object {
        private const val SYNCWORD_CORE_BE = 0x7FFE8001L
    }
}
