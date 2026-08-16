@file:OptIn(ExperimentalMaterial3Api::class)

package com.local.spacedcards.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.local.spacedcards.R
import com.local.spacedcards.core.Grade
import com.local.spacedcards.data.RaccoltaSummary
import com.local.spacedcards.data.ReviewCard
import com.local.spacedcards.data.SezioneInfo
import com.local.spacedcards.data.lan.DiscoveredPc
import com.local.spacedcards.data.quiz.QuizPackSummary
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class AppLanguageOption(@StringRes val labelResId: Int) {
    SYSTEM(R.string.settings_system),
    ENGLISH(R.string.settings_english),
    ITALIAN(R.string.settings_italian),
    ;

    fun toLocales(): LocaleListCompat = when (this) {
        SYSTEM -> LocaleListCompat.getEmptyLocaleList()
        ENGLISH -> LocaleListCompat.forLanguageTags("en")
        ITALIAN -> LocaleListCompat.forLanguageTags("it")
    }

    companion object {
        fun from(locales: LocaleListCompat): AppLanguageOption = when (locales.get(0)?.language) {
            "en" -> ENGLISH
            "it" -> ITALIAN
            else -> SYSTEM
        }
    }
}

@Composable
fun ReviewScreen(
    uiState: MainUiState,
    onImportRequested: (Uri, String?) -> Unit,
    onQzdImportRequested: (Uri, String?) -> Unit,
    onDismissMessage: () -> Unit,
    onOpenCollectionDetail: (String, String) -> Unit,
    onOpenGenerateQuizPlaceholder: (String, String) -> Unit,
    onGenerateQuizHostChanged: (String) -> Unit,
    onGenerateQuizPortChanged: (String) -> Unit,
    onGenerateQuizCodeChanged: (String) -> Unit,
    onDiscoverGenerateQuizPc: () -> Unit,
    onSelectDiscoveredGenerateQuizPc: (DiscoveredPc) -> Unit,
    onVerifyGenerateQuiz: () -> Unit,
    onStartLanGenerateQuiz: () -> Unit,
    onCancelGenerateQuizWait: () -> Unit,
    onDismissGenerateQuizMessage: () -> Unit,
    onStartQuiz: (String, String, QuizPackSummary) -> Unit,
    onDeleteQuizPack: (String) -> Unit,
    onQuizFinished: (Int, Int) -> Unit,
    onReview: (String, Grade, Long) -> Unit,
    onResetPass: () -> Unit,
    onOpenStudy: (String, String) -> Unit,
    onOpenCombinedQuiz: () -> Unit,
    onToggleCombinedQuizPack: (String) -> Unit,
    onStartCombinedQuiz: () -> Unit,
    onGoCollections: () -> Unit,
    onGoCollectionDetail: () -> Unit,
    onConfirmImport: (String, String?, String?) -> Unit,
    onDismissImportDialog: () -> Unit,
    onShowSections: (RaccoltaSummary) -> Unit,
    onDismissSectionsDialog: () -> Unit,
    onRequestRemoveSection: (SezioneInfo) -> Unit,
    onConfirmRemoveSection: () -> Unit,
    onDismissRemoveSection: () -> Unit,
) {
    val context = LocalContext.current
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    val currentLanguage = remember(showLanguageDialog) {
        AppLanguageOption.from(AppCompatDelegate.getApplicationLocales())
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImportRequested(uri, uri.displayName(context))
        }
    }
    val quizLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onQzdImportRequested(uri, uri.displayName(context))
        }
    }
    val launchImportPicker = remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
    val launchQuizImportPicker = remember(quizLauncher) { { quizLauncher.launch(arrayOf("*/*")) } }
    val colorScheme = MaterialTheme.colorScheme
    val screenGradientMiddle = colorScheme.surface.copy(alpha = 0.97f).compositeOver(colorScheme.background)
    val screenGradientEnd = colorScheme.primary.copy(alpha = 0.08f).compositeOver(colorScheme.background)
    var pendingQuizPackRemoval by remember { mutableStateOf<QuizPackSummary?>(null) }
    val hasOpenDialog = showLanguageDialog ||
        uiState.pendingImport != null ||
        uiState.sectionsDialog != null ||
        uiState.pendingRemoval != null ||
        pendingQuizPackRemoval != null
    val backAction: (() -> Unit)? = when (uiState.screen) {
        Screen.Collections -> null
        is Screen.CollectionDetail -> onGoCollections
        is Screen.Study -> onGoCollectionDetail
        is Screen.GenerateQuiz -> onGoCollectionDetail
        is Screen.QuizSession -> onGoCollectionDetail
        is Screen.CombinedQuizSetup -> onGoCollections
        is Screen.CombinedQuizSession -> onGoCollections
    }

    BackHandler(
        enabled = backAction != null && !hasOpenDialog,
        onBack = { backAction?.invoke() },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        colorScheme.background,
                        screenGradientMiddle,
                        screenGradientEnd,
                    ),
                ),
            ),
    ) {
        when (val screen = uiState.screen) {
            Screen.Collections -> CollectionsContent(
                uiState = uiState,
                onLaunchImportPicker = launchImportPicker,
                onShowSettings = { showLanguageDialog = true },
                onDismissMessage = onDismissMessage,
                onOpenCollectionDetail = onOpenCollectionDetail,
                onOpenCombinedQuiz = onOpenCombinedQuiz,
            )

            is Screen.CollectionDetail -> {
                val raccolta = uiState.raccolte.firstOrNull { it.uid == screen.raccoltaUid }
                val linkedPack = uiState.quizPacks
                    .filter { it.raccoltaUid == screen.raccoltaUid }
                    .maxByOrNull { it.importedAt }
                CollectionDetailContent(
                    uiState = uiState,
                    raccolta = raccolta,
                    linkedPack = linkedPack,
                    onGoCollections = onGoCollections,
                    onLaunchQuizImportPicker = launchQuizImportPicker,
                    onOpenStudy = onOpenStudy,
                    onStartQuiz = onStartQuiz,
                    onOpenGenerateQuizPlaceholder = onOpenGenerateQuizPlaceholder,
                    onShowSections = onShowSections,
                    onShowSettings = { showLanguageDialog = true },
                    onDismissMessage = onDismissMessage,
                    onRequestDeletePack = { pendingQuizPackRemoval = it },
                )
            }

            is Screen.QuizSession -> QuizScreen(
                title = screen.packName,
                questions = uiState.quizQuestions,
                onFinished = onQuizFinished,
                onExit = onGoCollectionDetail,
            )

            is Screen.CombinedQuizSetup -> CombinedQuizSetupContent(
                uiState = uiState,
                onBack = onGoCollections,
                onToggle = onToggleCombinedQuizPack,
                onStart = onStartCombinedQuiz,
            )

            is Screen.CombinedQuizSession -> QuizScreen(
                title = screen.title,
                questions = uiState.quizQuestions,
                onFinished = onQuizFinished,
                onExit = onGoCollections,
            )

            is Screen.GenerateQuiz -> GenerateQuizContent(
                state = uiState.generateQuiz,
                onBack = onGoCollectionDetail,
                onHostChanged = onGenerateQuizHostChanged,
                onPortChanged = onGenerateQuizPortChanged,
                onCodeChanged = onGenerateQuizCodeChanged,
                onDiscoverPc = onDiscoverGenerateQuizPc,
                onSelectDiscoveredPc = onSelectDiscoveredGenerateQuizPc,
                onVerify = onVerifyGenerateQuiz,
                onGenerate = onStartLanGenerateQuiz,
                onCancelWait = onCancelGenerateQuizWait,
                onDismissMessage = onDismissGenerateQuizMessage,
                onShowSettings = { showLanguageDialog = true },
            )

            is Screen.Study -> StudyContent(
                uiState = uiState,
                raccoltaUid = screen.raccoltaUid,
                raccoltaName = screen.raccoltaName,
                onBack = onGoCollectionDetail,
                onDismissMessage = onDismissMessage,
                onReview = onReview,
                onResetPass = onResetPass,
                onShowSettings = { showLanguageDialog = true },
            )
        }
    }

    if (showLanguageDialog) {
        LanguageSettingsDialog(
            selectedOption = currentLanguage,
            onDismiss = { showLanguageDialog = false },
            onSelect = { option ->
                showLanguageDialog = false
                AppCompatDelegate.setApplicationLocales(option.toLocales())
            },
        )
    }

    uiState.pendingImport?.let { pending ->
        ImportDeckDialog(
            pendingImport = pending,
            raccolte = uiState.raccolte,
            isImporting = uiState.isImporting,
            onDismiss = onDismissImportDialog,
            onConfirm = onConfirmImport,
        )
    }

    uiState.sectionsDialog?.let { dialog ->
        SectionsDialog(
            dialog = dialog,
            isRemoving = uiState.isRemovingSection,
            onDismiss = onDismissSectionsDialog,
            onRemoveSection = onRequestRemoveSection,
        )
    }

    uiState.pendingRemoval?.let { pending ->
        RemoveSectionDialog(
            pending = pending,
            isRemoving = uiState.isRemovingSection,
            onDismiss = onDismissRemoveSection,
            onConfirm = onConfirmRemoveSection,
        )
    }

    pendingQuizPackRemoval?.let { pending ->
        RemoveQuizPackDialog(
            pending = pending,
            isRemoving = uiState.isQuizLoading,
            onDismiss = { pendingQuizPackRemoval = null },
            onConfirm = {
                pendingQuizPackRemoval = null
                onDeleteQuizPack(pending.packUid)
            },
        )
    }
}

