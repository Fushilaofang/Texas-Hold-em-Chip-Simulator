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
 * 盲注管理器：负责庄位轮转与盲注预填
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
     * - 2 人局（单挑）：庄家 = 小盲，另一人 = 大盲
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
     * 计算盲注预填金额（不扣除筹码，仅返回每位玩家应预填的投入值）。
     * 如果玩家筹码不足盲注金额，则预填其全部筹码（all-in）。
     */
    fun calculateBlindPrefills(
        players: List<PlayerState>,
        blindsState: BlindsState
    ): Map<String, Int> {
        val blindContributions = mutableMapOf<String, Int>()
        val sortedPlayers = players.sortedBy { it.seatOrder }

        sortedPlayers.forEachIndexed { index, player ->
            val blindAmount = when (index) {
                blindsState.smallBlindIndex -> minOf(blindsState.config.smallBlind, player.chips)
                blindsState.bigBlindIndex -> minOf(blindsState.config.bigBlind, player.chips)
                else -> 0
            }
            if (blindAmount > 0) {
                blindContributions[player.id] = blindAmount
            }
        }

        return blindContributions
    }

    /**
     * 校验玩家投入是否符合德州扑克盲注规则
     * 
     * 规则：
     * - 小盲位：投入 >= 小盲额（筹码不足则必须 all-in）
     * - 大盲位：投入 >= 大盲额（筹码不足则必须 all-in）
     * - 其他位：投入 >= 大盲额（跟注），或投入 = 0（弃牌），或投入为全部筹码（all-in）
     * - 任何人的投入不能超过其筹码总额
     */
    fun validateContributions(
        players: List<PlayerState>,
        blindsState: BlindsState,
        contributions: Map<String, Int>
    ): List<String> {
        val sortedPlayers = players.sortedBy { it.seatOrder }
        val violations = mutableListOf<String>()

        sortedPlayers.forEachIndexed { index, player ->
            val contrib = contributions[player.id] ?: 0

            // 投入不能超过筹码
            if (contrib > player.chips) {
                violations.add("${player.name} 投入 $contrib 超过筹码 ${player.chips}")
                return@forEachIndexed
            }

            when (index) {
                blindsState.smallBlindIndex -> {
                    val required = minOf(blindsState.config.smallBlind, player.chips)
                    if (contrib < required) {
                        violations.add("${player.name}(小盲)投入 $contrib 不足，至少需要 $required")
                    }
                }
                blindsState.bigBlindIndex -> {
                    val required = minOf(blindsState.config.bigBlind, player.chips)
                    if (contrib < required) {
                        violations.add("${player.name}(大盲)投入 $contrib 不足，至少需要 $required")
                    }
                }
                else -> {
                    // 非盲注位：0 = 弃牌，>= 大盲 = 跟注/加注，= 全部筹码 = all-in
                    if (contrib > 0) {
                        val minCall = minOf(blindsState.config.bigBlind, player.chips)
                        if (contrib < minCall) {
                            violations.add("${player.name} 投入 $contrib 不足，跟注至少需要 $minCall（或全押 ${player.chips}）")
                        }
                    }
                }
            }
        }
        return violations
    }

    /**
     * 获取某个座位的最低投入要求
     */
    fun getMinContribution(
        playerIndex: Int,
        playerChips: Int,
        blindsState: BlindsState
    ): Int {
        return when (playerIndex) {
            blindsState.smallBlindIndex -> minOf(blindsState.config.smallBlind, playerChips)
            blindsState.bigBlindIndex -> minOf(blindsState.config.bigBlind, playerChips)
            else -> 0 // 非盲注位可以弃牌
        }
    }
}
