package com.fushilaofang.texasholdemchipsim.util

import android.content.Context
import java.util.UUID

/**
 * 设备唯一编码管理器
 *
 * 每台设备在首次运行时生成一个随机 UUID 并持久化到 SharedPreferences。
 * 该 ID 作为玩家账号的稳定标识符，确保即使昵称更改或应用重启，依然能
 * 识别为同一玩家，避免大厅中出现重复账号问题。
 */
object DeviceIdManager {

    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * 获取本设备的唯一 ID。
     * - 若已存在则直接返回；
     * - 否则生成新 UUID 并持久化后返回。
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
}
