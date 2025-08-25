package com.example.demoapplication.services

import com.example.demoapplication.domain.analytics.CameraEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST


interface CrowdApi {
    // ✅ endpoint matches your curl: /functions/SubmitCameraReport
    @POST("SubmitCameraReport")
    suspend fun postAggregates(
        @Body body: Map<String, CameraEnvelope>
    ): Response<Unit>
}