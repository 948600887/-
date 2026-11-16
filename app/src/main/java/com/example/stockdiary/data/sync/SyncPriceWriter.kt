package com.example.stockdiary.data.sync

import com.example.stockdiary.data.contract.PendingPriceWrite
import com.example.stockdiary.data.repository.AssetRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 同步期的价格批量落库器（原 [AssetSyncManager] 的私有嵌套类，外提为独立组件）。
 *
 * ## 为什么必须有它
 *
 * Assets 表每写一行，Room 就整表重查重发一次；逐只标的单独 UPDATE 会让
 * 「一次刷新 = N 次整页重组」。这里把拿到的价先攒在缓冲区，攒够一批才用一个事务
 * 写下去，Room 的失效通知次数约为原来的 1/8（批大小见
 * `AssetSyncManager.PRICE_WRITE_BATCH_SIZE`）。
 *
 * ## 失败语义（刻意与改造前对齐）
 *
 * 凑满一批触发的 flush 是**在调用方（逐标的同步协程）自己的协程里同步执行**的，
 * 落库异常照旧冒到调用方的 catch —— 与改造前「写库失败算这个标的失败」的语义一致，
 * 不会悄悄吞掉一次写库故障。只有收尾那次 [flushAll] 无法归属到单个标的，
 * 才由调用方退化为记一条告警。
 *
 * ## 并发
 *
 * 多个并发标的都可能同时放价。缓冲区用无锁队列；flush 用 [Mutex] 串行：
 * 已在 flush 中的协程会把缓冲区**一次抽干**（含别的协程刚放进去的），
 * 后来的协程用 [Mutex.tryLock] 抢不到锁就直接返回——它不该在信号量名额里
 * 为一个已经在进行的写操作空等。
 */
internal class SyncPriceWriter(
    private val repository: AssetRepository,
    private val batchSize: Int
) {
    private val pending = ConcurrentLinkedQueue<PendingPriceWrite>()
    private val flushMutex = Mutex()

    /** 放入一条落价意图；缓冲区够大就顺手 flush 一批（可能抛，交由调用方的 catch 记账） */
    suspend fun write(write: PendingPriceWrite) {
        pending.offer(write)
        flushIfBatchFull()
    }

    /** 收尾：把缓冲区里剩下的全写掉。同步被取消时也要调用，否则会丢价 */
    suspend fun flushAll() {
        flushMutex.withLock { drainAllLocked() }
    }

    private suspend fun flushIfBatchFull() {
        if (pending.size < batchSize) return
        if (!flushMutex.tryLock()) return
        try {
            drainAllLocked()
        } finally {
            flushMutex.unlock()
        }
    }

    /** 调用方必须持有 [flushMutex]。循环抽干：别的协程可能在 flush 期间又放进了新的 */
    private suspend fun drainAllLocked() {
        while (true) {
            val batch = ArrayList<PendingPriceWrite>(batchSize)
            repeat(batchSize) { pending.poll()?.let(batch::add) }
            if (batch.isEmpty()) return
            repository.updatePricesInTransaction(batch)
        }
    }
}
