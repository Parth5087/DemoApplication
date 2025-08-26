package com.example.demoapplication.services

import com.example.demoapplication.domain.analytics.CameraEnvelope
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query


interface CrowdApi {
    // ✅ endpoint matches your curl: /functions/SubmitCameraReport
    @POST("SubmitCameraReport")
    suspend fun postAggregates(
        @Body body: Map<String, CameraEnvelope>
    ): Response<Unit>

    // NEW: /v1/ingest?camera_id=cam1  (multipart: file + camera_id)
    @Multipart
    @POST("v1/ingest")
    suspend fun ingestZip(
        @Query("camera_id") cameraIdQuery: String,                    // ?camera_id=cam1
        @Part file: MultipartBody.Part                              // file part
    ): Response<ResponseBody>
}