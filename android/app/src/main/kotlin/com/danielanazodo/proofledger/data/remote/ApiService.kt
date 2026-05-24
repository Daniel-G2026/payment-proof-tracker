package com.danielanazodo.proofledger.data.remote

import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    @GET("businesses/{businessId}/transactions")
    suspend fun listTransactions(
        @Path("businessId") businessId: String,
        @Query("direction") direction: String? = null,
        @Query("search") search: String? = null,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
    ): List<TransactionDto>

    @POST("businesses/{businessId}/transactions")
    suspend fun createTransaction(
        @Path("businessId") businessId: String,
        @Body body: TransactionCreateDto,
    ): TransactionDto

    @GET("businesses/{businessId}/transactions/{transactionId}")
    suspend fun getTransaction(
        @Path("businessId") businessId: String,
        @Path("transactionId") transactionId: String,
    ): TransactionDto

    @PATCH("businesses/{businessId}/transactions/{transactionId}")
    suspend fun updateTransaction(
        @Path("businessId") businessId: String,
        @Path("transactionId") transactionId: String,
        @Body body: TransactionUpdateDto,
    ): TransactionDto

    @DELETE("businesses/{businessId}/transactions/{transactionId}")
    suspend fun deleteTransaction(
        @Path("businessId") businessId: String,
        @Path("transactionId") transactionId: String,
    )
}
