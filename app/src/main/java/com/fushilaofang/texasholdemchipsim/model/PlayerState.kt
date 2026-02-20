package com.fushilaofang.texasholdemchipsim.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerState(
    val id: String,
    val name: String,
    val chips: Int,
    val seatOrder: Int,
    val isReady: Boolean = false,
    val avatarBase64: String = "",
    /** 设备唯一编码，用于将同一台设备的玩家与账号绑定，防止改名后重复创建账号 */
    val deviceId: String = ""
)
