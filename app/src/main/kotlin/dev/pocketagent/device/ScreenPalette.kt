package dev.pocketagent.device

/** A coarse summary of visible pixels. No pixels, text, or image hashes are returned. */
internal data class ScreenPalette(val colors: List<Int>, val light: Boolean, val patterned: Boolean) {
    companion object {
        fun sample(
            width: Int, height: Int, regions: List<Bounds>, excluded: List<Bounds> = emptyList(),
            pixel: (Int, Int) -> Int,
        ): ScreenPalette? {
            val histogram = mutableMapOf<Int, Int>()
            var total = 0
            var bright = 0
            val step = maxOf(12, minOf(width, height) / 30)
            for (y in step / 2 until height step step) for (x in step / 2 until width step step) {
                if (regions.none { it.contains(x, y) } || excluded.any { it.contains(x, y) }) continue
                val rgb = pixel(x, y)
                val r = rgb ushr 16 and 255
                val g = rgb ushr 8 and 255
                val b = rgb and 255
                if (r * 2126 + g * 7152 + b * 722 >= 1_280_000) bright++
                val quantized = rgb and 0x00E0E0E0
                histogram[quantized] = (histogram[quantized] ?: 0) + 1
                total++
            }
            if (total < 8) return null
            val ranked = histogram.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            return ScreenPalette(ranked.take(4).map { it.key }, bright * 2 >= total, ranked.first().value * 4 < total * 3)
        }
    }
}
