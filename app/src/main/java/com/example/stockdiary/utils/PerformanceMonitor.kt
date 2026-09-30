package com.example.stockdiary.utils

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * 性能监控工具
 * 用于记录应用性能指标，如启动时间、网络请求时间、数据库操作时间等
 */
object PerformanceMonitor {
    private val performanceMetrics = ConcurrentHashMap<String, MutableList<Long>>()
    private val startTimeMap = ConcurrentHashMap<String, Long>()
    private val startTimestampMap = ConcurrentHashMap<String, Long>()
    
    /**
     * 开始计时
     * @param tag 计时标签
     */
    fun start(tag: String) {
        startTimeMap[tag] = SystemClock.elapsedRealtime()
        startTimestampMap[tag] = System.currentTimeMillis()
    }
    
    /**
     * 结束计时并记录时间
     * @param tag 计时标签
     * @return 耗时（毫秒）
     */
    fun end(tag: String): Long {
        val startTime = startTimeMap.remove(tag)
        if (startTime == null) return 0
        
        val duration = SystemClock.elapsedRealtime() - startTime
        val metricList = performanceMetrics.getOrPut(tag) { mutableListOf() }
        metricList.add(duration)
        
        // 限制每个标签的记录数量
        if (metricList.size > 100) {
            metricList.removeAt(0)
        }
        
        return duration
    }
    
    /**
     * 记录性能指标
     * @param tag 指标标签
     * @param value 指标值
     */
    fun recordMetric(tag: String, value: Long) {
        val metricList = performanceMetrics.getOrPut(tag) { mutableListOf() }
        metricList.add(value)
        
        // 限制每个标签的记录数量
        if (metricList.size > 100) {
            metricList.removeAt(0)
        }
    }
    
    /**
     * 获取性能指标统计
     * @param tag 指标标签
     * @return 统计结果
     */
    fun getMetricStats(tag: String): MetricStats? {
        val metrics = performanceMetrics[tag]
        if (metrics == null || metrics.isEmpty()) return null
        
        val min = metrics.minOrNull() ?: 0
        val max = metrics.maxOrNull() ?: 0
        val avg = metrics.average().toLong()
        val total = metrics.sum()
        val count = metrics.size
        
        return MetricStats(min, max, avg, total, count)
    }
    
    /**
     * 清除所有性能指标
     */
    fun clear() {
        performanceMetrics.clear()
        startTimeMap.clear()
        startTimestampMap.clear()
    }
    
    /**
     * 导出性能报告
     * @return 性能报告字符串
     */
    fun exportReport(): String {
        val sb = StringBuilder()
        sb.append("性能报告\n")
        sb.append("====================\n")
        
        performanceMetrics.forEach { (tag, _) ->
            val stats = getMetricStats(tag)
            if (stats != null) {
                sb.append("$tag:\n")
                sb.append("  最小值: ${stats.min}ms\n")
                sb.append("  最大值: ${stats.max}ms\n")
                sb.append("  平均值: ${stats.avg}ms\n")
                sb.append("  总耗时: ${stats.total}ms\n")
                sb.append("  次数: ${stats.count}\n")
                sb.append("\n")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * 性能指标统计数据类
     */
    data class MetricStats(
        val min: Long,
        val max: Long,
        val avg: Long,
        val total: Long,
        val count: Int
    )
}
