package com.fushilaofang.texasholdemchipsim.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerState(
    val id: String,
    val name: String,
    val chips: Int,
    val seatOrder: Int,
    val isReady: Boolean = false
)
