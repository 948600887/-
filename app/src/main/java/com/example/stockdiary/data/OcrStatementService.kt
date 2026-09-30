package com.example.stockdiary.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.stockdiary.utils.AppLogger
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * 交割单截图 OCR 导入服务。
 *
 * 只负责「图片 → 文本 → 解析为 ParsedTransaction 列表」。
 * 提交入库统一走 [BrokerageImportService.commit]，避免逻辑重复。
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
class OcrStatementService private constructor(private val context: Context) {

    data class OcrResult(
        val transactions: List<BrokerageImportService.ParsedTransaction>,
        val recognizedText: String,
        val year: Int,
        /** 年份是截图里读到的（true）还是按当前年份兜底的（false） */
        val yearFromImage: Boolean
    )

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: OcrStatementService? = null

        /**
         * 解码后的最长边上限。手机截图最长边通常在 2400 以内，会被完整保留（不降采样），
         * 避免损失识别精度；只有明显超大的照片才会等比缩小，防止几十 MB 的位图把内存打满。
         */
        private const val MAX_DECODE_EDGE = 2400

        /**
         * 判断同一行内两个元素之间要不要插空格：间距超过「上一个元素平均字宽」的这个比例才插。
         *
         * 取 0.35 的理由——真空格的宽度约为一个数字宽度的 0.5 倍，必须大于阈值才会被识别成空格；
         * 而 ML Kit 把一个数字从中间切开时两段几乎贴在一起（间距接近 0），不会被误插空格。
         * 阈值偏向"少插空格"是刻意的：粘连只是解析失败（可见），
         * 而把两个独立数字用空格分开是安全的，反过来才会造成静默错数。
         */
        private const val ELEMENT_GAP_RATIO = 0.35f

        fun getInstance(context: Context): OcrStatementService {
            return instance ?: synchronized(this) {
                instance ?: OcrStatementService(context.applicationContext).also { instance = it }
            }
        }

