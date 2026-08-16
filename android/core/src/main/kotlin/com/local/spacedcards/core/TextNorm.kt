package com.local.spacedcards.core

import java.text.Normalizer

/**
 * Porta Kotlin di baker/baker/textnorm.py (solo hnorm: e' l'unica funzione
 * che deve vivere sul telefono, PLAN.md par. 6.1).
 *
 * hnorm() e' CONGELATA -- PLAN.md vincolo C8. Entra nel calcolo del card uid
 * (vedi Uid.kt) e deve produrre lo stesso identico output della sua
 * controparte Python su qualunque input. Non modificarla senza aggiornare
 * baker/baker/textnorm.py in lockstep e cambiare UID_RECIPE_VERSION.
 *
 * Verificata contro baker/tests/test_m0.py::TestUidFrozen e TestHnorm.
 *
 * Scope noto della unescape HTML: copre per intero i riferimenti numerici
 * (&#NNN; e &#xHH;) piu' l'insieme comune delle entita' HTML4/XHTML con
 * nome (elencate sotto). Non copre l'intera tavola HTML5 (~2000 voci): una
 * entita' rara non in elenco resta invariata (con '&' e ';' letterali) sia
 * qui sia in Python, quindi non e' un rischio di divergenza.
 */
object TextNorm {

    private val WS_CODEPOINTS = intArrayOf(
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0,
        0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029,
        0x202F, 0x205F, 0x3000,
    )
    private val ZW_CODEPOINTS = intArrayOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)

    private fun charClass(codepoints: IntArray): String =
        codepoints.joinToString("") { String.format("\\u%04X", it) }

    private val wsRegex = Regex("[" + charClass(WS_CODEPOINTS) + "]+")
    private val zwRegex = Regex("[" + charClass(ZW_CODEPOINTS) + "]")
    private val tagRegex = Regex("<[^>]*>")
    private val entityRegex = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

    private val NAMED_ENTITIES: Map<String, Char> = mapOf(
        "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'',
        "nbsp" to '\u00A0', "copy" to '\u00A9', "reg" to '\u00AE', "trade" to '\u2122',
        "hellip" to '\u2026', "mdash" to '\u2014', "ndash" to '\u2013',
        "lsquo" to '\u2018', "rsquo" to '\u2019', "ldquo" to '\u201C', "rdquo" to '\u201D',
        "sbquo" to '\u201A', "bdquo" to '\u201E', "middot" to '\u00B7', "bull" to '\u2022',
        "deg" to '\u00B0', "plusmn" to '\u00B1', "times" to '\u00D7', "divide" to '\u00F7',
        "frac12" to '\u00BD', "frac14" to '\u00BC', "frac34" to '\u00BE',
        "sup1" to '\u00B9', "sup2" to '\u00B2', "sup3" to '\u00B3',
        "micro" to '\u00B5', "para" to '\u00B6', "sect" to '\u00A7',
        "laquo" to '\u00AB', "raquo" to '\u00BB', "iexcl" to '\u00A1', "iquest" to '\u00BF',
        "euro" to '\u20AC', "pound" to '\u00A3', "cent" to '\u00A2', "yen" to '\u00A5',
        "alpha" to '\u03B1', "beta" to '\u03B2', "gamma" to '\u03B3', "delta" to '\u03B4',
        "pi" to '\u03C0', "sigma" to '\u03C3', "omega" to '\u03C9',
        "larr" to '\u2190', "uarr" to '\u2191', "rarr" to '\u2192', "darr" to '\u2193',
        "aacute" to '\u00E1', "Aacute" to '\u00C1', "eacute" to '\u00E9', "Eacute" to '\u00C9',
        "iacute" to '\u00ED', "oacute" to '\u00F3', "uacute" to '\u00FA',
        "ntilde" to '\u00F1', "Ntilde" to '\u00D1',
        "agrave" to '\u00E0', "egrave" to '\u00E8', "igrave" to '\u00EC',
        "ograve" to '\u00F2', "ugrave" to '\u00F9',
        "acirc" to '\u00E2', "ecirc" to '\u00EA', "icirc" to '\u00EE',
        "ocirc" to '\u00F4', "ucirc" to '\u00FB',
        "auml" to '\u00E4', "Auml" to '\u00C4', "euml" to '\u00EB', "iuml" to '\u00EF',
        "ouml" to '\u00F6', "Ouml" to '\u00D6', "uuml" to '\u00FC', "Uuml" to '\u00DC',
        "szlig" to '\u00DF', "ccedil" to '\u00E7',
    )

    private fun unescapeHtml(s: String): String =
        entityRegex.replace(s) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)
                        ?.takeIf { it in 0..0x10FFFF }
                        ?.let { String(Character.toChars(it)) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()
                        ?.takeIf { it in 0..0x10FFFF }
                        ?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED_ENTITIES[body]?.toString() ?: m.value
            }
        }

    fun stripHtml(s: String): String = tagRegex.replace(s, " ")

    /** CONGELATA. Leggi il commento in testa al file prima di toccarla. */
    fun hnorm(s: String): String {
        var out = stripHtml(s)
        out = unescapeHtml(out)
        out = zwRegex.replace(out, "")
        out = Normalizer.normalize(out, Normalizer.Form.NFC)
        out = out.lowercase()
        out = wsRegex.replace(out, " ")
        return out.trim(' ')
    }
}
