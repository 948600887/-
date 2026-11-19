package com.example.stockdiary.data.importer

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.stockdiary.utils.AppLogger

/** 迁移结果：无旧数据 / 迁移成功 / 加密写入校验失败（保留明文） */
internal enum class MigrationOutcome { NO_LEGACY_DATA, MIGRATED, VERIFY_FAILED }

/**
 * 凭据实际落在哪里。
 *
 * - [ENCRYPTED]：加密存储可用，正常路径。
 * - [PLAINTEXT_LEGACY]：Keystore 不可用，但旧明文里还有密钥（与旧版行为一致，UI 需警示明文风险）。
 * - [PLAINTEXT_EMPTY]：Keystore 不可用且旧明文里也没有密钥——用户此前的密钥在本次升级中
 *   已无法读取，必须明确提示重新录入，不能只说「回退明文存储」。
 */
enum class CredentialStorageMode { ENCRYPTED, PLAINTEXT_LEGACY, PLAINTEXT_EMPTY }

/** 凭据存储 key。提升为 top-level internal，供纯函数与单测共用（值不变，兼容老数据） */
internal const val KEY_API_KEY = "api_key"
internal const val KEY_API_SECRET = "api_secret"

/** 加密存储的 preferences 文件名（值不变，兼容老数据） */
private const val PREFS_NAME_ENCRYPTED = "binance_api_enc"

/** Android Keystore 里的主密钥别名（不存在时 security-crypto 会自动创建） */
private const val MASTER_KEY_ALIAS = "binance_master_key"

/**
 * 创建加密 SharedPreferences（Android Keystore + AES-256）。
 *
 * 抽成顶层函数只有一个目的：让 [BinanceCredentialsManager] 的构造能够注入替身。
 * 「Keystore 不可用 → 回退明文」这条状态机在真机上几乎不会发生，而本工程没有
 * Robolectric，`EncryptedSharedPreferences.create` 无法在 JVM 单测里被强制失败——
 * 不给注入点，这条分支就永远无法被测试覆盖（这正是 storageMode 被覆盖回 ENCRYPTED
 * 却能逃过 1783 例门禁的原因）。**生产路径行为零变化。**
 */
private fun createEncryptedPrefs(context: Context): SharedPreferences =
    // security-crypto 1.0.0 的 create 用「masterKeyAlias 字符串」而非 MasterKey 对象，
    // 别名不存在时内部会自动在 Android Keystore 生成一个 AES256_GCM 主密钥。
    EncryptedSharedPreferences.create(
        PREFS_NAME_ENCRYPTED,
        MASTER_KEY_ALIAS,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

/**
 * 同步落盘 SharedPreferences 编辑（commit）。
 *
 * 抽出来是为了让 save/clear/迁移共用同一落盘口径，也便于单测用一个
 * 记录 commit 调用的内存版 Editor 验证「确实走同步落盘而非 apply」。
 * 返回值表示**是否真正写盘成功**；SharedPreferences 先在内存生效、写盘失败也读得回，
 * 所以调用方必须看这个返回值，不能靠读回判断。
 */
internal fun commitSync(editor: SharedPreferences.Editor): Boolean = editor.commit()

/** 保存密钥到给定 prefs（commit 同步落盘）。返回是否落盘成功。 */
internal fun saveCredentials(
    prefs: SharedPreferences,
    apiKey: String,
    apiSecret: String
): Boolean {
    return commitSync(
        prefs.edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_API_SECRET, apiSecret)
    )
}

/** 清除给定 prefs 里的密钥（commit 同步落盘）。返回是否落盘成功。 */
internal fun clearCredentials(prefs: SharedPreferences): Boolean {
    return commitSync(
        prefs.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_API_SECRET)
    )
}

/**
 * 把旧明文 prefs 的密钥搬到加密 prefs（纯逻辑便于单测）。
 *
 * 全程 commit，且**只有 commit 返回 true 且读回非空**才清旧明文：
 * SharedPreferences 的 commit 返回 false 时内存里已经是新值，读回照样通过，
 * 单靠读回会把「没写进磁盘」误判为成功，进而删掉唯一一份明文。
 */
