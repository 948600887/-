package com.example.stockdiary.data.importer

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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 「图片 → 视觉行」的通用能力。
 *
 * ## 为什么从 OcrStatementService 里搬出来
 *
 * 这段逻辑（解码降采样、长截图分块、把 ML Kit 按列切开的碎片重建成表格行）
 * 跟「这是交割单还是持仓页」没有半点关系，它只认「一张有表格的截图」。
 * 原先它私有关在交割单服务里，持仓导入要用只能复制第二份——
 * 而这段代码的每一处细节（重叠比例、间距阈值、位图回收时机）都是踩坑踩出来的，
 * 复制一份迟早两边跑偏。
 *
 * ## 输出的「视觉行」是什么
 *
 * 截图是表格，ML Kit 会**按列切块**：一行里的「名称 / 数量 / 冻结」会变成三条独立文本行。
 * 这里按垂直区间重叠把它们聚回一行，组内按 X 从左到右拼成
 * `黄金LOF 262 -` 这样的整行。上层解析器拿到的就是这种整行。
 */
class ImageTextRecognizer private constructor(private val context: Context) {

    /** 一次识别任务的产物：还原后的视觉行、原始文本、ML Kit 报告的行数 */
    data class RecognizedText(
        val lines: List<String>,
        val text: String,
        val lineCount: Int
    )

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ImageTextRecognizer? = null

        /**
         * 解码后的像素总量上限（约 48MB 位图）。
         *
         * 用「总像素」而不是「最长边」控制降采样：对账单经常被做成长截图，
         * 它的最长边天然很大，按最长边卡会把整张压扁到认不出字——
         * 1080×5888 的长截图按最长边 2400 卡会降到 270×1472，每个字只剩几像素。
         * 长截图改用下面 [MAX_BLOCK_HEIGHT] 分块识别来保证精度，不靠降采样。
         */
        private const val MAX_DECODE_PIXELS = 12_000_000L

        /**
         * 单次交给 ML Kit 的图像高度上限。超过就按高度分块，每块在原始分辨率下识别。
         *
         * 取 2400：普通手机截图（最长边普遍在 2400 以内）走单块整图识别这条最稳的路径，
         * 不给它引入块边界；只有真正的长截图才会被切开。
         */
        private const val MAX_BLOCK_HEIGHT = 2400

        /**
         * 相邻分块的重叠高度。避免块边界正好把一行切成两半，
         * 拼接时再按最长的重复前缀把重叠部分去掉。
         */
        private const val BLOCK_OVERLAP = 120

        /**
         * 判定两个文本片段属于同一视觉行的垂直区间重叠比例下限。
         * 同一行的片段几乎完全重叠（≈1.0），相邻行几乎不重叠（≈0）；
         * 取 0.6 给字号差异、上下标留出余量。
         */
        private const val SAME_ROW_OVERLAP_RATIO = 0.6f

        /**
         * 判断同一行内两个元素之间要不要插空格：间距超过「上一个元素平均字宽」的这个比例才插。
         *
         * 取 0.35 的理由——真空格的宽度约为一个数字宽度的 0.5 倍，必须大于阈值才会被识别成空格；
         * 而 ML Kit 把一个数字从中间切开时两段几乎贴在一起（间距接近 0），不会被误插空格。
         * 阈值偏向"少插空格"是刻意的：粘连只是解析失败（可见），
         * 而把两个独立数字用空格分开是安全的，反过来才会造成静默错数。
         */
        private const val ELEMENT_GAP_RATIO = 0.35f

        fun getInstance(context: Context): ImageTextRecognizer {
            return instance ?: synchronized(this) {
                instance ?: ImageTextRecognizer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 把各分块还原出的行按顺序拼回去。
         *
         * 块之间留了重叠，因此后一块开头会重复上一块末尾的一到几行；
         * 取「最长的相同前缀」去掉它们——只比前缀、且要求整段相同，
         * 不会误删两块交界处两笔内容恰好相同的独立交易。
         */
        internal fun mergeBlockLines(blockLines: List<List<String>>): List<String> {
            val out = mutableListOf<String>()
            for (lines in blockLines) {
                var overlap = 0
                val maxCheck = minOf(lines.size, out.size, 4)
                for (k in maxCheck downTo 1) {
                    if (out.subList(out.size - k, out.size) == lines.subList(0, k)) {
                        overlap = k
                        break
                    }
                }
                out.addAll(lines.drop(overlap))
            }
            return out
        }
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

    /** 从图片 URI 识别出视觉行。上层拿到结果后自行解析成业务模型。 */
    suspend fun recognize(imageUri: Uri): RecognizedText = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(imageUri)
        // 位图生命周期由 recognizeAll / recognizeText 内部管理：必须在 ML Kit 回调
        // （识别真正结束）后回收，否则协程取消时提前 recycle，ML Kit 后台线程仍在读
        // 会抛 "trying to use a recycled bitmap" 崩溃。
        val recognized = recognizeAll(bitmap)
        AppLogger.i("OCR 识别 ${recognized.lineCount} 行文字", "ImageTextRecognizer")
        recognized
    }

    /**
     * 两遍解码：先只读尺寸（不分配像素内存），据此算出采样率，再真正解码。
     */
    private suspend fun decodeBitmap(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：bounds 模式下 decodeStream 恒返回 null（只填 outWidth/outHeight），
        // 所以这里不能把 use{} 的返回值接给 Elvis 判空——返回值是 decodeStream 的 null，
        // 不是流打开失败。流是否打开失败必须在 use 之前单独判断。
        val boundsStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开图片（可能无读取权限或文件已被删除）")
        boundsStream.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("无法解码图片（格式不支持或文件损坏）")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val pixelStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开图片（可能无读取权限或文件已被删除）")
        pixelStream.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalStateException("无法解码图片（内存不足或格式不支持）")
    }

    /** 取最小的 2 的幂，使解码后的像素总量不超过 [MAX_DECODE_PIXELS]（inSampleSize 只认 2 的幂） */
    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sample = 1
        val pixels = width.toLong() * height.toLong()
        while (pixels / (sample.toLong() * sample) > MAX_DECODE_PIXELS) sample *= 2
        return sample
    }

