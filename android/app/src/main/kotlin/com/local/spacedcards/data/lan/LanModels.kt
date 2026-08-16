package com.local.spacedcards.data.lan

const val DEFAULT_LAN_PORT: Int = 8765

data class PcInfo(
    val name: String,
    val version: String,
)

data class DiscoveredPc(
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
)

data class LanCard(
    val uid: String,
    val front: String,
    val back: String,
)

data class BakeStatus(
    val state: String,
    val stage: String?,
    val progress: Float,
    val message: String?,
    val error: String?,
    val uidMismatchCount: Int,
)

data class LanConnectionSettings(
    val host: String = "",
    val port: Int = DEFAULT_LAN_PORT,
    val code: String = "",
)

sealed class LanError(
    override val message: String,
) : Exception(message) {
    data class InvalidAddress(
        val detail: String,
    ) : LanError(detail)

    data class NotReachable(
        val host: String,
        val port: Int,
    ) : LanError(
        "Could not reach $host:$port on the local network. Check the address, port, and that the PC server is running.",
    )

    data class WrongApp(
        val foundApp: String?,
    ) : LanError(
        if (foundApp.isNullOrBlank()) {
            "This address replied, but it is not a Mindloop Baker server."
        } else {
            "This address replied with \"$foundApp\", not Mindloop Baker."
        },
    )

    data class BadCode(
        val detail: String = "The pairing code is not valid. Check the 6-digit code shown on the PC.",
    ) : LanError(detail)

    data class ServerError(
        val detail: String,
    ) : LanError(detail)

    data object JobNotFound : LanError("The PC no longer knows this generation job. Start it again.")

    data object NotReady : LanError("The quiz file is not ready yet.")

    data object Timeout : LanError(
        "The PC did not respond in time. Check that it is on, connected to the same network, and still running the server.",
    )
}
