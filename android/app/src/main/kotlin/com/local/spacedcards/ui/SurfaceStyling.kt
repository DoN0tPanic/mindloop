package com.local.spacedcards.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

/**
 * Colori per una card, con il fondo reso sempre OPACO.
 *
 * Un contenitore semitrasparente sopra una card con elevazione lascia
 * trasparire l'ombra che sta sotto: sullo schermo compare una banda piu'
 * chiara dentro la card, dietro al testo, che sembra un difetto di disegno.
 * Si schiaccia qui il colore sul fondo della schermata una volta per tutte,
 * cosi' chi chiama puo' continuare a ragionare in termini di trasparenza
 * senza portarsi dietro l'artefatto.
 */
@Composable
internal fun themedCardColors(
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
): CardColors = CardDefaults.cardColors(
    containerColor = containerColor.compositeOver(MaterialTheme.colorScheme.background),
    contentColor = contentColor,
)

@Composable
internal fun elevatedCardBorder(): BorderStroke = BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(
        alpha = if (isSystemInDarkTheme()) 0.72f else 0.24f,
    ),
)
