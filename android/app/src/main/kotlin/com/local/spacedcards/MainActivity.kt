package com.local.spacedcards

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.spacedcards.data.StudyRepository
import com.local.spacedcards.data.content.ContentDb
import com.local.spacedcards.data.importer.ApkgImporter
import com.local.spacedcards.data.quiz.QuizRepository
import com.local.spacedcards.data.state.StateDb
import com.local.spacedcards.ui.MainViewModel
import com.local.spacedcards.ui.MainViewModelFactory
import com.local.spacedcards.ui.ReviewScreen
import com.local.spacedcards.ui.theme.SpacedCardsTheme

class MainActivity : AppCompatActivity() {
    private val repository by lazy {
        val contentDb = ContentDb.getInstance(applicationContext)
        StudyRepository(
            contentDb = contentDb,
            stateDb = StateDb.getInstance(applicationContext),
            importer = ApkgImporter(applicationContext),
        )
    }
    private val quizRepository by lazy {
        QuizRepository(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModelFactory(repository, quizRepository, applicationContext),
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // Il quiz puo' essere stato importato dal servizio mentre l'app
            // era in background: al rientro rileggiamo, altrimenti la home
            // continuerebbe a mostrare la raccolta senza il suo quiz.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResumed()
            }

            LaunchedEffect(Unit) {
                intent.getStringExtra(GenerationPollingService.EXTRA_COLLECTION_UID)?.let { uid ->
                    viewModel.openCollectionDetail(uid, intent.getStringExtra(GenerationPollingService.EXTRA_COLLECTION_NAME).orEmpty())
                }
            }

            SpacedCardsTheme {
                ReviewScreen(
                    uiState = uiState,
                    onImportRequested = viewModel::onImportRequested,
                    onQzdImportRequested = viewModel::onQzdImportRequested,
                    onDismissMessage = viewModel::clearMessage,
                    onOpenCollectionDetail = viewModel::openCollectionDetail,
                    onOpenGenerateQuizPlaceholder = viewModel::openGenerateQuizPlaceholder,
                    onGenerateQuizHostChanged = viewModel::updateGenerateQuizHost,
                    onGenerateQuizPortChanged = viewModel::updateGenerateQuizPort,
                    onGenerateQuizCodeChanged = viewModel::updateGenerateQuizCode,
                    onDiscoverGenerateQuizPc = viewModel::discoverLanPcs,
                    onSelectDiscoveredGenerateQuizPc = viewModel::selectDiscoveredPc,
                    onVerifyGenerateQuiz = viewModel::verifyGenerateQuizConnection,
                    onStartLanGenerateQuiz = {
                        requestNotificationPermissionForGeneration()
                        viewModel.startLanGenerateQuiz()
                    },
                    onCancelGenerateQuizWait = viewModel::cancelGenerateQuizWait,
                    onDismissGenerateQuizMessage = viewModel::dismissGenerateQuizMessage,
                    onStartQuiz = viewModel::startQuiz,
                    onDeleteQuizPack = viewModel::deleteQuizPack,
                    onQuizFinished = viewModel::onQuizFinished,
                    onReview = viewModel::submitReview,
                    onResetPass = viewModel::resetPass,
                    onOpenStudy = viewModel::openStudy,
                    onOpenCombinedQuiz = viewModel::openCombinedQuizSetup,
                    onToggleCombinedQuizPack = viewModel::toggleCombinedQuizPack,
                    onStartCombinedQuiz = viewModel::startCombinedQuiz,
                    onGoCollections = viewModel::goToCollections,
                    onGoCollectionDetail = viewModel::goToCurrentCollectionDetail,
                    onConfirmImport = viewModel::confirmImport,
                    onDismissImportDialog = viewModel::dismissImportDialog,
                    onShowSections = viewModel::showSections,
                    onDismissSectionsDialog = viewModel::dismissSectionsDialog,
                    onRequestRemoveSection = viewModel::requestRemoveSection,
                    onConfirmRemoveSection = viewModel::confirmRemoveSection,
                    onDismissRemoveSection = viewModel::dismissRemoveSection,
                )
            }
        }
    }

    private fun requestNotificationPermissionForGeneration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
