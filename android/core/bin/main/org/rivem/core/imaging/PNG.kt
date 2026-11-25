package org.rivem.core.imaging

import java.io.ByteArrayOutputStream

/**
 * Minimal PNG writer for `RGBAImage` (8-bit RGBA, colour type 6, no interlace), pure Kotlin with no zlib: the image
 * data goes into a zlib stream of *stored* (uncompressed) deflate blocks, so files are about 4·W·H bytes. Used for
 * desktop's `transformed_image.png`, `mask.png` and `final_mask.png`; any PNG reader (PIL, browsers, Electron)
 * opens them. Byte-identical to the Swift writer (java.util.zip's Deflater would produce a different stream). The app
 * may re-encode with Bitmap.compress when size matters.
 */
object PNG {
    val signature: ByteArray = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun encode(image: RGBAImage): ByteArray {
        val rowBytes = image.width * 4
        val raw = (rowBytes + 1) * image.height
        val out = ByteArrayOutputStream(raw + raw / 65535 * 5 + 128)
        out.write(signature)

        val ihdr = ByteArrayOutputStream(13)
        appendBigEndian(ihdr, image.width.toUInt())
        appendBigEndian(ihdr, image.height.toUInt())
        ihdr.write(byteArrayOf(8, 6, 0, 0, 0)) // bit depth, colour type RGBA, deflate, filter 0, no interlace
        appendChunk(out, "IHDR", ihdr.toByteArray())

        // Scanlines with filter byte 0 (None).
        val scan = ByteArray(raw)
        for (y in 0 until image.height) {
            val start = y * rowBytes
            scan[y * (rowBytes + 1)] = 0
            image.bytes.copyInto(scan, y * (rowBytes + 1) + 1, start, start + rowBytes)
        }
        appendChunk(out, "IDAT", zlibStored(scan))
        appendChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** zlib stream (RFC 1950) with stored deflate blocks (RFC 1951, BTYPE 00) of at most 65 535 bytes. */
    fun zlibStored(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + data.size / 65535 * 5 + 16)
        out.write(0x78) // CMF: deflate, 32 KiB window
        out.write(0x01) // FLG: no dict, level 0, (CMF·256+FLG) % 31 == 0
        var offset = 0
        do {
            val n = minOf(65535, data.size - offset)
            val isLast = if (offset + n >= data.size) 1 else 0
            out.write(isLast)
            val len = n and 0xFFFF
            val nlen = len.inv() and 0xFFFF
            out.write(len and 0xFF)
            out.write(len shr 8)
            out.write(nlen and 0xFF)
            out.write(nlen shr 8)
            out.write(data, offset, n)
            offset += n
        } while (offset < data.size)
        appendBigEndian(out, adler32(data))
        return out.toByteArray()
    }

    fun adler32(data: ByteArray): UInt {
        var a = 1u
        var b = 0u
        var i = 0
        while (i < data.size) {
            val end = minOf(i + 5552, data.size) // largest block before the sums can overflow 32 bits
            while (i < end) {
                a += (data[i].toInt() and 0xFF).toUInt()
                b += a
                i += 1
            }
            a %= 65521u
            b %= 65521u
        }
        return (b shl 16) or a
    }

    /** Stored as the bits of each UInt32 entry (IntArray avoids the experimental UIntArray). */
    private val crcTable: IntArray = IntArray(256).also { table ->
        for (n in 0 until 256) {
            var c = n.toUInt()
            repeat(8) { c = if ((c and 1u) != 0u) 0xEDB8_8320u xor (c shr 1) else c shr 1 }
            table[n] = c.toInt()
        }
    }

    /** CRC-32 (ISO 3309 / PNG). */
    fun crc32(bytes: ByteArray): UInt {
        var c = 0xFFFF_FFFFu
        for (byte in bytes) c = crcTable[((c xor (byte.toInt() and 0xFF).toUInt()) and 0xFFu).toInt()].toUInt() xor (c shr 8)
        return c xor 0xFFFF_FFFFu
    }

    internal fun appendBigEndian(out: ByteArrayOutputStream, v: UInt) {
        out.write(((v shr 24) and 0xFFu).toInt())
        out.write(((v shr 16) and 0xFFu).toInt())
        out.write(((v shr 8) and 0xFFu).toInt())
        out.write((v and 0xFFu).toInt())
    }

    internal fun appendChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        appendBigEndian(out, data.size.toUInt())
        val typeBytes = type.toByteArray(Charsets.UTF_8)
        out.write(typeBytes)
        out.write(data)
        appendBigEndian(out, crc32(typeBytes + data))
    }
}
