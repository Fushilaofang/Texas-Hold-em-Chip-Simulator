package com.fushilaofang.texasholdemchipsim.blinds

import com.fushilaofang.texasholdemchipsim.model.PlayerState
import kotlinx.serialization.Serializable

/**
 * 盲注配置
 */
@Serializable
data class BlindsConfig(
    val smallBlind: Int = 10,
    val bigBlind: Int = 20
)

/**
 * 盲注状态快照：当前庄位、大小盲位置索引
 */
@Serializable
data class BlindsState(
    val dealerIndex: Int = 0,
    val smallBlindIndex: Int = 1,
    val bigBlindIndex: Int = 2,
    val config: BlindsConfig = BlindsConfig()
)

/**
 * 盲注管理器：负责庄位轮转与自动扣除盲注
 */
class BlindsManager {

    /**
     * 初始化盲注状态（第一手开始前）
     */
    fun initialize(playerCount: Int, config: BlindsConfig): BlindsState {
        return computePositions(dealerIndex = 0, playerCount = playerCount, config = config)
    }

    /**
     * 庄位前移一位，重新计算大小盲位置
     */
    fun rotate(current: BlindsState, playerCount: Int): BlindsState {
        if (playerCount < 2) return current
        val nextDealer = (current.dealerIndex + 1) % playerCount
        return computePositions(nextDealer, playerCount, current.config)
    }

    /**
     * 根据庄位计算 SB / BB 位置
     * - 2 人局：庄家 = 小盲，另一人 = 大盲
     * - 3+ 人局：SB = dealer+1, BB = dealer+2
     */
    private fun computePositions(dealerIndex: Int, playerCount: Int, config: BlindsConfig): BlindsState {
        return if (playerCount == 2) {
            BlindsState(
                dealerIndex = dealerIndex,
                smallBlindIndex = dealerIndex,
                bigBlindIndex = (dealerIndex + 1) % playerCount,
                config = config
            )
        } else {
            BlindsState(
                dealerIndex = dealerIndex,
                smallBlindIndex = (dealerIndex + 1) % playerCount,
                bigBlindIndex = (dealerIndex + 2) % playerCount,
                config = config
            )
        }
    }

    /**
     * 自动扣除盲注并返回更新后的玩家列表和每位玩家的盲注贡献。
     * 如果玩家筹码不足盲注金额，则全部投入（all-in）。
     */
    fun deductBlinds(
        players: List<PlayerState>,
        blindsState: BlindsState
    ): Pair<List<PlayerState>, Map<String, Int>> {
        val blindContributions = mutableMapOf<String, Int>()
        val sortedPlayers = players.sortedBy { it.seatOrder }

        val updatedPlayers = sortedPlayers.mapIndexed { index, player ->
            val blindAmount = when (index) {
                blindsState.smallBlindIndex -> minOf(blindsState.config.smallBlind, player.chips)
                blindsState.bigBlindIndex -> minOf(blindsState.config.bigBlind, player.chips)
                else -> 0
            }
            if (blindAmount > 0) {
                blindContributions[player.id] = blindAmount
            }
            player.copy(chips = player.chips - blindAmount)
        }

        return updatedPlayers to blindContributions
    }
}
