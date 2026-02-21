package com.fushilaofang.texasholdemchipsim.settlement

import com.fushilaofang.texasholdemchipsim.model.ChipTransaction
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import com.fushilaofang.texasholdemchipsim.model.TransactionType
import java.util.UUID

data class SettlementResult(
    val updatedPlayers: List<PlayerState>,
    val transactions: List<ChipTransaction>,
    val totalPot: Int,
    val sidePots: List<SidePot>
)

class SettlementEngine {

    /**
     * 支持边池的结算。
     *
     * @param winnerRanking  赢家优先级列表，索引越小优先级越高。
     *                       每个元素是一组同优先级的赢家 ID（例如平分）。
     *                       最常见用法：`listOf(setOf(winnerId))` 表示只有一个赢家。
     */
    fun settleHand(
        handId: String,
        players: List<PlayerState>,
        contributions: Map<String, Int>,
        winnerRanking: List<Set<String>>,
        timestamp: Long
    ): SettlementResult {
        require(players.isNotEmpty()) { "players cannot be empty" }
        require(winnerRanking.isNotEmpty()) { "at least one winner group is required" }

        val allWinnerIds = winnerRanking.flatten().toSet()
        val playerMap = players.associateBy { it.id }
        require(allWinnerIds.all { playerMap.containsKey(it) }) { "winner ids must all exist" }

        val normalizedContrib = contributions.mapValues { (_, v) -> v.coerceAtLeast(0) }
        val totalPot = normalizedContrib.values.sum()

        // 构建边池
        val sidePots = SidePotCalculator.buildPots(normalizedContrib)

        // 为每个边池分配给优先级最高的有资格赢家组
        // potPayouts[i] = Map<playerId, amount> 表示第 i 个池子各赢家实际获得金额
        val potPayouts = mutableListOf<Pair<SidePot, Map<String, Int>>>()
        val payouts = mutableMapOf<String, Int>()
        for (pot in sidePots) {
            val potWinMap = mutableMapOf<String, Int>()
            var distributed = false
            for (winnerGroup in winnerRanking) {
                val eligible = winnerGroup.filter { pot.eligiblePlayerIds.contains(it) }
                if (eligible.isNotEmpty()) {
                    val base = pot.amount / eligible.size
                    val remainder = pot.amount % eligible.size
                    val sortedEligible = eligible.sortedBy { playerMap[it]?.seatOrder ?: 0 }
                    sortedEligible.forEachIndexed { idx, pid ->
                        val extra = if (idx < remainder) 1 else 0
                        val amount = base + extra
                        potWinMap[pid] = (potWinMap[pid] ?: 0) + amount
                        payouts[pid] = (payouts[pid] ?: 0) + amount
                    }
                    distributed = true
                    break
                }
            }
            // 如果没有赢家在该池中有资格（理论上不应发生），退还给贡献者
            if (!distributed) {
                for (pid in pot.eligiblePlayerIds) {
                    val share = pot.amount / pot.eligiblePlayerIds.size
                    potWinMap[pid] = (potWinMap[pid] ?: 0) + share
                    payouts[pid] = (payouts[pid] ?: 0) + share
                }
            }
            if (potWinMap.isNotEmpty()) potPayouts.add(pot to potWinMap)
        }

        // 更新玩家筹码（coerceAtLeast 防止极端情况出现负值）
        val updatedPlayers = players.map { player ->
            val spent = normalizedContrib[player.id] ?: 0
            val won = payouts[player.id] ?: 0
            player.copy(chips = (player.chips - spent + won).coerceAtLeast(0))
        }

        // 生成交易记录
        val updatedMap = updatedPlayers.associateBy { it.id }
        val nameMap = players.associate { it.id to it.name }
        val transactions = buildList {
            normalizedContrib.forEach { (playerId, spent) ->
                if (spent > 0) {
                    add(
                        ChipTransaction(
                            id = UUID.randomUUID().toString(),
                            timestamp = timestamp,
                            handId = handId,
                            playerId = playerId,
                            playerName = nameMap[playerId] ?: playerId.take(6),
                            amount = -spent,
                            type = TransactionType.CONTRIBUTION,
                            note = "投入底池",
                            balanceAfter = updatedMap[playerId]?.chips ?: 0
                        )
                    )
                }
            }
            // 按池逐个生成赢彩记录，label 区分主池/边池
            potPayouts.forEach { (pot, winMap) ->
                winMap.forEach { (playerId, payout) ->
                    if (payout > 0) {
                        add(
                            ChipTransaction(
                                id = UUID.randomUUID().toString(),
                                timestamp = timestamp,
                                handId = handId,
                                playerId = playerId,
                                playerName = nameMap[playerId] ?: playerId.take(6),
                                amount = payout,
                                type = TransactionType.WIN_PAYOUT,
                                note = "赢得${pot.label}",
                                balanceAfter = updatedMap[playerId]?.chips ?: 0
                            )
                        )
                    }
                }
            }
        }

        return SettlementResult(
            updatedPlayers = updatedPlayers,
            transactions = transactions,
            totalPot = totalPot,
            sidePots = sidePots
        )
    }

    /**
     * 便捷方法：所有选中的赢家同优先级（即平分所有底池）
     */
    fun settleHandSimple(
        handId: String,
        players: List<PlayerState>,
        contributions: Map<String, Int>,
        winnerIds: List<String>,
        timestamp: Long
    ): SettlementResult {
        return settleHand(
            handId = handId,
            players = players,
            contributions = contributions,
            winnerRanking = listOf(winnerIds.toSet()),
            timestamp = timestamp
        )
    }
}
