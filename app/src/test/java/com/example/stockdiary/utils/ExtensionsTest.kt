package com.example.stockdiary.utils

import com.example.stockdiary.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 加密货币精度相关工具的单测：数量/报价精度给足 [CRYPTO_DECIMALS]，
 * 以及 [formatTrim] 既保住有效位又不给高价币补一堆 0。
 */
class ExtensionsTest {

    @Test
    fun 加密货币数量精度给足10位_其余不变() {
        assertEquals(10, quantityPrecisionFor(AssetType.CRYPTO, "BTCUSDT"))
        // 其余类型口径不变，别顺手统一
        assertEquals(0, quantityPrecisionFor(AssetType.STOCK, "sh600000"))
        assertEquals(2, quantityPrecisionFor(AssetType.FUND, "fund_000001"))
    }

    @Test
    fun 加密货币报价精度给足10位_其余不变() {
        assertEquals(10, pricePrecisionFor(AssetType.CRYPTO, "BTCUSDT"))
        assertEquals(3, pricePrecisionFor(AssetType.FUND, "fund_000001"))
        assertEquals(2, pricePrecisionFor(AssetType.STOCK, "sh600000"))
    }

    @Test
    fun formatTrim_保留有效位并去掉末尾0() {
        // 6 位小数：保住，不丢
        assertEquals("0.123456", 0.123456.formatTrim(CRYPTO_DECIMALS))
        // 整数：不补一堆 0
        assertEquals("1", 1.0.formatTrim(CRYPTO_DECIMALS))
        // 2 位小数：只显示 2 位
        assertEquals("1.5", 1.5.formatTrim(CRYPTO_DECIMALS))
        // 10 位有效数字：全部保住（formatTrim 只去“末尾多余的 0”，不丢有效位）
        assertEquals("0.1234567891", 0.1234567891.formatTrim(CRYPTO_DECIMALS))
    }

    // ---------- 加密货币「数量」固定 8 位（用户需求：为什么有些显示 8 位、有些没有） ----------

    @Test
    fun 加密数量固定8位_保留尾随0_位数不再忽多忽少() {
        // 用户报的原话就是这两个数看着「精度不一致」：0.0006068 只有 7 位、127.13507288 有 8 位。
        // 固定 8 位后两者位宽一致，一列数字能对齐比较（旧 formatTrim 会 trim 掉尾随 0）
        assertEquals("0.00060680", 0.0006068.formatCryptoQuantity())
        assertEquals("127.13507288", 127.13507288.formatCryptoQuantity())
        // 整数与 0 也要补齐 8 位（以前显示 "1" / "0"）
        assertEquals("1.00000000", 1.0.formatCryptoQuantity())
        assertEquals("0.00000000", 0.0.formatCryptoQuantity())
        // 借币负债行是负数，格式与正数同口径，仅多一个负号
        assertEquals("-50.80033811", (-50.80033811).formatCryptoQuantity())
    }

    @Test
    fun 加密数量_8位会舍成0的极小值仍保住有效位() {
        // 币安最小精度可到 1e-9。固定 8 位会把它舍成 0.00000000，用户会以为这笔钱凭空消失，
        // 所以非 0 但 8 位舍成 0 时必须退回 formatTrim(CRYPTO_DECIMALS) 保住有效位
        assertEquals("0.000000001", 1e-9.formatCryptoQuantity())
        assertEquals("0.0000000004", 4e-10.formatCryptoQuantity())
        // 负的 dust 同理，绝不能被显示成 "-0.00000000"
        assertEquals("-0.000000001", (-1e-9).formatCryptoQuantity())
        // 边界：5e-9 按 HALF_UP 进位到 0.00000001，本来就非 0，不该触发兜底
        assertEquals("0.00000001", 5e-9.formatCryptoQuantity())
    }

    @Test
    fun 加密数量_小数分隔符固定为点_不受系统locale影响() {
        // 与 formatTrim 同一口径：分词符锁死 '.'（DecimalFormatSymbols(Locale.US)），
        // 否则德语/法语设备会显示成 "0,00060680"，既看着像算错也让复制粘贴解析失败
        val formatted = 0.0006068.formatCryptoQuantity()
        assertTrue("应含 '.'：$formatted", formatted.contains('.'))
        assertFalse("不该出现 ','：$formatted", formatted.contains(','))
        // 展示常量与计算精度解耦：常量改 8 位不能动 CRYPTO_DECIMALS（它还驱动价格精度与写库判定）
        assertEquals(8, CRYPTO_QUANTITY_DISPLAY_DIGITS)
        assertEquals(10, CRYPTO_DECIMALS)
    }

