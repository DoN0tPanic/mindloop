package com.local.spacedcards.core

import java.security.MessageDigest

/**
 * L'unica identita' che trasporta valore nel sistema (PLAN.md C8, C11).
 * Porta Kotlin esatta di baker/baker/uid.py.
 *
 *     uid = base32( sha256("v1|" + hnorm(front)) )[:26], minuscolo
 *
 * CONGELATA. Verificata contro i valori d'oro di
 * baker/tests/test_m0.py::TestUidFrozen. Se i test sotto non passano, il
 * bug e' qui (o in TextNorm.kt) -- il valore atteso e' l'autorita', prodotto
 * dal baker Python, non va cambiato per far passare il test.
 */
object Uid {
    private const val RECIPE_VERSION = "v1"
    private const val UID_LEN = 26
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET[(value shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET[(value shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }

    fun cardUid(front: String): String {
        val payload = "$RECIPE_VERSION|${TextNorm.hnorm(front)}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return base32Encode(digest).lowercase().take(UID_LEN)
    }

    /** Identita' di raccolte/sezioni: casuale, NON derivata (C11, bassa posta in gioco). */
    fun containerUid(): String = java.util.UUID.randomUUID().toString().replace("-", "")
}
