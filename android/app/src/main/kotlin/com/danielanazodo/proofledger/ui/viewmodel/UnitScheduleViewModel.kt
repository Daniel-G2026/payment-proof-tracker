package com.danielanazodo.proofledger.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielanazodo.proofledger.data.balance.AllocationEngine
import com.danielanazodo.proofledger.data.balance.Installment
import com.danielanazodo.proofledger.data.balance.InstallmentFill
import com.danielanazodo.proofledger.data.balance.Payment
import com.danielanazodo.proofledger.data.local.entity.LocalInstallmentEntity
import com.danielanazodo.proofledger.data.local.entity.LocalScheduleVersionEntity
import com.danielanazodo.proofledger.data.repository.BusinessRepository
import com.danielanazodo.proofledger.data.repository.ScheduleLineInput
import com.danielanazodo.proofledger.data.repository.ScheduleRepository
import com.danielanazodo.proofledger.data.repository.TransactionRepository
import com.danielanazodo.proofledger.data.repository.UnitRepository
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
) : ViewModel() {
    private val unitId: String = checkNotNull(savedStateHandle["unitId"])

    private val _ui = MutableStateFlow(UnitScheduleUiState())
    private var businessId: String? = null

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
            _ui.update { it.copy(unitLabel = unitRepository.getById(unitId)?.label ?: "Unit") }
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
        it.copy(editing = false, draftLines = listOf(ScheduleLineDraft(), ScheduleLineDraft()), error = null)
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
            scheduleRepository.createScheduleVersion(id, unitId, parsed)
            _ui.update {
                it.copy(
                    saving = false,
                    editing = false,
                    draftLines = listOf(ScheduleLineDraft(), ScheduleLineDraft()),
                    error = null,
                )
            }
        }
    }

    private fun parseAmountMinor(raw: String): Long? {
        val cleaned = raw.trim().replace(",", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val major = cleaned.toBigDecimalOrNull() ?: return null
        return major.movePointRight(2).toBigInteger().toLong()
    }

    private fun parseDate(raw: String): LocalDate? = runCatching {
        LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()
}
