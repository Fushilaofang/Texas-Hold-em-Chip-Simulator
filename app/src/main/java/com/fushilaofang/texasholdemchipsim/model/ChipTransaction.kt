package com.fushilaofang.texasholdemchipsim.model

import kotlinx.serialization.Serializable

@Serializable
data class ChipTransaction(
    val id: String,
    val timestamp: Long,
    val handId: String,
    val playerId: String,
    val playerName: String = "",
    val amount: Int,
    val type: TransactionType,
    val note: String,
    val balanceAfter: Int
)

@Serializable
enum class TransactionType {
    // 实时操作类型
    BLIND_DEDUCTION,   // 盲注扣除
    BET,               // 主动下注
    CALL,              // 跟注
    RAISE,             // 加注
    ALL_IN,            // 全压
    CHECK,             // 过牌（不投入）
    FOLD,              // 弃牌
    // 结算类型
    WIN_PAYOUT,        // 赢得筹码
    CONTRIBUTION       // 已弃用，保留向后兼容旧序列化数据
}
