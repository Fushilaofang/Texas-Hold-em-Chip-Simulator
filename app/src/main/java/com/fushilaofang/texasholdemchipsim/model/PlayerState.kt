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
    /** 设备唯一标识符，用于将玩家账号与设备绑定 */
    val deviceId: String = ""
)