    /**
     * 识别整张图，必要时分块。
     *
     * 长截图整张丢给 ML Kit 会因为降采样而糊掉（见 [MAX_DECODE_PIXELS] 的说明），
     * 所以超过 [MAX_BLOCK_HEIGHT] 就按高度切块分别识别，再按顺序把行拼回去。
     */
    private suspend fun recognizeAll(bitmap: Bitmap): RecognizedText {
        if (bitmap.height <= MAX_BLOCK_HEIGHT) {
            val text = recognizeText(bitmap)
            return RecognizedText(
                lines = extractSortedLines(text),
                text = text.text,
                lineCount = text.textBlocks.sumOf { it.lines.size }
            )
        }

        AppLogger.i(
            "长截图 ${bitmap.width}x${bitmap.height} 按高度分块识别（块高 $MAX_BLOCK_HEIGHT，重叠 $BLOCK_OVERLAP）",
            "ImageTextRecognizer"
        )
        val blockLines = mutableListOf<List<String>>()
        val texts = mutableListOf<String>()
        var lineCount = 0
        var blockCount = 0
        var y = 0
        try {
            while (y < bitmap.height) {
                val h = minOf(MAX_BLOCK_HEIGHT, bitmap.height - y)
                // createBitmap 只对整图范围返回原对象，这里的块一定比原图矮
                val block = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, h)
                // recognizeText 会在这一块的识别真正结束后回收它
                val text = recognizeText(block)
                blockLines.add(extractSortedLines(text))
                texts.add(text.text)
                blockCount++
                lineCount += text.textBlocks.sumOf { it.lines.size }
                if (y + h >= bitmap.height) break
                y += maxOf(h - BLOCK_OVERLAP, 1)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        val merged = mergeBlockLines(blockLines)
        AppLogger.i("分块识别完成：$blockCount 块，还原出 ${merged.size} 行", "ImageTextRecognizer")
        return RecognizedText(merged, texts.joinToString("\n"), lineCount)
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
        data class Fragment(val top: Int, val bottom: Int, val left: Int, val content: String) {
            val centerY: Int get() = (top + bottom) / 2
        }

        val fragments = mutableListOf<Fragment>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val content = joinElements(line.elements)
                if (content.isBlank()) continue
                val box = line.boundingBox
                fragments.add(
                    Fragment(
                        top = box?.top ?: 0,
                        bottom = box?.bottom ?: 0,
                        left = box?.left ?: 0,
                        content = content
                    )
                )
            }
        }
        if (fragments.isEmpty()) return emptyList()

        // 用「垂直区间重叠」判断两个片段是否属于同一视觉行，而不是比较中心点距离。
        // 距离法依赖容差（= 行高中位数的一半），一旦 OCR 报出的行高偏大，
        // 容差就跟着变大，相邻两行会被粘成一行——表现为「每一笔都配不上明细」。
        // 区间重叠是相对判断：同一行的片段几乎完全重叠，相邻行几乎不重叠，不受行高绝对值影响。
        val rows = mutableListOf<MutableList<Fragment>>()
        var anchorTop = 0
        var anchorBottom = 0
        for (fragment in fragments.sortedBy { it.centerY }) {
            val hasBox = fragment.bottom > fragment.top
            // 拿不到坐标的片段一律自成一组，不去猜它属于哪一行
            val sameRow = rows.isNotEmpty() &&
                hasBox && anchorBottom > anchorTop &&
                verticalOverlapRatio(fragment.top, fragment.bottom, anchorTop, anchorBottom) >= SAME_ROW_OVERLAP_RATIO
            if (sameRow) {
                rows.last().add(fragment)
            } else {
                rows.add(mutableListOf(fragment))
                anchorTop = fragment.top
                anchorBottom = fragment.bottom
            }
        }
        return rows.map { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.content }.trim()
        }
    }

    /** 两个垂直区间重叠部分占「较矮者」的比例（0~1） */
    private fun verticalOverlapRatio(aTop: Int, aBottom: Int, bTop: Int, bBottom: Int): Float {
        val overlap = minOf(aBottom, bBottom) - maxOf(aTop, bTop)
        if (overlap <= 0) return 0f
        val shorter = minOf(aBottom - aTop, bBottom - bTop).coerceAtLeast(1)
        return overlap.toFloat() / shorter
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
