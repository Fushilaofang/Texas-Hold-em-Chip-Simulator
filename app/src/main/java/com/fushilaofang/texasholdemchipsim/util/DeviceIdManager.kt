package com.fushilaofang.texasholdemchipsim.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 设备唯一标识管理器
 *
 * 为每个设备生成一个唯一且持久的标识符，用于绑定玩家账号。
 * 即使玩家修改昵称或重新安装应用，只要保留了SharedPreferences数据，
 * 设备ID保持不变，从而避免账号重复的问题。
 */
object DeviceIdManager {
    private const val PREFS_NAME = "device_id_prefs"
    private const val KEY_DEVICE_ID = "unique_device_id"

    /**
     * 获取或生成设备唯一ID
     *
     * 首次调用时会生成一个全局唯一的UUID并持久化。
     * 后续调用始终返回同一个ID。
     *
     * @param context Android应用上下文
     * @return 设备唯一标识符（UUID格式）
     */
    fun getDeviceId(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    /**
     * 重置设备ID（仅用于测试或特殊场景）
     * @param context Android应用上下文
     */
    fun resetDeviceId(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DEVICE_ID).apply()
    }
}
