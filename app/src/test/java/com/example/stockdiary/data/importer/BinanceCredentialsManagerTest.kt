package com.example.stockdiary.data.importer

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 币安凭据落盘路径回归（R07）。
 *
 * 关键不是「setValue 后能读回来」——那种断言在内存版 Fake 里 apply 和 commit 都成立，
 * 是典型假绿。这里用 [RecordingEditor] 分别记录 commit / apply 调用次数，
 * **真钉死** save / clear / 迁移走的是同步落盘（commit），apply 一次都不许有。
 *
 * 除此之外还钉死 [BinanceCredentialsManager] 的**存储方式状态机**（storageMode /
 * isPlaintextFallback / needsCredentialReentry）。这三者曾经零测试覆盖，于是
 * 「storageMode 的属性初始化器晚于 prefs 执行、把 buildPrefs() 写的回退状态覆盖回
 * ENCRYPTED」这种静默失效一路穿过了门禁：UI 上的「本机不支持加密存储」和
 * 「请重新填写 API Key 与 Secret」两条提示永远不会出现。见本文件末尾三例。
 *
 * 加密 prefs 的构造通过 `BinanceCredentialsManager.createForTest` 注入替身来模拟
 * Keystore 不可用（本工程无 Robolectric，真机上 `create` 也不会失败）；生产的
 * [BinanceCredentialsManager.getInstance] 路径与注入点无关，行为不变。
 */
class BinanceCredentialsManagerTest {

