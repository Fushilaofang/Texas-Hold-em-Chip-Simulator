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
        val transactions: List<ChipTransaction>,
        val contributions: Map<String, Int> = emptyMap()
    ) : NetworkMessage()

    /**
     * 玩家提交本手投入（客户端 → 服务端）
     */
    @Serializable
    @SerialName("submit_contribution")
    data class SubmitContribution(
        val playerId: String,
        val amount: Int
    ) : NetworkMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val reason: String
    ) : NetworkMessage()
}
