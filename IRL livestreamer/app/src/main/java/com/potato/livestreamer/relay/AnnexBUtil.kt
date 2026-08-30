package com.potato.livestreamer.relay

/**
 * Utilitas kecil untuk cari NAL unit SPS/PPS di dalam access unit H.264 Annex-B.
 */
object AnnexBUtil {

    data class NalUnit(val type: Int, val start: Int, val end: Int)

    fun findNalUnits(data: ByteArray): List<NalUnit> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts.add(i + 3)
                i += 3
            } else if (i < data.size - 4 &&
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                starts.add(i + 4)
                i += 4
            } else {
                i++
            }
        }
        val units = mutableListOf<NalUnit>()
        for ((idx, s) in starts.withIndex()) {
            if (s >= data.size) continue
            val type = data[s].toInt() and 0x1F
            val end = if (idx + 1 < starts.size) {
                var backTo = starts[idx + 1]
                while (backTo > s && data[backTo - 1] == 0.toByte()) backTo--
                backTo - 3
            } else {
                data.size
            }
            units.add(NalUnit(type, s, end.coerceAtLeast(s)))
        }
        return units
    }

    fun sps(data: ByteArray): ByteArray? = findNalUnits(data).firstOrNull { it.type == 7 }
        ?.let { data.copyOfRange(it.start, it.end) }

    fun pps(data: ByteArray): ByteArray? = findNalUnits(data).firstOrNull { it.type == 8 }
        ?.let { data.copyOfRange(it.start, it.end) }

    fun containsKeyFrame(data: ByteArray): Boolean = findNalUnits(data).any { it.type == 5 }
}
