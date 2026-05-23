package com.danielanazodo.proofledger.data.remote

import retrofit2.http.GET

data class HealthResponse(val status: String)

interface ApiService {
    @GET("health")
    suspend fun health(): HealthResponse
}
