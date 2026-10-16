package com.example.stockdiary.data.importer

import android.content.Context
import android.content.SharedPreferences

/**
 * 币安 API 密钥的本地存储（只读密钥）。
 *
 * ## 为什么用普通 SharedPreferences 而不是 EncryptedSharedPreferences
 *
 * 项目当前**没有**依赖 androidx.security:security-crypto，为一个只读密钥
 * 引入它要连带改依赖与最低 SDK 约束，收益不成比例。这里退而用普通
 * SharedPreferences（私有目录，Root 设备才可读），并在注释里写明这个取舍。
 *
 * 只读密钥的暴露面本就有限：它**不能下单、不能提币**，最坏情况是被人拿到后
 * 查看持仓。真正敏感的是 Secret，而 Secret 只参与本地 HMAC 签名、从不上传、
 * 不写日志。若日后要加固，把底层换成 EncryptedSharedPreferences 即可，
 * 对上层 [BinanceCredentialsManager] 的接口没有影响。
 *
 * ## 铁律：密钥绝不外泄
 *
 * - 不写进 AppLogger（日志会落盘成可分享文件）
 * - 不拼进异常 message
 * - 不参与任何 UI 之外的隐式传递
 */
class BinanceCredentialsManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否已保存密钥（用于 Settings 副标题/弹窗提示，不返回密钥本身） */
    fun hasCredentials(): Boolean = getApiKey()?.isNotBlank() == true

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun getApiSecret(): String? = prefs.getString(KEY_API_SECRET, null)

    /**
     * 保存密钥。
     *
     * 用 apply() 异步落盘：写密钥不该阻塞调用线程。
     * 注意这里刻意不打印任何确认日志——「已保存 api_key=xxx」这种日志
     * 正是密钥泄露最常见的方式。
     */
    fun save(apiKey: String, apiSecret: String) {
        prefs.edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_API_SECRET, apiSecret)
            .apply()
    }

    /** 清除密钥。用户解除绑定/换账号时调用 */
    fun clear() {
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_SECRET)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "binance_api"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_SECRET = "api_secret"

        @Volatile
        private var instance: BinanceCredentialsManager? = null

        fun getInstance(context: Context): BinanceCredentialsManager {
            return instance ?: synchronized(this) {
                instance ?: BinanceCredentialsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
