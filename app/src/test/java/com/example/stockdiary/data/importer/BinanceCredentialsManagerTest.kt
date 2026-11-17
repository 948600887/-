package com.example.stockdiary.data.importer

import android.content.SharedPreferences
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
 * 不依赖 Robolectric / Keystore：只测抽出来的纯函数，EncryptedSharedPreferences
 * 的构造在原类里仍是 try-catch 兜底，不在本测试范围。
 */
class BinanceCredentialsManagerTest {

    /** 内存版 Editor：记录 commit / apply 各自调用次数 */
    private class RecordingEditor(private val values: MutableMap<String, String>) : SharedPreferences.Editor {
        var commitCount = 0
        var applyCount = 0
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

        override fun commit(): Boolean { commitCount++; flush(); return true }
        override fun apply() { applyCount++; flush() }
    }

    private open class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, String>()
        var lastEditor: RecordingEditor? = null
        var editCount = 0

        override fun edit(): SharedPreferences.Editor {
            editCount++
            return RecordingEditor(values).also { lastEditor = it }
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
        saveCredentials(prefs, "my-key", "my-secret")

        assertEquals(1, prefs.lastEditor!!.commitCount)
        assertEquals(0, prefs.lastEditor!!.applyCount)
        assertEquals("my-key", prefs.getString(KEY_API_KEY, null))
        assertEquals("my-secret", prefs.getString(KEY_API_SECRET, null))
    }

    @Test
    fun `clear走commit同步落盘且apply零调用`() {
        val prefs = FakePrefs().apply {
            values[KEY_API_KEY] = "old-key"
            values[KEY_API_SECRET] = "old-secret"
        }
        clearCredentials(prefs)

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

        // enc 写入走 commit
        assertEquals(1, enc.lastEditor!!.commitCount)
        assertEquals(0, enc.lastEditor!!.applyCount)
        assertEquals("old-key", enc.getString(KEY_API_KEY, null))
        assertEquals("old-secret", enc.getString(KEY_API_SECRET, null))
        // legacy 清空也走 commit
        assertEquals(1, legacy.lastEditor!!.commitCount)
        assertEquals(0, legacy.lastEditor!!.applyCount)
        assertNull(legacy.getString(KEY_API_KEY, null))
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
}
