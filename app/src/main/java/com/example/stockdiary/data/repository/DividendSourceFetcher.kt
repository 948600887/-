package com.example.stockdiary.data.repository

import com.example.stockdiary.data.dividend.DividendRecord
import com.example.stockdiary.data.dividend.DividendService
import com.example.stockdiary.network.EastmoneyDividendApiService
import com.example.stockdiary.network.FundDividendApiService
import com.example.stockdiary.utils.AppLogger
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * 「数据源没应答」的信号 —— 与「问过了，确实没有分红」是两回事，不能混。
 *
 * 分红抓取的失败与空结果在 List 上完全同形（都是 emptyList），一旦把「没问到」
 * 记成「没有」，冷启动的只读缓存路径会把它当有效结论复用 7 天：
 * 预计分红 / 收息进度 / 日历全显示 0，**且界面没有任何错误提示**。
 * 所以每个数据源失败时抛本异常而不是返回 emptyList，由调用方汇总后决定
 * 这是不是真的「没有分红」。
 */
internal class DividendSourceUnavailableException(message: String) : IOException(message)

/**
 * 三个分红数据源的适配层（东财 / 新浪 / 天天基金）。
 *
 * 从 [DividendRepository] 外提：这里只负责「调接口并把应答映射成 [DividendRecord]」，
 * 不决定先问谁、批量还是逐条、失败后怎么退避 —— 那是编排层的事。
 * 所有失败一律转成 [DividendSourceUnavailableException]，语义与改造前一致。
 */
internal class DividendSourceFetcher(
    private val eastmoneyApi: EastmoneyDividendApiService
) {
    private val sinaService = DividendService()
    private val fundDividendApi = FundDividendApiService()

    suspend fun fetchFromEastmoney(stockCode: String): List<DividendRecord> {
        return try {
            val result = eastmoneyApi.getDividendHistory(stockCode)
            if (result.isSuccess) {
                val dividendDataList = result.getOrNull() ?: emptyList()
                dividendDataList.map { divData ->
                    DividendRecord(
                        exDividendDate = divData.date,
                        dividendPerShare = divData.dividendPerShare.toString(),
                        announceDate = divData.reportDate ?: "",
                        fiscalYear = divData.date.substring(0, 4),
                        paymentDate = divData.paymentDate
                    )
                }
            } else {
                throw DividendSourceUnavailableException("东方财富分红接口未应答[$stockCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("东方财富分红数据获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("东方财富分红数据获取失败[$stockCode]")
        }
    }

    suspend fun fetchFromEastmoneyWithFormat(securityCode: String): List<DividendRecord> {
        return try {
            val result = eastmoneyApi.getDividendHistoryBySecurityCode(securityCode)
            if (result.isSuccess) {
                val dividendDataList = result.getOrNull() ?: emptyList()
                dividendDataList.map { divData ->
                    DividendRecord(
                        exDividendDate = divData.date,
                        dividendPerShare = divData.dividendPerShare.toString(),
                        announceDate = divData.reportDate ?: "",
                        fiscalYear = divData.date.substring(0, 4),
                        paymentDate = divData.paymentDate
                    )
                }
            } else {
                throw DividendSourceUnavailableException("东方财富带格式分红接口未应答[$securityCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF东方财富带格式[$securityCode]获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("东方财富带格式分红获取失败[$securityCode]")
        }
    }

    suspend fun fetchFromFundApiForETF(fundCode: String): List<DividendRecord> {
        return try {
            val result = fundDividendApi.getFundDividendHistory(fundCode)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                throw DividendSourceUnavailableException("天天基金分红接口未应答(ETF)[$fundCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("ETF天天基金接口获取失败[${fundCode}]: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("天天基金分红获取失败(ETF)[$fundCode]")
        }
    }

    suspend fun fetchFromSina(stockCode: String, market: String): List<DividendRecord> {
        return try {
            val result = sinaService.fetchDividendData(stockCode, market)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                throw DividendSourceUnavailableException("新浪分红接口未应答[$stockCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("新浪分红数据获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("新浪分红数据获取失败[$stockCode]")
        }
    }

    suspend fun fetchFromFundApi(fundCode: String): List<DividendRecord> {
        return try {
            AppLogger.d("天天基金分红数据获取: $fundCode", "DividendRepository")
            val result = fundDividendApi.getFundDividendHistory(fundCode)
            if (result.isSuccess) {
                result.getOrNull() ?: emptyList()
            } else {
                throw DividendSourceUnavailableException("天天基金分红接口未应答[$fundCode]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DividendSourceUnavailableException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("天天基金分红数据获取失败: ${e.message}", "DividendRepository")
            throw DividendSourceUnavailableException("天天基金分红数据获取失败[$fundCode]")
        }
    }
}
