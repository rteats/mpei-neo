package com.rteats.mpeineo.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rteats.mpeineo.model.ScheduleTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mpei_neo_preferences")

class UserPreferences(
    private val context: Context,
    private val gson: Gson,
) {
    private val favoritesKey = stringPreferencesKey("favorites")
    private val selectedKey = stringPreferencesKey("selected_schedule")
    private val refreshOnLaunchKey = booleanPreferencesKey("refresh_on_launch")

    val favorites: Flow<List<ScheduleTarget>> = context.dataStore.data.map { prefs ->
        decodeFavorites(prefs[favoritesKey])
    }

    val selected: Flow<ScheduleTarget?> = context.dataStore.data.map { prefs ->
        prefs[selectedKey]?.let { runCatching { gson.fromJson(it, ScheduleTarget::class.java) }.getOrNull() }
    }

    val refreshOnLaunch: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[refreshOnLaunchKey] ?: true
    }

    suspend fun setSelected(target: ScheduleTarget) {
        context.dataStore.edit { it[selectedKey] = gson.toJson(target) }
    }

    suspend fun setRefreshOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[refreshOnLaunchKey] = enabled }
    }

    suspend fun toggleFavorite(target: ScheduleTarget) {
        context.dataStore.edit { prefs ->
            val current = decodeFavorites(prefs[favoritesKey]).toMutableList()
            val index = current.indexOfFirst { it.id == target.id && it.type == target.type }
            if (index >= 0) current.removeAt(index) else current.add(target)
            prefs[favoritesKey] = gson.toJson(current)
        }
    }

    private fun decodeFavorites(raw: String?): List<ScheduleTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<ScheduleTarget>>() {}.type
        return runCatching { gson.fromJson<List<ScheduleTarget>>(raw, type) }.getOrDefault(emptyList())
    }
}