@Composable
private fun CollectionsContent(
    uiState: MainUiState,
    onLaunchImportPicker: () -> Unit,
    onShowSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenCollectionDetail: (String, String) -> Unit,
    onOpenCombinedQuiz: () -> Unit,
) {
    val linkedPackCollectionUids = remember(uiState.quizPacks) {
        uiState.quizPacks.mapNotNull { it.raccoltaUid }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.app_name),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(
                    enabled = !uiState.isImporting,
                    onClick = onShowSettings,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            },
        )

        MessageBanner(
            message = uiState.message,
            onDismiss = onDismissMessage,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        FilledTonalButton(
            onClick = onLaunchImportPicker,
            enabled = !uiState.isImporting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(
                    if (uiState.isImporting) {
                        R.string.action_importing
                    } else {
                        R.string.action_import
                    },
                ),
            )
        }

        // Compare solo con almeno due pacchetti: con uno solo un "quiz
        // combinato" sarebbe identico al quiz della sua raccolta, e una voce
        // che non fa niente di diverso confonde invece di aiutare.
        if (uiState.quizPacks.size >= 2) {
            OutlinedButton(
                onClick = onOpenCombinedQuiz,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.combined_quiz_entry))
            }
        }

        when {
            uiState.isCollectionsLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            uiState.raccolte.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.home_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onLaunchImportPicker) {
                            Text(stringResource(R.string.home_empty_action))
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 16.dp,
                        bottom = 28.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(uiState.raccolte, key = { it.uid }) { raccolta ->
                        RaccoltaCard(
                            raccolta = raccolta,
                            isHighlighted = raccolta.uid == uiState.highlightedRaccoltaUid,
                            hasLinkedQuiz = raccolta.uid in linkedPackCollectionUids,
                            onOpen = { onOpenCollectionDetail(raccolta.uid, raccolta.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionDetailContent(
    uiState: MainUiState,
    raccolta: RaccoltaSummary?,
    linkedPack: QuizPackSummary?,
    onGoCollections: () -> Unit,
    onLaunchQuizImportPicker: () -> Unit,
    onOpenStudy: (String, String) -> Unit,
    onStartQuiz: (String, String, QuizPackSummary) -> Unit,
    onOpenGenerateQuizPlaceholder: (String, String) -> Unit,
    onShowSections: (RaccoltaSummary) -> Unit,
    onShowSettings: () -> Unit,
    onDismissMessage: () -> Unit,
    onRequestDeletePack: (QuizPackSummary) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onGoCollections) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            title = {
                Text(
                    text = raccolta?.name ?: stringResource(R.string.collections_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                if (raccolta != null) {
                    IconButton(
                        enabled = !uiState.isRemovingSection,
                        onClick = { onShowSections(raccolta) },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.collection_sections),
                        )
                    }
                }
                IconButton(onClick = onShowSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            },
        )

        MessageBanner(
            message = uiState.message,
            onDismiss = onDismissMessage,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        when {
            raccolta == null && uiState.isCollectionsLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.loading),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            raccolta == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_cards_available),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Card(
                        shape = RoundedCornerShape(30.dp),
                        colors = themedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        ),
                        border = elevatedCardBorder(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.collection_detail_cards, raccolta.totalCards),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (raccolta.totalCards > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.collection_detail_known_progress,
                                        raccolta.knownCount,
                                        raccolta.totalCards,
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onLaunchQuizImportPicker,
                        enabled = !uiState.isQuizLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_import_qzd))
                    }

                    if (linkedPack != null) {
                        LinkedQuizPackCard(
                            pack = linkedPack,
                            enabled = !uiState.isQuizLoading,
                            onDelete = { onRequestDeletePack(linkedPack) },
                        )
                    }

                    HomeBubbleCard(
                        title = stringResource(R.string.collection_study_title),
                        subtitle = stringResource(R.string.collection_study_subtitle),
                        icon = Icons.Outlined.Style,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onOpenStudy(raccolta.uid, raccolta.name) },
                    )

                    HomeBubbleCard(
                        title = stringResource(R.string.collection_quiz_title),
                        subtitle = stringResource(
                            if (linkedPack == null) {
                                R.string.collection_quiz_subtitle_disabled
                            } else {
                                R.string.collection_quiz_subtitle_ready
                            },
                        ),
                        icon = Icons.Outlined.Quiz,
                        accent = MaterialTheme.colorScheme.secondary,
                        enabled = linkedPack != null && !uiState.isQuizLoading,
                        onClick = {
                            if (linkedPack != null) {
                                onStartQuiz(raccolta.uid, raccolta.name, linkedPack)
                            }
                        },
                    )

                    HomeBubbleCard(
                        title = stringResource(R.string.collection_generate_quiz_title),
                        subtitle = stringResource(R.string.collection_generate_quiz_subtitle),
                        icon = Icons.Outlined.AutoAwesome,
                        accent = MaterialTheme.colorScheme.tertiary,
                        onClick = { onOpenGenerateQuizPlaceholder(raccolta.uid, raccolta.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkedQuizPackCard(
    pack: QuizPackSummary,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = themedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
        ),
        border = elevatedCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.secondary,
                border = elevatedCardBorder(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Quiz,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.collection_linked_quiz_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.quiz_pack_meta,
                        pack.cardCount,
                        pack.sectionCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!pack.llmModel.isNullOrBlank()) {
                    Text(
                        text = pack.llmModel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                enabled = enabled,
                onClick = onDelete,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.quiz_pack_delete_content_description),
                )
            }
        }
    }
}

@Composable
private fun CombinedQuizSetupContent(
    uiState: MainUiState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
) {
    val selezionati = uiState.combinedQuizSelection
    val cardTotali = remember(uiState.quizPacks, selezionati) {
        uiState.quizPacks.filter { it.packUid in selezionati }.sumOf { it.cardCount }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.combined_quiz_title),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Text(
            text = stringResource(R.string.combined_quiz_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(uiState.quizPacks, key = { it.packUid }) { pacchetto ->
                val scelto = pacchetto.packUid in selezionati
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = scelto,
                            role = Role.Checkbox,
                            onClick = { onToggle(pacchetto.packUid) },
                        ),
                    shape = RoundedCornerShape(22.dp),
                    colors = themedCardColors(
                        containerColor = if (scelto) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                .compositeOver(MaterialTheme.colorScheme.surface)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        },
                    ),
                    border = elevatedCardBorder(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = scelto, onCheckedChange = { onToggle(pacchetto.packUid) })
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = pacchetto.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(
                                    R.string.quiz_pack_meta,
                                    pacchetto.cardCount,
                                    pacchetto.sectionCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onStart,
            // Serve almeno un pacchetto; con zero il pulsante non deve
            // sembrare disponibile.
            enabled = selezionati.isNotEmpty() && !uiState.isQuizLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Text(
                text = if (selezionati.isEmpty()) {
                    stringResource(R.string.combined_quiz_pick_at_least_one)
                } else {
                    stringResource(R.string.combined_quiz_start, selezionati.size, cardTotali)
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun HomeBubbleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val shape = remember { RoundedCornerShape(30.dp) }
    val bubbleSurface = MaterialTheme.colorScheme.surface
    val bubbleGradientStart = accent.copy(alpha = if (enabled) 0.2f else 0.1f).compositeOver(bubbleSurface)
    val bubbleGradientMiddle = accent.copy(alpha = if (enabled) 0.08f else 0.04f).compositeOver(bubbleSurface)
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(164.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.68f
            }
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        colors = themedCardColors(
            containerColor = Color.Transparent,
        ),
        border = elevatedCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            bubbleGradientStart,
                            bubbleGradientMiddle,
                            bubbleSurface,
                        ),
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = accent.copy(alpha = if (enabled) 0.14f else 0.1f),
                contentColor = accent,
                border = elevatedCardBorder(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(18.dp)
                        .size(34.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = titleColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GenerateQuizContent(
    state: GenerateQuizUiState,
    onBack: () -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onDiscoverPc: () -> Unit,
    onSelectDiscoveredPc: (DiscoveredPc) -> Unit,
    onVerify: () -> Unit,
    onGenerate: () -> Unit,
    onCancelWait: () -> Unit,
    onDismissMessage: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val context = LocalContext.current
    val batteryWarningPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("generation_ui", Context.MODE_PRIVATE)
    }
    var showBatteryWarning by remember {
        mutableStateOf(!batteryWarningPrefs.getBoolean("battery_warning_seen", false))
    }
    val dismissBatteryWarning = {
        batteryWarningPrefs.edit().putBoolean("battery_warning_seen", true).apply()
        showBatteryWarning = false
    }
    val canEditFields = !state.isVerifying && !state.isSubmitting && !state.isWaiting && !state.isImporting
    val canDiscover = !state.isDiscovering && !state.isVerifying && !state.isSubmitting && !state.isWaiting && !state.isImporting
    val canSelectDiscoveredPc = canEditFields && !state.isDiscovering
    val canVerify = canEditFields && state.host.trim().isNotEmpty()
    val canGenerate = when (state.action) {
        GenerateQuizAction.START -> canEditFields && state.verifiedPc != null
        GenerateQuizAction.RESUME_WAIT ->
            !state.currentJobId.isNullOrBlank() && !state.isVerifying && !state.isSubmitting
    }
    val stageLabel = stringResource(
        if (state.isImporting) {
            R.string.generate_quiz_stage_import
        } else {
            generateQuizStageLabelRes(state.stage)
        },
    )
    val progressValue = if (state.isImporting) {
        1f
    } else {
        state.progress.coerceIn(0f, 1f)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            title = {
                Text(
                    text = state.raccoltaName.ifBlank { stringResource(R.string.collection_generate_quiz_title) },
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                IconButton(onClick = onShowSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            },
        )

        MessageBanner(
            message = state.bannerMessage,
            onDismiss = onDismissMessage,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showBatteryWarning) {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = themedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)),
                    border = elevatedCardBorder(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.generate_quiz_battery_warning_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.generate_quiz_battery_warning_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                dismissBatteryWarning()
                                val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                runCatching { context.startActivity(settingsIntent) }.getOrElse {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    })
                                }
                            }) { Text(stringResource(R.string.generate_quiz_battery_settings_action)) }
                            TextButton(onClick = dismissBatteryWarning) {
                                Text(stringResource(R.string.generate_quiz_battery_dismiss_action))
                            }
                        }
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(30.dp),
                colors = themedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
                border = elevatedCardBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.collection_generate_quiz_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.generate_quiz_discovery_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = onDiscoverPc,
                        enabled = canDiscover,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.isDiscovering) {
                                    R.string.generate_quiz_discover_running
                                } else {
                                    R.string.generate_quiz_discover_action
                                },
                            ),
                        )
                    }
                    if (state.isDiscovering) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp)),
                        )
                    }
                    when {
                        state.discoveredPcs.isNotEmpty() -> {
                            Text(
                                text = stringResource(R.string.generate_quiz_discovery_results_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                state.discoveredPcs.forEach { pc ->
                                    val isSelected = state.host.trim() == pc.host &&
                                        state.portText.trim() == pc.port.toString()
                                    val containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f)
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                    }
                                    val titleColor = if (isSelected) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable(
                                                enabled = canSelectDiscoveredPc,
                                                onClick = { onSelectDiscoveredPc(pc) },
                                            ),
                                        shape = RoundedCornerShape(22.dp),
                                        colors = themedCardColors(containerColor = containerColor),
                                        border = elevatedCardBorder(),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = pc.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = titleColor,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.generate_quiz_discovery_pc_subtitle,
                                                    pc.host,
                                                    pc.port,
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        state.hasDiscoveryAttempted && !state.isDiscovering -> {
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                colors = themedCardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
                                ),
                                border = elevatedCardBorder(),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.generate_quiz_discovery_empty_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(R.string.generate_quiz_discovery_empty_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(30.dp),
                colors = themedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
                border = elevatedCardBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.generate_quiz_manual_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.generate_quiz_console_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = onHostChanged,
                        enabled = canEditFields,
                        singleLine = true,
                        label = { Text(stringResource(R.string.generate_quiz_host_label)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        OutlinedTextField(
                            value = state.portText,
                            onValueChange = onPortChanged,
                            enabled = canEditFields,
                            singleLine = true,
                            label = { Text(stringResource(R.string.generate_quiz_port_label)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            modifier = Modifier.weight(0.42f),
                        )
                        OutlinedTextField(
                            value = state.code,
                            onValueChange = onCodeChanged,
                            enabled = canEditFields,
                            singleLine = true,
                            label = { Text(stringResource(R.string.generate_quiz_code_label)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                            ),
                            modifier = Modifier.weight(0.58f),
                        )
                    }
                    FilledTonalButton(
                        onClick = onVerify,
                        enabled = canVerify,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.isVerifying) {
                                    R.string.generate_quiz_verify_running
                                } else {
                                    R.string.generate_quiz_verify_action
                                },
                            ),
                        )
                    }
                }
            }

            state.verifiedPc?.let { pcInfo ->
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = themedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    ),
                    border = elevatedCardBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.generate_quiz_pc_found_title, pcInfo.name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.generate_quiz_pc_found_subtitle, pcInfo.version),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.action == GenerateQuizAction.RESUME_WAIT &&
                !state.currentJobId.isNullOrBlank() &&
                !state.isWaiting &&
                !state.isImporting
            ) {
                Card(
                    shape = RoundedCornerShape(26.dp),
                    colors = themedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                    ),
                    border = elevatedCardBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.generate_quiz_resume_hint),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.statusMessage ?: stageLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (state.isWaiting || state.isImporting) {
                Card(
                    shape = RoundedCornerShape(30.dp),
                    colors = themedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    ),
                    border = elevatedCardBorder(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = stageLabel,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.statusMessage ?: stringResource(R.string.generate_quiz_waiting_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.generate_quiz_wait_keep_open),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium,
                        )
                        LinearProgressIndicator(
                            progress = { progressValue },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                        )
                        Text(
                            text = stringResource(
                                R.string.generate_quiz_progress_percent,
                                (progressValue * 100f).toInt(),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FilledTonalButton(
                            onClick = onCancelWait,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.generate_quiz_cancel_wait))
                        }
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onGenerate,
                    enabled = canGenerate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            when {
                                state.isSubmitting -> R.string.generate_quiz_starting
                                state.action == GenerateQuizAction.RESUME_WAIT -> R.string.generate_quiz_resume_action
                                else -> R.string.generate_quiz_start_action
                            },
                        ),
                    )
                }
            }
        }
    }
}

