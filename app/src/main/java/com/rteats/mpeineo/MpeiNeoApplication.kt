package com.rteats.mpeineo

import android.app.Application
import com.google.gson.GsonBuilder
import com.rteats.mpeineo.data.FileScheduleCache
import com.rteats.mpeineo.data.MpeiScheduleRemote
import com.rteats.mpeineo.data.ScheduleRepository
import com.rteats.mpeineo.data.UserPreferences
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class MpeiNeoApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val gson = GsonBuilder().create()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    val preferences = UserPreferences(application, gson)
    val repository = ScheduleRepository(
        remote = MpeiScheduleRemote(httpClient, gson),
        cache = FileScheduleCache(application, gson),
    )
}
