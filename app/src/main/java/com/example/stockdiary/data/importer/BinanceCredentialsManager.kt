package com.example.stockdiary.data.importer

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.stockdiary.utils.AppLogger

/** 迁移结果：无旧数据 / 迁移成功 / 加密写入校验失败（保留明文） */
internal enum class MigrationOutcome { NO_LEGACY_DATA, MIGRATED, VERIFY_FAILED }

/** 凭据存储 key。提升为 top-level internal，供纯函数与单测共用（值不变，兼容老数据） */
internal const val KEY_API_KEY = "api_key"
internal const val KEY_API_SECRET = "api_secret"

/**
 * 同步落盘 SharedPreferences 编辑（commit）。
 *
 * 抽出来是为了让 save/clear/迁移共用同一落盘口径，也便于单测用一个
 * 记录 commit 调用的内存版 Editor 验证「确实走同步落盘而非 apply」。
 * 返回是否成功写入；凭据路径忽略返回值（加密 prefs 的 commit 失败极罕见）。
 */
internal fun commitSync(editor: SharedPreferences.Editor): Boolean = editor.commit()

/** 保存密钥到给定 prefs（commit 同步落盘，纯逻辑便于单测） */
internal fun saveCredentials(prefs: SharedPreferences, apiKey: String, apiSecret: String) {
    commitSync(
        prefs.edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_API_SECRET, apiSecret)
    )
}

/** 清除给定 prefs 里的密钥（commit 同步落盘） */
internal fun clearCredentials(prefs: SharedPreferences) {
    commitSync(
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_SECRET)
    )
}

/**
 * 把旧明文 prefs 的密钥搬到加密 prefs（纯逻辑便于单测）。
 *
 * 全程 commit：先在 enc 写密文并 commit，读回校验成功后再清旧明文并 commit。
 * 这样任何一步被杀进程都不会「密文没写成却删了明文」导致密钥丢失。
 */
internal fun migrateCredentials(
    enc: SharedPreferences,
    legacy: SharedPreferences
): MigrationOutcome {
    val oldKey = legacy.getString(KEY_API_KEY, null)
    val oldSecret = legacy.getString(KEY_API_SECRET, null)
    if (oldKey.isNullOrBlank() && oldSecret.isNullOrBlank()) return MigrationOutcome.NO_LEGACY_DATA

    commitSync(
        enc.edit()
            .putString(KEY_API_KEY, oldKey)
            .putString(KEY_API_SECRET, oldSecret)
    )
    // 读回校验加密写入真的成功，再清旧明文——避免「写密文失败却删了明文」丢密钥
    return if (!enc.getString(KEY_API_KEY, null).isNullOrBlank()) {
        commitSync(legacy.edit().clear())
        MigrationOutcome.MIGRATED
    } else {
        MigrationOutcome.VERIFY_FAILED
    }
}

/**
 * 币安 API 密钥的本地存储（只读密钥）。
 *
 * ## 存储方式：EncryptedSharedPreferences 优先，明文 SharedPreferences 兜底
 *
 * 默认用 [EncryptedSharedPreferences]（底层 Android Keystore + AES-256）加密密钥，
 * 比普通 SharedPreferences 安全得多——即便设备被 root，落盘的也是密文。
 *
 * 但 Android Keystore 在极少数设备 / 模拟器上不可用（或密钥丢失后无法恢复）。
 * 为**绝不崩**、也为了兼容旧版升级用户，这里做两层兜底：
 *   1. 构造时尝试创建加密存储；抛任何异常就回退到普通 SharedPreferences（与旧行为一致）。
 *   2. 升级迁移：若旧明文 prefs（`binance_api`）里还有密钥，首次成功创建加密存储后
 *      把密钥搬过去并校验回读成功，再清空旧明文文件，避免两份并存或「写密文失败却删了明文」。
 *
 * 加密不可用时（回退明文），安全性与旧版完全相同，不引入任何回归；加密可用时则严格更好。
 *
 * ## 铁律：密钥绝不外泄
 *
 * - 不写进 AppLogger（日志会落盘成可分享文件）——迁移 / 回退只记「发生了什么」，不记密钥值。
 * - 不拼进异常 message。
 * - 不参与任何 UI 之外的隐式传递。
 */
class BinanceCredentialsManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    /** 真正使用的存储：加密优先，明文兜底 */
    private val prefs: SharedPreferences = buildPrefs()

    /**
     * 是否因 Android Keystore 不可用而回退到明文 SharedPreferences。
     * true 时密钥以明文落盘（已 root 设备可读），UI 应据此向用户告警。
     */
    var isPlaintextFallback: Boolean = false
        private set

    private fun buildPrefs(): SharedPreferences {
        return try {
            // security-crypto 1.0.0 的 create 用「masterKeyAlias 字符串」而非 MasterKey 对象，
            // 别名不存在时内部会自动在 Android Keystore 生成一个 AES256_GCM 主密钥。
            val enc = EncryptedSharedPreferences.create(
                PREFS_NAME_ENCRYPTED,
                MASTER_KEY_ALIAS,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // 升级迁移：把旧明文 prefs 里的密钥搬过来（旧版用的就是 PREFS_NAME_LEGACY）。
            // 全程 commit() 同步落盘：迁移成功后立刻清旧明文，若中途进程被杀，
            // commit 保证「密文已落盘」才清明文，不会出现「写密文半途而废却删了明文」丢密钥。
            val legacy = appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
            when (migrateCredentials(enc, legacy)) {
                MigrationOutcome.MIGRATED -> AppLogger.i("币安密钥已从明文迁移到加密存储", TAG)
                MigrationOutcome.VERIFY_FAILED -> AppLogger.w("币安密钥加密写入校验失败，保留明文兜底", TAG)
                MigrationOutcome.NO_LEGACY_DATA -> Unit
            }
            enc
        } catch (e: Exception) {
            // Keystore 不可用：退化明文，与旧版一致，绝不崩
            isPlaintextFallback = true
            AppLogger.w("EncryptedSharedPreferences 不可用，币安密钥回退明文存储: ${e.message}", TAG)
            AppLogger.w(
                "【安全警告】本设备 Android Keystore 不可用，币安 API Key/Secret 将以明文保存在 SharedPreferences，" +
                    "已 root 的设备可读。建议尽快更换支持加密存储的设备，或仅在可信环境使用币安导入。",
                TAG
            )
            appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
        }
    }

    /** 是否已保存密钥（用于 Settings 副标题/弹窗提示，不返回密钥本身） */
    fun hasCredentials(): Boolean = getApiKey()?.isNotBlank() == true

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    fun getApiSecret(): String? = prefs.getString(KEY_API_SECRET, null)

    /**
     * 保存密钥。
     *
     * 用 [commitSync]（commit）同步落盘：密钥是凭据，用户保存后可能立刻杀进程 /
     * 币安导入马上读，apply() 异步落盘在进程被杀前来不及写盘就会丢密钥——
     * 表现为「刚填的 API Key 重启后又没了」。凭据路径值得这点同步开销。
     * 崩溃主路径（GlobalExceptionHandler.recordCrash）同样已是 commit，这里补齐凭据路径。
     * 注意这里刻意不打印任何确认日志——「已保存 api_key=xxx」这种日志
     * 正是密钥泄露最常见的方式。
     */
    fun save(apiKey: String, apiSecret: String) {
        saveCredentials(prefs, apiKey, apiSecret)
    }

    /** 清除密钥。用户解除绑定/换账号时调用，同样 commit 同步落盘防杀进程后复活 */
    fun clear() {
        clearCredentials(prefs)
    }

    companion object {
        private const val TAG = "BinanceCreds"
        private const val PREFS_NAME_ENCRYPTED = "binance_api_enc"
        private const val PREFS_NAME_LEGACY = "binance_api"

        /** Android Keystore 里的主密钥别名（不存在时 security-crypto 会自动创建） */
        private const val MASTER_KEY_ALIAS = "binance_master_key"

        @Volatile
        private var instance: BinanceCredentialsManager? = null

        fun getInstance(context: Context): BinanceCredentialsManager {
            return instance ?: synchronized(this) {
                instance ?: BinanceCredentialsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
