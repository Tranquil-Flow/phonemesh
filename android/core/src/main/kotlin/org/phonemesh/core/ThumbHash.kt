package org.phonemesh.core

/**
 * ThumbHash by Evan Wallace (https://github.com/evanw/thumbhash), MIT licence, ported to Kotlin line for line:
 *
 * Copyright (c) 2023 Evan Wallace. Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions: The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 *
 * A 20-25 byte blurred colour sketch of a picture. [decode] is total: radio input that is not a sketch gives null.
 */
object ThumbHash {
    class Image(val width: Int, val height: Int, val rgba: ByteArray)

    /** RGBA rows, not premultiplied; each side at most 100 px. */
    fun encode(w: Int, h: Int, rgba: ByteArray): ByteArray {
        require(w in 1..100 && h in 1..100) { "${w}x$h doesn't fit in 100x100" }
        require(rgba.size == w * h * 4) { "need w*h*4 bytes" }
        var avgR = 0f; var avgG = 0f; var avgB = 0f; var avgA = 0f
        run {
            var j = 0
            for (i in 0 until w * h) {
                val alpha = (rgba[j + 3].toInt() and 255) / 255.0f
                avgR += alpha / 255.0f * (rgba[j].toInt() and 255)
                avgG += alpha / 255.0f * (rgba[j + 1].toInt() and 255)
                avgB += alpha / 255.0f * (rgba[j + 2].toInt() and 255)
                avgA += alpha
                j += 4
            }
        }
        if (avgA > 0) { avgR /= avgA; avgG /= avgA; avgB /= avgA }
        val hasAlpha = avgA < w * h
        val lLimit = if (hasAlpha) 5 else 7
        val lx = Math.max(1, Math.round((lLimit * w).toFloat() / Math.max(w, h).toFloat()))
        val ly = Math.max(1, Math.round((lLimit * h).toFloat() / Math.max(w, h).toFloat()))
        val l = FloatArray(w * h); val p = FloatArray(w * h); val q = FloatArray(w * h); val a = FloatArray(w * h)
        run {
            var j = 0
            for (i in 0 until w * h) {
                val alpha = (rgba[j + 3].toInt() and 255) / 255.0f
                val r = avgR * (1.0f - alpha) + alpha / 255.0f * (rgba[j].toInt() and 255)
                val g = avgG * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 1].toInt() and 255)
                val b = avgB * (1.0f - alpha) + alpha / 255.0f * (rgba[j + 2].toInt() and 255)
                l[i] = (r + g + b) / 3.0f
                p[i] = (r + g) / 2.0f - b
                q[i] = r - g
                a[i] = alpha
                j += 4
            }
        }
        val lC = Channel(Math.max(3, lx), Math.max(3, ly)).encode(w, h, l)
        val pC = Channel(3, 3).encode(w, h, p)
        val qC = Channel(3, 3).encode(w, h, q)
        val aC = if (hasAlpha) Channel(5, 5).encode(w, h, a) else null
        val isLandscape = w > h
        val header24 = Math.round(63.0f * lC.dc) or
            (Math.round(31.5f + 31.5f * pC.dc) shl 6) or
            (Math.round(31.5f + 31.5f * qC.dc) shl 12) or
            (Math.round(31.0f * lC.scale) shl 18) or
            (if (hasAlpha) 1 shl 23 else 0)
        val header16 = (if (isLandscape) ly else lx) or
            (Math.round(63.0f * pC.scale) shl 3) or
            (Math.round(63.0f * qC.scale) shl 9) or
            (if (isLandscape) 1 shl 15 else 0)
        val acStart = if (hasAlpha) 6 else 5
        val acCount = lC.ac.size + pC.ac.size + qC.ac.size + (aC?.ac?.size ?: 0)
        val hash = ByteArray(acStart + (acCount + 1) / 2)
        hash[0] = header24.toByte(); hash[1] = (header24 shr 8).toByte(); hash[2] = (header24 shr 16).toByte()
        hash[3] = header16.toByte(); hash[4] = (header16 shr 8).toByte()
        if (aC != null) hash[5] = (Math.round(15.0f * aC.dc) or (Math.round(15.0f * aC.scale) shl 4)).toByte()
        var acIndex = 0
        acIndex = lC.writeTo(hash, acStart, acIndex)
        acIndex = pC.writeTo(hash, acStart, acIndex)
        acIndex = qC.writeTo(hash, acStart, acIndex)
        aC?.writeTo(hash, acStart, acIndex)
        return hash
    }

    /** The sketch as RGBA (at most 32 px a side); null for bytes that are not a whole ThumbHash. */
    fun decode(hash: ByteArray): Image? {
        if (hash.size < 6) return null
        val header24 = (hash[0].toInt() and 255) or ((hash[1].toInt() and 255) shl 8) or ((hash[2].toInt() and 255) shl 16)
        val header16 = (hash[3].toInt() and 255) or ((hash[4].toInt() and 255) shl 8)
        val lDc = (header24 and 63).toFloat() / 63.0f
        val pDc = ((header24 shr 6) and 63).toFloat() / 31.5f - 1.0f
        val qDc = ((header24 shr 12) and 63).toFloat() / 31.5f - 1.0f
        val lScale = ((header24 shr 18) and 31).toFloat() / 31.0f
        val hasAlpha = (header24 shr 23) != 0
        val pScale = ((header16 shr 3) and 63).toFloat() / 63.0f
        val qScale = ((header16 shr 9) and 63).toFloat() / 63.0f
        val isLandscape = (header16 shr 15) != 0
        val lx = Math.max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else header16 and 7)
        val ly = Math.max(3, if (isLandscape) header16 and 7 else (if (hasAlpha) 5 else 7))
        val aDc = if (hasAlpha) (hash[5].toInt() and 15).toFloat() / 15.0f else 1.0f
        val aScale = ((hash[5].toInt() shr 4) and 15).toFloat() / 15.0f
        val acStart = if (hasAlpha) 6 else 5
        val lC = Channel(lx, ly); val pC = Channel(3, 3); val qC = Channel(3, 3)
        val aC = if (hasAlpha) Channel(5, 5) else null
        val acCount = lC.ac.size + pC.ac.size + qC.ac.size + (aC?.ac?.size ?: 0)
        if (hash.size < acStart + (acCount + 1) / 2) return null
        var acIndex = 0
        acIndex = lC.decode(hash, acStart, acIndex, lScale)
        acIndex = pC.decode(hash, acStart, acIndex, pScale * 1.25f)
        acIndex = qC.decode(hash, acStart, acIndex, qScale * 1.25f)
        aC?.decode(hash, acStart, acIndex, aScale)
        val ratio = aspectRatio(hash) ?: return null
        val w = Math.round(if (ratio > 1.0f) 32.0f else 32.0f * ratio)
        val h = Math.round(if (ratio > 1.0f) 32.0f / ratio else 32.0f)
        if (w < 1 || h < 1) return null
        val rgba = ByteArray(w * h * 4)
        val cxStop = Math.max(lx, if (hasAlpha) 5 else 3)
        val cyStop = Math.max(ly, if (hasAlpha) 5 else 3)
        val fx = FloatArray(cxStop); val fy = FloatArray(cyStop)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                var l = lDc; var p = pDc; var q = qDc; var a = aDc
                for (cx in 0 until cxStop) fx[cx] = Math.cos(Math.PI / w * (x + 0.5f) * cx).toFloat()
                for (cy in 0 until cyStop) fy[cy] = Math.cos(Math.PI / h * (y + 0.5f) * cy).toFloat()
                var j = 0
                for (cy in 0 until ly) {
                    val fy2 = fy[cy] * 2.0f
                    var cx = if (cy > 0) 0 else 1
                    while (cx * ly < lx * (ly - cy)) { l += lC.ac[j] * fx[cx] * fy2; cx++; j++ }
                }
                j = 0
                for (cy in 0 until 3) {
                    val fy2 = fy[cy] * 2.0f
                    var cx = if (cy > 0) 0 else 1
                    while (cx < 3 - cy) { val f = fx[cx] * fy2; p += pC.ac[j] * f; q += qC.ac[j] * f; cx++; j++ }
                }
                if (aC != null) {
                    j = 0
                    for (cy in 0 until 5) {
                        val fy2 = fy[cy] * 2.0f
                        var cx = if (cy > 0) 0 else 1
                        while (cx < 5 - cy) { a += aC.ac[j] * fx[cx] * fy2; cx++; j++ }
                    }
                }
                val b = l - 2.0f / 3.0f * p
                val r = (3.0f * l - b + q) / 2.0f
                val g = r - q
                rgba[i] = Math.max(0, Math.round(255.0f * Math.min(1f, r))).toByte()
                rgba[i + 1] = Math.max(0, Math.round(255.0f * Math.min(1f, g))).toByte()
                rgba[i + 2] = Math.max(0, Math.round(255.0f * Math.min(1f, b))).toByte()
                rgba[i + 3] = Math.max(0, Math.round(255.0f * Math.min(1f, a))).toByte()
                i += 4
            }
        }
        return Image(w, h, rgba)
    }

    /** Width / height of the original picture, roughly; null when the header is not a sketch. */
    fun aspectRatio(hash: ByteArray): Float? {
        if (hash.size < 5) return null
        val header = hash[3].toInt()
        val hasAlpha = (hash[2].toInt() and 0x80) != 0
        val isLandscape = (hash[4].toInt() and 0x80) != 0
        val lx = if (isLandscape) (if (hasAlpha) 5 else 7) else header and 7
        val ly = if (isLandscape) header and 7 else (if (hasAlpha) 5 else 7)
        if (lx == 0 || ly == 0) return null
        return lx.toFloat() / ly.toFloat()
    }

    private class Channel(val nx: Int, val ny: Int) {
        var dc = 0f
        var scale = 0f
        val ac: FloatArray

        init {
            var n = 0
            for (cy in 0 until ny) {
                var cx = if (cy > 0) 0 else 1
                while (cx * ny < nx * (ny - cy)) { n++; cx++ }
            }
            ac = FloatArray(n)
        }

        fun encode(w: Int, h: Int, channel: FloatArray): Channel {
            var n = 0
            val fx = FloatArray(w)
            for (cy in 0 until ny) {
                var cx = 0
                while (cx * ny < nx * (ny - cy)) {
                    var f = 0f
                    for (x in 0 until w) fx[x] = Math.cos(Math.PI / w * cx * (x + 0.5f)).toFloat()
                    for (y in 0 until h) {
                        val fy = Math.cos(Math.PI / h * cy * (y + 0.5f)).toFloat()
                        for (x in 0 until w) f += channel[x + y * w] * fx[x] * fy
                    }
                    f /= (w * h).toFloat()
                    if (cx > 0 || cy > 0) { ac[n++] = f; scale = Math.max(scale, Math.abs(f)) } else dc = f
                    cx++
                }
            }
            if (scale > 0) for (i in ac.indices) ac[i] = 0.5f + 0.5f / scale * ac[i]
            return this
        }

        fun decode(hash: ByteArray, start: Int, index: Int, scale: Float): Int {
            var idx = index
            for (i in ac.indices) {
                val data = hash[start + (idx shr 1)].toInt() shr ((idx and 1) shl 2)
                ac[i] = ((data and 15).toFloat() / 7.5f - 1.0f) * scale
                idx++
            }
            return idx
        }

        fun writeTo(hash: ByteArray, start: Int, index: Int): Int {
            var idx = index
            for (v in ac) {
                val at = start + (idx shr 1)
                hash[at] = (hash[at].toInt() or (Math.round(15.0f * v) shl ((idx and 1) shl 2))).toByte()
                idx++
            }
            return idx
        }
    }
}
