package com.rteats.mpeineo.data

import android.content.Context
import com.google.gson.Gson
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleWeek
import com.rteats.mpeineo.model.isUsable
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileScheduleCache(
    context: Context,
    private val gson: Gson,
) : ScheduleCacheStore {
    private val directory = File(context.filesDir, "schedule-cache")

    override suspend fun read(target: ScheduleTarget, weekStart: LocalDate): ScheduleWeek? =
        withContext(Dispatchers.IO) {
            val file = fileFor(target, weekStart)
            if (!file.exists()) return@withContext null
            val parsed = runCatching {
                gson.fromJson(file.readText(), ScheduleWeek::class.java)
            }.getOrNull()
            if (parsed?.isUsable() == true) {
                parsed
            } else {
                file.delete()
                null
            }
        }

    override suspend fun write(week: ScheduleWeek) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val file = fileFor(week.target, LocalDate.parse(week.weekStart))
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(gson.toJson(week))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        directory.deleteRecursively()
        Unit
    }

    private fun fileFor(target: ScheduleTarget, weekStart: LocalDate): File {
        val safeName = "${target.type.apiName}-${target.id}-${weekStart}.json"
        return File(directory, safeName)
    }
}