    /** 内存版 Editor：记录 commit / apply 各自调用次数 */
    private class RecordingEditor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
        var commitCount = 0
        var applyCount = 0
        /** 模拟写盘失败：commit 返回 false，但内存里的值仍会更新（与真实 SharedPreferences 一致） */
        var commitResult = true
        private var staged = mutableMapOf<String, String?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            staged[key] = value; return this
        }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { /* 未使用 */ }
        override fun putInt(key: String, value: Int) = apply { /* 未使用 */ }
        override fun putLong(key: String, value: Long) = apply { /* 未使用 */ }
        override fun putFloat(key: String, value: Float) = apply { /* 未使用 */ }
        override fun putBoolean(key: String, value: Boolean) = apply { /* 未使用 */ }
        override fun remove(key: String): SharedPreferences.Editor {
            staged[key] = null; return this
        }
        override fun clear(): SharedPreferences.Editor {
            staged["__clear__"] = ""; return this
        }

        private fun flush() {
            if (staged.containsKey("__clear__")) values.clear()
            staged.forEach { (k, v) -> if (k != "__clear__") { if (v == null) values.remove(k) else values[k] = v } }
        }

        // 真实 commit 无论成败都会先改内存；这里保持一致，才能证明「读回通过≠落盘成功」
        override fun commit(): Boolean { commitCount++; flush(); return commitResult }
        override fun apply() { applyCount++; flush() }
    }

    private open class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, String>()
        var lastEditor: RecordingEditor? = null
        val editors = mutableListOf<RecordingEditor>()
        var editCount = 0
        var nextCommitResult = true

        override fun edit(): SharedPreferences.Editor {
            editCount++
            return RecordingEditor(values).also {
                it.commitResult = nextCommitResult
                lastEditor = it
                editors += it
            }
        }
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, default: String?): String? = values[key] ?: default
        override fun getStringSet(key: String, default: MutableSet<String>?) = default
        override fun getInt(key: String, default: Int) = default
        override fun getLong(key: String, default: Long) = default
        override fun getFloat(key: String, default: Float) = default
        override fun getBoolean(key: String, default: Boolean) = default
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    @Test
    fun `save走commit同步落盘且apply零调用`() {
        val prefs = FakePrefs()
        assertTrue(saveCredentials(prefs, "my-key", "my-secret"))

        assertEquals(1, prefs.lastEditor!!.commitCount)
        assertEquals(0, prefs.lastEditor!!.applyCount)
        assertEquals("my-key", prefs.getString(KEY_API_KEY, null))
        assertEquals("my-secret", prefs.getString(KEY_API_SECRET, null))
    }

    @Test
    fun `save落盘失败返回false_不能当成功`() {
        val prefs = FakePrefs().apply { nextCommitResult = false }
        assertFalse(saveCredentials(prefs, "my-key", "my-secret"))
    }

    @Test
    fun `clear落盘失败返回false_不能当成功`() {
        val prefs = FakePrefs().apply {
            values[KEY_API_KEY] = "old-key"
            nextCommitResult = false
        }
        assertFalse(clearCredentials(prefs))
    }

    @Test
    fun `clear走commit同步落盘且apply零调用`() {
        val prefs = FakePrefs().apply {
            values[KEY_API_KEY] = "old-key"
            values[KEY_API_SECRET] = "old-secret"
        }
        assertTrue(clearCredentials(prefs))

        assertEquals(1, prefs.lastEditor!!.commitCount)
        assertEquals(0, prefs.lastEditor!!.applyCount)
        assertNull(prefs.getString(KEY_API_KEY, null))
        assertNull(prefs.getString(KEY_API_SECRET, null))
    }

    @Test
    fun `commitSync字面调用commit不是apply`() {
        val editor = RecordingEditor(mutableMapOf())
        commitSync(editor)
        assertEquals(1, editor.commitCount)
        assertEquals(0, editor.applyCount)
    }

    @Test
    fun `迁移无旧数据时返回NO_LEGACY_DATA且不写enc`() {
        val enc = FakePrefs()
        val legacy = FakePrefs()
        assertEquals(MigrationOutcome.NO_LEGACY_DATA, migrateCredentials(enc, legacy))
        assertEquals(0, enc.editCount)
    }

    @Test
    fun `迁移成功时先commit写enc再commit清legacy`() {
        val enc = FakePrefs()
        val legacy = FakePrefs().apply {
            values[KEY_API_KEY] = "old-key"
            values[KEY_API_SECRET] = "old-secret"
        }
        assertEquals(MigrationOutcome.MIGRATED, migrateCredentials(enc, legacy))

        // 每个 prefs 只允许 edit 一次；多调一次会在这里暴露（不再只看 lastEditor）
        assertEquals(1, enc.editCount)
        assertEquals(1, legacy.editCount)
        assertEquals(1, enc.editors.sumOf { it.commitCount })
        assertEquals(1, legacy.editors.sumOf { it.commitCount })
        assertEquals(0, enc.editors.sumOf { it.applyCount })
        assertEquals(0, legacy.editors.sumOf { it.applyCount })
        assertEquals("old-key", enc.getString(KEY_API_KEY, null))
        assertEquals("old-secret", enc.getString(KEY_API_SECRET, null))
        assertNull(legacy.getString(KEY_API_KEY, null))
    }

    @Test
    fun `迁移时enc的commit失败_即使读回成功也必须保留legacy`() {
        // 关键回归：SharedPreferences 先改内存再写盘，commit 返回 false 时读回照样非空。
        // 只看读回就会误判成功并删掉明文；这里必须返回 VERIFY_FAILED 且不动 legacy。
        val enc = FakePrefs().apply { nextCommitResult = false }
        val legacy = FakePrefs().apply { values[KEY_API_KEY] = "old-key" }

        assertEquals(MigrationOutcome.VERIFY_FAILED, migrateCredentials(enc, legacy))

        assertEquals("old-key", enc.getString(KEY_API_KEY, null)) // 内存里确实“写”进去了
        assertEquals("old-key", legacy.getString(KEY_API_KEY, null))
        assertEquals(0, legacy.editCount)
    }

    @Test
    fun `迁移校验失败时保留legacy明文不清空`() {
        // 用一个「写入后读回仍为空」的 enc 模拟加密写入校验失败
        val enc = object : FakePrefs() {
            override fun getString(key: String, default: String?): String? = null
        }
        val legacy = FakePrefs().apply { values[KEY_API_KEY] = "old-key" }

        assertEquals(MigrationOutcome.VERIFY_FAILED, migrateCredentials(enc, legacy))
        // 校验失败绝不能清旧明文——否则密钥就真丢了
        assertEquals("old-key", legacy.getString(KEY_API_KEY, null))
        assertEquals(0, legacy.editCount)
    }

    // ---------------------------------------------------------------- //
    // 存储方式状态机：storageMode / isPlaintextFallback / needsCredentialReentry
    // ---------------------------------------------------------------- //

    /**
     * 伪造 Context：`applicationContext` 指向自身，`getSharedPreferences` 返回指定的
     * 旧明文 prefs（`binance_api`）。
     *
     * 刻意让两个角色共用同一个 mock（不用两个 mock 互相指向）：这样桩不会被
     * 「context → applicationContext → 另一个 mock」这层间接引丢。
     */
    private fun fakeContext(legacy: SharedPreferences): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSharedPreferences(any(), any()) } returns legacy
        return ctx
    }

    /** 构造一个「加密存储可用」的 manager */
    private fun managerWithEncryptedAvailable(legacy: SharedPreferences = FakePrefs()): BinanceCredentialsManager =
        BinanceCredentialsManager.createForTest(fakeContext(legacy)) { FakePrefs() }

    /**
     * 构造一个「Android Keystore 不可用」的 manager：加密 prefs 提供者直接抛异常，
     * 与真机上 `EncryptedSharedPreferences.create` 失败（Keystore 缺失 / 密钥不可恢复）等价。
     */
    private fun managerWithEncryptedUnavailable(legacy: SharedPreferences): BinanceCredentialsManager =
        BinanceCredentialsManager.createForTest(fakeContext(legacy)) {
            throw IllegalStateException("Android Keystore 不可用（单测注入）")
        }

    @Test
    fun `加密可用时storageMode为ENCRYPTED且两条告警都不出现`() {
        val manager = managerWithEncryptedAvailable()

        assertEquals(CredentialStorageMode.ENCRYPTED, manager.storageMode)
        assertFalse("加密可用不该报明文回退", manager.isPlaintextFallback)
        assertFalse("加密可用不该要求重新录入", manager.needsCredentialReentry)
    }

    @Test
    fun `加密不可用但旧明文有密钥时为PLAINTEXT_LEGACY且只提示明文风险`() {
        val legacy = FakePrefs().apply {
            values[KEY_API_KEY] = "old-key"
            values[KEY_API_SECRET] = "old-secret"
        }

        val manager = managerWithEncryptedUnavailable(legacy)

        // 这条断言曾经必挂：storageMode 的初始化器在 buildPrefs() 之后执行，把回退状态覆盖回 ENCRYPTED
        assertEquals(CredentialStorageMode.PLAINTEXT_LEGACY, manager.storageMode)
        assertTrue("回退明文必须让 UI 报警", manager.isPlaintextFallback)
        assertFalse("旧明文里还有密钥，不该要求重新录入", manager.needsCredentialReentry)
        assertEquals("回退后读的必须是旧明文文件", "old-key", manager.getApiKey())
    }

    @Test
    fun `加密不可用且旧明文为空时为PLAINTEXT_EMPTY且必须要求重新录入`() {
        val manager = managerWithEncryptedUnavailable(FakePrefs())

        // 同上：覆盖回 ENCRYPTED 时这条也必挂
        assertEquals(CredentialStorageMode.PLAINTEXT_EMPTY, manager.storageMode)
        assertTrue("回退明文必须让 UI 报警", manager.isPlaintextFallback)
        assertTrue("密钥已不可读，必须提示重新填写 API Key 与 Secret", manager.needsCredentialReentry)
        assertFalse("旧明文里确实没有密钥", manager.hasCredentials())
    }
}
