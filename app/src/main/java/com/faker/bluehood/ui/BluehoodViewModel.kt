package com.faker.bluehood.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faker.bluehood.data.AppDatabase
import com.faker.bluehood.data.DeviceCluster
import com.faker.bluehood.data.Observation
import com.faker.bluehood.evidence.EvidenceExporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BluehoodViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)   // ScanServiceと同一インスタンスを共有

    val clusters: Flow<List<DeviceCluster>> = db.dao().clusters()
    val stalkerCandidates: Flow<List<DeviceCluster>> = db.dao().stalkerCandidates(5.0)
    val trackers: Flow<List<DeviceCluster>> = db.dao().trackers()            // 匿名化トラッカー検知
    val located: Flow<List<Observation>> = db.dao().locatedObservations()   // 地図用の観測点群

    private val _lastExport = MutableStateFlow<String?>(null)
    val lastExport: StateFlow<String?> = _lastExport

    fun export(clusterId: Long) = viewModelScope.launch {
        val dao = db.dao()
        val cluster = dao.findById(clusterId) ?: return@launch
        val obs = dao.observationsFor(clusterId)
        _lastExport.value = EvidenceExporter.export(cluster, obs)
        // TODO: SAF(ACTION_CREATE_DOCUMENT)でファイル保存 or 共有インテント
    }
}
