package com.example.stockdiary.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.stockdiary.ui.theme.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Savings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stockdiary.database.Asset
import com.example.stockdiary.database.AssetType
import com.example.stockdiary.network.BinanceHostGate
import com.example.stockdiary.utils.ExchangeRateManager
import com.example.stockdiary.utils.StockCodeManager
import com.example.stockdiary.utils.format
import com.example.stockdiary.utils.formatMoney
import com.example.stockdiary.utils.CRYPTO_DECIMALS
import com.example.stockdiary.utils.formatTrim
import com.example.stockdiary.utils.pricePrecisionFor
import com.example.stockdiary.utils.totalValueCny

@Composable
fun PortfolioOverviewCard(
    totalAssets: Double,
    totalLiabilities: Double,
    netAssets: Double,
    estimatedAnnualDividend: Double,
    isDividendLoading: Boolean,
    dividendStatus: String?,
    assetCount: Int,
    /** 被显示开关藏起来的资产条数；>0 时在标题行如实标出（列表项数 ≠ 总资产口径） */
    hiddenAssetCount: Int = 0
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("总资产", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onSurface)
                Text(
                    // 总资产是全量口径（与资产页、每日快照一致），列表只显示其中一部分时
                    // 必须说清楚，否则用户拿列表逐项加总会对不上顶部这个数字
                    if (hiddenAssetCount > 0) "$assetCount 项 · 另有 $hiddenAssetCount 项已隐藏" else "$assetCount 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(totalAssets.formatMoney(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface)
            // 有负债时净资产比总资产更值得看，别让用户自己心算
            if (totalLiabilities > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("净资产 ${netAssets.formatMoney()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = AppTheme.colors.onSurface)
                    // 原来负债用的是 profitGreen：本 App 里绿色 = 跌/负，看上去像"负债在跌"，
                    // 语义是反的。改成 error 色（Material 里代表需要注意的负面数值）。
                    Text("负债 ${totalLiabilities.formatMoney()}", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.error)
                }
                // 用户最容易算错的一件事：顶部大数是总资产，净资产已经减过负债。
                // 这里显式写一句，省得有人拿列表逐项加总去对总资产、然后发现对不上。
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "净资产 = 总资产 − 负债，负债不计入净资产",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = AppTheme.colors.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(12.dp))
            // 抓取期间照常显示旧值，不用「计算中」把屏幕占住——
            // 缓存命中时根本不抓，冷启动那一下也会有旧值可看
            // 精度口径：以前这里用了 format(0)（"¥1234"），而资产页同一份"年度收入"
            // 用的是 format(2)（"¥1234.56"）。同一个数字在两个标签页位数不同，用户左右
            // 一切就以为算错了。统一走 formatMoney()（一律 2 位），两处就对得上了。
            val dividendText = when {
                isDividendLoading && estimatedAnnualDividend <= 0.0 -> "计算中"
                estimatedAnnualDividend > 0.0 -> estimatedAnnualDividend.formatMoney()
                else -> "-"
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PortfolioMetricColumn("年度分红", dividendText, isDividendLoading && estimatedAnnualDividend <= 0.0)
                PortfolioMetricColumn("月均分红", if (estimatedAnnualDividend > 0) (estimatedAnnualDividend / 12).formatMoney() else "-", false)
                PortfolioMetricColumn(dividendStatus ?: "持有", if (dividendStatus != null) "" else "$assetCount", isDividendLoading && dividendStatus != null)
            }
        }
    }
}

/**
 * 市场大区头（场内 / 场外 / 其他）。比账户头更醒目，让两批来源一眼分开。
 *
 * 分组依据是 symbol 形态（`fund_` 前缀 = 场外份额，`sh`/`sz`/`bj` = 场内），
 * 不是账户名——账户名是用户自己填的，两次导入填同一个名字就会混在一起。
 */
