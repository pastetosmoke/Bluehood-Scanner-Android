package com.faker.bluehood.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * スキャンの生存状態。ServiceとUIで共有する唯一の真実。
 *
 * 護身アプリの最悪の失敗は「守られていると誤認させること」。
 * スキャンが死んでいる状態と、スキャンは生きていて脅威が無い状態は、
 * 画面上で絶対に同じに見えてはならない。そのためUIは常にこれを購読する。
 */
object ScanState {

    enum class Status {
        STOPPED,    // ユーザーが明示的に停止した
        SCANNING,   // 実際にスキャンが回っている
        BLOCKED,    // 開始したいが前提条件が欠けている(BT OFF・権限・位置情報)
        STALLED,    // 開始したが結果が途絶えている(無音故障の受け皿)
    }

    data class State(
        val status: Status = Status.STOPPED,
        val reason: String? = null,      // BLOCKED/STALLED のときの人間可読な理由
        val results: Long = 0,           // 起動以降に受信した広告数(生存の証拠)
        val lastResultAt: Long = 0,      // 最後に結果が来た時刻(0=まだ無い)
        val locatedRatio: Float = -1f,   // 直近観測のうち位置が付いた割合(-1=未計測)
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(transform: (State) -> State) { _state.value = transform(_state.value) }

    fun stopped() { _state.value = State(Status.STOPPED) }

    fun blocked(reason: String) {
        _state.value = _state.value.copy(status = Status.BLOCKED, reason = reason)
    }

    fun scanning() {
        _state.value = _state.value.copy(status = Status.SCANNING, reason = null)
    }
}
