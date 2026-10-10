package com.rteats.mpeineo.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteSearchTest {
    private val teacher = ScheduleTarget(
        id = 42, name = "Иванов И.И.", description = "Кафедра", type = ScheduleTargetType.PERSON,
    )
    private val group = ScheduleTarget(
        id = 42, name = "А-12", type = ScheduleTargetType.GROUP,
    )
    private val favorites = listOf(teacher, group)
    private val aliases = mapOf(teacher.favoriteKey() to "Физика любимая")

    @Test fun blankQueryShowsAllFavorites() {
        assertEquals(favorites, matchingFavorites(favorites, aliases, "", null))
    }

    @Test fun customNameCanBeSearchedCaseInsensitively() {
        assertEquals(listOf(teacher), matchingFavorites(favorites, aliases, "ЛЮБИМАЯ", null))
    }

    @Test fun originalNameStaysSearchableAfterRename() {
        assertEquals(listOf(teacher), matchingFavorites(favorites, aliases, "иванов", null))
    }

    @Test fun typeFilterStillAppliesToAliases() {
        assertEquals(emptyList<ScheduleTarget>(), matchingFavorites(
            favorites, aliases, "физика", ScheduleTargetType.GROUP,
        ))
    }

    @Test fun typeAndIdTogetherFormUniqueFavoriteKey() {
        assertEquals("person:42", teacher.favoriteKey())
        assertEquals("group:42", group.favoriteKey())
        assertEquals("Физика любимая", teacher.displayName(aliases))
        assertEquals("А-12", group.displayName(aliases))
    }
}
