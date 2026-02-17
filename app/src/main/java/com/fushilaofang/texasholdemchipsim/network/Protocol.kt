package com.fushilaofang.texasholdemchipsim.network

import com.fushilaofang.texasholdemchipsim.model.ChipTransaction
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class NetworkMessage {
    @Serializable
    @SerialName("join_request")
    data class JoinRequest(
        val playerName: String,
        val buyIn: Int
    ) : NetworkMessage()

    @Serializable
    @SerialName("join_accepted")
    data class JoinAccepted(
        val assignedPlayerId: String
    ) : NetworkMessage()

    @Serializable
    @SerialName("state_sync")
    data class StateSync(
        val players: List<PlayerState>,
        val handCounter: Int,
        val transactions: List<ChipTransaction>
    ) : NetworkMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val reason: String
    ) : NetworkMessage()
}