internal fun migrateCredentials(
    enc: SharedPreferences,
    legacy: SharedPreferences
): MigrationOutcome {
    val oldKey = legacy.getString(KEY_API_KEY, null)
    val oldSecret = legacy.getString(KEY_API_SECRET, null)
    if (oldKey.isNullOrBlank() && oldSecret.isNullOrBlank()) return MigrationOutcome.NO_LEGACY_DATA

    val encCommitted = commitSync(
        enc.edit()
            .putString(KEY_API_KEY, oldKey)
            .putString(KEY_API_SECRET, oldSecret)
    )
    // 落盘成功 + 读回非空，两个条件同时满足才允许清理旧明文
    return if (encCommitted && !enc.getString(KEY_API_KEY, null).isNullOrBlank()) {
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
class BinanceCredentialsManager private constructor(
    context: Context,
    /**
     * 加密 prefs 提供者。生产固定为 [createEncryptedPrefs]（默认值）；
     * 单测通过 [createForTest] 注入「直接抛异常」的替身来模拟 Keystore 不可用。
     * 业务代码不得传入其他实现。
     */
    private val encryptedPrefsProvider: (Context) -> SharedPreferences = ::createEncryptedPrefs
) {

    private val appContext = context.applicationContext

    /**
     * 当前凭据实际使用的存储方式。
     * [CredentialStorageMode.PLAINTEXT_EMPTY] 表示需要提示用户重新录入（见 [needsCredentialReentry]）。
     *
     * ## 声明顺序是行为的一部分，不能挪到 [prefs] 之后
     *
     * Kotlin 的属性初始化器**按声明顺序**执行：谁在后，谁的值就是最终值。
     * [buildPrefs] 内部会写 [CredentialStorageMode.ENCRYPTED] /
     * [CredentialStorageMode.PLAINTEXT_LEGACY] / [CredentialStorageMode.PLAINTEXT_EMPTY]，
     * 所以本字段必须声明在 [prefs] **之前**——否则下面的 `= CredentialStorageMode.ENCRYPTED`
     * 会在 [buildPrefs] 之后才执行，把回退状态整体覆盖回 ENCRYPTED，
     * 导致 [isPlaintextFallback] 恒 false、[needsCredentialReentry] 恒 false，
     * UI 上的「本机不支持加密存储」与「请重新填写 API Key 与 Secret」两条提示静默失效。
     * 初始值 ENCRYPTED 只是「尚未探测」的占位，探测结果由 [buildPrefs] 覆盖。
     */
    var storageMode: CredentialStorageMode = CredentialStorageMode.ENCRYPTED
        private set

    /** 真正使用的存储：加密优先，明文兜底（构造时探测，并回填 [storageMode]） */
    private val prefs: SharedPreferences = buildPrefs()

    /**
     * 是否因 Android Keystore 不可用而回退到明文 SharedPreferences。
     * true 时密钥以明文落盘（已 root 设备可读），UI 应据此向用户告警。
     */
    val isPlaintextFallback: Boolean
        get() = storageMode != CredentialStorageMode.ENCRYPTED

    /**
     * 回退明文、且明文区里也没有任何密钥——用户此前保存的密钥已不可读，
     * UI 必须提示重新录入，而不是笼统说「已回退明文存储」。
     */
    val needsCredentialReentry: Boolean
        get() = storageMode == CredentialStorageMode.PLAINTEXT_EMPTY && !hasCredentials()

    private fun buildPrefs(): SharedPreferences {
        return try {
            val enc = encryptedPrefsProvider(appContext)
            // 升级迁移：把旧明文 prefs 里的密钥搬过来（旧版用的就是 PREFS_NAME_LEGACY）。
            // 迁移内部要求「commit 成功 + 读回非空」才清旧明文，见 [migrateCredentials]。
            val legacy = appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
            when (migrateCredentials(enc, legacy)) {
                MigrationOutcome.MIGRATED -> AppLogger.i("币安密钥已从明文迁移到加密存储", TAG)
                MigrationOutcome.VERIFY_FAILED -> AppLogger.w("币安密钥加密写入校验失败，保留明文兜底", TAG)
                MigrationOutcome.NO_LEGACY_DATA -> Unit
            }
            storageMode = CredentialStorageMode.ENCRYPTED
            enc
        } catch (e: Exception) {
            // Keystore 不可用：退化明文，与旧版一致，绝不崩
            val legacy = appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
            val legacyHasCredentials = !legacy.getString(KEY_API_KEY, null).isNullOrBlank() ||
                !legacy.getString(KEY_API_SECRET, null).isNullOrBlank()
            storageMode = if (legacyHasCredentials) {
                CredentialStorageMode.PLAINTEXT_LEGACY
            } else {
                CredentialStorageMode.PLAINTEXT_EMPTY
            }
            AppLogger.w("EncryptedSharedPreferences 不可用，币安密钥回退明文存储: ${e.message}", TAG)
            if (legacyHasCredentials) {
                AppLogger.w(
                    "【安全警告】本设备 Android Keystore 不可用，币安 API Key/Secret 将以明文保存在 SharedPreferences，" +
                        "已 root 的设备可读。建议尽快更换支持加密存储的设备，或仅在可信环境使用币安导入。",
                    TAG
                )
            } else {
                // 不回显任何密钥内容，只说明状态与用户需要做什么
                AppLogger.w(
                    "【安全警告】本设备 Android Keystore 不可用，且未找到旧的明文密钥：" +
                        "此前保存的币安密钥已无法读取，需要重新录入；重新保存后将明文存储在 SharedPreferences。",
                    TAG
                )
            }
            legacy
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
     *
     * @return 是否真正写盘成功（commit 返回值）；false 时调用方必须提示用户，不能当成功。
     */
    fun save(apiKey: String, apiSecret: String): Boolean {
        return saveCredentials(prefs, apiKey, apiSecret)
    }

    /**
     * 清除密钥。用户解除绑定/换账号时调用，同样 commit 同步落盘防杀进程后复活。
     *
     * @return 是否真正写盘成功；false 表示清除可能没生效，调用方必须提示用户。
     */
    fun clear(): Boolean {
        return clearCredentials(prefs)
    }

    companion object {
        private const val TAG = "BinanceCreds"
        private const val PREFS_NAME_LEGACY = "binance_api"

        @Volatile
        private var instance: BinanceCredentialsManager? = null

        fun getInstance(context: Context): BinanceCredentialsManager {
            return instance ?: synchronized(this) {
                instance ?: BinanceCredentialsManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * **仅供单测**：注入加密 prefs 提供者，用来模拟「Android Keystore 不可用」。
         *
         * 生产入口是 [getInstance]，永远走 [createEncryptedPrefs]；这里不缓存实例、
         * 也不改变任何生产行为。
         */
        internal fun createForTest(
            context: Context,
            encryptedPrefsProvider: (Context) -> SharedPreferences
        ): BinanceCredentialsManager =
            BinanceCredentialsManager(context.applicationContext, encryptedPrefsProvider)
    }
}
