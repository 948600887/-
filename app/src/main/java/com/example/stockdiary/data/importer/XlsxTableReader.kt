package com.example.stockdiary.data.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 轻量 xlsx 读取器：把第一个工作表读成「行 → 单元格文本」的二维表。
 *
 * ## 为什么不引 Apache POI
 *
 * 我们只需要「把一张表读出来」这一件事。POI 在 Android 上体积大、方法数多，
 * 还得处理与 Android 框架重复类的兼容问题——为了读一张导出表把 APK 撑大几 MB 不划算。
 * xlsx 本身就是「zip 里装几个 XML」，Android 自带的 [ZipInputStream] 与 [Xml] 足够，
 * **零额外依赖**。
 *
 * ## 边界（刻意只做这么多）
 *
 * 只认「sharedStrings + 第一个工作表」这种最常见的导出形态：
 * 合并单元格、公式求值、日期序列号、多工作表都不处理。
 * 导出的对账单用不到这些，真遇到了在 [Result] 里报错也比悄悄读错强。
 */
object XlsxTableReader {

    /** 读出来的表：按行号顺序，每行按列号补齐，缺失的单元格是空串 */
    data class Table(val rows: List<List<String>>)

    private const val SHARED_STRINGS_PATH = "xl/sharedStrings.xml"
    private const val SHEET_DIR = "xl/worksheets/"

    /**
     * 单个 XML 的上限。正常的导出表解压后也就几百 KB，
     * 这个上限只为挡住异常文件把内存吃光。
     */
    private const val MAX_XML_BYTES = 32 * 1024 * 1024

    /**
     * 读第一个工作表。
     *
     * 用 [ZipInputStream] 而不是 `ZipFile`：导入进来的是 Uri，通常拿不到可直接随机访问的路径。
     * 代价是只能顺序扫一遍，因此两个 XML 都先取成字节**再**解析——
     * 单元格里的文本要靠 sharedStrings 的下标还原，顺序上不能先解析工作表。
     */
    fun read(input: InputStream): Table {
        var sharedBytes: ByteArray? = null
        var sheetBytes: ByteArray? = null

        ZipInputStream(input.buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == SHARED_STRINGS_PATH -> sharedBytes = zip.readCappedBytes()
                    // 只取第一个工作表：导出的对账单都是单表，多出来的往往是模板页或水印页
                    sheetBytes == null && name.startsWith(SHEET_DIR) && name.endsWith(".xml") ->
                        sheetBytes = zip.readCappedBytes()
                }
                entry = zip.nextEntry
            }
        }

        val sheet = sheetBytes
            ?: throw IllegalArgumentException("这个文件里没有工作表，可能不是一份 Excel 导出")
        val shared = sharedBytes?.let { parseSharedStrings(it) } ?: emptyList()
        return Table(parseSheet(sheet, shared))
    }

    private fun InputStream.readCappedBytes(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_XML_BYTES) {
                throw IllegalArgumentException("表格内容过大，已放弃读取")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /** 解析 xl/sharedStrings.xml：每个 `<si>` 可能由多个 `<t>` 拼成（富文本分段） */
    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val parser = newParser(bytes)
        var text = StringBuilder()
        var inSi = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> {
                        text = StringBuilder()
                        inSi = true
                    }
                    // 不用 nextText()：它会一路读到结束标签，把外层循环的事件流冲乱
                    "t" -> if (inSi && parser.next() == XmlPullParser.TEXT) {
                        text.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "si" && inSi) {
                    out.add(text.toString())
                    inSi = false
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * 解析工作表。
     *
     * 按 `r` 属性里的列字母定位，而不是按出现顺序往列表里追加：
     * Excel 会省略空单元格，顺序追加会让整行错位。
     */
    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var cells: MutableMap<Int, String>? = null
        var nextColumn = 1

        val parser = newParser(bytes)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        cells = LinkedHashMap()
                        // 行内列号游标：`r` 属性缺失时按出现顺序递增。
                        // 有些导出工具会省略 `r`，若此时一律返回第 1 列，
                        // 整行的单元格会互相覆盖，按列名定位全部失败
                        nextColumn = 1
                    }
                    "c" -> {
                        val declared = columnIndex(parser.getAttributeValue(null, "r"))
                        val column = if (declared > 0) declared else nextColumn
                        nextColumn = column + 1
                        cells?.put(column, cellValue(parser, sharedStrings))
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "row" && cells != null) {
                    rows.add(sparseToList(cells))
                    cells = null
                }
            }
            event = parser.next()
        }
        return rows
    }

    /**
     * 读取当前 `<c>` 的值，读完时停在 `</c>`。
     *
     * @param type `t` 属性：s=共享字符串下标，inlineStr=内嵌文本，其余（数字/布尔/公式结果）取 `<v>`
     */
    private fun cellValue(parser: XmlPullParser, sharedStrings: List<String>): String {
        val type = parser.getAttributeValue(null, "t")
        var raw = ""
        val inline = StringBuilder()

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "v" -> if (parser.next() == XmlPullParser.TEXT) raw = parser.text.orEmpty()
                    "t" -> if (parser.next() == XmlPullParser.TEXT) inline.append(parser.text)
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "c") {
                break
            }
            event = parser.next()
        }

        return when (type) {
            "s" -> sharedStrings.getOrNull(raw.trim().toIntOrNull() ?: -1).orEmpty()
            "inlineStr" -> inline.toString()
            // str=公式结果字符串、b=布尔、其余是数字：直接拿 <v> 里的文本就行
            else -> raw.trim()
        }
    }

    /**
     * "B6" → 2。没有列字母（`r` 缺失或格式异常）时返回 0，
     * 由调用方按行内游标递补——见 [parseSheet]。
     */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrBlank()) return 0
        var index = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            index = index * 26 + (ch - 'A' + 1)
        }
        return index
    }

    private fun sparseToList(cells: Map<Int, String>): List<String> {
        val width = cells.keys.maxOrNull() ?: return emptyList()
        return (1..width).map { cells[it].orEmpty() }
    }

    private fun newParser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
}