@StringRes
private fun generateQuizStageLabelRes(stage: String?): Int = when (stage) {
    "ingest" -> R.string.generate_quiz_stage_ingest
    "normalize" -> R.string.generate_quiz_stage_normalize
    "classify" -> R.string.generate_quiz_stage_classify
    "index" -> R.string.generate_quiz_stage_index
    "generate" -> R.string.generate_quiz_stage_generate
    "validate" -> R.string.generate_quiz_stage_validate
    "export" -> R.string.generate_quiz_stage_export
    else -> R.string.generate_quiz_stage_waiting
}

@Composable
private fun PlaceholderContent(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    icon: ImageVector,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(titleRes),
                    fontWeight = FontWeight.SemiBold,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = RoundedCornerShape(32.dp),
                colors = themedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                ),
                border = elevatedCardBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.tertiary,
                        border = elevatedCardBorder(),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(18.dp)
                                .size(32.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.placeholder_coming_soon),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyContent(
    uiState: MainUiState,
    raccoltaUid: String,
    raccoltaName: String,
    onBack: () -> Unit,
    onDismissMessage: () -> Unit,
    onReview: (String, Grade, Long) -> Unit,
    onResetPass: () -> Unit,
    onShowSettings: () -> Unit,
) {
    val study = uiState.study
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = raccoltaName,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.subtitle_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                if (study.totalCards > 0) {
                    TextButton(onClick = onResetPass) {
                        Text(stringResource(R.string.action_reset))
                    }
                }
                IconButton(onClick = onShowSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            },
        )

        MessageBanner(
            message = uiState.message,
            onDismiss = onDismissMessage,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        if (study.totalCards > 0) {
            ProgressHeader(
                knownCount = study.knownCount,
                totalCards = study.totalCards,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val currentCard = if (study.raccoltaUid == raccoltaUid) study.currentCard else null
            if (currentCard == null) {
                when {
                    study.isLoading -> {
                        Text(
                            text = stringResource(R.string.loading),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                    }

                    study.totalCards == 0 -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.study_empty_collection),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = onBack) {
                                Text(stringResource(R.string.study_empty_action))
                            }
                        }
                    }

                    study.isDeckComplete -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.deck_complete_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = onResetPass) {
                                Text(stringResource(R.string.deck_complete_action))
                            }
                        }
                    }

                    else -> {
                        Text(
                            text = stringResource(R.string.no_cards_available),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                SwipeReviewCard(
                    card = currentCard,
                    onReview = { grade, elapsedMs -> onReview(currentCard.uid, grade, elapsedMs) },
                )
            }
        }
    }
}

