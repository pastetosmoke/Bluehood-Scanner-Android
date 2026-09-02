package com.faker.bluehood

import com.faker.bluehood.data.Observation
import com.faker.bluehood.detect.StalkerDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerDetectorTest {

    private val t0 = 1_754_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    // 200m以上離れた地点(文脈が分かれる)
    private val tokyo = 35.6812 to 139.7671
    private val shinjuku = 35.6896 to 139.7006
    private val shibuya = 35.6580 to 139.7016
    private val asakusa = 35.7148 to 139.7967

    private fun obs(place: Pair<Double, Double>, ts: Long) = Observation(
        clusterId = 1, timestamp = ts, rssi = -70, mac = null,
        myLat = place.first, myLon = place.second, myAccuracyM = 5f, rawPayloadHex = "aa"
    )

    private fun scoreOf(list: List<Observation>): Double {
        val track = list.map { Triple(it.myLat!!, it.myLon!!, it.timestamp) }
        return StalkerDetector.score(list, StalkerDetector.buildContexts(track))
    }

    @Test
    fun `自宅と職場の2地点だけなら生活圏の重なりとみなしスコア0`() {
        val list = (0..2).map { obs(tokyo, t0 + it * min) } +
            (0..2).map { obs(shinjuku, t0 + 5 * hour + it * min) }
        assertEquals(0.0, scoreOf(list), 0.001)
    }

    @Test
    fun `3地点でも観測数が5未満ならスコア0`() {
        val list = listOf(
            obs(tokyo, t0),
            obs(shinjuku, t0 + 5 * hour),
            obs(shibuya, t0 + 10 * hour),
            obs(shibuya, t0 + 10 * hour + min),
        )
        assertEquals(0.0, scoreOf(list), 0.001)
    }

    @Test
    fun `3地点を短時間で連続移動しただけでは閾値5に届かない`() {
        val list = listOf(
            obs(tokyo, t0), obs(tokyo, t0 + min),
            obs(shinjuku, t0 + 2 * min), obs(shinjuku, t0 + 3 * min),
            obs(shibuya, t0 + 4 * min), obs(shibuya, t0 + 5 * min),
        )
        assertTrue("同一トリップの同乗は沈むべき", scoreOf(list) < 5.0)
    }

    @Test
    fun `4地点で日をまたぎ非連続に再出現したら閾値5を超える`() {
        val list = listOf(
            obs(tokyo, t0), obs(tokyo, t0 + min),
            obs(shinjuku, t0 + 6 * hour),
            obs(shibuya, t0 + 20 * hour),
            obs(asakusa, t0 + 30 * hour),
            obs(asakusa, t0 + 30 * hour + min),
        )
        assertTrue("複数日・複数地点の再出現は検知すべき", scoreOf(list) >= 5.0)
    }

    @Test
    fun `固定機器のように1地点しか出ないならスコア0`() {
        val list = (0..9).map { obs(tokyo, t0 + it * min) }
        assertEquals(0.0, scoreOf(list), 0.001)
    }
}