        // 找年份：截图顶部通常有"2026-09"
        private val YEAR_REGEX = GalaxyStatementParser.YEAR_REGEX
    }

    /**
     * 复用同一个识别器。每次识别都新建+关闭 TextRecognizer 会有几百毫秒的模型初始化开销，
     * 连续导入多张截图时尤其明显。
     */
    @Volatile
    private var recognizer: TextRecognizer? = null

    private fun obtainRecognizer(): TextRecognizer =
        recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build()
            ).also { recognizer = it }
        }

    /**
     * 从图片 URI 执行 OCR 并解析成交割单交易列表。
     */
    suspend fun recognizeAndParse(imageUri: Uri): OcrResult = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(imageUri)
        // 位图生命周期由 recognizeText 内部管理：必须在 ML Kit 回调（识别真正结束）后回收，
        // 否则协程取消时 finally 提前 recycle，ML Kit 后台线程仍在读会抛
        // "trying to use a recycled bitmap" 崩溃。
        val text = recognizeText(bitmap)

        val fullText = text.text
        AppLogger.d("OCR 识别文本:\n$fullText", "OcrStatement")
        AppLogger.i("OCR 识别 ${text.textBlocks.sumOf { it.lines.size }} 行文字", "OcrStatement")

        // 推断年份
        val yearFromImage = YEAR_REGEX.find(fullText)?.groupValues?.get(1)?.toIntOrNull()
        val year = yearFromImage ?: LocalDate.now().year

        val lines = extractSortedLines(text)
        AppLogger.i("排序后共 ${lines.size} 行，开始解析, year=$year", "OcrStatement")
        var transactions = GalaxyStatementParser.parse(lines, year, ::logParser)

        // 兜底：结构化行解析不到东西时，用 ML Kit 的原始整文本（按换行切）再试一次。
        // 典型触发场景是识别块的坐标信息缺失或不连贯，导致按 Y 排序的结果不可用。
        if (transactions.isEmpty()) {
            val rawLines = fullText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
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

        OcrResult(
            transactions = transactions,
            recognizedText = fullText,
            year = year,
            yearFromImage = yearFromImage != null
        )
    }

    private fun logParser(msg: String) {
        if (msg.startsWith("WARN")) AppLogger.w(msg, "GalaxyParser")
        else AppLogger.d(msg, "GalaxyParser")
    }

    /**
     * 两遍解码：先只读尺寸（不分配像素内存），据此算出采样率，再真正解码。
     */
    private suspend fun decodeBitmap(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: throw IllegalStateException("无法解码图片")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("图片尺寸无效，可能不是有效的图片文件")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalStateException("无法解码图片")
    }

    /** 取最小的 2 的幂，使最长边不超过 [maxEdge]（inSampleSize 只认 2 的幂） */
    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxEdge) sample *= 2
        return sample
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            fun recycleSafely() {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            val image = InputImage.fromBitmap(bitmap, 0)
            try {
                obtainRecognizer().process(image)
                    .addOnSuccessListener { result ->
                        // 识别真正结束才释放位图；协程已取消也要释放，避免泄漏
                        recycleSafely()
                        if (cont.isActive) cont.resume(result)
                    }
                    .addOnFailureListener { e ->
                        recycleSafely()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
            } catch (e: Exception) {
                // 识别器构建/调用本身失败（非协程取消路径），此处回收是安全的
                recycleSafely()
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /**
     * 把 ML Kit 的识别结果重建成「视觉行」。
     *
     * 对账单是一张表格，ML Kit 会**按列切块**——一行里的「名称 / 价格 / 金额」
     * 会变成三条独立文本行。如果只按 Y 排序、不做合并，一笔交易会被拆成 6 行
     * （名称、价格、金额、日期、数量、费用），后续按行配对必然全部失败。
     *
     * 做法：先按**垂直中心**把属于同一视觉行的片段聚成一组（容差取行高的一半），
     * 组内再按 X 从左到右拼接，还原出
     * `证券买入-中证红利 1.546 -1,082.30` 这样的整行。
     */
    private fun extractSortedLines(text: Text): List<String> {
        data class Fragment(val centerY: Int, val left: Int, val content: String)

        val fragments = mutableListOf<Fragment>()
        val heights = mutableListOf<Int>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val content = joinElements(line.elements)
                if (content.isBlank()) continue
                val box = line.boundingBox
                if (box != null && box.height() > 0) heights.add(box.height())
                fragments.add(
                    Fragment(
                        centerY = if (box != null) (box.top + box.bottom) / 2 else 0,
                        left = box?.left ?: 0,
                        content = content
                    )
                )
            }
        }
        if (fragments.isEmpty()) return emptyList()

        // 容差基准取行高的中位数。拿不到坐标时退化成最小值，等价于「每行各自成组」，
        // 与旧行为一致，不会比原来更差。
        val tolerance = if (heights.isEmpty()) 4 else maxOf(4, median(heights) / 2)

        val rows = mutableListOf<MutableList<Fragment>>()
        for (fragment in fragments.sortedBy { it.centerY }) {
            val current = rows.lastOrNull()
            val anchorY = current?.first()?.centerY
            if (current != null && anchorY != null && abs(fragment.centerY - anchorY) <= tolerance) {
                current.add(fragment)
            } else {
                rows.add(mutableListOf(fragment))
            }
        }
        return rows.map { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.content }.trim()
        }
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * 拼接同一行内的元素。
     *
     * 关键：不能无条件用空格分隔。ML Kit 经常把一个数字拆成多个元素
     * （"1.546" → "1.546" / "1" + "." + "546"），若无脑加空格会得到 "1 . 546"，
     * 后续正则就没法把价格当成一个数字，整笔交易直接解析失败。
     * 因此按元素之间的水平间距决定是否插空格——见 [ELEMENT_GAP_RATIO]。
     */
    private fun joinElements(elements: List<Text.Element>): String {
        if (elements.isEmpty()) return ""
        val sorted = elements.sortedBy { it.boundingBox?.left ?: 0 }
        val sb = StringBuilder()
        var prevRight = 0
        var prevWidth = 0
        var prevCharCount = 0

        sorted.forEachIndexed { index, element ->
            val box = element.boundingBox
            if (index == 0) {
                sb.append(element.text)
            } else {
                val gap = if (box != null) box.left - prevRight else 0
                val avgCharWidth =
                    if (prevCharCount > 0) prevWidth.toFloat() / prevCharCount else 0f
                val needSpace = gap > 0 && (avgCharWidth <= 0f || gap > avgCharWidth * ELEMENT_GAP_RATIO)
                if (needSpace) sb.append(' ')
                sb.append(element.text)
            }
            if (box != null) {
                prevRight = box.right
                prevWidth = box.width()
                prevCharCount = element.text.length
            } else {
                prevCharCount = 0
            }
        }
        return sb.toString().trim()
    }
}
