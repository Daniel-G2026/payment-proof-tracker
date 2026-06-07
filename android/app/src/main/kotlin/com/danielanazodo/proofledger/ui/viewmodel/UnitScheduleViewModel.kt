package com.danielanazodo.proofledger.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.balance.AllocationEngine
import com.danielanazodo.proofledger.data.balance.Installment
import com.danielanazodo.proofledger.data.balance.InstallmentFill
import com.danielanazodo.proofledger.data.balance.Payment
import com.danielanazodo.proofledger.data.local.entity.LocalInstallmentEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScheduleVersionEntity
import com.danielanazodo.proofledger.data.ocr.ScheduleDraftPoller
import com.danielanazodo.proofledger.data.ocr.ScheduleDraftState
import com.danielanazodo.proofledger.data.proof.ProofRepository
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.CounterpartyRepository
import com.danielanazodo.proofledger.data.repository.ScheduleLineInput
import com.danielanazodo.proofledger.data.repository.ScheduleRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.repository.UnitRepository
import java.util.UUID
import com.danielanazodo.proofledger.ui.format.formatNaira
import com.danielanazodo.proofledger.ui.format.formatNairaInput
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One installment row as the user types it (major-unit amount + ISO date). */
data class ScheduleLineDraft(val amount: String = "", val dueDate: String = "")

