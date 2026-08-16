@file:OptIn(ExperimentalMaterial3Api::class)

package com.local.spacedcards.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.local.spacedcards.R
import com.local.spacedcards.core.QuizQuestion

private const val NoSelection = -1

@Composable
fun QuizScreen(
    title: String,
    questions: List<QuizQuestion>,
    onFinished: (correct: Int, total: Int) -> Unit,
    onExit: () -> Unit,
) {
    val sessionKey = remember(title, questions) {
        buildString {
            append(title)
            append('|')
            questions.forEach { question ->
                append(question.cardUid)
                append(';')
            }
        }
    }
    var currentIndex by rememberSaveable(sessionKey) { mutableIntStateOf(0) }
    var selectedOptionIndex by rememberSaveable(sessionKey) { mutableIntStateOf(NoSelection) }
    var correctCount by rememberSaveable(sessionKey) { mutableIntStateOf(0) }
    var showSummary by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var errori by remember(sessionKey) { mutableStateOf(emptyList<RispostaSbagliata>()) }

    BackHandler(onBack = onExit)

    val colorScheme = MaterialTheme.colorScheme
    val screenGradientMiddle = colorScheme.surface.copy(alpha = 0.97f).compositeOver(colorScheme.background)
    val screenGradientEnd = colorScheme.primary.copy(alpha = 0.08f).compositeOver(colorScheme.background)

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
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    title = {
                        Text(
                            text = title,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { innerPadding ->
            when {
                questions.isEmpty() -> {
                    QuizEmptyState(
                        onExit = onExit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }

                showSummary -> {
                    QuizSummaryState(
                        correctCount = correctCount,
                        total = questions.size,
                        errori = errori,
                        onExit = onExit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }

                else -> {
                    val question = questions[currentIndex]
                    QuizQuestionState(
                        question = question,
                        questionIndex = currentIndex + 1,
                        totalQuestions = questions.size,
                        selectedOptionIndex = selectedOptionIndex,
                        onOptionSelected = { optionIndex ->
                            selectedOptionIndex = optionIndex
                            if (optionIndex == question.correctIndex) {
                                correctCount += 1
                            } else {
                                // Si registra l'errore mentre accade: alla fine
                                // sapere solo "18 su 25" non dice su cosa
                                // tornare, ed e' proprio quello che serve a chi
                                // sta studiando.
                                errori = errori + RispostaSbagliata(
                                    domanda = question.prompt,
                                    scelta = question.options[optionIndex],
                                    corretta = question.correctOption,
                                )
                            }
                        },
                        onAdvance = {
                            if (currentIndex == questions.lastIndex) {
                                onFinished(correctCount, questions.size)
                                showSummary = true
                            } else {
                                currentIndex += 1
                                selectedOptionIndex = NoSelection
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizQuestionState(
    question: QuizQuestion,
    questionIndex: Int,
    totalQuestions: Int,
    selectedOptionIndex: Int,
    onOptionSelected: (Int) -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = selectedOptionIndex != NoSelection
    val progress = questionIndex.toFloat() / totalQuestions.toFloat()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = themedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ),
            border = elevatedCardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$questionIndex / $totalQuestions",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
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

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            colors = themedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ),
            border = elevatedCardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Text(
                text = question.prompt,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            question.options.forEachIndexed { index, option ->
                QuizOptionCard(
                    text = option,
                    isEnabled = !hasSelection,
                    state = optionState(
                        optionIndex = index,
                        selectedOptionIndex = selectedOptionIndex,
                        correctIndex = question.correctIndex,
                    ),
                    onClick = { onOptionSelected(index) },
                )
            }
        }

        if (hasSelection) {
            Button(
                onClick = onAdvance,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(26.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(
                        if (questionIndex == totalQuestions) {
                            R.string.quiz_action_finish
                        } else {
                            R.string.quiz_action_next
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun QuizOptionCard(
    text: String,
    state: OptionState,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor by animateColorAsState(
        targetValue = state.containerColor,
        label = "quiz-option-background",
    )
    val borderColor by animateColorAsState(
        targetValue = state.borderColor,
        label = "quiz-option-border",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled, onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = themedCardColors(containerColor = backgroundColor),
        // Un bordo solo. Prima ce n'erano due sovrapposti, uno disegnato dal
        // modifier fuori dal ritaglio della card e uno dalla card stessa: agli
        // angoli arrotondati si vedevano sdoppiati.
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (state.isHighlighted) 8.dp else 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = if (state.isDimmed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** Una risposta sbagliata, con cosa si e' scelto e cosa era giusto. */
private data class RispostaSbagliata(
    val domanda: String,
    val scelta: String,
    val corretta: String,
)

@Composable
private fun QuizSummaryState(
    correctCount: Int,
    total: Int,
    errori: List<RispostaSbagliata>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // La percentuale e' cio' che si guarda per primo per capire com'e' andata,
    // ma da sola non insegna niente: sotto ci sono gli errori, con la risposta
    // scelta accanto a quella giusta, perche' e' li' che si torna a studiare.
    val percentuale = if (total > 0) correctCount * 100 / total else 0

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                colors = themedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
                border = elevatedCardBorder(),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.quiz_summary_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "$percentuale%",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.quiz_summary_score, correctCount, total),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (errori.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.quiz_summary_all_correct),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.quiz_summary_mistakes_title, errori.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(errori) { errore ->
                CardErrore(errore)
            }
        }

        item {
            Button(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                shape = RoundedCornerShape(26.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.quiz_action_exit),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun CardErrore(errore: RispostaSbagliata) {
    val verde = Color(0xFF2E7D5B)
    val rosso = Color(0xFFB44D4D)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = themedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
        border = elevatedCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = errore.domanda,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            RigaEsito(
                etichetta = stringResource(R.string.quiz_summary_your_answer),
                testo = errore.scelta,
                colore = rosso,
            )
            RigaEsito(
                etichetta = stringResource(R.string.quiz_summary_correct_answer),
                testo = errore.corretta,
                colore = verde,
            )
        }
    }
}

@Composable
private fun RigaEsito(etichetta: String, testo: String, colore: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = etichetta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = testo, style = MaterialTheme.typography.bodyMedium, color = colore)
    }
}

@Composable
private fun QuizEmptyState(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            colors = themedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ),
            border = elevatedCardBorder(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.quiz_empty_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.quiz_empty_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Button(
            onClick = onExit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.quiz_empty_action),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun optionState(
    optionIndex: Int,
    selectedOptionIndex: Int,
    correctIndex: Int,
): OptionState {
    val colorScheme = MaterialTheme.colorScheme
    val neutralContainer = colorScheme.surface.copy(alpha = 0.94f)
    val neutralBorder = colorScheme.outline.copy(alpha = 0.18f)
    val success = Color(0xFF2E7D5B)
    val error = Color(0xFFB44D4D)

    return when {
        selectedOptionIndex == NoSelection -> OptionState(
            containerColor = neutralContainer,
            borderColor = neutralBorder,
            isHighlighted = false,
        )

        optionIndex == correctIndex -> OptionState(
            containerColor = success.copy(alpha = 0.18f).compositeOver(colorScheme.surface),
            borderColor = success.copy(alpha = 0.55f),
            isHighlighted = true,
        )

        optionIndex == selectedOptionIndex -> OptionState(
            containerColor = error.copy(alpha = 0.18f).compositeOver(colorScheme.surface),
            borderColor = error.copy(alpha = 0.55f),
            isHighlighted = true,
        )

        // Le opzioni rimaste fuori dopo la risposta: devono farsi da parte,
        // non sporcarsi. surfaceVariant con alpha dava un grigio-fango che
        // sembrava un errore di disegno; qui restano dello stesso colore delle
        // altre e si attenua solo il testo, che e' come si dice "questa non
        // conta piu'" senza inventare un colore nuovo.
        else -> OptionState(
            containerColor = neutralContainer,
            borderColor = neutralBorder,
            isHighlighted = false,
            isDimmed = true,
        )
    }
}

private data class OptionState(
    val containerColor: Color,
    val borderColor: Color,
    val isHighlighted: Boolean,
    val isDimmed: Boolean = false,
)