@Composable
fun MarketHeaderCard(
    label: String,
    assetCount: Int,
    totalValue: Double,
    isExpanded: Boolean,
    expandKey: String,
    onToggleExpand: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = { onToggleExpand(expandKey) }).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.onSurface
                )
                Text("$assetCount 项", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    totalValue.formatMoney(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = AppTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 账户分组头。**只渲染头部**，资产行由外层 LazyColumn 直接渲染。
 *
 * 这里曾经把该账户下的资产也用 Column + forEach 一起渲染，结果 LazyColumn 的懒加载
 * 完全失效——它只对自己的直接子项生效。持仓上百只时，一个账户就是上百个 Card
 * 一次性全部组合，首屏必然卡死。现在拍扁后每一项都是 LazyColumn 的直接子项。
 */
@Composable
fun AccountHeaderCard(
    account: String,
    assetCount: Int,
    totalValue: Double,
    isExpanded: Boolean,
    expandKey: String,
    onToggleExpand: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = { onToggleExpand(expandKey) }).padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 账户名是可压缩的那一侧：券商导入的账户名可以很长（"华泰证券-融资融券账户"），
            // 以前这里没有 weight，Row 会先按它想要的最大宽度去量，结果把右侧金额挤出卡片。
            // 现在文本侧 weight(1f) 先让位给金额，截断也只截文字、不截数字。
            Column(modifier = Modifier.weight(1f)) {
                SingleLineText(
                    account,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text("$assetCount 项", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            }
            // 金额侧不加 weight：先按自身宽度量，永远完整显示
            Column(horizontalAlignment = Alignment.End) {
                Text(totalValue.formatMoney(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onSurface)
            }
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "收起" else "展开",
                tint = AppTheme.colors.onSurfaceVariant
            )
        }
    }
}

/**
 * 一只资产的列表行：持仓页与搜索弹窗**共用这一个实现**。
 *
 * 以前搜索弹窗里另有一份 `SearchAssetItem`，结构完全相同但已经漂移：少了到期日显示、
 * 没有批量选择态、价格的几种状态（退市 / 币安不可达 / 待更新）又抄了一遍。
 * 现在搜索那边直接复用本组件，改价格口径只改一处。
 *
 * @param onToggleSelection 批量选择态下勾选/取消；不需要选择功能的调用方可以不传
 */
@Composable
fun StockAssetItem(
    asset: Asset,
    onClick: (Long) -> Unit,
    onDividendClick: (Long) -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelection: (Long) -> Unit = {}
) {
    val isLiability = asset.type.isLiability
    val currentPrice = asset.currentPrice
    // 精度口径单点定义（utils/Extensions.pricePrecisionFor）：加减仓弹窗以前硬编成恒 3 位，
    // 同一只股票列表显示 12.34、弹窗显示 12.340，现在三处共用同一套判定
    val pricePrecision = pricePrecisionFor(asset.type, asset.symbol)
    val unitLabel = if (asset.type == AssetType.STOCK) "股" else "份"

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = { onClick(asset.id) }),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) AppTheme.colors.primaryContainer.copy(alpha = 0.4f) else AppTheme.colors.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection(asset.id) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                // 资产名是可压缩的一侧：基金名动辄二十几个字，以前只写了 maxLines=1
                // （默认 Clip，从中间硬切、没有省略号），现在统一走 SingleLineText
                SingleLineText(
                    asset.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    asset.symbol?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                        Text("•", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                    }
                    // 负债没有「持仓数量」的概念，balance 就是欠多少钱。
                    // 加密货币数量常见 6~10 位小数（如 0.1234567890），必须给足位数，否则会被舍成 0 或 0.12
                    Text(
                        if (isLiability) "负债"
                        else if (asset.type == AssetType.CRYPTO) "${asset.balance.formatTrim(CRYPTO_DECIMALS)}$unitLabel"
                        else "${asset.balance.format(0)}$unitLabel",
                        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(asset.totalValueCny.formatMoney(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onSurface)
                Text(
                    when {
                        isLiability -> "待偿还"
                        // 价格是原币口径（与 totalValue 同币种），而同行市值是折算后人民币。
                        // 不过这道换算直接套 ¥，USD 加密货币会把 $67,000 显示成 ¥67,000
                        // （真实约 ¥48 万）——与详情页 AssetDetailScreen:180 同一口径
                        currentPrice != null ->
                            if (asset.type == AssetType.CRYPTO)
                                "¥" + ExchangeRateManager.toCny(currentPrice, asset.currency).formatTrim(CRYPTO_DECIMALS)
                            else
                                ExchangeRateManager.toCny(currentPrice, asset.currency).formatMoney(digits = pricePrecision)
                        StockCodeManager.isDelistedForSync(asset.symbol) -> "退市"
                        // 币安主机级熔断期间（连不上币安公开行情，见 BinanceHostGate）要给一个
                        // 区别于「价格待更新」的提示：前者是网络/主机问题，后者可能是数据源缺数据。
                        // 注意 isUnavailable 是普通 volatile 读、不可观察；列表在每轮同步结束后会重组，
                        // 届时标签会刷新——这个局限写进注释，别做成轮询。它是纯读、无副作用，
                        // 不会驱动熔断状态机（请求闸门是 tryAcquireProbe 的职责）。
                        asset.type == AssetType.CRYPTO &&
                            BinanceHostGate.isUnavailable() -> "币安暂不可达"
                        else -> "价格待更新"
                    },
                    style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Medium
                )
                if (asset.millionCopiesIncome != null) {
                    Text("万份收益 ${asset.millionCopiesIncome.format(4)}",
                        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                }
                // 到期日：存款/贷款/信用卡等固定期限资产才有，但统一「有值就显示」
                asset.dueDate?.let {
                    Text("到期日 $it",
                        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                }
            }
            // 分红入口只对能分红的类型有意义，负债点了也是空列表
            if (!selectionMode && !isLiability) {
                // 触摸目标从 32dp 放大到 40dp：这是每行都要点的次高频入口，32dp 明显偏小，
                // 而且紧贴卡片右缘容易点空。图标同步放大到 20dp，右侧留 4dp 不让按钮贴边。
                IconButton(onClick = { onDividendClick(asset.id) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Savings,
                        contentDescription = "查看「${asset.name}」的分红",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioMetricColumn(label: String, value: String, isLoading: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.colors.primary)
        } else {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onSurface)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
    }
}
