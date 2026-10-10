package com.rteats.mpeineo.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.favoriteKey
import com.rteats.mpeineo.model.isUsable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mpei_neo_preferences")

class UserPreferences(
    private val context: Context,
    private val gson: Gson,
) {
    private val favoritesKey = stringPreferencesKey("favorites")
    private val selectedKey = stringPreferencesKey("selected_schedule")
    private val favoriteNamesKey = stringPreferencesKey("favorite_custom_names")

    val favorites: Flow<List<ScheduleTarget>> = context.dataStore.data.map { prefs ->
        decodeFavorites(prefs[favoritesKey])
    }

    val selected: Flow<ScheduleTarget?> = context.dataStore.data.map { prefs ->
        prefs[selectedKey]?.let { raw ->
            runCatching { gson.fromJson(raw, ScheduleTarget::class.java) }
                .getOrNull()
                ?.takeIf { it.isUsable() }
        }
    }

    val favoriteNames: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        decodeFavoriteNames(prefs[favoriteNamesKey])
    }

    suspend fun setSelected(target: ScheduleTarget) {
        context.dataStore.edit { it[selectedKey] = gson.toJson(target) }
    }

    suspend fun toggleFavorite(target: ScheduleTarget) {
        context.dataStore.edit { prefs ->
            val current = decodeFavorites(prefs[favoritesKey]).toMutableList()
            val index = current.indexOfFirst { it.id == target.id && it.type == target.type }
            if (index >= 0) {
                current.removeAt(index)
                val names = decodeFavoriteNames(prefs[favoriteNamesKey]).toMutableMap()
                names.remove(target.favoriteKey())
                prefs[favoriteNamesKey] = gson.toJson(names)
            } else {
                current.add(target)
            }
            prefs[favoritesKey] = gson.toJson(current)
        }
    }

    suspend fun renameFavorite(target: ScheduleTarget, name: String) {
        context.dataStore.edit { prefs ->
            if (decodeFavorites(prefs[favoritesKey]).none {
                it.favoriteKey() == target.favoriteKey()
            }) return@edit
            val names = decodeFavoriteNames(prefs[favoriteNamesKey]).toMutableMap()
            val trimmed = name.trim()
            if (trimmed.isBlank() || trimmed == target.name) {
                names.remove(target.favoriteKey())
            } else {
                names[target.favoriteKey()] = trimmed.take(80)
            }
            prefs[favoriteNamesKey] = gson.toJson(names)
        }
    }

    private fun decodeFavoriteNames(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val type = object : TypeToken<Map<String, String>>() {}.type
        return runCatching { gson.fromJson<Map<String, String>>(raw, type) }
            .getOrNull().orEmpty().filterValues { !it.isNullOrBlank() }
    }

    private fun decodeFavorites(raw: String?): List<ScheduleTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<ScheduleTarget>>() {}.type
        return runCatching { gson.fromJson<List<ScheduleTarget>>(raw, type) }
            .getOrDefault(emptyList())
            .filter { it.isUsable() }
    }
}
