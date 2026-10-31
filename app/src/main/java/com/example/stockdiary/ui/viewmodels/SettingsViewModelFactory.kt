package com.example.stockdiary.ui.viewmodels

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.stockdiary.di.AppContainer
import com.example.stockdiary.utils.DataSourceHealthChecker

/**
 * [SettingsViewModel] 的构造工厂，从 VM 的 companion 抽出独立成文件，
 * 同包下可直接调用，UI 侧改用 [createSettingsViewModelFactory]。
 *
 * 用 lifecycle 的 viewModelFactory DSL：initializer 按请求的 modelClass 自动匹配，
 * 免去手写 create(modelClass) 逐个 cast 的样板。
 */
fun createSettingsViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        SettingsViewModel(
            appContext = container.context.applicationContext,
            assetRepository = container.assetRepository,
            dividendRepository = container.dividendRepository,
            transactionRepository = container.transactionRepository,
            dataExporter = container.dataExporter,
            dashboardPreferencesManager = container.dashboardPreferencesManager,
            dataSourceHealthChecker = DataSourceHealthChecker,
            priceSyncPreferencesManager = container.priceSyncPreferencesManager
        )
    }
}