data class UnitScheduleUiState(
    val unitLabel: String = "",
    val activeVersion: LocalScheduleVersionEntity? = null,
    val activeInstallments: List<LocalInstallmentEntity> = emptyList(),
    // Computed (never stored) fills over the unit's linked money-in payments.
    val fills: List<InstallmentFill> = emptyList(),
    val arrearsMinor: Long = 0,
    val creditMinor: Long = 0,
    val versionCount: Int = 0,
    // Seed two rows so the form reads as a list of installments, not one field.
    val draftLines: List<ScheduleLineDraft> = listOf(ScheduleLineDraft(), ScheduleLineDraft()),
    // View vs edit: an existing schedule shows read-only until the user taps
    // "Revise schedule". First-time entry (no active version) is always editing.
    val editing: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
    // Phase 13 Cut 6 — offer-letter scan: "Reading offer letter…" banner while
    // the AI draft is in flight, an advisory message on failure/partial read,
    // and the screenshot id carried onto the schedule version on save.
    val offerLetterScanning: Boolean = false,
    val offerLetterError: String? = null,
    val pendingOfferLetterScreenshotId: String? = null,
    // The buyer name the offer letter was extracted for (shown for the user to
    // confirm it's the right unit). mismatch = differs from this unit's buyer.
    val offerLetterBuyerName: String? = null,
    val offerLetterBuyerMismatch: Boolean = false,
    // Phase 13 Cut 6 — after saving an offer-letter schedule onto a unit that has
    // no buyer, offer to add the extracted person as the buyer (advisory, opt-in).
    val addBuyerPromptName: String? = null,
    val addingBuyer: Boolean = false,
    val buyerAddedName: String? = null,
) {
    val showEditor: Boolean get() = activeVersion == null || editing
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class UnitScheduleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val businessRepository: BusinessRepository,
    private val unitRepository: UnitRepository,
    private val scheduleRepository: ScheduleRepository,
    private val transactionRepository: TransactionRepository,
    private val proofRepository: ProofRepository,
    private val counterpartyRepository: CounterpartyRepository,
    private val scheduleDraftPoller: ScheduleDraftPoller,
) : ViewModel() {
    private val unitId: String = checkNotNull(savedStateHandle["unitId"])

    private val _ui = MutableStateFlow(UnitScheduleUiState())
    private var businessId: String? = null
    // The unit's buyer name, resolved once, to sanity-check the offer letter
    // names the same person (advisory — see contract §1).
    private var unitBuyerName: String? = null
    // Whether the unit already has a buyer assigned — gates the post-save
    // "add as buyer" offer (we only offer when the unit has none).
    private var unitHasBuyer: Boolean = false

    private val activeInstallments: StateFlow<List<LocalInstallmentEntity>> =
        scheduleRepository.observeActiveVersion(unitId)
            .flatMapLatest { active ->
                if (active == null) flowOf(emptyList())
                else scheduleRepository.observeInstallments(active.id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state: StateFlow<UnitScheduleUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            businessId = businessRepository.getSelectedBusinessId()
            val unit = unitRepository.getById(unitId)
            _ui.update { it.copy(unitLabel = unit?.label ?: "Unit") }
            unitHasBuyer = unit?.buyerCounterpartyId != null
            unitBuyerName = unit?.buyerCounterpartyId?.let { counterpartyRepository.getById(it)?.name }
            businessId?.let { scheduleRepository.refreshForUnit(it, unitId) }
        }
        viewModelScope.launch {
            scheduleRepository.observeActiveVersion(unitId).collect { active ->
                _ui.update { it.copy(activeVersion = active) }
            }
        }
        viewModelScope.launch {
            scheduleRepository.observeVersionsByUnit(unitId).collect { versions ->
                _ui.update { it.copy(versionCount = versions.size) }
            }
        }
        viewModelScope.launch {
            activeInstallments.collect { rows ->
                _ui.update { it.copy(activeInstallments = rows) }
            }
        }
        // Compute fills on read: pour the unit's linked money-in payments into
        // the active schedule. Nothing persisted — re-derives whenever either
        // the schedule or the payments change.
        viewModelScope.launch {
            combine(
                activeInstallments,
                transactionRepository.observeMoneyInForUnit(unitId),
            ) { installments, payments ->
                AllocationEngine.allocate(
                    installments = installments.map {
                        Installment(it.id, it.sequence, it.amountMinor, LocalDate.parse(it.dueDate))
                    },
                    payments = payments.map {
                        Payment(
                            id = it.id,
                            amountMinor = it.amountMinor,
                            date = LocalDate.parse(it.transactionDate.take(10)),
                            earmarkedInstallmentId = it.earmarkedInstallmentId,
                        )
                    },
                    asOf = LocalDate.now(),
                )
            }.collect { result ->
                _ui.update {
                    it.copy(
                        fills = result.fills,
                        arrearsMinor = result.arrearsMinor,
                        creditMinor = result.creditMinor,
                    )
                }
            }
        }
    }

    /** Enter edit mode to revise an existing schedule. Pre-fills the draft with
     * the current installments so the user edits from the real numbers (a new
     * version is created on save — the old one is preserved). */
    fun onRevise() = _ui.update { s ->
        val seed = s.activeInstallments
            .sortedBy { it.sequence }
            .map { ScheduleLineDraft(amount = formatNairaInput(it.amountMinor), dueDate = it.dueDate) }
            .ifEmpty { listOf(ScheduleLineDraft(), ScheduleLineDraft()) }
        s.copy(editing = true, draftLines = seed, error = null)
    }

    fun onCancelEdit() = _ui.update {
        it.copy(
            editing = false,
            draftLines = listOf(ScheduleLineDraft(), ScheduleLineDraft()),
            error = null,
            offerLetterError = null,
            pendingOfferLetterScreenshotId = null,
            offerLetterBuyerName = null,
            offerLetterBuyerMismatch = false,
        )
    }

    fun onDismissOfferLetterError() = _ui.update { it.copy(offerLetterError = null) }

    /**
     * Scan an offer letter: upload the image, poll the AI draft, and on a ready
     * draft pre-fill the editor with the extracted installments. The editor
     * opens either way so the user can review/edit before confirming; a failed
     * or partial read just shows a message and leaves the rows for manual entry.
     * The screenshot is linked onto the schedule version on save.
     */
    fun onScanOfferLetter(uri: String) {
        _ui.update { it.copy(offerLetterScanning = true, offerLetterError = null) }
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId()
            if (id == null) {
                _ui.update { it.copy(offerLetterScanning = false, offerLetterError = "No business selected.") }
                return@launch
            }
            businessId = id
            val upload = runCatching {
                proofRepository.uploadOfferLetter(Uri.parse(uri), id, UUID.randomUUID().toString())
            }.getOrNull()
            if (upload == null) {
                _ui.update {
                    it.copy(
                        offerLetterScanning = false,
                        offerLetterError = OFFLINE_MESSAGE,
                    )
                }
                return@launch
            }
            val screenshotId = upload.screenshot.id
            scheduleDraftPoller.poll(screenshotId).collect { st ->
                when (st) {
                    ScheduleDraftState.Processing -> { /* banner already shown */ }
                    is ScheduleDraftState.Ready -> {
                        val rows = st.draft.installments?.sortedBy { it.sequence }
                        val lines = rows
                            ?.map { ScheduleLineDraft(amount = formatNairaInput(it.amountMinor), dueDate = it.dueDate) }
                            ?.takeIf { it.isNotEmpty() }
                        val buyer = st.draft.buyerName?.trim()?.ifBlank { null }
                        _ui.update { s ->
                            s.copy(
                                offerLetterScanning = false,
                                editing = true,
                                pendingOfferLetterScreenshotId = screenshotId,
                                draftLines = lines ?: s.draftLines,
                                offerLetterError = reviewNote(st.draft.totalMinor, rows, lines != null),
                                offerLetterBuyerName = buyer,
                                offerLetterBuyerMismatch = buyerMismatch(buyer, unitBuyerName),
                            )
                        }
                    }
                    is ScheduleDraftState.Failed -> {
                        _ui.update {
                            it.copy(
                                offerLetterScanning = false,
                                editing = true,
                                pendingOfferLetterScreenshotId = screenshotId,
                                offerLetterError = FAILED_MESSAGE,
                            )
                        }
                    }
                }
            }
        }
    }

    fun onAddLine() = _ui.update { it.copy(draftLines = it.draftLines + ScheduleLineDraft()) }

    fun onRemoveLine(index: Int) = _ui.update {
        if (it.draftLines.size <= 1) it
        else it.copy(draftLines = it.draftLines.filterIndexed { i, _ -> i != index })
    }

    fun onAmountChange(index: Int, value: String) = _ui.update { s ->
        // Group digits as the user types (₦1,250,000), same as the money forms.
        val formatted = formatNairaInput(value)
        s.copy(draftLines = s.draftLines.mapIndexed { i, l -> if (i == index) l.copy(amount = formatted) else l })
    }

    fun onDueDateChange(index: Int, value: String) = _ui.update { s ->
        s.copy(draftLines = s.draftLines.mapIndexed { i, l -> if (i == index) l.copy(dueDate = value) else l })
    }

    fun onSave() {
        val lines = _ui.value.draftLines
        val parsed = mutableListOf<ScheduleLineInput>()
        lines.forEachIndexed { index, draft ->
            val minor = parseAmountMinor(draft.amount)
            if (minor == null || minor <= 0) {
                _ui.update { it.copy(error = "Line ${index + 1}: enter an amount greater than zero.") }
                return
            }
            val date = parseDate(draft.dueDate)
            if (date == null) {
                _ui.update { it.copy(error = "Line ${index + 1}: enter a due date as YYYY-MM-DD.") }
                return
            }
            parsed.add(ScheduleLineInput(sequence = index + 1, amountMinor = minor, dueDate = date))
        }
        if (parsed.isEmpty()) {
            _ui.update { it.copy(error = "Add at least one installment.") }
            return
        }
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId()
            if (id == null) {
                _ui.update { it.copy(saving = false, error = "No business selected.") }
                return@launch
            }
            businessId = id
            val screenshotId = _ui.value.pendingOfferLetterScreenshotId
            val extractedBuyer = _ui.value.offerLetterBuyerName
            scheduleRepository.createScheduleVersion(
                businessId = id,
                unitId = unitId,
                lines = parsed,
                source = if (screenshotId != null) "offer_letter" else "manual",
                offerLetterScreenshotId = screenshotId,
            )
            // Offer to add the extracted buyer only after a successful offer-letter
            // save, and only when the unit has no buyer yet (contract §1: never
            // auto-assign; the user opts in).
            val offerBuyer =
                if (screenshotId != null && extractedBuyer != null && !unitHasBuyer) extractedBuyer
                else null
            _ui.update {
                it.copy(
                    saving = false,
                    editing = false,
                    draftLines = listOf(ScheduleLineDraft(), ScheduleLineDraft()),
                    error = null,
                    offerLetterError = null,
                    pendingOfferLetterScreenshotId = null,
                    offerLetterBuyerName = null,
                    offerLetterBuyerMismatch = false,
                    addBuyerPromptName = offerBuyer,
                    buyerAddedName = null,
                )
            }
        }
    }

    /** Create the offer-letter's named buyer as a counterparty and assign them to
     * this unit, in one step (mirrors the project screen's create-and-assign).
     * Only reachable from the post-save prompt, so the unit had no buyer. */
    fun onAddOfferLetterBuyer() {
        val name = _ui.value.addBuyerPromptName?.trim()?.ifBlank { null } ?: return
        _ui.update { it.copy(addingBuyer = true) }
        viewModelScope.launch {
            val id = businessId ?: businessRepository.getSelectedBusinessId()
            if (id == null) {
                _ui.update { it.copy(addingBuyer = false, error = "No business selected.") }
                return@launch
            }
            businessId = id
            val result = runCatching {
                val created = counterpartyRepository.createCounterparty(
                    businessId = id,
                    name = name,
                    type = "customer",
                    phone = null,
                    notes = null,
                )
                unitRepository.updateUnit(unitId = unitId, buyerCounterpartyId = created.id)
                created
            }
            result.fold(
                onSuccess = { created ->
                    unitHasBuyer = true
                    unitBuyerName = created.name
                    _ui.update {
                        it.copy(addingBuyer = false, addBuyerPromptName = null, buyerAddedName = name)
                    }
                },
                onFailure = {
                    _ui.update {
                        it.copy(
                            addingBuyer = false,
                            error = "Couldn't add the buyer. You can assign them from the house instead.",
                        )
                    }
                },
            )
        }
    }

    fun onDismissAddBuyer() = _ui.update { it.copy(addBuyerPromptName = null) }

    private fun parseAmountMinor(raw: String): Long? {
        val cleaned = raw.trim().replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val major = cleaned.toBigDecimalOrNull() ?: return null
        return major.movePointRight(2).toBigInteger().toLong()
    }

    private fun parseDate(raw: String): LocalDate? = runCatching {
        LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

    /**
     * Advisory message shown after an AI draft lands (contract §5.4). Priority:
     * rows unreadable → manual-entry prompt; otherwise, if the extracted total
     * doesn't equal the sum of the rows, a "doesn't add up" warning (never
     * auto-corrected). Null when everything reconciles.
     */
    private fun reviewNote(
        totalMinor: Long?,
        rows: List<com.danielanazodo.proofledger.data.remote.dto.ScheduleDraftInstallmentDto>?,
        rowsUsable: Boolean,
    ): String? {
        if (!rowsUsable) return ROWS_UNREADABLE_MESSAGE
        val sum = rows?.sumOf { it.amountMinor } ?: return null
        if (totalMinor != null && totalMinor != sum) {
            return "Heads up: the letter's total (${formatNaira(totalMinor)}) doesn't match the " +
                "installments (${formatNaira(sum)}). Check the amounts before saving."
        }
        return null
    }

    /**
     * Advisory buyer check (contract §1): true when the offer letter clearly
     * names a different person than this unit's buyer. Lenient — a missing name
     * on either side, or one name containing the other (e.g. "Ada Obi" vs
     * "Mrs Ada Obi"), is NOT a mismatch, to avoid false alarms.
     */
    private fun buyerMismatch(extracted: String?, unitBuyer: String?): Boolean {
        val a = normalizeName(extracted) ?: return false
        val b = normalizeName(unitBuyer) ?: return false
        return a != b && !a.contains(b) && !b.contains(a)
    }

    private fun normalizeName(value: String?): String? =
        value?.lowercase()?.replace(Regex("[^a-z0-9]+"), " ")?.trim()?.ifBlank { null }

    private companion object {
        const val OFFLINE_MESSAGE =
            "Couldn't upload the offer letter. Check your connection, or enter the schedule yourself."
        const val FAILED_MESSAGE =
            "Couldn't read the offer letter — enter the schedule yourself."
        const val ROWS_UNREADABLE_MESSAGE =
            "Couldn't read the installment rows — enter them yourself. Other details were filled where possible."
    }
}
