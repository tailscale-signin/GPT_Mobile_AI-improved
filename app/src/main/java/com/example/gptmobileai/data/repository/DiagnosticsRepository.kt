package com.example.gptmobileai.data.repository

import com.example.gptmobileai.data.model.HardwareDiagnostics
import com.example.gptmobileai.data.model.NetworkDiagnostics
import com.example.gptmobileai.data.model.SessionTokenMetrics
import com.example.gptmobileai.data.model.TokenMetrics
import com.example.gptmobileai.data.model.ToolExecutionMetrics
import kotlinx.coroutines.flow.Flow

interface DiagnosticsRepository {
    suspend fun saveTokenMetrics(metrics: TokenMetrics)
    suspend fun saveSessionMetrics(metrics: SessionTokenMetrics)
    suspend fun saveToolMetrics(metrics: ToolExecutionMetrics)
    suspend fun saveHardwareDiagnostics(diagnostics: HardwareDiagnostics)
    suspend fun saveNetworkDiagnostics(diagnostics: NetworkDiagnostics)
    fun getRecentTokenMetrics(limit: Int = 100): Flow<List<TokenMetrics>>
    fun getRecentHardwareDiagnostics(limit: Int = 50): Flow<List<HardwareDiagnostics>>
}
