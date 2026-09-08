package com.faker.bluehood.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faker.bluehood.data.AppDatabase
import com.faker.bluehood.data.AttackEvent
import com.faker.bluehood.data.DeviceCluster
import com.faker.bluehood.data.Observation
import com.faker.bluehood.detect.AttackTestInjector
import com.faker.bluehood.evidence.EvidenceExporter
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BluehoodViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)   // ScanServiceと同一インスタンスを共有

    val clusters: Flow<List<DeviceCluster>> = db.dao().clusters()
    val stalkerCandidates: Flow<List<DeviceCluster>> = db.dao().stalkerCandidates(5.0)
    val trackers: Flow<List<DeviceCluster>> = db.dao().trackers()            // 匿名化トラッカー検知
    val located: Flow<List<Observation>> = db.dao().locatedObservations()   // 地図用の観測点群
    val attackEvents: Flow<List<AttackEvent>> = db.dao().attackEvents()     // 攻撃シグネチャ検出履歴

    private val _lastExport = MutableStateFlow<String?>(null)
    val lastExport: StateFlow<String?> = _lastExport

    /** 自分の持ち物として申告/取り消し。申告時はスコアも0に戻す(過去の誤検知を残さない)。 */
    fun setMine(id: Long, mine: Boolean) = viewModelScope.launch {
        db.dao().setMine(id, mine)
    }

    fun injectTestAttack(tc: AttackTestInjector.TestCase) = viewModelScope.launch {
        db.dao().insertAttackEvent(AttackTestInjector.makeEvent(tc))
    }

    fun export(clusterId: Long) = viewModelScope.launch {
        val dao = db.dao()
        val cluster = dao.findById(clusterId) ?: return@launch
        val obs = dao.observationsFor(clusterId)
        _lastExport.value = EvidenceExporter.export(cluster, obs)
        // TODO: SAF(ACTION_CREATE_DOCUMENT)でファイル保存 or 共有インテント
    }
}
