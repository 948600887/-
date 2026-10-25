package com.example.stockdiary.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * [DateFormats] 的回归：锁口径、锁缓存契约、锁线程安全。
 *
 * 常量值是本项目的「接口」——导出文件、行情解析、日志都按它走，
 * 改一个字符就是跨模块行为变更，所以逐个断言钉死。
 */
class DateFormatsTest {

    @Test
    fun `常量值与历史散落字面量逐一一致`() {
        assertEquals("yyyy-MM-dd", DateFormats.ISO_DATE)
        assertEquals("yyyy-MM-dd HH:mm:ss", DateFormats.ISO_DATETIME)
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", DateFormats.ISO_DATETIME_MILLIS)
        assertEquals("yyyy-MM-dd HH:mm", DateFormats.DISPLAY_DATETIME)
        assertEquals("yyyyMMdd", DateFormats.COMPACT_DATE)
        assertEquals("yyyy/MM/dd", DateFormats.SLASH_DATE)
        assertEquals("yyyy年MM月dd日", DateFormats.CN_DATE)
        assertEquals("yyyy年MM月dd日 HH:mm", DateFormats.CN_DATETIME)
        assertEquals("yyyy年MM月", DateFormats.CN_MONTH)
        assertEquals("HH:mm", DateFormats.TIME_ONLY)
        assertEquals("MM-dd HH:mm", DateFormats.MONTH_DAY_TIME)
        assertEquals("yyyyMMdd_HHmmss", DateFormats.FILE_TIMESTAMP)
    }

    @Test
    fun `预构造 formatter 按各自 pattern 工作`() {
        val date = LocalDate.of(2026, 10, 25)
        assertEquals("2026-10-25", date.format(DateFormats.ISO_DATE_FORMATTER))
        assertEquals("20261025", date.format(DateFormats.COMPACT_DATE_FORMATTER))
        assertEquals("2026/10/25", date.format(DateFormats.SLASH_DATE_FORMATTER))
        assertEquals("2026年10月25日", date.format(DateFormats.CN_DATE_FORMATTER))
        assertEquals("2026年10月", date.format(DateFormats.CN_MONTH_FORMATTER))
        val dateTime = LocalDateTime.of(2026, 10, 25, 18, 38, 48)
        assertEquals("2026-10-25 18:38:48", dateTime.format(DateFormats.ISO_DATETIME_FORMATTER))
        assertEquals("2026-10-25 18:38:48.000", dateTime.format(DateFormats.ISO_DATETIME_MILLIS_FORMATTER))
        assertEquals("2026年10月25日 18:38", dateTime.format(DateFormats.CN_DATETIME_FORMATTER))
        assertEquals("20261025_183848", dateTime.format(DateFormats.FILE_TIMESTAMP_FORMATTER))
    }

    @Test
    fun `预构造 formatter 可双向解析`() {
        assertEquals(LocalDate.of(2026, 10, 25), LocalDate.parse("2026-10-25", DateFormats.ISO_DATE_FORMATTER))
        assertEquals(LocalDate.of(2026, 10, 25), LocalDate.parse("20261025", DateFormats.COMPACT_DATE_FORMATTER))
        assertEquals(LocalDate.of(2026, 10, 25), LocalDate.parse("2026/10/25", DateFormats.SLASH_DATE_FORMATTER))
    }

    @Test
    fun `ofPattern 同 pattern 返回同一实例（缓存契约）`() {
        val first = DateFormats.ofPattern("yyyy-MM-dd")
        val second = DateFormats.ofPattern("yyyy-MM-dd")
        assertSame("同一 pattern 必须命中缓存返回同一实例，否则失去缓存意义", first, second)
        val other = DateFormats.ofPattern("yyyy/MM/dd")
        assertTrue("不同 pattern 必须是不同实例", first !== other)
    }

    @Test
    fun `ofPattern 动态 pattern 格式化正确`() {
        val date = LocalDate.of(2026, 1, 5)
        assertEquals("2026-01-05", date.format(DateFormats.ofPattern("yyyy-MM-dd")))
        assertEquals("05/01/2026", date.format(DateFormats.ofPattern("dd/MM/yyyy")))
    }

    /** 日历 42 格/图表刻度是并发刷的重，formatter 必须线程安全（DateTimeFormatter 本身不可变，这里锁住别退化） */
    @Test
    fun `并发格式化不抛异常且结果一致`() {
        val threads = 8
        val perThread = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val wrongResults = ConcurrentLinkedQueue<String>()

        repeat(threads) {
            Thread {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        val d = LocalDate.of(2026, 10, 25).plusDays(i.toLong() % 28)
                        val expected = "%04d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
                        val actual = d.format(DateFormats.ISO_DATE_FORMATTER)
                        if (actual != expected) wrongResults.add("$actual != $expected")
                        // 动态入口也并发打
                        d.format(DateFormats.ofPattern("yyyyMMdd"))
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.await()

        assertTrue("并发格式化抛异常: $failures", failures.isEmpty())
        assertTrue("并发格式化结果不一致: $wrongResults", wrongResults.isEmpty())
    }

    @Test
    fun `新实例非空且静态 formatter 与缓存入口同实例`() {
        assertNotNull(DateFormats.ISO_DATE_FORMATTER)
        // ISO_DATE_FORMATTER 与 ofPattern(ISO_DATE) 收敛到同一实例：静态入口不建第二套
        assertSame(DateFormats.ISO_DATE_FORMATTER, DateFormats.ofPattern(DateFormats.ISO_DATE))
    }
}
