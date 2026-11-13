package com.rohitkhandel.ggaudio.audio

data class EqualizerBand(
    val frequencyHz: Int,
    val label: String,
    val gainDb: Float = 0f
) {
    init {
        require(frequencyHz > 0) { "Frequency must be positive." }
        require(gainDb in -12f..12f) { "Gain must be between -12 dB and +12 dB." }
    }
}

object EqualizerDefaults {
    val frequencies = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    val labels = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

    fun flat(): List<EqualizerBand> = frequencies.mapIndexed { index, hz ->
        EqualizerBand(hz, labels[index], 0f)
    }

    fun balanced(): List<EqualizerBand> =
        flat().mapIndexed { index, band ->
            band.copy(gainDb = listOf(2f, 3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 1f)[index])
        }
}