@Composable
private fun RaccoltaCard(
    raccolta: RaccoltaSummary,
    isHighlighted: Boolean,
    hasLinkedQuiz: Boolean,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        colors = themedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
        ),
        border = if (isHighlighted) {
            androidx.compose.foundation.BorderStroke(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            elevatedCardBorder()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isHighlighted) 8.dp else 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = raccolta.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.cards_count, raccolta.totalCards),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (raccolta.knownCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        border = elevatedCardBorder(),
                    ) {
                        Text(
                            text = stringResource(R.string.known_count_short, raccolta.knownCount),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (hasLinkedQuiz) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.secondary,
                        border = elevatedCardBorder(),
                    ) {
                        Text(
                            text = stringResource(R.string.collection_quiz_title),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: UiMessage?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AnimatedVisibility(visible = message != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            border = elevatedCardBorder(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = message?.resolve(context).orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun ImportDeckDialog(
    pendingImport: PendingImportUiState,
    raccolte: List<RaccoltaSummary>,
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?) -> Unit,
) {
    val dialogKey = pendingImport.uri.toString()
    var sectionName by rememberSaveable(dialogKey) {
        mutableStateOf(pendingImport.preview.suggestedSectionName)
    }
    val hasExistingCollections = raccolte.isNotEmpty()
    var useNewCollection by rememberSaveable(dialogKey) {
        mutableStateOf(!hasExistingCollections)
    }
    var selectedExistingRaccoltaUid by rememberSaveable(dialogKey) {
        mutableStateOf(pendingImport.preferredExistingRaccoltaUid)
    }
    var newCollectionName by rememberSaveable(dialogKey) {
        mutableStateOf(pendingImport.preview.suggestedSectionName)
    }
    val canImport = !isImporting && (!useNewCollection || newCollectionName.trim().isNotEmpty())

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.import_dialog_title, pendingImport.displayName),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.import_dialog_detected_cards,
                        pendingImport.preview.detectedCardCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = sectionName,
                    onValueChange = { sectionName = it },
                    enabled = !isImporting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_section_name)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.import_collection_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    raccolte.forEach { raccolta ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .selectable(
                                    selected = !useNewCollection && selectedExistingRaccoltaUid == raccolta.uid,
                                    role = Role.RadioButton,
                                    enabled = !isImporting,
                                    onClick = {
                                        useNewCollection = false
                                        selectedExistingRaccoltaUid = raccolta.uid
                                    },
                                )
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = !useNewCollection && selectedExistingRaccoltaUid == raccolta.uid,
                                onClick = null,
                                enabled = !isImporting,
                            )
                            Column {
                                Text(
                                    text = raccolta.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.cards_count, raccolta.totalCards),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .selectable(
                                selected = useNewCollection,
                                role = Role.RadioButton,
                                enabled = !isImporting,
                                onClick = { useNewCollection = true },
                            )
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = useNewCollection,
                            onClick = null,
                            enabled = !isImporting,
                        )
                        Text(
                            text = stringResource(R.string.import_new_collection),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                if (useNewCollection) {
                    OutlinedTextField(
                        value = newCollectionName,
                        onValueChange = { newCollectionName = it },
                        enabled = !isImporting,
                        singleLine = true,
                        label = { Text(stringResource(R.string.import_new_collection_name)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canImport,
                onClick = {
                    onConfirm(
                        sectionName,
                        if (useNewCollection) null else selectedExistingRaccoltaUid,
                        if (useNewCollection) newCollectionName else null,
                    )
                },
            ) {
                Text(
                    stringResource(
                        if (isImporting) {
                            R.string.action_importing
                        } else {
                            R.string.import_dialog_confirm
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isImporting,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.import_dialog_cancel))
            }
        },
    )
}

@Composable
private fun SectionsDialog(
    dialog: SectionsDialogUiState,
    isRemoving: Boolean,
    onDismiss: () -> Unit,
    onRemoveSection: (SezioneInfo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.sections_dialog_title, dialog.raccoltaName))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (dialog.sections.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sections_dialog_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    dialog.sections.forEach { section ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            border = elevatedCardBorder(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = section.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = buildString {
                                            append(
                                                stringResource(
                                                    R.string.cards_count,
                                                    section.cardCount,
                                                ),
                                            )
                                            if (!section.srcName.isNullOrBlank()) {
                                                append(" - ")
                                                append(section.srcName)
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    enabled = !isRemoving,
                                    onClick = { onRemoveSection(section) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.action_remove_section),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun RemoveSectionDialog(
    pending: RemoveSectionUiState,
    isRemoving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRemoving) onDismiss() },
        title = {
            Text(stringResource(R.string.remove_section_title, pending.section.name))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.remove_section_message,
                    pending.section.name,
                    pending.raccoltaName,
                ),
            )
        },
        confirmButton = {
            Button(
                enabled = !isRemoving,
                onClick = onConfirm,
            ) {
                Text(
                    stringResource(
                        if (isRemoving) R.string.action_removing else R.string.action_remove,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isRemoving,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.import_dialog_cancel))
            }
        },
    )
}

@Composable
private fun RemoveQuizPackDialog(
    pending: QuizPackSummary,
    isRemoving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRemoving) onDismiss() },
        title = {
            Text(stringResource(R.string.quiz_delete_pack_title, pending.name))
        },
        text = {
            Text(stringResource(R.string.quiz_delete_pack_message, pending.name))
        },
        confirmButton = {
            Button(
                enabled = !isRemoving,
                onClick = onConfirm,
            ) {
                Text(
                    stringResource(
                        if (isRemoving) R.string.action_removing else R.string.action_remove,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isRemoving,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.import_dialog_cancel))
            }
        },
    )
}

@Composable
private fun ProgressHeader(
    knownCount: Int,
    totalCards: Int,
    modifier: Modifier = Modifier,
) {
    val progress = if (totalCards == 0) 0f else knownCount.toFloat() / totalCards.toFloat()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$knownCount / $totalCards",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
        )
    }
}

