package com.local.spacedcards.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.local.spacedcards.R
import com.local.spacedcards.GenerationPollingService
import com.local.spacedcards.core.Grade
import com.local.spacedcards.core.QuizAssembler
import com.local.spacedcards.core.QuizQuestion
import com.local.spacedcards.data.RaccoltaRef
import com.local.spacedcards.data.RaccoltaSummary
import com.local.spacedcards.data.ReviewCard
import com.local.spacedcards.data.SezioneInfo
import com.local.spacedcards.data.StudyImportPreview
import com.local.spacedcards.data.StudyRepository
import com.local.spacedcards.data.importer.ApkgImportException
import com.local.spacedcards.data.lan.DEFAULT_LAN_PORT
import com.local.spacedcards.data.lan.LanCard
import com.local.spacedcards.data.lan.LanClient
import com.local.spacedcards.data.lan.LanDiscovery
import com.local.spacedcards.data.lan.DiscoveredPc
import com.local.spacedcards.data.lan.LanError
import com.local.spacedcards.data.lan.LanSettingsStore
import com.local.spacedcards.data.lan.GenerationJob
import com.local.spacedcards.data.lan.GenerationJobStore
import com.local.spacedcards.data.lan.PcInfo
import com.local.spacedcards.data.quiz.ImportedQuizPackPayload
import com.local.spacedcards.data.quiz.QzdImportException
import com.local.spacedcards.data.quiz.QuizPackSummary
import com.local.spacedcards.data.quiz.QuizRepository
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UiMessage {
    data class Resource(
        @StringRes val resId: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : UiMessage

    data class Dynamic(val value: String) : UiMessage
}

fun UiMessage.resolve(context: Context): String = when (this) {
    is UiMessage.Resource -> context.getString(resId, *formatArgs.toTypedArray())
    is UiMessage.Dynamic -> value
}

sealed interface Screen {
    data object Collections : Screen
    data class CollectionDetail(val raccoltaUid: String, val raccoltaName: String) : Screen
    data class Study(val raccoltaUid: String, val raccoltaName: String) : Screen
    data class GenerateQuiz(val raccoltaUid: String, val raccoltaName: String) : Screen
    data class QuizSession(
        val raccoltaUid: String,
        val raccoltaName: String,
        val packUid: String,
        val packName: String,
    ) : Screen

    /** Scelta dei pacchetti da unire in un unico quiz. */
    data object CombinedQuizSetup : Screen

    /** Quiz che attinge a piu' pacchetti insieme. */
    data class CombinedQuizSession(val title: String) : Screen
}

data class StudySessionUiState(
    val raccoltaUid: String? = null,
    val raccoltaName: String = "",
    val currentCard: ReviewCard? = null,
    val totalCards: Int = 0,
    val knownCount: Int = 0,
    val isLoading: Boolean = false,
    val isDeckComplete: Boolean = false,
)

data class QuizResultUiState(
    val correct: Int,
    val total: Int,
)

data class PendingImportUiState(
    val uri: Uri,
    val displayName: String,
    val preview: StudyImportPreview,
    val preferredExistingRaccoltaUid: String?,
)

data class SectionsDialogUiState(
    val raccoltaUid: String,
    val raccoltaName: String,
    val sections: List<SezioneInfo>,
)

data class RemoveSectionUiState(
    val raccoltaUid: String,
    val raccoltaName: String,
    val section: SezioneInfo,
)

enum class GenerateQuizAction {
    START,
    RESUME_WAIT,
}

data class GenerateQuizUiState(
    val raccoltaUid: String? = null,
    val raccoltaName: String = "",
    val host: String = "",
    val portText: String = DEFAULT_LAN_PORT.toString(),
    val code: String = "",
    val discoveredPcs: List<DiscoveredPc> = emptyList(),
    val isDiscovering: Boolean = false,
    val hasDiscoveryAttempted: Boolean = false,
    val verifiedPc: PcInfo? = null,
    val bannerMessage: UiMessage? = null,
    val isVerifying: Boolean = false,
    val isSubmitting: Boolean = false,
    val isWaiting: Boolean = false,
    val isImporting: Boolean = false,
    val currentJobId: String? = null,
    val progress: Float = 0f,
    val stage: String? = null,
    val statusMessage: String? = null,
    val uidMismatchCount: Int = 0,
    val action: GenerateQuizAction = GenerateQuizAction.START,
)

data class MainUiState(
    val screen: Screen = Screen.Collections,
    val raccolte: List<RaccoltaSummary> = emptyList(),
    val quizPacks: List<QuizPackSummary> = emptyList(),
    val combinedQuizSelection: Set<String> = emptySet(),
    val study: StudySessionUiState = StudySessionUiState(),
    val generateQuiz: GenerateQuizUiState = GenerateQuizUiState(),
    val isCollectionsLoading: Boolean = true,
    val isImporting: Boolean = false,
    val isRemovingSection: Boolean = false,
    val isQuizLoading: Boolean = false,
    val highlightedRaccoltaUid: String? = null,
    val pendingImport: PendingImportUiState? = null,
    val sectionsDialog: SectionsDialogUiState? = null,
    val pendingRemoval: RemoveSectionUiState? = null,
    val quizQuestions: List<QuizQuestion> = emptyList(),
    val quizResult: QuizResultUiState? = null,
    val message: UiMessage? = null,
)

class MainViewModel(
    private val repository: StudyRepository,
    private val quizRepository: QuizRepository,
    applicationContext: Context,
) : ViewModel() {
    private val appContext = applicationContext.applicationContext
    private val lanClient = LanClient()
    private val lanDiscovery = LanDiscovery()
    private val lanSettingsStore = LanSettingsStore(this.appContext)
    private val generationJobStore = GenerationJobStore(this.appContext)
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState = _uiState.asStateFlow()
    private val lastShownCardUidByRaccolta = mutableMapOf<String, String?>()
    private var bakePollingJob: Job? = null
    private var discoveryJob: Job? = null

    init {
        refreshCollections(destination = Screen.Collections)
        resumePersistedGeneration()
    }

    override fun onCleared() {
        cancelDiscovery()
        bakePollingJob?.cancel()
        super.onCleared()
    }

    fun onImportRequested(uri: Uri, displayName: String?) {
        if (displayName == null || !displayName.lowercase().endsWith(".apkg")) {
            _uiState.update { it.copy(message = UiMessage.Resource(R.string.import_wrong_extension)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, message = null) }
            runCatching {
                repository.previewApkg(uri)
            }.onSuccess { preview ->
                val state = _uiState.value
                _uiState.value = state.copy(
                    isImporting = false,
                    pendingImport = PendingImportUiState(
                        uri = uri,
                        displayName = displayName,
                        preview = preview,
                        preferredExistingRaccoltaUid = preferredImportRaccolta(state.raccolte),
                    ),
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        message = error.asUiMessage(),
                    )
                }
            }
        }
    }

    fun onQzdImportRequested(uri: Uri, displayName: String?) {
        if (displayName == null || !displayName.lowercase().endsWith(".qzd")) {
            _uiState.update { it.copy(message = UiMessage.Resource(R.string.quiz_import_wrong_extension)) }
            return
        }

        val preferredRaccoltaUid = currentCollectionRef()?.uid
        viewModelScope.launch {
            _uiState.update { it.copy(isQuizLoading = true, message = null) }
            runCatching {
                importQuizPack(
                    uri = uri,
                    preferredRaccoltaUid = preferredRaccoltaUid,
                    forcedCollection = null,
                )
            }.onSuccess { outcome ->
                refreshCollections(
                    highlightedRaccoltaUid = outcome.targetCollection.uid,
                    message = UiMessage.Resource(
                        resId = if (outcome.createdCollection) {
                            R.string.quiz_import_created_collection_message
                        } else {
                            R.string.quiz_import_linked_message
                        },
                        formatArgs = listOf(outcome.packName, outcome.targetCollection.name),
                    ),
                    destination = Screen.CollectionDetail(
                        raccoltaUid = outcome.targetCollection.uid,
                        raccoltaName = outcome.targetCollection.name,
                    ),
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isQuizLoading = false,
                        message = error.asUiMessage(),
                    )
                }
            }
        }
    }

    fun confirmImport(
        sectionName: String,
        existingRaccoltaUid: String?,
        newRaccoltaName: String?,
    ) {
        val draft = _uiState.value.pendingImport ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, message = null) }
            runCatching {
                val targetRaccolta = if (existingRaccoltaUid == null) {
                    val normalizedName = newRaccoltaName.orEmpty().trim()
                    require(normalizedName.isNotEmpty()) { "Collection name cannot be blank." }
                    val uid = repository.createRaccolta(normalizedName)
                    uid to normalizedName
                } else {
                    val name = _uiState.value.raccolte.firstOrNull { it.uid == existingRaccoltaUid }?.name
                        ?: error("Unknown collection: $existingRaccoltaUid")
                    existingRaccoltaUid to name
                }
                val summary = repository.importApkg(
                    uri = draft.uri,
                    raccoltaUid = targetRaccolta.first,
                    sezioneName = sectionName,
                )
                Triple(targetRaccolta.first, targetRaccolta.second, summary)
            }.onSuccess { (raccoltaUid, _, summary) ->
                lastShownCardUidByRaccolta[raccoltaUid] = null
                refreshCollections(
                    highlightedRaccoltaUid = raccoltaUid,
                    message = UiMessage.Resource(
                        R.string.import_summary,
                        listOf(summary.notesRead, summary.imported),
                    ),
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        message = error.asUiMessage(),
                    )
                }
            }
        }
    }

    fun dismissImportDialog() {
        _uiState.update {
            it.copy(
                pendingImport = null,
                isImporting = false,
            )
        }
    }

    fun openCollectionDetail(raccoltaUid: String, raccoltaName: String) {
        _uiState.update {
            it.copy(
                screen = Screen.CollectionDetail(raccoltaUid, raccoltaName),
                highlightedRaccoltaUid = raccoltaUid,
                pendingImport = null,
                sectionsDialog = null,
                pendingRemoval = null,
                message = null,
            )
        }
    }

    fun openStudy(raccoltaUid: String, raccoltaName: String) {
        _uiState.update {
            it.copy(
                screen = Screen.Study(raccoltaUid, raccoltaName),
                study = StudySessionUiState(
                    raccoltaUid = raccoltaUid,
                    raccoltaName = raccoltaName,
                    isLoading = true,
                ),
                highlightedRaccoltaUid = raccoltaUid,
                message = null,
            )
        }
        refreshStudy(raccoltaUid, raccoltaName)
    }

    fun openCombinedQuizSetup() {
        _uiState.update {
            it.copy(
                screen = Screen.CombinedQuizSetup,
                combinedQuizSelection = emptySet(),
                message = null,
            )
        }
    }

    fun toggleCombinedQuizPack(packUid: String) {
        _uiState.update {
            val selezione = it.combinedQuizSelection
            it.copy(
                combinedQuizSelection =
                    if (packUid in selezione) selezione - packUid else selezione + packUid,
            )
        }
    }

    /**
     * Un quiz solo, con le domande di piu' pacchetti mescolate insieme.
     *
     * Unire i pacchetti e' sicuro senza controlli aggiuntivi: ogni card si
     * porta dietro i propri distrattori e le proprie esclusioni, calcolati dal
     * baker dentro il suo mazzo. Le opzioni sbagliate di una domanda restano
     * quindi quelle del suo pacchetto, e non puo' capitare che la risposta
     * giusta di un mazzo compaia fra le sbagliate di un altro.
     */
    fun startCombinedQuiz() {
        val stato = _uiState.value
        val scelti = stato.quizPacks.filter { it.packUid in stato.combinedQuizSelection }
        if (scelti.isEmpty()) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isQuizLoading = true, message = null, quizResult = null) }
            runCatching {
                val cards = scelti.flatMap { quizRepository.loadQuizCards(it.packUid) }
                QuizAssembler.assembleAll(
                    cards = cards,
                    optionCount = 4,
                    random = Random(System.currentTimeMillis().toInt()),
                )
            }.onSuccess { domande ->
                if (domande.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isQuizLoading = false,
                            quizQuestions = emptyList(),
                            message = UiMessage.Resource(R.string.quiz_no_questions_available),
                        )
                    }
                    return@onSuccess
                }
                _uiState.update {
                    it.copy(
                        screen = Screen.CombinedQuizSession(
                            title = titoloCombinato(scelti.size),
                        ),
                        isQuizLoading = false,
                        quizQuestions = domande,
                    )
                }
            }.onFailure { errore ->
                _uiState.update {
                    it.copy(
                        isQuizLoading = false,
                        message = UiMessage.Dynamic(errore.message ?: "errore"),
                    )
                }
            }
        }
    }

    private fun titoloCombinato(quanti: Int): String =
        appContext.getString(R.string.combined_quiz_session_title, quanti)

    fun startQuiz(
        raccoltaUid: String,
        raccoltaName: String,
        pack: QuizPackSummary,
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isQuizLoading = true,
                    highlightedRaccoltaUid = raccoltaUid,
                    message = null,
                    quizResult = null,
                )
            }
            runCatching {
                val cards = quizRepository.loadQuizCards(pack.packUid)
                QuizAssembler.assembleAll(
                    cards = cards,
                    optionCount = 4,
                    random = Random(System.currentTimeMillis().toInt()),
                )
            }.onSuccess { questions ->
                if (questions.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            screen = Screen.CollectionDetail(raccoltaUid, raccoltaName),
                            isQuizLoading = false,
                            quizQuestions = emptyList(),
                            message = UiMessage.Resource(R.string.quiz_no_questions_available),
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            screen = Screen.QuizSession(
                                raccoltaUid = raccoltaUid,
                                raccoltaName = raccoltaName,
                                packUid = pack.packUid,
                                packName = pack.name,
                            ),
                            isQuizLoading = false,
                            quizQuestions = questions,
                            quizResult = null,
                            message = null,
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        screen = Screen.CollectionDetail(raccoltaUid, raccoltaName),
                        isQuizLoading = false,
                        message = error.asUiMessage(),
                    )
                }
            }
        }
    }

    fun deleteQuizPack(packUid: String) {
        val destination = currentCollectionRef()?.toDetailScreen() ?: Screen.Collections
        viewModelScope.launch {
            _uiState.update { it.copy(isQuizLoading = true, message = null) }
            runCatching {
                quizRepository.deletePack(packUid)
            }.onSuccess {
                refreshCollections(
                    highlightedRaccoltaUid = currentCollectionRef()?.uid ?: _uiState.value.highlightedRaccoltaUid,
                    message = null,
                    destination = destination,
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isQuizLoading = false,
                        message = error.asUiMessage(),
                    )
                }
            }
        }
    }

    fun onQuizFinished(correct: Int, total: Int) {
        _uiState.update {
            it.copy(
                quizResult = QuizResultUiState(correct = correct, total = total),
            )
        }
    }

    fun openGenerateQuizPlaceholder(raccoltaUid: String, raccoltaName: String) {
        val current = _uiState.value.generateQuiz
        val saved = lanSettingsStore.load()
        val missingJob = generationJobStore.consumeMissingJob(raccoltaUid)
        val durableJob = generationJobStore.current()?.takeIf { it.raccoltaUid == raccoltaUid }
        if (durableJob != null) {
            resumePersistedGeneration()
        }
        val generateState = if (durableJob != null) {
            current.copy(
                raccoltaUid = raccoltaUid,
                raccoltaName = raccoltaName,
                host = durableJob.host,
                portText = durableJob.port.toString(),
                code = durableJob.code,
                currentJobId = durableJob.id,
                isWaiting = generationJobStore.current()?.pollingActive == true,
                progress = durableJob.progress,
                stage = durableJob.stage,
                statusMessage = durableJob.message,
                action = GenerateQuizAction.RESUME_WAIT,
            )
        } else if (current.raccoltaUid == raccoltaUid) {
            current.copy(
                raccoltaUid = raccoltaUid,
                raccoltaName = raccoltaName,
            )
        } else {
            GenerateQuizUiState(
                raccoltaUid = raccoltaUid,
                raccoltaName = raccoltaName,
                host = saved.host,
                portText = saved.port.toString(),
                code = saved.code,
                bannerMessage = if (missingJob) UiMessage.Resource(R.string.generate_quiz_error_job_missing) else null,
            )
        }

        _uiState.update {
            it.copy(
                screen = Screen.GenerateQuiz(raccoltaUid, raccoltaName),
                highlightedRaccoltaUid = raccoltaUid,
                pendingImport = null,
                sectionsDialog = null,
                pendingRemoval = null,
                message = null,
                generateQuiz = generateState,
            )
        }
        durableJob?.let {
            checkPersistedJobImmediately(it)
            watchPersistedJob(it)
        }
    }

    /** Keeps a restored screen from displaying a stale wait state while the service is starting. */
    private fun checkPersistedJobImmediately(job: GenerationJob) {
        viewModelScope.launch {
            val result = lanClient.status(job.host, job.port, job.code, job.id)
            if (result.exceptionOrNull() !== LanError.JobNotFound) return@launch
            generationJobStore.clearAsMissing(job)
            _uiState.update { state ->
                if (state.generateQuiz.currentJobId != job.id) state else state.copy(
                    generateQuiz = state.generateQuiz.copy(
                        bannerMessage = UiMessage.Resource(R.string.generate_quiz_error_job_missing),
                        isWaiting = false,
                        isImporting = false,
                        currentJobId = null,
                        action = GenerateQuizAction.START,
                    ),
                )
            }
        }
    }

    /** The foreground service owns network polling; the screen only mirrors its durable state. */
    private fun watchPersistedJob(job: GenerationJob) {
        bakePollingJob?.cancel()
        bakePollingJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                val latest = generationJobStore.current()
                if (latest?.id != job.id) {
                    val wasMissing = generationJobStore.consumeMissingJob(job.raccoltaUid)
                    _uiState.update { state ->
                        if (state.generateQuiz.currentJobId != job.id) state else state.copy(
                            generateQuiz = state.generateQuiz.copy(
                                bannerMessage = if (wasMissing) {
                                    UiMessage.Resource(R.string.generate_quiz_error_job_missing)
                                } else {
                                    state.generateQuiz.bannerMessage
                                },
                                isWaiting = false,
                                isImporting = false,
                                currentJobId = null,
                                action = GenerateQuizAction.START,
                            ),
                        )
                    }
                    return@launch
                }
                _uiState.update { state ->
                    if (state.generateQuiz.currentJobId != job.id) state else state.copy(
                        generateQuiz = state.generateQuiz.copy(
                            isWaiting = latest.pollingActive,
                            progress = latest.progress,
                            stage = latest.stage,
                            statusMessage = latest.message,
                        ),
                    )
                }
                delay(500L)
            }
        }
    }

    fun discoverLanPcs() {
        cancelDiscovery()
        var launchedJob: Job? = null
        launchedJob = viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        generateQuiz = it.generateQuiz.copy(
                            isDiscovering = true,
                            hasDiscoveryAttempted = true,
                            discoveredPcs = emptyList(),
                            bannerMessage = null,
                        ),
                    )
                }
                val discovered = lanDiscovery.discover()
                _uiState.update {
                    it.copy(
                        generateQuiz = it.generateQuiz.copy(
                            discoveredPcs = discovered,
                            isDiscovering = false,
                            hasDiscoveryAttempted = true,
                        ),
                    )
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) {
                    throw error
                }
                _uiState.update {
                    it.copy(
                        generateQuiz = it.generateQuiz.copy(
                            discoveredPcs = emptyList(),
                            isDiscovering = false,
                            hasDiscoveryAttempted = true,
                            bannerMessage = UiMessage.Dynamic(
                                error.message ?: "Could not search the local network for the PC.",
                            ),
                        ),
                    )
                }
            } finally {
                if (discoveryJob === launchedJob) {
                    discoveryJob = null
                }
            }
        }
        discoveryJob = launchedJob
    }

    fun selectDiscoveredPc(pc: DiscoveredPc) {
        val current = _uiState.value.generateQuiz
        lanSettingsStore.save(pc.host, pc.port, current.code)
        _uiState.update {
            it.copy(
                generateQuiz = resetGenerateConnectionState(
                    state = current,
                    host = pc.host,
                    portText = pc.port.toString(),
                ),
            )
        }
        watchPersistedJob(generationJobStore.current() ?: return)
        verifyGenerateQuizConnection()
    }

    fun updateGenerateQuizHost(host: String) {
        val current = _uiState.value.generateQuiz
        val normalizedPort = current.portText.toIntOrNull() ?: DEFAULT_LAN_PORT
        lanSettingsStore.save(host, normalizedPort, current.code)
        _uiState.update {
            it.copy(
                generateQuiz = resetGenerateConnectionState(
                    state = current,
                    host = host,
                ),
            )
        }
    }

    fun updateGenerateQuizPort(portText: String) {
        val sanitized = portText.filter { ch -> ch.isDigit() }.take(5)
        val current = _uiState.value.generateQuiz
        val normalizedPort = sanitized.toIntOrNull() ?: DEFAULT_LAN_PORT
        lanSettingsStore.save(current.host, normalizedPort, current.code)
        _uiState.update {
            it.copy(
                generateQuiz = resetGenerateConnectionState(
                    state = current,
                    portText = sanitized,
                ),
            )
        }
    }

    fun updateGenerateQuizCode(code: String) {
        val sanitized = code.filter { ch -> ch.isDigit() }.take(6)
        val current = _uiState.value.generateQuiz
        val normalizedPort = current.portText.toIntOrNull() ?: DEFAULT_LAN_PORT
        lanSettingsStore.save(current.host, normalizedPort, sanitized)
        _uiState.update {
            it.copy(
                generateQuiz = current.copy(
                    code = sanitized,
                    bannerMessage = if (current.bannerMessage is UiMessage.Dynamic) null else current.bannerMessage,
                ),
            )
        }
    }

    fun dismissGenerateQuizMessage() {
        _uiState.update {
            it.copy(
                generateQuiz = it.generateQuiz.copy(
                    bannerMessage = null,
                ),
            )
        }
    }

    fun verifyGenerateQuizConnection() {
        val current = _uiState.value.generateQuiz
        val port = runCatching { parseLanPort(current.portText) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    generateQuiz = current.copy(
                        bannerMessage = error.asUiMessage(),
                    ),
                )
            }
            return
        }
        lanSettingsStore.save(current.host, port, current.code)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    generateQuiz = it.generateQuiz.copy(
                        isVerifying = true,
                        bannerMessage = null,
                    ),
                )
            }
            lanClient.ping(current.host, port)
                .onSuccess { pcInfo ->
                    _uiState.update {
                        it.copy(
                            generateQuiz = it.generateQuiz.copy(
                                isVerifying = false,
                                verifiedPc = pcInfo,
                                bannerMessage = null,
                                action = if (!it.generateQuiz.currentJobId.isNullOrBlank()) {
                                    GenerateQuizAction.RESUME_WAIT
                                } else {
                                    GenerateQuizAction.START
                                },
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            generateQuiz = it.generateQuiz.copy(
                                isVerifying = false,
                                verifiedPc = null,
                                bannerMessage = error.asUiMessage(),
                                action = GenerateQuizAction.START,
                            ),
                        )
                    }
                }
        }
    }

    fun startLanGenerateQuiz() {
        val current = _uiState.value.generateQuiz
        val collection = currentCollectionRef() ?: return
        if (current.action == GenerateQuizAction.RESUME_WAIT && !current.currentJobId.isNullOrBlank()) {
            resumeLanGenerateQuiz(collection, current.currentJobId)
            return
        }
        if (current.verifiedPc == null) {
            _uiState.update {
                it.copy(
                    generateQuiz = current.copy(
                        bannerMessage = UiMessage.Resource(R.string.generate_quiz_verify_first),
                    ),
                )
            }
            return
        }
        val port = runCatching { parseLanPort(current.portText) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    generateQuiz = current.copy(
                        bannerMessage = error.asUiMessage(),
                    ),
                )
            }
            return
        }
        lanSettingsStore.save(current.host, port, current.code)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    generateQuiz = it.generateQuiz.copy(
                        bannerMessage = null,
                        isSubmitting = true,
                        isWaiting = false,
                        isImporting = false,
                        currentJobId = null,
                        progress = 0f,
                        stage = null,
                        statusMessage = null,
                        uidMismatchCount = 0,
                        action = GenerateQuizAction.START,
                    ),
                )
            }
            runCatching {
                repository.cardsInRaccolta(collection.uid)
            }.onSuccess { cards ->
                if (cards.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            generateQuiz = it.generateQuiz.copy(
                                isSubmitting = false,
                                bannerMessage = UiMessage.Resource(R.string.generate_quiz_no_cards),
                            ),
                        )
                    }
                    return@launch
                }

                lanClient.bake(
                    host = current.host,
                    port = port,
                    code = current.code,
                    raccoltaName = collection.name,
                    lang = BAKE_LANGUAGE,
                    cards = cards.map { card ->
                        LanCard(
                            uid = card.uid,
                            front = card.front,
                            back = card.back,
                        )
                    },
                ).onSuccess { jobId ->
                    beginPolling(
                        collection = collection,
                        host = current.host,
                        port = port,
                        code = current.code,
                        jobId = jobId,
                    )
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            generateQuiz = it.generateQuiz.copy(
                                isSubmitting = false,
                                bannerMessage = error.asUiMessage(),
                                action = GenerateQuizAction.START,
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        generateQuiz = it.generateQuiz.copy(
                            isSubmitting = false,
                            bannerMessage = error.asUiMessage(),
                        ),
                    )
                }
            }
        }
    }

    fun cancelGenerateQuizWait() {
        if (!_uiState.value.generateQuiz.isWaiting && !_uiState.value.generateQuiz.isImporting) {
            return
        }
        val updated = stopGenerateQuizPolling(
            state = _uiState.value.generateQuiz,
            showMessage = true,
        )
        GenerationPollingService.cancel(appContext)
        _uiState.update { it.copy(generateQuiz = updated) }
    }

    fun goToCollections() {
        val highlightedUid = currentCollectionRef()?.uid ?: _uiState.value.highlightedRaccoltaUid
        val generateState = prepareGenerateStateForLeave()
        refreshCollections(
            highlightedRaccoltaUid = highlightedUid,
            message = _uiState.value.message,
            destination = Screen.Collections,
            generateQuiz = generateState,
        )
    }

    fun goToCurrentCollectionDetail() {
        val destination = currentCollectionRef()?.toDetailScreen() ?: Screen.Collections
        val generateState = prepareGenerateStateForLeave()
        refreshCollections(
            highlightedRaccoltaUid = currentCollectionRef()?.uid ?: _uiState.value.highlightedRaccoltaUid,
            message = _uiState.value.message,
            destination = destination,
            generateQuiz = generateState,
        )
    }

    fun submitReview(cardUid: String, grade: Grade, elapsedMs: Long) {
        val study = _uiState.value.study
        val raccoltaUid = study.raccoltaUid ?: return
        viewModelScope.launch {
            repository.review(raccoltaUid, cardUid, grade, elapsedMs, System.currentTimeMillis())
            refreshStudyState(raccoltaUid, study.raccoltaName)
        }
    }

    fun resetPass() {
        val study = _uiState.value.study
        val raccoltaUid = study.raccoltaUid ?: return
        viewModelScope.launch {
            repository.resetPass(raccoltaUid)
            lastShownCardUidByRaccolta[raccoltaUid] = null
            refreshStudyState(raccoltaUid, study.raccoltaName)
        }
    }

    fun showSections(raccolta: RaccoltaSummary) {
        viewModelScope.launch {
            runCatching {
                repository.sezioniInRaccolta(raccolta.uid)
            }.onSuccess { sections ->
                _uiState.update {
                    it.copy(
                        sectionsDialog = SectionsDialogUiState(
                            raccoltaUid = raccolta.uid,
                            raccoltaName = raccolta.name,
                            sections = sections,
                        ),
                        pendingRemoval = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(message = error.asUiMessage()) }
            }
        }
    }

    fun dismissSectionsDialog() {
        _uiState.update {
            it.copy(
                sectionsDialog = null,
                pendingRemoval = null,
            )
        }
    }

    fun requestRemoveSection(section: SezioneInfo) {
        val dialog = _uiState.value.sectionsDialog ?: return
        _uiState.update {
            it.copy(
                pendingRemoval = RemoveSectionUiState(
                    raccoltaUid = dialog.raccoltaUid,
                    raccoltaName = dialog.raccoltaName,
                    section = section,
                ),
            )
        }
    }

    fun dismissRemoveSection() {
        _uiState.update { it.copy(pendingRemoval = null) }
    }

    fun confirmRemoveSection() {
        val pending = _uiState.value.pendingRemoval ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRemovingSection = true) }
            runCatching {
                repository.removeSezione(pending.section.uid)
                val raccolte = repository.listRaccolte()
                val sections = repository.sezioniInRaccolta(pending.raccoltaUid)
                raccolte to sections
            }.onSuccess { (raccolte, sections) ->
                _uiState.update {
                    it.copy(
                        raccolte = raccolte,
                        isCollectionsLoading = false,
                        isRemovingSection = false,
                        sectionsDialog = SectionsDialogUiState(
                            raccoltaUid = pending.raccoltaUid,
                            raccoltaName = pending.raccoltaName,
                            sections = sections,
                        ),
                        pendingRemoval = null,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isRemovingSection = false,
                        message = error.asUiMessage(),
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun refreshCollections(
        highlightedRaccoltaUid: String? = _uiState.value.highlightedRaccoltaUid,
        message: UiMessage? = _uiState.value.message,
        destination: Screen = _uiState.value.screen,
        generateQuiz: GenerateQuizUiState = _uiState.value.generateQuiz,
    ) {
        viewModelScope.launch {
            refreshCollectionsState(highlightedRaccoltaUid, message, destination, generateQuiz)
        }
    }

    private suspend fun refreshCollectionsState(
        highlightedRaccoltaUid: String?,
        message: UiMessage?,
        destination: Screen,
        generateQuiz: GenerateQuizUiState,
    ) {
        val raccolte = repository.listRaccolte()
        val quizPacks = quizRepository.listPacks()
        val screen = normalizeDestination(destination, raccolte)
        _uiState.value = _uiState.value.copy(
            screen = screen,
            raccolte = raccolte,
            quizPacks = quizPacks,
            isCollectionsLoading = false,
            isImporting = false,
            isRemovingSection = false,
            isQuizLoading = false,
            highlightedRaccoltaUid = highlightedRaccoltaUid,
            pendingImport = null,
            sectionsDialog = null,
            pendingRemoval = null,
            quizQuestions = emptyList(),
            quizResult = null,
            message = message,
            generateQuiz = generateQuiz,
        )
    }

    private fun refreshStudy(raccoltaUid: String, raccoltaName: String) {
        viewModelScope.launch {
            refreshStudyState(raccoltaUid, raccoltaName)
        }
    }

    private suspend fun refreshStudyState(
        raccoltaUid: String,
        raccoltaName: String,
    ) {
        val total = repository.totalCards(raccoltaUid)
        val knownCount = repository.knownCount(raccoltaUid)
        val nextCard = repository.nextDueCard(
            raccoltaUid = raccoltaUid,
            excludeUid = lastShownCardUidByRaccolta[raccoltaUid],
        )
        lastShownCardUidByRaccolta[raccoltaUid] = nextCard?.uid
        _uiState.update {
            it.copy(
                screen = Screen.Study(raccoltaUid, raccoltaName),
                study = StudySessionUiState(
                    raccoltaUid = raccoltaUid,
                    raccoltaName = raccoltaName,
                    currentCard = nextCard,
                    totalCards = total,
                    knownCount = knownCount,
                    isLoading = false,
                    isDeckComplete = total > 0 && nextCard == null,
                ),
                isImporting = false,
            )
        }
    }

    private fun preferredImportRaccolta(raccolte: List<RaccoltaSummary>): String? {
        val highlighted = _uiState.value.highlightedRaccoltaUid
        return when {
            highlighted != null && raccolte.any { it.uid == highlighted } -> highlighted
            else -> raccolte.firstOrNull()?.uid
        }
    }

    private fun currentCollectionRef(): RaccoltaRef? = when (val screen = _uiState.value.screen) {
        is Screen.CollectionDetail -> RaccoltaRef(screen.raccoltaUid, screen.raccoltaName)
        is Screen.Study -> RaccoltaRef(screen.raccoltaUid, screen.raccoltaName)
        is Screen.GenerateQuiz -> RaccoltaRef(screen.raccoltaUid, screen.raccoltaName)
        is Screen.QuizSession -> RaccoltaRef(screen.raccoltaUid, screen.raccoltaName)
        // Il quiz combinato non appartiene a una singola raccolta.
        Screen.CombinedQuizSetup -> null
        is Screen.CombinedQuizSession -> null
        Screen.Collections -> null
    }

    private fun normalizeDestination(
        destination: Screen,
        raccolte: List<RaccoltaSummary>,
    ): Screen = when (destination) {
        Screen.Collections -> Screen.Collections
        // Non dipendono da una raccolta, quindi restano valide comunque.
        Screen.CombinedQuizSetup -> destination
        is Screen.CombinedQuizSession -> destination
        is Screen.CollectionDetail ->
            if (raccolte.any { it.uid == destination.raccoltaUid }) destination else Screen.Collections
        is Screen.Study ->
            if (raccolte.any { it.uid == destination.raccoltaUid }) destination else Screen.Collections
        is Screen.GenerateQuiz ->
            if (raccolte.any { it.uid == destination.raccoltaUid }) destination else Screen.Collections
        is Screen.QuizSession ->
            if (raccolte.any { it.uid == destination.raccoltaUid }) destination else Screen.Collections
    }

    private fun resumeLanGenerateQuiz(
        collection: RaccoltaRef,
        jobId: String?,
    ) {
        if (jobId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    generateQuiz = it.generateQuiz.copy(
                        action = GenerateQuizAction.START,
                        bannerMessage = UiMessage.Resource(R.string.generate_quiz_resume_missing_job),
                    ),
                )
            }
            return
        }
        val current = _uiState.value.generateQuiz
        val port = runCatching { parseLanPort(current.portText) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    generateQuiz = current.copy(
                        bannerMessage = error.asUiMessage(),
                    ),
                )
            }
            return
        }
        lanSettingsStore.save(current.host, port, current.code)
        generationJobStore.current()?.takeIf { it.id == jobId } ?: generationJobStore.save(
            GenerationJob(jobId, current.host, port, current.code, collection.uid, collection.name, System.currentTimeMillis()),
        )
        beginPolling(
            collection = collection,
            host = current.host,
            port = port,
            code = current.code,
            jobId = jobId,
        )
    }

    private fun beginPolling(
        collection: RaccoltaRef,
        host: String,
        port: Int,
        code: String,
        jobId: String,
    ) {
        bakePollingJob?.cancel()
        generationJobStore.save(
            GenerationJob(jobId, host, port, code, collection.uid, collection.name, System.currentTimeMillis()),
        )
        val pollingStarted = GenerationPollingService.start(appContext)
        generationJobStore.updatePollingActive(pollingStarted)
        _uiState.update {
            it.copy(
                generateQuiz = it.generateQuiz.copy(
                    bannerMessage = null,
                    isSubmitting = false,
                    isWaiting = pollingStarted,
                    isImporting = false,
                    currentJobId = jobId,
                    action = GenerateQuizAction.RESUME_WAIT,
                ),
            )
        }
    }

    /** Rilegge le raccolte quando si torna nell'app.
     *
     * Il servizio di polling puo' importare un pacchetto mentre l'app e' in
     * background: lo stato tenuto in memoria non se ne accorge e la lista
     * continuerebbe a mostrare la raccolta senza il suo quiz finche' l'utente
     * non riavvia l'app. Rileggere dal disco al rientro e' il modo piu'
     * semplice per non mostrare qualcosa di gia' superato.
     *
     * Non tocca la ripresa della generazione: quella parte alla creazione del
     * ViewModel, e rifarla a ogni rientro rischierebbe di avviare due giri di
     * interrogazione sullo stesso job.
     */
    fun onAppResumed() {
        refreshCollections()
    }

    /** A process killed by an aggressive vendor power manager leaves only the durable job.
     * On every app launch we deliberately restart the foreground poller; its first request is immediate. */
    fun resumePersistedGeneration() {
        val job = generationJobStore.current() ?: return
        generationJobStore.updatePollingActive(false)
        generationJobStore.updatePollingActive(GenerationPollingService.start(appContext))
    }

    private suspend fun pollBakeStatus(
        collection: RaccoltaRef,
        host: String,
        port: Int,
        code: String,
        jobId: String,
    ) {
        while (currentCoroutineContext().isActive) {
            val result = lanClient.status(
                host = host,
                port = port,
                code = code,
                job = jobId,
            )
            val status = result.getOrElse { error ->
                publishGenerateQuizError(
                    error = error,
                    jobId = jobId,
                    allowResume = true,
                )
                return
            }

            if (status.state.equals("error", ignoreCase = true)) {
                publishGenerateQuizError(
                    error = LanError.ServerError(
                        status.error ?: status.message ?: "The PC reported an error while generating the quiz.",
                    ),
                    jobId = jobId,
                    allowResume = true,
                    stage = status.stage,
                    progress = status.progress,
                    statusMessage = status.message ?: status.error,
                    uidMismatchCount = status.uidMismatchCount,
                )
                return
            }

            if (status.state.equals("done", ignoreCase = true)) {
                val imported = downloadAndImportGeneratedQuiz(
                    collection = collection,
                    host = host,
                    port = port,
                    code = code,
                    jobId = jobId,
                    status = status,
                )
                if (imported) {
                    return
                }
            } else {
                _uiState.update {
                    it.copy(
                        generateQuiz = it.generateQuiz.copy(
                            isWaiting = true,
                            isImporting = false,
                            currentJobId = jobId,
                            progress = status.progress,
                            stage = status.stage,
                            statusMessage = status.message,
                            uidMismatchCount = status.uidMismatchCount,
                            action = GenerateQuizAction.RESUME_WAIT,
                        ),
                    )
                }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun downloadAndImportGeneratedQuiz(
        collection: RaccoltaRef,
        host: String,
        port: Int,
        code: String,
        jobId: String,
        status: com.local.spacedcards.data.lan.BakeStatus,
    ): Boolean {
        _uiState.update {
            it.copy(
                generateQuiz = it.generateQuiz.copy(
                    isWaiting = true,
                    isImporting = true,
                    progress = 1f,
                    stage = status.stage,
                    statusMessage = status.message,
                    uidMismatchCount = status.uidMismatchCount,
                    currentJobId = jobId,
                ),
            )
        }
        val tempFile = File.createTempFile("mindloop-lan-", ".qzd", appContext.cacheDir)
        try {
            val downloaded = lanClient.downloadResult(
                host = host,
                port = port,
                code = code,
                job = jobId,
                into = tempFile,
            ).getOrElse { error ->
                if (error === LanError.NotReady) {
                    _uiState.update {
                        it.copy(
                            generateQuiz = it.generateQuiz.copy(
                                isWaiting = true,
                                isImporting = false,
                                progress = 1f,
                            ),
                        )
                    }
                    return false
                }
                publishGenerateQuizError(
                    error = error,
                    jobId = jobId,
                    allowResume = true,
                    stage = status.stage,
                    progress = 1f,
                    statusMessage = status.message,
                    uidMismatchCount = status.uidMismatchCount,
                )
                return true
            }

            val outcome = runCatching {
                importQuizPack(
                    uri = Uri.fromFile(downloaded),
                    preferredRaccoltaUid = collection.uid,
                    forcedCollection = collection,
                )
            }.getOrElse { error ->
                publishGenerateQuizError(
                    error = error,
                    jobId = jobId,
                    allowResume = true,
                    stage = status.stage,
                    progress = 1f,
                    statusMessage = status.message,
                    uidMismatchCount = status.uidMismatchCount,
                )
                return true
            }

            lastShownCardUidByRaccolta[collection.uid] = null
            refreshCollections(
                highlightedRaccoltaUid = collection.uid,
                message = buildLanImportMessage(
                    packName = outcome.packName,
                    collectionName = collection.name,
                    uidMismatchCount = status.uidMismatchCount,
                ),
                destination = Screen.CollectionDetail(collection.uid, collection.name),
                generateQuiz = GenerateQuizUiState(),
            )
            return true
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun buildLanImportMessage(
        packName: String,
        collectionName: String,
        uidMismatchCount: Int,
    ): UiMessage = if (uidMismatchCount > 0) {
        UiMessage.Resource(
            R.string.generate_quiz_imported_with_uid_warning,
            listOf(packName, collectionName, uidMismatchCount),
        )
    } else {
        UiMessage.Resource(
            R.string.generate_quiz_imported_message,
            listOf(packName, collectionName),
        )
    }

    private fun publishGenerateQuizError(
        error: Throwable,
        jobId: String?,
        allowResume: Boolean,
        stage: String? = _uiState.value.generateQuiz.stage,
        progress: Float = _uiState.value.generateQuiz.progress,
        statusMessage: String? = _uiState.value.generateQuiz.statusMessage,
        uidMismatchCount: Int = _uiState.value.generateQuiz.uidMismatchCount,
    ) {
        bakePollingJob?.cancel()
        bakePollingJob = null
        _uiState.update {
            it.copy(
                generateQuiz = it.generateQuiz.copy(
                    bannerMessage = error.asUiMessage(),
                    isSubmitting = false,
                    isWaiting = false,
                    isImporting = false,
                    currentJobId = jobId,
                    stage = stage,
                    progress = progress,
                    statusMessage = statusMessage,
                    uidMismatchCount = uidMismatchCount,
                    action = if (allowResume && !jobId.isNullOrBlank()) {
                        GenerateQuizAction.RESUME_WAIT
                    } else {
                        GenerateQuizAction.START
                    },
                ),
            )
        }
    }

    private fun prepareGenerateStateForLeave(): GenerateQuizUiState {
        cancelDiscovery()
        val current = _uiState.value.generateQuiz
        val withoutDiscovery = current.copy(isDiscovering = false)
        return if (withoutDiscovery.isWaiting || withoutDiscovery.isImporting) {
            stopGenerateQuizPolling(withoutDiscovery, showMessage = true)
        } else {
            withoutDiscovery
        }
    }

    private fun stopGenerateQuizPolling(
        state: GenerateQuizUiState,
        showMessage: Boolean,
    ): GenerateQuizUiState {
        bakePollingJob?.cancel()
        bakePollingJob = null
        return state.copy(
            isSubmitting = false,
            isWaiting = false,
            isImporting = false,
            bannerMessage = if (showMessage && !state.currentJobId.isNullOrBlank()) {
                UiMessage.Resource(R.string.generate_quiz_wait_cancelled)
            } else {
                state.bannerMessage
            },
            action = if (!state.currentJobId.isNullOrBlank()) {
                GenerateQuizAction.RESUME_WAIT
            } else {
                GenerateQuizAction.START
            },
        )
    }

    private fun cancelDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    private fun resetGenerateConnectionState(
        state: GenerateQuizUiState,
        host: String = state.host,
        portText: String = state.portText,
    ): GenerateQuizUiState = state.copy(
        host = host,
        portText = portText,
        verifiedPc = null,
        bannerMessage = null,
        isVerifying = false,
        isSubmitting = false,
        isWaiting = false,
        isImporting = false,
        currentJobId = null,
        progress = 0f,
        stage = null,
        statusMessage = null,
        uidMismatchCount = 0,
        action = GenerateQuizAction.START,
    )

    private fun parseLanPort(portText: String): Int {
        val trimmed = portText.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_LAN_PORT
        }
        val port = trimmed.toIntOrNull()
            ?: throw LanError.InvalidAddress("Enter a valid numeric port.")
        if (port !in 1..65535) {
            throw LanError.InvalidAddress("The port must be between 1 and 65535.")
        }
        return port
    }

    private suspend fun importQuizPack(
        uri: Uri,
        preferredRaccoltaUid: String?,
        forcedCollection: RaccoltaRef?,
    ): QuizImportOutcome {
        var importedPayload: ImportedQuizPackPayload? = null
        var attachedToCollection = false
        try {
            importedPayload = quizRepository.importQzd(uri)
            val payload = requireNotNull(importedPayload)

            val matchedCollection = if (forcedCollection == null) {
                repository.findBestMatchingRaccolta(
                    cardUids = payload.cards.mapTo(linkedSetOf()) { it.uid },
                    preferredRaccoltaUid = preferredRaccoltaUid,
                )
            } else {
                null
            }
            val targetCollection = forcedCollection
                ?: matchedCollection
                ?: repository.createRaccoltaFromQuizPack(payload)
            val createdCollection = forcedCollection == null && matchedCollection == null

            quizRepository.attachPackToRaccolta(payload.packUid, targetCollection.uid)
            attachedToCollection = true

            return QuizImportOutcome(
                packName = payload.packName,
                targetCollection = targetCollection,
                createdCollection = createdCollection,
            )
        } catch (error: Throwable) {
            if (importedPayload != null && !attachedToCollection) {
                runCatching { quizRepository.deletePack(importedPayload.packUid) }
            }
            throw error
        }
    }

    private fun Throwable.asUiMessage(): UiMessage = when (this) {
        is ApkgImportException -> when (reason) {
            ApkgImportException.Reason.OPEN_FAILED ->
                UiMessage.Resource(R.string.import_open_failed)
            else ->
                UiMessage.Dynamic(detail ?: reason.name)
        }
        is LanError.InvalidAddress -> UiMessage.Resource(R.string.generate_quiz_error_invalid_address)
        is LanError.NotReachable -> UiMessage.Resource(
            R.string.generate_quiz_error_not_reachable,
            listOf(host, port),
        )
        is LanError.WrongApp ->
            if (foundApp.isNullOrBlank()) {
                UiMessage.Resource(R.string.generate_quiz_error_wrong_app)
            } else {
                UiMessage.Resource(
                    R.string.generate_quiz_error_wrong_app_named,
                    listOf(foundApp),
                )
            }
        is LanError.BadCode -> UiMessage.Resource(R.string.generate_quiz_error_bad_code)
        LanError.JobNotFound -> UiMessage.Resource(R.string.generate_quiz_error_job_missing)
        LanError.Timeout -> UiMessage.Resource(R.string.generate_quiz_error_timeout)
        LanError.NotReady -> UiMessage.Resource(R.string.generate_quiz_error_not_ready)
        is LanError.ServerError -> UiMessage.Dynamic(detail)
        is QzdImportException -> UiMessage.Dynamic(error.message)
        else -> UiMessage.Dynamic(message ?: this::class.java.simpleName)
    }

    private data class QuizImportOutcome(
        val packName: String,
        val targetCollection: RaccoltaRef,
        val createdCollection: Boolean,
    )

    private fun RaccoltaRef.toDetailScreen(): Screen.CollectionDetail =
        Screen.CollectionDetail(raccoltaUid = uid, raccoltaName = name)

    private companion object {
        private const val BAKE_LANGUAGE = "it"
        private const val POLL_INTERVAL_MS = 1_500L
    }
}

class MainViewModelFactory(
    private val repository: StudyRepository,
    private val quizRepository: QuizRepository,
    private val applicationContext: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, quizRepository, applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
