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
 * 盲注管理器：负责庄位轮转与盲注预填。
 *
 * 核心改动：
 * - 计算 SB/BB 位时跳过筹码为 0 的已破产玩家
 * - 盲注立即从玩家筹码扣除
 */
class BlindsManager {

    /**
     * 初始化盲注状态（第一手开始前）
     */
    fun initialize(playerCount: Int, config: BlindsConfig): BlindsState {
        return computePositions(dealerIndex = 0, playerCount = playerCount, config = config)
    }

    /** 指定庄家位初始化 */
    fun computeFromDealer(dealerIndex: Int, playerCount: Int, config: BlindsConfig): BlindsState {
        return computePositions(dealerIndex = dealerIndex, playerCount = playerCount, config = config)
    }

    /**
     * 指定庄家位初始化，跳过筹码为 0 的玩家
     */
    fun computeFromDealerSkippingBroke(
        dealerIndex: Int,
        players: List<PlayerState>,
        config: BlindsConfig
    ): BlindsState {
        val sorted = players.sortedBy { it.seatOrder }
        val n = sorted.size
        if (n < 2) return computePositions(dealerIndex, n, config)

        val safeDealer = dealerIndex.coerceIn(0, n - 1)

        return if (n == 2) {
            // 单挑：庄家 = 小盲
            BlindsState(
                dealerIndex = safeDealer,
                smallBlindIndex = safeDealer,
                bigBlindIndex = (safeDealer + 1) % n,
                config = config
            )
        } else {
            // 3+ 人：SB = 庄后第一个有筹码的，BB = SB 后第一个有筹码的
            val sbIndex = findNextWithChips(sorted, safeDealer, n)
            val bbIndex = findNextWithChips(sorted, sbIndex, n)
            BlindsState(
                dealerIndex = safeDealer,
                smallBlindIndex = sbIndex,
                bigBlindIndex = bbIndex,
                config = config
            )
        }
    }

    /**
     * 庄位前移一位（跳过破产玩家），重新计算大小盲位置
     */
    fun rotateSkippingBroke(current: BlindsState, players: List<PlayerState>): BlindsState {
        val sorted = players.sortedBy { it.seatOrder }
        val n = sorted.size
        if (n < 2) return current

        // 庄家前移到下一个有筹码的玩家（庄家可以是筹码为 0 的玩家，但 SB/BB 不可以）
        val nextDealer = (current.dealerIndex + 1) % n
        return computeFromDealerSkippingBroke(nextDealer, players, current.config)
    }

    /**
     * 庄位前移一位，重新计算大小盲位置（不检查筹码，保持向后兼容）
     */
    fun rotate(current: BlindsState, playerCount: Int): BlindsState {
        if (playerCount < 2) return current
        val nextDealer = (current.dealerIndex + 1) % playerCount
        return computePositions(nextDealer, playerCount, current.config)
    }

    /**
     * 从 startIndex 的下一位开始，找到第一个筹码 > 0 的玩家索引。
     * 如果所有人都为 0，回退到简单的 +1 循环。
     */
    private fun findNextWithChips(sorted: List<PlayerState>, startIndex: Int, n: Int): Int {
        for (i in 1 until n) {
            val idx = (startIndex + i) % n
            if (sorted[idx].chips > 0) return idx
        }
        // 所有人筹码都为 0（极端情况），退回 startIndex+1
        return (startIndex + 1) % n
    }

    /**
     * 根据庄位计算 SB / BB 位置（简单版，不检查筹码）
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
     * 筹码为 0 的玩家不预填盲注。
     */
    fun calculateBlindPrefills(
        players: List<PlayerState>,
        blindsState: BlindsState
    ): Map<String, Int> {
        val blindContributions = mutableMapOf<String, Int>()
        val sortedPlayers = players.sortedBy { it.seatOrder }

        sortedPlayers.forEachIndexed { index, player ->
            if (player.chips <= 0) return@forEachIndexed // 跳过破产玩家
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
     * 从玩家筹码中立即扣除盲注，返回更新后的玩家列表。
     */
    fun deductBlinds(
        players: List<PlayerState>,
        blindPrefills: Map<String, Int>
    ): List<PlayerState> {
        return players.map { player ->
            val deduction = blindPrefills[player.id] ?: 0
            if (deduction > 0) player.copy(chips = player.chips - deduction)
            else player
        }
    }

    /**
     * 结算前恢复被预扣的盲注（加回筹码），使结算引擎可以做完整扣除。
     */
    fun restoreBlindsForSettlement(
        players: List<PlayerState>,
        blindContributions: Map<String, Int>
    ): List<PlayerState> {
        return players.map { player ->
            val amount = blindContributions[player.id] ?: 0
            if (amount > 0) player.copy(chips = player.chips + amount)
            else player
        }
    }

    /**
     * 校验玩家投入是否符合德州扑克盲注规则
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
