package com.rteats.mpeineo

import android.app.Application
import com.google.gson.GsonBuilder
import com.rteats.mpeineo.data.DiagnosticLog
import com.rteats.mpeineo.data.FileScheduleCache
import com.rteats.mpeineo.data.GithubUpdateRepository
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

        val cleanup = container.updater.cleanupAfterLaunch(BuildConfig.VERSION_CODE)
        container.diagnostics.log(
            "APP",
            "started version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} channel=${BuildConfig.UPDATE_CHANNEL}",
        )
        container.diagnostics.log(
            "UPDATE",
            "startup cleanup deleted=${cleanup.deletedFiles} completedUpdate=${cleanup.completedInstalledUpdate}",
        )
    }
}

class AppContainer(application: Application) {
    private val gson = GsonBuilder().create()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val diagnostics = DiagnosticLog(application)
    val preferences = UserPreferences(application, gson)
    val updater = GithubUpdateRepository(
        application = application,
        httpClient = httpClient,
        gson = gson,
        diagnostics = diagnostics,
    )
    val repository = ScheduleRepository(
        remote = MpeiScheduleRemote(httpClient, gson),
        cache = FileScheduleCache(application, gson),
    )
}
