package com.rteats.mpeineo.model

import java.util.Locale

/** IDs are scoped to a search category, so room 42 and group 42 remain distinct. */
fun ScheduleTarget.favoriteKey(): String = "${type.apiName}:$id"

fun ScheduleTarget.displayName(favoriteNames: Map<String, String>): String =
    favoriteNames[favoriteKey()]?.takeIf(String::isNotBlank) ?: name

/** Local favorites are immediately searchable, even for aliases unknown to the remote API. */
fun matchingFavorites(
    favorites: List<ScheduleTarget>,
    favoriteNames: Map<String, String>,
    query: String,
    type: ScheduleTargetType?,
): List<ScheduleTarget> {
    val needle = query.trim().lowercase(Locale.ROOT)
    return favorites.filter { favorite ->
        (type == null || favorite.type == type) &&
            (needle.isEmpty() || listOf(
                favorite.name,
                favorite.description,
                favorite.type.displayName,
                favoriteNames[favorite.favoriteKey()].orEmpty(),
            ).any { it.lowercase(Locale.ROOT).contains(needle) })
    }
}
