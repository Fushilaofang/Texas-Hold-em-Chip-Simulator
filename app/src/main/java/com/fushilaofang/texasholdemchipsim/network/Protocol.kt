package com.fushilaofang.texasholdemchipsim.network

import com.fushilaofang.texasholdemchipsim.blinds.BlindsState
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
        val contributions: Map<String, Int> = emptyMap(),
        val blindsState: BlindsState = BlindsState(),
        val blindsEnabled: Boolean = true,
        val gameStarted: Boolean = false
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

    /**
     * 玩家切换准备状态（客户端 → 服务端）
     */
    @Serializable
    @SerialName("ready_toggle")
    data class ReadyToggle(
        val playerId: String,
        val isReady: Boolean
    ) : NetworkMessage()

    @Serializable
    @SerialName("error")
    data class Error(
        val reason: String
    ) : NetworkMessage()

    /** 心跳包（双向） */
    @Serializable
    @SerialName("ping")
    data object Ping : NetworkMessage()

    @Serializable
    @SerialName("pong")
    data object Pong : NetworkMessage()

    /** 掉线重连请求（客户端 → 服务端，携带旧playerId） */
    @Serializable
    @SerialName("reconnect")
    data class Reconnect(
        val playerId: String,
        val playerName: String
    ) : NetworkMessage()

    /** 重连成功响应 */
    @Serializable
    @SerialName("reconnect_accepted")
    data class ReconnectAccepted(
        val playerId: String
    ) : NetworkMessage()

    /** 房主主动踢出玩家（服务端 → 被踢客户端） */
    @Serializable
    @SerialName("kicked")
    data object Kicked : NetworkMessage()
}
