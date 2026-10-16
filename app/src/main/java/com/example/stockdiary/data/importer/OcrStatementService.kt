package com.example.stockdiary.data.importer

import android.content.Context
import android.net.Uri
import com.example.stockdiary.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 交割单截图 OCR 导入服务。
 *
 * 只负责「图片 → 视觉行 → 解析为 ParsedTransaction 列表」。
 * 提交入库统一走 [BrokerageImportService.commit]，避免逻辑重复。
 *
 * 「图片 → 视觉行」那一段（解码降采样、长截图分块、ML Kit 按列切块的还原）
 * 已经搬到 [ImageTextRecognizer]——它跟"这是交割单还是持仓页"无关，
 * 持仓导入也走同一条路。这里只留交割单特有的部分：
 * 年份推断/兜底、以及喂给 [GalaxyStatementParser]。
 *
 * 目前适配【中国银河证券】对账单截图格式：
 *   第1行：证券买入-中证红利              1.546    -1,082.30
 *   第2行：买 09-23 10:48                700      0.10
 *
 *  首行：操作-名称  价格  金额
 *  次行：方向 日期(+时间)  数量  费用
 *
 * 限制：
 * - 截图无证券代码，解析后 symbol 为空（errors 中含「需手动填写证券代码」），
 *   需用户在预览页手动补填；补填后 [BrokerageImportService.commit] 才会处理。
 * - OCR 可能识别错数字，预览页必须人工核对。
 */
class OcrStatementService private constructor(
    private val recognizer: ImageTextRecognizer
) {

    data class OcrResult(
        val transactions: List<BrokerageImportService.ParsedTransaction>,
        val recognizedText: String,
        val year: Int,
        /** 年份是截图里读到的（true）还是按当前年份兜底的（false） */
        val yearFromImage: Boolean,
        /**
         * 重建出的「视觉行」（同一行的多列已合并）。
         * 解析出问题时，这一份数据比原始文本更能说明问题出在行还原还是行配对，
         * 预览页可以把它展示/复制出来。
         */
        val lines: List<String> = emptyList()
    )

    companion object {
        @Volatile
        private var instance: OcrStatementService? = null

        fun getInstance(context: Context): OcrStatementService {
            return instance ?: synchronized(this) {
                instance ?: OcrStatementService(ImageTextRecognizer.getInstance(context))
                    .also { instance = it }
            }
        }

        // 找年份：截图顶部通常有"2026-09"
        private val YEAR_REGEX = GalaxyStatementParser.YEAR_REGEX

        /** 供测试与外部复用：分块行合并的实现在 [ImageTextRecognizer]。 */
        internal fun mergeBlockLines(blockLines: List<List<String>>): List<String> =
            ImageTextRecognizer.mergeBlockLines(blockLines)
    }

    /**
     * 从图片 URI 执行 OCR 并解析成交割单交易列表。
     */
    suspend fun recognizeAndParse(imageUri: Uri): OcrResult = withContext(Dispatchers.IO) {
        val recognized = recognizer.recognize(imageUri)
        val fullText = recognized.text
        AppLogger.d("OCR 识别文本:\n$fullText", "OcrStatement")

        // 推断年份
        val yearFromImage = YEAR_REGEX.find(fullText)?.groupValues?.get(1)?.toIntOrNull()
        val year = yearFromImage ?: LocalDate.now().year

        val lines = recognized.lines
        AppLogger.i("排序后共 ${lines.size} 行，开始解析, year=$year", "OcrStatement")
        var transactions = GalaxyStatementParser.parse(lines, year, ::logParser)

        // 兜底：结构化行解析不到东西时，用 ML Kit 的原始整文本（按换行切）再试一次。
        // 典型触发场景是识别块的坐标信息缺失或不连贯，导致按 Y 排序的结果不可用。
        if (transactions.isEmpty()) {
            val rawLines = fullText.lines()
                .map { rawLine -> rawLine.trim() }
                .filter { rawLine -> rawLine.isNotEmpty() }
            if (rawLines.isNotEmpty() && rawLines != lines) {
                AppLogger.w(
                    "结构化行解析无结果，改用原始文本兜底重试（${rawLines.size} 行）",
                    "OcrStatement"
                )
                val fallback = GalaxyStatementParser.parse(rawLines, year, ::logParser)
                if (fallback.size > transactions.size) transactions = fallback
            }
        }

        if (yearFromImage == null) {
            // 截图没有年份时按当前年份兜底，并把「未来日期」整体回退一年——
            // 对账单不会包含尚未发生的交易，出现未来日期几乎一定是往年的单子。
            // 逐行判断而不是整体切年，跨年对账单（12 月 + 次年 1 月）也能各自落到正确的年份。
            val today = LocalDate.now().toString()
            val shifted = transactions.count { it.date > today }
            if (shifted > 0) {
                transactions = transactions.map { tx ->
                    if (tx.date > today) {
                        tx.copy(date = "${year - 1}${tx.date.removePrefix(year.toString())}")
                    } else {
                        tx
                    }
                }
                AppLogger.w(
                    "截图未包含年份，$shifted 笔日期落在未来，已回退到 ${year - 1} 年",
                    "OcrStatement"
                )
            }
            AppLogger.w(
                "截图未包含年份，已按当前年份 $year 兜底解析；请在预览页核对日期",
                "OcrStatement"
            )
        }

        // 明细没配上的比例偏高时明确告警：这几乎总是行还原出了问题，
        // 而不是截图本身没这段信息（否则这一笔连首行都认不出来）。
        val incomplete = transactions.count {
            it.errors.contains(GalaxyStatementParser.ERR_DETAIL_MISSING)
        }
        if (incomplete > 0) {
            AppLogger.w(
                "有 $incomplete/${transactions.size} 笔没配上明细行（缺日期/数量）。" +
                    "还原后的行共 ${lines.size} 行，可导出「识别原文」核对",
                "OcrStatement"
            )
        }

        OcrResult(
            transactions = transactions,
            recognizedText = fullText,
            year = year,
            yearFromImage = yearFromImage != null,
            lines = lines
        )
    }

    private fun logParser(msg: String) {
        if (msg.startsWith("WARN")) AppLogger.w(msg, "GalaxyParser")
        else AppLogger.d(msg, "GalaxyParser")
    }
}
