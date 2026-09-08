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

    private fun obs(place: Pair<Double, Double>, ts: Long, wifi: String? = null) = Observation(
        clusterId = 1, timestamp = ts, rssi = -70, mac = null,
        myLat = place.first, myLon = place.second, myAccuracyM = 5f, rawPayloadHex = "aa",
        wifiFp = wifi
    )

    /** 座標を持たない観測(屋内でGPS未fix)。WiFi指紋だけで場所を数えられるかの検証用。 */
    private fun indoorObs(wifi: String, ts: Long) = Observation(
        clusterId = 1, timestamp = ts, rssi = -70, mac = null,
        myLat = null, myLon = null, myAccuracyM = null, rawPayloadHex = "aa", wifiFp = wifi
    )

    private fun scoreOf(list: List<Observation>): Double = StalkerDetector.score(list)

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

    // --- WiFi指紋による場所同定(2026-09-02 追加) ---

    @Test
    fun `GPSが揺れても同じWiFi指紋なら1地点として畳まれる`() {
        // 実機で3つのgeohash-7セルに割れた間隔を、大宮駅を原点に平行移動したもの。
        // 屋内GPSの揺れであって移動ではない。
        val jitter = listOf(
            35.906110 to 139.623610,
            35.906727 to 139.624456,
            35.906356 to 139.623983,
        )
        val home = "aa,bb,cc,dd"
        val list = (0..9).map { obs(jitter[it % 3], t0 + it * min, home) }
        assertEquals("同一AP集合の範囲内は1地点", 0.0, scoreOf(list), 0.001)
    }

    @Test
    fun `GPSが一切取れなくてもWiFi指紋が違えば地点として数える`() {
        // 従来は myLat が null の観測を捨てていたため、屋内では判定が永久に0.0のままだった。
        // 「近隣一覧は埋まるのに安全と誤認する」最も危険な壊れ方への回帰テスト。
        val list = listOf(
            indoorObs("a1,a2,a3,a4", t0),
            indoorObs("a1,a2,a3,a4", t0 + min),
            indoorObs("b1,b2,b3,b4", t0 + 6 * hour),
            indoorObs("c1,c2,c3,c4", t0 + 20 * hour),
            indoorObs("d1,d2,d3,d4", t0 + 30 * hour),
            indoorObs("d1,d2,d3,d4", t0 + 30 * hour + min),
        )
        assertTrue("座標が無くても検知できるべき", scoreOf(list) >= 5.0)
    }

    @Test
    fun `一度離れて戻った場所を新しい地点として数えない`() {
        val a = "a1,a2,a3,a4"
        val b = "b1,b2,b3,b4"
        val list = listOf(
            indoorObs(a, t0), indoorObs(a, t0 + min),
            indoorObs(b, t0 + 6 * hour),
            indoorObs(a, t0 + 12 * hour), indoorObs(a, t0 + 12 * hour + min),
            indoorObs(b, t0 + 20 * hour),
        )
        assertEquals("A→B→A は2地点なので生活圏の重なりとして沈む", 0.0, scoreOf(list), 0.001)
    }

    @Test
    fun `AP数が少なすぎる指紋は場所の根拠にしない`() {
        // 2局だと1台の電源断で別の場所に見えてしまう。座標も無ければ判定不能。
        val list = (0..9).map { indoorObs("x$it,y$it", t0 + it * hour) }
        assertEquals(0.0, scoreOf(list), 0.001)
    }
}
