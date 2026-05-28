package com.danielanazodo.proofledger.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.local.dao.LocalScreenshotDao
import com.danielanazodo.proofledger.data.local.entity.LocalCounterpartyEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScreenshotEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.ocr.DraftState
import com.danielanazodo.proofledger.data.ocr.OcrDraft
import com.danielanazodo.proofledger.data.ocr.OcrDraftParser
import com.danielanazodo.proofledger.data.ocr.OcrService
import com.danielanazodo.proofledger.data.ocr.ServerDraftPoller
import com.danielanazodo.proofledger.data.ocr.toOcrDraft
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.proof.SharedProofIntakeRepository
import com.danielanazodo.proofledger.data.remote.dto.DraftErrorCode
import com.danielanazodo.proofledger.ui.format.parseNairaToMinor
import com.danielanazodo.proofledger.ui.format.todayIsoDate
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransactionFormUiState(
    val transactionId: String? = null,
    val direction: String = "money_in",
    val amount: String = "",
    val transactionDate: String = todayIsoDate(),
    val counterpartyId: String? = null,
    val counterpartyQuery: String = "",
    val suggestions: List<LocalCounterpartyEntity> = emptyList(),
    val addingNewCounterparty: Boolean = false,
    val newCounterpartyType: String = defaultCounterpartyType(direction),
    val counterpartyName: String = "",
    val category: String = "",
    val note: String = "",
    val bankName: String = "",
    val referenceNumber: String = "",
    val proofUri: String? = null,
    val proofOriginalSizeBytes: Long? = null,
    /** Raw OCR text captured from the currently-attached proof. Persisted onto
     *  the proof row at save time so future server OCR can compare. */
    val proofOcrText: String? = null,
    /** True after the OCR parser pre-filled at least one field on this form
     *  session. Drives the "Draft from screenshot — please review" banner.
     *  Reset when proof is removed/replaced. */
    val ocrDraftApplied: Boolean = false,
    /** True while OCR is running against a freshly-attached proof. */
    val ocrRunning: Boolean = false,
    /** Short, user-facing error from the most recent server-extraction
     *  attempt. Null when extraction succeeded, is still running, or hasn't
     *  been attempted. Cleared when proof is replaced/removed. */
    val serverDraftError: String? = null,
    /** Names of form fields most recently populated by the OCR parser
     *  (not by the user). When the user replaces the proof, the new OCR
     *  results are allowed to *overwrite* these fields — but a field the
     *  user has edited by hand will never be clobbered. */
    val ocrFilledFields: Set<String> = emptySet(),
    /** Id of the proof row already attached to this transaction when the form
     *  opened. Used so we can delete the old proof when the user replaces it
     *  and avoid creating a duplicate row on every save. */
    val existingProofId: String? = null,
    /** True once the user has touched the proof field on this form session
     *  (picked, replaced, or removed). Until then, save() leaves the existing
     *  proof alone. */
    val proofDirty: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    private val businessRepository: BusinessRepository,
    private val transactionRepository: TransactionRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val proofRepository: ProofRepository,
    private val sharedProofIntakeRepository: SharedProofIntakeRepository,
    private val ocrService: OcrService,
    private val ocrDraftParser: OcrDraftParser,
    private val serverDraftPoller: ServerDraftPoller,
    private val screenshotDao: LocalScreenshotDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val transactionId: String? = savedStateHandle["transactionId"]
    private val initialDirection: String = savedStateHandle["direction"] ?: "money_in"
    private val initialIntakeToken: String? = savedStateHandle["intakeToken"]

    private val _state = MutableStateFlow(
        TransactionFormUiState(transactionId = transactionId, direction = initialDirection)
    )
    val state: StateFlow<TransactionFormUiState> = _state.asStateFlow()
    private var businessId: String? = null
    private var suggestionsJob: Job? = null
    private var activeIntakeToken: String? = null

    init {
        viewModelScope.launch {
            businessId = businessRepository.getSelectedBusinessId()
        }
        initialIntakeToken?.let { token ->
            val item = sharedProofIntakeRepository.get(token)
            if (item == null) {
                _state.update { it.copy(error = "Shared proof is no longer available.") }
            } else {
                activeIntakeToken = token
                _state.update {
                    it.copy(
                        proofUri = item.localUri,
                        proofOriginalSizeBytes = item.sizeBytes,
                        // Share-sheet intake is always a fresh proof to attach.
                        proofDirty = true,
                    )
                }
                runOcrInBackground(Uri.parse(item.localUri))
            }
        }
        if (transactionId != null) {
            // Seed the form once from the local row, then stop collecting.
            // Continuing to collect would let a background dashboard refresh
            // (or any other write to this row) overwrite the user's in-progress
            // edits while they're typing.
            viewModelScope.launch {
                val transaction = transactionRepository.observeTransaction(transactionId)
                    .filterNotNull()
                    .firstOrNull()
                if (transaction != null) {
                    val existingProof = proofRepository
                        .observeProofForTransaction(transactionId)
                        .firstOrNull()
                    _state.update {
                        it.copy(
                            direction = transaction.direction,
                            amount = formatAmountForInput(transaction.amountMinor),
                            transactionDate = transaction.transactionDate,
                            counterpartyId = transaction.counterpartyId,
                            counterpartyQuery = transaction.counterpartyName.orEmpty(),
                            counterpartyName = transaction.counterpartyName.orEmpty(),
                            category = transaction.category.orEmpty(),
                            note = transaction.note.orEmpty(),
                            bankName = transaction.bankName.orEmpty(),
                            referenceNumber = transaction.referenceNumber.orEmpty(),
                            // Surface the existing proof in the UI without
                            // marking dirty, so leaving it untouched on save
                            // doesn't re-attach a duplicate.
                            proofUri = existingProof?.localOriginalPath,
                            proofOriginalSizeBytes = existingProof?.originalFileSizeBytes,
                            existingProofId = existingProof?.id,
                            proofDirty = false,
                        )
                    }
                    // Phase 8: if this proof already lives on the server and
                    // the extraction draft hasn't yet been merged into the
                    // form, try to fetch it now. Best-effort — failures stay
                    // silent and never block editing.
                    existingProof?.let { tryServerDraftMergeFor(it) }
                }
            }
        }
    }

    fun onDirectionChange(value: String) = _state.update {
        val previousDefault = defaultCounterpartyType(it.direction)
        it.copy(
            direction = value,
            newCounterpartyType = if (it.newCounterpartyType == previousDefault) {
                defaultCounterpartyType(value)
            } else {
                it.newCounterpartyType
            },
            saved = false,
        )
    }
    fun onAmountChange(value: String) = _state.update {
        it.copy(amount = value, error = null, saved = false, ocrFilledFields = it.ocrFilledFields - FIELD_AMOUNT)
    }
    fun onDateChange(value: String) = _state.update {
        it.copy(transactionDate = value, saved = false, ocrFilledFields = it.ocrFilledFields - FIELD_DATE)
    }
    fun onCounterpartyQueryChange(value: String) {
        _state.update {
            it.copy(
                counterpartyId = null,
                counterpartyQuery = value,
                counterpartyName = value,
                suggestions = emptyList(),
                addingNewCounterparty = false,
                saved = false,
            )
        }
        observeSuggestions(value)
    }

    fun onCounterpartySelect(counterparty: LocalCounterpartyEntity) {
        suggestionsJob?.cancel()
        _state.update {
            it.copy(
                counterpartyId = counterparty.id,
                counterpartyQuery = counterparty.name,
                counterpartyName = counterparty.name,
                suggestions = emptyList(),
                addingNewCounterparty = false,
                newCounterpartyType = counterparty.type,
                saved = false,
            )
        }
    }

    fun onAddNewCounterparty() {
        suggestionsJob?.cancel()
        _state.update {
            it.copy(
                addingNewCounterparty = true,
                suggestions = emptyList(),
                newCounterpartyType = defaultCounterpartyType(it.direction),
                saved = false,
            )
        }
    }

    fun onNewCounterpartyTypeChange(value: String) = _state.update {
        it.copy(newCounterpartyType = value, saved = false)
    }
    fun onCategoryChange(value: String) = _state.update { it.copy(category = value, saved = false) }
    fun onNoteChange(value: String) = _state.update { it.copy(note = value, saved = false) }
    fun onBankNameChange(value: String) = _state.update {
        it.copy(bankName = value, saved = false, ocrFilledFields = it.ocrFilledFields - FIELD_BANK)
    }
    fun onReferenceNumberChange(value: String) = _state.update {
        it.copy(referenceNumber = value, saved = false, ocrFilledFields = it.ocrFilledFields - FIELD_REFERENCE)
    }
    fun onProofSelected(uri: String, sizeBytes: Long?) {
        clearActiveIntake()
        _state.update { current ->
            // When the user replaces the proof, clear any field values that
            // came from the previous proof's OCR. Otherwise a wrong reading
            // would stick even after they swap in a clearer image. Fields
            // the user has typed (i.e. not in ocrFilledFields) are kept.
            current.copy(
                proofUri = uri,
                proofOriginalSizeBytes = sizeBytes,
                proofDirty = true,
                proofOcrText = null,
                ocrDraftApplied = false,
                amount = if (FIELD_AMOUNT in current.ocrFilledFields) "" else current.amount,
                transactionDate = if (FIELD_DATE in current.ocrFilledFields) todayIsoDate() else current.transactionDate,
                referenceNumber = if (FIELD_REFERENCE in current.ocrFilledFields) "" else current.referenceNumber,
                bankName = if (FIELD_BANK in current.ocrFilledFields) "" else current.bankName,
                ocrFilledFields = emptySet(),
                serverDraftError = null,
                error = null,
                saved = false,
            )
        }
        runOcrInBackground(Uri.parse(uri))
    }
    fun onProofRemoved() {
        clearActiveIntake()
        _state.update { current ->
            current.copy(
                proofUri = null,
                proofOriginalSizeBytes = null,
                proofDirty = true,
                proofOcrText = null,
                ocrDraftApplied = false,
                ocrRunning = false,
                amount = if (FIELD_AMOUNT in current.ocrFilledFields) "" else current.amount,
                transactionDate = if (FIELD_DATE in current.ocrFilledFields) todayIsoDate() else current.transactionDate,
                referenceNumber = if (FIELD_REFERENCE in current.ocrFilledFields) "" else current.referenceNumber,
                bankName = if (FIELD_BANK in current.ocrFilledFields) "" else current.bankName,
                ocrFilledFields = emptySet(),
                serverDraftError = null,
                saved = false,
            )
        }
    }

    fun discardIntakeProof() {
        clearActiveIntake()
    }

    fun save() {
        val current = _state.value
        val amountMinor = parseNairaToMinor(current.amount)
        if (amountMinor == null || amountMinor <= 0) {
            _state.update { it.copy(error = "Enter a valid amount") }
            return
        }
        if (!current.hasResolvedCounterparty()) {
            _state.update {
                it.copy(error = "Choose a saved counterparty or add this as a new one.")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, saved = false) }
            runCatching {
                val transactionId = current.transactionId
                val selectedBusinessId = businessId ?: businessRepository.getSelectedBusinessId()
                    ?: throw IllegalStateException("Select a business first.")
                businessId = selectedBusinessId
                val linkedCounterparty = createInlineCounterpartyIfNeeded(
                    current = current,
                    businessId = selectedBusinessId,
                )
                val counterpartyId = linkedCounterparty?.id ?: current.counterpartyId
                val counterpartyName = linkedCounterparty?.name ?: current.counterpartyName.clean()
                val savedTransaction = if (transactionId == null) {
                    transactionRepository.createTransaction(
                        businessId = selectedBusinessId,
                        amountMinor = amountMinor,
                        direction = current.direction,
                        transactionDate = current.transactionDate,
                        counterpartyId = counterpartyId,
                        counterpartyName = counterpartyName,
                        category = current.category.clean(),
                        note = current.note.clean(),
                        bankName = current.bankName.clean(),
                        referenceNumber = current.referenceNumber.clean(),
                    )
                } else {
                    transactionRepository.updateTransaction(
                        transactionId = transactionId,
                        amountMinor = amountMinor,
                        direction = current.direction,
                        transactionDate = current.transactionDate,
                        counterpartyId = counterpartyId,
                        counterpartyName = counterpartyName,
                        category = current.category.clean(),
                        note = current.note.clean(),
                        bankName = current.bankName.clean(),
                        referenceNumber = current.referenceNumber.clean(),
                    )
                }
                // Only touch proof storage when the user actually changed
                // something on this form session. Otherwise we'd re-attach the
                // same proof every save and accumulate duplicate rows + files.
                if (current.proofDirty) {
                    current.existingProofId?.let { proofRepository.deleteProof(it) }
                    current.proofUri?.let { uri ->
                        proofRepository.attachProof(
                            Uri.parse(uri),
                            savedTransaction,
                            ocrText = current.proofOcrText,
                        )
                    }
                }
                activeIntakeToken?.let { token ->
                    sharedProofIntakeRepository.complete(token)
                    activeIntakeToken = null
                }
            }.onSuccess {
                _state.update { it.copy(loading = false, saved = true) }
            }.onFailure { t ->
                _state.update {
                    it.copy(loading = false, error = t.message ?: "Could not save transaction")
                }
            }
        }
    }

    private fun observeSuggestions(query: String) {
        suggestionsJob?.cancel()
        if (query.trim().isBlank()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionsJob = viewModelScope.launch {
            delay(250)
            val id = businessId ?: businessRepository.getSelectedBusinessId() ?: return@launch
            businessId = id
            counterpartyRepository.observeFiltered(
                businessId = id,
                search = query,
                type = null,
            ).collect { rows ->
                _state.update { it.copy(suggestions = rows.take(5)) }
            }
        }
    }

    private suspend fun createInlineCounterpartyIfNeeded(
        current: TransactionFormUiState,
        businessId: String,
    ): LocalCounterpartyEntity? {
        if (!current.addingNewCounterparty) return null
        val name = current.counterpartyQuery.clean()
            ?: throw IllegalStateException("Enter who paid or who you paid.")
        return counterpartyRepository.createCounterparty(
            businessId = businessId,
            name = name,
            type = current.newCounterpartyType,
            phone = null,
            notes = null,
        )
    }

    private fun String.clean(): String? = trim().ifBlank { null }

    private fun TransactionFormUiState.hasResolvedCounterparty(): Boolean {
        val hasTypedName = counterpartyQuery.clean() != null
        return !hasTypedName || counterpartyId != null || addingNewCounterparty
    }

    /**
     * Called when a proof is freshly attached in the form. In the current
     * architecture the proof isn't uploaded to the server until save() runs,
     * so there's nothing to poll yet — extraction happens once the proof
     * lands on the backend, and the draft is merged on the *next* edit-mode
     * open of this transaction via [tryServerDraftMergeFor].
     *
     * On-device OCR (Phase 7) is gated off via OCR_DRAFTING_ENABLED — see
     * the Phase 7 notes in CLAUDE.md for why. Keeping the OcrService /
     * OcrDraftParser code in tree so the flag can be flipped on for
     * dogfooding without a revert.
     */
    private fun runOcrInBackground(uri: Uri) {
        if (!OCR_DRAFTING_ENABLED) {
            _state.update { it.copy(ocrRunning = false) }
            return
        }
        _state.update { it.copy(ocrRunning = true) }
        viewModelScope.launch {
            val rawText = runCatching { ocrService.extractText(uri) }
                .getOrNull()
            if (rawText.isNullOrBlank()) {
                _state.update { it.copy(ocrRunning = false) }
                return@launch
            }
            val draft = ocrDraftParser.parse(rawText)
            applyDraftIfFieldsBlank(draft)
        }
    }

    /**
     * Phase 8 — if the proof has already been uploaded and the server has
     * (or will soon have) an extraction draft, poll for it and merge any
     * fields the user hasn't yet typed.
     *
     * Skipped when:
     *  - extraction is globally disabled by feature flag
     *  - the proof has no remote id yet (upload still pending)
     *  - the draft was already merged on a previous open (serverDraftStatus = ready)
     *  - the draft previously failed terminally (don't retry every open)
     */
    private fun tryServerDraftMergeFor(proof: LocalScreenshotEntity) {
        if (!SERVER_EXTRACTION_ENABLED) return
        val remoteId = proof.remoteScreenshotId ?: return
        val status = proof.serverDraftStatus
        if (status == DraftStatusLocal.READY || status == DraftStatusLocal.FAILED) {
            // Surface the prior error so the user sees consistent state.
            if (status == DraftStatusLocal.FAILED) {
                _state.update { it.copy(serverDraftError = proof.serverDraftError) }
            }
            return
        }
        _state.update { it.copy(ocrRunning = true, serverDraftError = null) }
        viewModelScope.launch {
            serverDraftPoller.poll(remoteId).collect { state ->
                when (state) {
                    is DraftState.Processing -> {
                        // Keep the spinner up; nothing to do until we get a terminal state.
                    }
                    is DraftState.Ready -> {
                        val draft = state.draft.toOcrDraft()
                        applyDraftIfFieldsBlank(draft)
                        persistDraftStatus(
                            proof = proof,
                            status = DraftStatusLocal.READY,
                            error = null,
                        )
                    }
                    is DraftState.Failed -> {
                        val message = when (state.errorCode) {
                            DraftErrorCode.UNREADABLE -> "Couldn't read this one — type the fields yourself."
                            DraftErrorCode.TIMEOUT -> "Draft is taking longer than expected — try again later."
                            else -> "Couldn't draft from your screenshot."
                        }
                        _state.update {
                            it.copy(ocrRunning = false, serverDraftError = message)
                        }
                        persistDraftStatus(
                            proof = proof,
                            status = DraftStatusLocal.FAILED,
                            error = message,
                        )
                    }
                }
            }
        }
    }

    private suspend fun persistDraftStatus(
        proof: LocalScreenshotEntity,
        status: String,
        error: String?,
    ) {
        // Re-read in case the row changed under us (backup status update etc.)
        // before stamping our own draft fields.
        val latest = screenshotDao.getById(proof.id) ?: return
        screenshotDao.upsert(
            latest.copy(
                serverDraftStatus = status,
                serverDraftError = error,
                serverDraftFetchedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Dismiss the inline failure banner. */
    fun onServerDraftErrorDismissed() {
        _state.update { it.copy(serverDraftError = null) }
    }

    private fun applyDraftIfFieldsBlank(draft: OcrDraft) {
        _state.update { current ->
            // A field is "OCR-writable" when it is either still empty (user
            // hasn't typed) or was last filled by OCR itself. Anything the
            // user has typed is sacred — we never clobber it, even when the
            // user attaches a new proof and a fresh OCR pass returns a
            // different value.
            val amountWritable = current.amount.isBlank() || FIELD_AMOUNT in current.ocrFilledFields
            val nextAmount = if (amountWritable && draft.amountMinor != null) {
                formatAmountForInput(draft.amountMinor)
            } else current.amount

            // The form pre-seeds transactionDate with today's date, so "blank"
            // for the date means "still showing today." OCR may also overwrite
            // a date it set itself on a previous proof.
            val dateWritable = current.transactionDate == todayIsoDate() ||
                FIELD_DATE in current.ocrFilledFields
            val nextDate = if (dateWritable && draft.dateIso != null) {
                draft.dateIso
            } else current.transactionDate

            val referenceWritable = current.referenceNumber.isBlank() ||
                FIELD_REFERENCE in current.ocrFilledFields
            val nextReference = if (referenceWritable && draft.reference != null) {
                draft.reference
            } else current.referenceNumber

            val bankWritable = current.bankName.isBlank() || FIELD_BANK in current.ocrFilledFields
            val nextBank = if (bankWritable && draft.bankName != null) {
                draft.bankName
            } else current.bankName

            // Track which fields the new OCR result actually populated, so
            // a *future* proof replacement can clear them too.
            val nextOcrFields = buildSet {
                if (amountWritable && draft.amountMinor != null) add(FIELD_AMOUNT)
                if (dateWritable && draft.dateIso != null) add(FIELD_DATE)
                if (referenceWritable && draft.reference != null) add(FIELD_REFERENCE)
                if (bankWritable && draft.bankName != null) add(FIELD_BANK)
            }

            val applied = nextOcrFields.isNotEmpty()

            current.copy(
                amount = nextAmount,
                transactionDate = nextDate,
                referenceNumber = nextReference,
                bankName = nextBank,
                proofOcrText = draft.rawText,
                ocrDraftApplied = current.ocrDraftApplied || applied,
                ocrRunning = false,
                ocrFilledFields = nextOcrFields,
            )
        }
    }

    private fun clearActiveIntake() {
        activeIntakeToken?.let { token ->
            sharedProofIntakeRepository.cancel(token)
            activeIntakeToken = null
        }
    }

    private fun formatAmountForInput(amountMinor: Long): String {
        val whole = amountMinor / 100
        val fraction = amountMinor % 100
        return if (fraction == 0L) whole.toString() else "$whole.${fraction.toString().padStart(2, '0')}"
    }
}

private fun defaultCounterpartyType(direction: String): String =
    if (direction == "money_out") "supplier" else "customer"

// Field-name keys for ocrFilledFields. Keeping them as constants avoids
// typos that would silently break the "don't clobber user edits" logic.
private const val FIELD_AMOUNT = "amount"
private const val FIELD_DATE = "date"
private const val FIELD_REFERENCE = "reference"
private const val FIELD_BANK = "bank"

// Master switch for the on-device OCR drafting pass. Off in v1 — see
// runOcrInBackground for rationale. Flip to true to dogfood the heuristic
// parser against real receipts without removing the gate.
private const val OCR_DRAFTING_ENABLED = false

// Phase 8 — master switch for server-side AI extraction. Polling and merge
// are gated on this so we can disable in production within one config change
// if the vision model misbehaves on real receipts.
private const val SERVER_EXTRACTION_ENABLED = true

// Mirrors the backend draft status strings — kept here so the ViewModel
// doesn't need to import the DTO module for two comparisons.
private object DraftStatusLocal {
    const val QUEUED = "queued"
    const val PROCESSING = "processing"
    const val READY = "ready"
    const val FAILED = "failed"
}
