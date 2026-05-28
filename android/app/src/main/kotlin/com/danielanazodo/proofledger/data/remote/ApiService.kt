package com.danielanazodo.proofledger.data.remote

import com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto
import com.danielanazodo.proofledger.data.remote.dto.BusinessDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyCreateDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartySummaryDto
import com.danielanazodo.proofledger.data.remote.dto.CounterpartyUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileDto
import com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto
import com.danielanazodo.proofledger.data.remote.dto.ScreenshotDto
import com.danielanazodo.proofledger.data.remote.dto.SignedScreenshotUrlDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftConfirmDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDraftDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionDto
import com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.MultipartBody
import okhttp3.RequestBody

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

    @GET("businesses/{businessId}/counterparties")
    suspend fun listCounterparties(
        @Path("businessId") businessId: String,
        @Query("search") search: String? = null,
        @Query("type") type: String? = null,
    ): List<CounterpartyDto>

    @POST("businesses/{businessId}/counterparties")
    suspend fun createCounterparty(
        @Path("businessId") businessId: String,
        @Body body: CounterpartyCreateDto,
    ): CounterpartyDto

    @GET("businesses/{businessId}/counterparties/{counterpartyId}")
    suspend fun getCounterparty(
        @Path("businessId") businessId: String,
        @Path("counterpartyId") counterpartyId: String,
    ): CounterpartyDto

    @PATCH("businesses/{businessId}/counterparties/{counterpartyId}")
    suspend fun updateCounterparty(
        @Path("businessId") businessId: String,
        @Path("counterpartyId") counterpartyId: String,
        @Body body: CounterpartyUpdateDto,
    ): CounterpartyDto

    @DELETE("businesses/{businessId}/counterparties/{counterpartyId}")
    suspend fun deleteCounterparty(
        @Path("businessId") businessId: String,
        @Path("counterpartyId") counterpartyId: String,
    )

    @GET("businesses/{businessId}/counterparties/{counterpartyId}/summary")
    suspend fun getCounterpartySummary(
        @Path("businessId") businessId: String,
        @Path("counterpartyId") counterpartyId: String,
    ): CounterpartySummaryDto

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

    @Multipart
    @POST("businesses/{businessId}/screenshots")
    suspend fun uploadScreenshot(
        @Path("businessId") businessId: String,
        @Part("id") id: RequestBody,
        @Part("transaction_id") transactionId: RequestBody,
        @Part("original_file_size") originalFileSize: RequestBody,
        @Part("width") width: RequestBody,
        @Part("height") height: RequestBody,
        @Part file: MultipartBody.Part,
    ): ScreenshotDto

    /**
     * Phase 8.1 — orphan upload, no transaction_id. Same backend route as
     * uploadScreenshot but the transaction_id part is omitted, signaling
     * "this is a draft proof; link it to a transaction later via the
     * transaction-create endpoint's screenshot_id field."
     *
     * Two methods (rather than a nullable transaction_id RequestBody) keeps
     * the Retrofit multipart contract simple — omitting a @Part by passing
     * null would require all parts to be nullable, which weakens the
     * existing transaction-linked path.
     */
    @Multipart
    @POST("businesses/{businessId}/screenshots")
    suspend fun uploadOrphanScreenshot(
        @Path("businessId") businessId: String,
        @Part("id") id: RequestBody,
        @Part("original_file_size") originalFileSize: RequestBody,
        @Part("width") width: RequestBody,
        @Part("height") height: RequestBody,
        @Part file: MultipartBody.Part,
    ): ScreenshotDto

    /**
     * Phase 8.1 — delete a screenshot. Used to clean up an orphan when the
     * user replaces or removes the proof mid-form. Idempotent on the server
     * side (404 swallowed by the caller).
     */
    @DELETE("screenshots/{screenshotId}")
    suspend fun deleteScreenshot(
        @Path("screenshotId") screenshotId: String,
    )

    @GET("screenshots/{screenshotId}/signed-url")
    suspend fun getScreenshotSignedUrl(
        @Path("screenshotId") screenshotId: String,
    ): SignedScreenshotUrlDto

    /**
     * Phase 8 — poll the server-side extraction result for a screenshot.
     * Returns 404 (Retrofit throws HttpException) until the worker has
     * created the draft row, so callers must treat 404 as "keep polling."
     */
    @GET("screenshots/{screenshotId}/draft")
    suspend fun getScreenshotDraft(
        @Path("screenshotId") screenshotId: String,
    ): TransactionDraftDto

    /**
     * Phase 8 — telemetry. Tells the server which final transaction the
     * user actually saved from this draft, so we can later measure draft
     * acceptance rate. Idempotent.
     */
    @POST("transaction-drafts/{draftId}/confirm")
    suspend fun confirmDraft(
        @Path("draftId") draftId: String,
        @Body body: TransactionDraftConfirmDto,
    ): TransactionDraftDto
}
