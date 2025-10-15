package com.uav.analytics.services

import com.uav.analytics.domain.analytics.HourlyPayload
import com.uav.analytics.models.DeviceStatusRequest
import com.uav.analytics.models.RegisterDeviceRequest
import com.uav.analytics.models.RegisterDeviceResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query


interface CrowdApi {
    @POST("store-analytics-data")
    suspend fun postAggregates(
        @Body body: HourlyPayload
    ): Response<Unit>

    // NEW: /v1/ingest?camera_id=cam1  (multipart: file + camera_id)
    @Multipart
    @POST("v1/ingest")
    suspend fun ingestZip(
        @Query("camera_id") cameraIdQuery: String,                    // ?camera_id=cam1
        @Part file: MultipartBody.Part                              // file part
    ): Response<ResponseBody>

    @POST("store-device-data")
    suspend fun registerDevice(
        @Body request: RegisterDeviceRequest
    ): Response<RegisterDeviceResponse>

    @POST("store-device-status")
    suspend fun deviceStatus(
        @Body request: DeviceStatusRequest
    ): Response<RegisterDeviceResponse>
}