@Composable
private fun LanguageSettingsDialog(
    selectedOption: AppLanguageOption,
    onDismiss: () -> Unit,
    onSelect: (AppLanguageOption) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguageOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .selectable(
                                selected = option == selectedOption,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == selectedOption,
                            onClick = null,
                        )
                        Text(
                            text = stringResource(option.labelResId),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun SwipeReviewCard(
    card: ReviewCard,
    onReview: (Grade, Long) -> Unit,
) {
    var showBack by rememberSaveable(card.uid) { mutableStateOf(false) }
    var isPressed by remember(card.uid) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    // Stato semplice, non Animatable: durante il drag va scritto dall'interno
    // del gesture-handler, dove non si puo' sospendere. Le animazioni (uscita
    // e ritorno elastico) girano a parte, in `animation`.
    var offsetX by remember(card.uid) { mutableFloatStateOf(0f) }
    var animation by remember(card.uid) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val reviewStartedAt = remember(card.uid) { SystemClock.elapsedRealtime() }
    val scrollState = rememberScrollState()
    val cardShape = remember { RoundedCornerShape(32.dp) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "card-press",
    )
    val flipRotation by animateFloatAsState(
        targetValue = if (showBack) 180f else 0f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "card-flip",
    )
    val currentShowBack = rememberUpdatedState(showBack)
    val currentFlipRotation = rememberUpdatedState(flipRotation)
    val currentOnReview = rememberUpdatedState(onReview)
    val showingBackFace = flipRotation > 90f

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val cardHeight = (maxHeight * 0.55f).coerceIn(280.dp, 520.dp)
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val threshold = widthPx * 0.35f
        val canGrade = flipRotation <= 1f || flipRotation >= 179f
        val dragTint = when {
            offsetX > 0f -> Color(0xFFB44D4D)
            offsetX < 0f -> Color(0xFF2E7D5B)
            else -> Color.Transparent
        }
        val dragProgress = if (canGrade) {
            (abs(offsetX) / threshold).coerceIn(0f, 1f)
        } else {
            0f
        }
        val dragAlpha = if (canGrade) 0.48f * dragProgress else 0f

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .graphicsLayer {
                    translationX = offsetX
                    rotationY = flipRotation
                    rotationZ = if (canGrade) (offsetX / widthPx) * 4f else 0f
                    scaleX = pressScale
                    scaleY = pressScale
                    cameraDistance = 12f * density
                }
                .clip(cardShape)
                .pointerInput(card.uid) {
                    // UN SOLO awaitEachGesture per tutto il gesto: l'handler dei
                    // puntatori resta installato dal down all'up. La versione
                    // precedente entrava e usciva da awaitPointerEventScope a ogni
                    // evento, e fra un'uscita e la successiva rientrata gli eventi
                    // venivano persi -- compreso l'UP finale, da cui la card che
                    // restava incollata al dito. Con lo swipe sintetico non si
                    // vedeva (pochi eventi), col dito vero si'.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        animation?.cancel()
                        animation = null
                        isPressed = true
                        var pointerId = down.id
                        var totalDx = 0f
                        var totalDy = 0f
                        var settled = false

                        fun commit(grade: Grade, flyTo: Float) {
                            val elapsedMs = SystemClock.elapsedRealtime() - reviewStartedAt
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnReview.value(grade, elapsedMs)
                            val from = offsetX
                            animation = scope.launch {
                                animate(from, flyTo, animationSpec = tween(durationMillis = 160)) { v, _ ->
                                    offsetX = v
                                }
                            }
                        }

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            var change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null) {
                                // Il puntatore che seguivamo e' sparito. Se il sistema
                                // ne ha aperto un altro ancora premuto (MIUI lo fa
                                // quando rivaluta i gesti di navigazione), lo adottiamo
                                // e proseguiamo lo stesso gesto invece di buttarlo.
                                val adopted = event.changes.firstOrNull { it.pressed }
                                if (adopted == null) {
                                    break
                                }
                                pointerId = adopted.id
                                change = adopted
                            }

                            val rotation = currentFlipRotation.value
                            val canGradeNow = rotation <= 1f || rotation >= 179f

                            if (change.changedToUpIgnoreConsumed()) {
                                if (canGradeNow) change.consume()
                                val isTap = abs(totalDx) < viewConfiguration.touchSlop &&
                                    abs(totalDy) < viewConfiguration.touchSlop
                                if (canGradeNow) {
                                    when {
                                        isTap -> {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showBack = !currentShowBack.value
                                            offsetX = 0f
                                            settled = true
                                        }

                                        offsetX >= threshold -> {
                                            settled = true
                                            commit(Grade.AGAIN, widthPx * 1.1f)
                                        }

                                        offsetX <= -threshold -> {
                                            settled = true
                                            commit(Grade.GOOD, -widthPx * 1.1f)
                                        }
                                    }
                                }
                                break
                            }

                            if (canGradeNow) {
                                val delta = change.positionChange()
                                change.consume()
                                totalDx += if (rotation > 90f) -delta.x else delta.x
                                totalDy += delta.y
                                offsetX = totalDx
                            }
                        }

                        isPressed = false
                        if (!settled && offsetX != 0f) {
                            val from = offsetX
                            animation = scope.launch {
                                animate(from, 0f, animationSpec = spring()) { v, _ -> offsetX = v }
                            }
                        }
                    }
                },
            shape = cardShape,
            colors = themedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ),
            border = elevatedCardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationY = if (showingBackFace) 180f else 0f
                        }
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!card.sourceDeck.isNullOrBlank()) {
                        Text(
                            text = card.sourceDeck,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                    HtmlText(
                        html = if (showingBackFace) card.back else card.front,
                        modifier = Modifier.fillMaxWidth(),
                        style = if (showingBackFace) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        textAlign = TextAlign.Center,
                    )
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(dragTint.copy(alpha = dragAlpha)),
                )

                if (canGrade && dragProgress > 0f) {
                    val badgeLabel = stringResource(
                        if (offsetX > 0f) R.string.grade_again else R.string.grade_good,
                    )
                    Text(
                        text = if (offsetX > 0f) "\u2717 $badgeLabel" else "\u2713 $badgeLabel",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                alpha = dragProgress
                                val scale = 0.92f + (dragProgress * 0.18f)
                                scaleX = scale
                                scaleY = scale
                                rotationY = if (showingBackFace) 180f else 0f
                            }
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                MaterialTheme.colorScheme.scrim.copy(
                                    alpha = 0.18f + (dragProgress * 0.16f),
                                ),
                            )
                            .padding(horizontal = 22.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    )
                }
            }
        }

        LaunchedEffect(card.uid) {
            offsetX = 0f
            showBack = false
            isPressed = false
        }

        LaunchedEffect(card.uid, showingBackFace) {
            scrollState.scrollTo(0)
        }

        AnimatedVisibility(
            visible = !showingBackFace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.flip_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
    }
}

private fun Uri.displayName(context: Context): String? {
    if (scheme != "content") {
        return lastPathSegment
    }
    return context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(0)
            }
        } ?: lastPathSegment
}