    /**
     * 同上一条，但**真的把默认 Locale 换成德语**再断言。
     *
     * 上一条只在当前（zh/en）环境里断言「输出里有 `.`」，德语设备上是否会变成 `0,00060680`
     * 其实测不到——它无论缓存有没有问题都会通过。德语的小数分隔符是 `,`，是本 App 最危险的
     * 一类 locale 回归（金额被显示成 `¥1.234,56`，既像算错、也让 [formatTrim] 按 `.` 截断时截错）。
     *
     * ## 为什么要先「预热缓存」再切 Locale
     *
     * [format] 用的是**按小数位缓存的 DecimalFormat**（ThreadLocal + digits 作键，键里**不含**
     * Locale）。如果哪天有人把 `decimalFormatSymbols = DecimalFormatSymbols(Locale.US)` 删掉或
     * 改成默认 Locale，那么「先在本机 Locale 下建好实例、再切语言」就会拿到带 `,` 的缓存实例
     * ——只在切换语言的设备上才复现。所以这里刻意先调一次把缓存建出来，再切 Locale 复调。
     */
    @Test
    fun 加密数量_德语设备上仍是点分隔_含缓存预热后切换locale() {
        val original = Locale.getDefault()
        try {
            // 1) 先在原 Locale 下把 8 位 / 10 位的 DecimalFormat 缓存建出来（模拟「用户已经用过 App」）
            assertEquals("0.00060680", 0.0006068.formatCryptoQuantity())
            assertEquals("0.1234567891", 0.1234567891.formatTrim(CRYPTO_DECIMALS))

            // 2) 切到德语（小数分隔符是 ','）再调——被缓存的实例如果绑了默认 Locale，这里就会露馅
            Locale.setDefault(Locale.GERMANY)
            val quantity = 0.0006068.formatCryptoQuantity()
            assertEquals("德语设备上数量仍必须是点分隔：实际=$quantity", "0.00060680", quantity)
            assertEquals("1234.5", 1234.5.formatTrim(CRYPTO_DECIMALS))
            assertEquals("67000.12", 67000.12.format(2))

            // 3) 期间新建（未预热过）的位数也要用点分隔，不能靠「恰好没被缓存过」才正确
            assertEquals("0.50", 0.5.format(2))
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * `-0.0` 必须显示成 `0.00000000`，不能是 `-0.00000000`。
     *
     * 负数零没有业务含义，但会出现在两处真实路径里：借币负债被全额还清、以及
     * `spotTotal + flex + lock` 的浮点抵消。用户看到「-0.00000000 份」会以为还欠着东西。
     */
    @Test
    fun 加密数量_负零显示为正零() {
        assertEquals("0.00000000", (-0.0).formatCryptoQuantity())
        // 确认输入真的是负零而不是正零（否则上面那条等于没测）
        assertEquals("-0.0", (-0.0).toString())
        // 只比 -0.0 稍大的负值仍要带负号（不能顺手把所有负数都归零）
        assertEquals("-0.00000001", (-1e-8).formatCryptoQuantity())
    }

    /**
     * 已知边界：**比 10 位还小**的值会显示成 `0`（不是 `0.0000000000`）。
     *
     * 兜底只能退到 [CRYPTO_DECIMALS]（10 位），1e-12 在那里仍被舍成 0，`formatTrim` 去掉
     * 尾随 0 后就是 `"0"`。这里把它钉成**已知行为**而不是假装没这回事：
     *
     * - 币安导入路径**到不了**这里：低于 `DUST_THRESHOLD`（1e-8）的余额在 buildPreview 就被过滤，
     *   所以 8 位下最小可见值是 `0.00000001`，兜底分支实际只需要覆盖 1e-9~1e-8 这一段；
     * - 只有「手工录入 1e-12 个 BTC」这种极端数据才会看到 0。
     *
     * 真要修的话得让兜底一直退到「至少一位有效数字」，但那会让 1e-12 显示成
     * `0.000000000001`（12 位）——与「统一 8 位」的产品要求冲突，不值得。所以记录现状。
     */
    @Test
    fun 加密数量_比10位还小的值显示为0_已知边界() {
        assertEquals("0", 1e-12.formatCryptoQuantity())
        // 关键：不能变成「10 位全 0」那种 `0.0000000000`——那比 "0" 更像数据坏了
        assertFalse(1e-12.formatCryptoQuantity().startsWith("0.0000000000"))
    }

    /**
     * 反向验证：这次「固定 8 位」**只动数量**，价格与基金/股票的口径一位都没变。
     *
     * 不看 diff、直接断言输出，堵住「改常量顺手把 CRYPTO_DECIMALS 或 pricePrecisionFor 也动了」
     * 这类连带伤——那会让价格从 `67000.12` 变成 `67000.1200000000`，
     * 也会让基金份额从 2 位变成别的位数。
     */
    @Test
    fun 价格与基金股票口径未被本次改动波及() {
        // 价格：仍走 formatTrim(CRYPTO_DECIMALS)（10 位、去尾随 0、不补 0）
        assertEquals("67000.12", 67000.12.formatTrim(CRYPTO_DECIMALS))
        assertEquals("0.0000000001", 0.0000000001.formatTrim(CRYPTO_DECIMALS))
        // 精度常量本身没被改（它同时驱动价格精度与写库变更判定）
        assertEquals(10, CRYPTO_DECIMALS)
        assertEquals(10, pricePrecisionFor(AssetType.CRYPTO, "BTCUSDT"))
        // 基金/ETF：3 位价格、2 位数量（format 不 trim，100.5 份仍是 "100.50"）
        assertEquals(3, pricePrecisionFor(AssetType.FUND, "fund_000001"))
        assertEquals(3, pricePrecisionFor(AssetType.FUND, "sh510300"))
        assertEquals("100.50", 100.5.format(quantityPrecisionFor(AssetType.FUND, "fund_000001")))
        // 股票：0 位数量、2 位价格，12.5 股按 HALF_UP 进位成 13
        assertEquals(0, quantityPrecisionFor(AssetType.STOCK, "sh600000"))
        assertEquals("13", 12.5.format(quantityPrecisionFor(AssetType.STOCK, "sh600000")))
        assertEquals("12", 12.4.format(quantityPrecisionFor(AssetType.STOCK, "sh600000")))
        assertEquals(2, pricePrecisionFor(AssetType.STOCK, "sh600000"))
    }
}
