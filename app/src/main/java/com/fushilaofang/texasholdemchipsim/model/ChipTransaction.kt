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
    CONTRIBUTION,
    WIN_PAYOUT,
    BLIND_DEDUCTION
}
