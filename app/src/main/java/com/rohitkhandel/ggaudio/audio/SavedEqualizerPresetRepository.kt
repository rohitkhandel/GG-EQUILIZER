package com.rohitkhandel.ggaudio.audio

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

private val Context.ggAudioPresetDataStore by preferencesDataStore(
    name = "gg_audio_presets",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

private val PRESETS_JSON = stringPreferencesKey("custom_equalizer_presets")

class SavedEqualizerPresetRepository(context: Context) {
    private val dataStore = context.applicationContext.ggAudioPresetDataStore

    val presets: Flow<List<SavedEqualizerPreset>> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> PresetJsonCodec.decode(preferences[PRESETS_JSON].orEmpty()) }
        .distinctUntilChanged()

    suspend fun savePreset(
        name: String,
        bands: List<EqualizerBand>,
        preampDb: Float
    ): SavedEqualizerPreset {
        val cleanName = name.trim()
        require(cleanName.length in 1..32) { "Enter a preset name between 1 and 32 characters." }
        require(cleanName.none { it.isISOControl() }) { "Preset name contains unsupported characters." }
        require(cleanName.lowercase() !in EqualizerPresetLibrary.reservedNames) {
            "That name belongs to a built-in preset. Choose another name."
        }
        require(bands.size == EqualizerDefaults.frequencies.size) { "The current equalizer must have 10 bands." }
        require(preampDb.isFinite() && preampDb in -12f..12f) { "Preamp must be between -12 dB and +12 dB." }

        val preset = SavedEqualizerPreset(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            gainsDb = bands.map { it.gainDb },
            preampDb = preampDb
        )

        dataStore.edit { preferences ->
            val current = PresetJsonCodec.decode(preferences[PRESETS_JSON].orEmpty())
            require(current.none { it.name.equals(cleanName, ignoreCase = true) }) {
                "A custom preset with this name already exists."
            }
            preferences[PRESETS_JSON] = PresetJsonCodec.encode(current + preset)
        }
        return preset
    }

    suspend fun deletePreset(id: String) {
        require(id.isNotBlank()) { "Preset ID cannot be blank." }
        dataStore.edit { preferences ->
            val current = PresetJsonCodec.decode(preferences[PRESETS_JSON].orEmpty())
            preferences[PRESETS_JSON] = PresetJsonCodec.encode(current.filterNot { it.id == id })
        }
    }
}
