package com.uav.analytics.services

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val BASE_URL_AUTO_PHOTO = "https://analytics.untitledad.in/api/"
    private const val BASE_URL_LIVE_DETECT = "https://analytics.untitledad.in/api/"

    @Volatile private var baseUrl: String = BASE_URL_AUTO_PHOTO
    @Volatile private var retrofitRef: Retrofit? = null

    private val logging = HttpLoggingInterceptor().apply {
        // BODY/HEADERS : debug ma BODY, prod ma NONE
        level = HttpLoggingInterceptor.Level.NONE
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(provideLoggingInterceptor())
            .build()
    }

    private fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            Log.d("--API--", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY // This will log headers, body, etc.
        }
    }

    private fun buildRetrofit(url: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()

    /** Map start_destination → base URL and rebuild Retrofit if needed */
    @Synchronized
    fun applyStartDestination(dest: String?) {
        val newUrl = when (dest?.lowercase()) {
            "auto_photo_capture" -> BASE_URL_AUTO_PHOTO
            "live_camera_detect" -> BASE_URL_LIVE_DETECT
            else -> BASE_URL_AUTO_PHOTO // fallback
        }
        if (newUrl != baseUrl || retrofitRef == null) {
            baseUrl = newUrl
            retrofitRef = buildRetrofit(baseUrl)
        }
    }

    val api: CrowdApi
        get() {
            // lazy-init if not built yet
            if (retrofitRef == null) {
                synchronized(this) {
                    if (retrofitRef == null) retrofitRef = buildRetrofit(baseUrl)
                }
            }
            return retrofitRef!!.create(CrowdApi::class.java)
        }
}