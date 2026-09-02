package com.faker.bluehood

import com.faker.bluehood.data.Observation
import com.faker.bluehood.detect.TrackerFollowDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 物理トラッカー無しで追尾判定を検証する。
 * 「至近＋連続＋**実際の移動を伴う**複数地点 → ⚠」
 * 「遠い/不連続/短時間/自分が動いていない → ⚠出ない(誤検知なし)」を固定する。
 */
class TrackerFollowDetectorTest {

    private var idc = 0L
    private fun obs(t: Long, rssi: Int, lat: Double?, lon: Double?) =
        Observation(
            id = ++idc, clusterId = 1, timestamp = t, rssi = rssi, mac = null,
            myLat = lat, myLon = lon, myAccuracyM = 5f, rawPayloadHex = ""
        )

    // 約333m間隔=別々の geohash7(≒150m)マス
    private val P1 = 35.780 to 139.400
    private val P2 = 35.783 to 139.400
    private val P3 = 35.786 to 139.400
    private val MIN = 60_000L

    @Test fun following_close_continuous_multiplace() {
        val o = mutableListOf<Observation>(); var t = 0L
        for ((lat, lon) in listOf(P1, P1, P2, P2, P3, P3)) {
            o += obs(t, -50, lat, lon); t += 30_000L        // 30秒間隔=連続
        }
        val f = TrackerFollowDetector.assess(o)
        assertTrue("至近で3地点を連続移動→追尾", f.following)
        assertTrue("スコアは⚠閾値5以上", TrackerFollowDetector.score(f) >= 5.0)
    }

    @Test fun notFollowing_far() {
        val o = mutableListOf<Observation>(); var t = 0L
        for ((lat, lon) in listOf(P1, P2, P3)) { o += obs(t, -85, lat, lon); t += 30_000L }
        val f = TrackerFollowDetector.assess(o)
        assertFalse("遠い(RSSI<-65)は除外→非追尾", f.following)
        assertEquals(0.0, TrackerFollowDetector.score(f), 0.001)
    }

    @Test fun notFollowing_discontinuous_multiplace() {
        // 3地点だが各間が5分空く→セッションが切れ各1地点→非追尾(偶然のすれ違いを弾く)
        val o = mutableListOf<Observation>(); var t = 0L
        for ((lat, lon) in listOf(P1, P2, P3)) { o += obs(t, -50, lat, lon); t += 5 * MIN }
        val f = TrackerFollowDetector.assess(o)
        assertFalse("至近でも不連続なら非追尾", f.following)
    }

    @Test fun notFollowing_longDwell_singlePlace() {
        // 【2026-08-09に期待値を反転】
        // 旧実装は「1地点でも至近で15分以上居座れば追尾」としていたが、これは誤りだった。
        // 自宅で座っているだけで成立し、実機で自宅から出ていないのに40件が警告された。
        // 追尾とは「自分が動いたのに付いてくる」ことであり、滞在時間は移動の代わりにならない。
        // 自分が動いていない状態では、付いてきたのか元からそこに在るのかを区別できない。
        val o = mutableListOf<Observation>(); var t = 0L
        repeat(17) { o += obs(t, -55, P1.first, P1.second); t += MIN }
        val f = TrackerFollowDetector.assess(o)
        assertTrue("居座り自体は検出されている", f.longestMin >= 15)
        assertTrue("自分が動いていないので判定不能", f.undecidable)
        assertFalse("居座りだけで追尾成立にしない", f.following)
        assertEquals(0.0, TrackerFollowDetector.score(f), 0.001)
    }

    @Test fun notFollowing_briefSinglePlace() {
        val o = mutableListOf<Observation>(); var t = 0L
        repeat(5) { o += obs(t, -55, P1.first, P1.second); t += MIN }   // 4分だけ
        val f = TrackerFollowDetector.assess(o)
        assertFalse("短時間・1地点は非追尾", f.following)
    }
}
