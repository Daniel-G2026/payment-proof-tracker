package com.danielanazodo.proofledger.data.remote

import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

data class HealthResponse(val status: String)

interface ApiService {
    @GET("health")
    suspend fun health(): HealthResponse

    @GET("me")
    suspend fun getMe(): ProfileDto

    @PATCH("me")
    suspend fun updateMe(@Body body: ProfileUpdateDto): ProfileDto

    @GET("businesses")
    suspend fun listBusinesses(): List<BusinessDto>

    @POST("businesses")
    suspend fun createBusiness(@Body body: BusinessCreateDto): BusinessDto

    @GET("businesses/{id}")
    suspend fun getBusiness(@Path("id") id: String): BusinessDto
}
