package com.local.spacedcards.ui

import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.isUnspecified
import androidx.core.text.HtmlCompat

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    textAlign: TextAlign = TextAlign.Start,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    // style.fontSize e' gia' un TextUnit in sp (i Typography di Material3
    // sono definiti in sp): .value da' direttamente il numero, non serve
    // passare da Dp/Density.
    val fontSize = if (style.fontSize.isUnspecified) 18f else style.fontSize.value

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                // NIENTE LinkMovementMethod: intercetta ogni tocco sulla
                // TextView (non solo quelli su un link) prima che possa
                // risalire al .clickable del Card che la contiene, che e'
                // il modo in cui la card flippa al tocco. Le card Anki sono
                // testo di domanda/risposta, non pagine web: un <a href>
                // vero e proprio dentro il contenuto e' un caso limite che
                // qui si sacrifica deliberatamente al tap-per-girare.
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                gravity = when (textAlign) {
                    TextAlign.Center -> Gravity.CENTER
                    TextAlign.End, TextAlign.Right -> Gravity.END
                    else -> Gravity.START
                }
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
            textView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        },
    )
}
