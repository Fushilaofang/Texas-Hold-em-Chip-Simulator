package com.fushilaofang.texasholdemchipsim.settlement

/**
 * 边池（Side Pot）数据：金额 + 有资格分配的玩家集合
 */
data class SidePot(
    val amount: Int,
    val eligiblePlayerIds: Set<String>,
    val label: String
)

/**
 * 根据每位玩家的投入计算主池和边池。
 *
 * 算法：
 * 1. 将所有投入去重排序
 * 2. 从最低投入层开始，每个层级的增量 × 达到该层级的人数 = 该层池子金额
 * 3. 只有投入 >= 该层级的玩家才有资格分配该池
 */
object SidePotCalculator {

    fun buildPots(contributions: Map<String, Int>): List<SidePot> {
        val positiveContribs = contributions.filter { (_, v) -> v > 0 }
        if (positiveContribs.isEmpty()) return emptyList()

        val sortedLevels = positiveContribs.values.distinct().sorted()
        val pots = mutableListOf<SidePot>()
        var previousLevel = 0

        for ((index, level) in sortedLevels.withIndex()) {
            val increment = level - previousLevel
            if (increment <= 0) continue

            val eligible = positiveContribs.filter { (_, c) -> c >= level }.keys
            val potAmount = eligible.size * increment

            val label = if (index == 0) "主池" else "边池$index"
            pots.add(SidePot(amount = potAmount, eligiblePlayerIds = eligible, label = label))
            previousLevel = level
        }

        return pots
    }
}
