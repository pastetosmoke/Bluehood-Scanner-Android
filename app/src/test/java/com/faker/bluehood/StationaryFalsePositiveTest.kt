package com.faker.bluehood

import com.faker.bluehood.data.Observation
import com.faker.bluehood.detect.StalkerDetector
import com.faker.bluehood.detect.TrackerFollowDetector
import org.junit.Assert.*
import org.junit.Test

/**
 * 実機で起きた誤検知(2026-08-09)の再発防止。
 *
 * 症状: 自宅から一歩も出ていないのに Apple Find My が40件、スコア6.0で警告された。
 * 原因は2つ:
 *  1. 地点数を geohash のセル数で数えていた。geohash は距離の尺度ではないので、
 *     103m四方に留まっていても3セルにまたがり closePlaces>=3 を満たした。
 *  2. 「至近で15分以上」だけで追尾成立としていた。自宅で座っているだけで満たされる。
 *
 * 追尾とは「自分が動いたのに付いてくる」こと。自分が動いていなければ判定できない。
 */
class StationaryFalsePositiveTest {

    /**
     * 実機で記録された誤検知時の座標間隔(103m四方)を、大宮駅を原点に平行移動したもの。
     * 判定は距離ベースなので絶対位置には依存しない。実座標は投稿者の居所そのものなので使わない。
     */
    private val homeLat = 35.906110
    private val homeLon = 139.623610
    private val awayLat = 35.906727      // 約68m北
    private val awayLon = 139.624456     // 約77m東

    private fun obs(lat: Double, lon: Double, tMin: Long, rssi: Int = -55) =
        Observation(
            clusterId = 1, timestamp = tMin * 60_000L, rssi = rssi, mac = null,
            myLat = lat, myLon = lon, myAccuracyM = 13f, rawPayloadHex = ""
        )

    /** 100m四方に留まっている限り、何時間居座っても追尾にはならない。 */
    @Test
    fun stationaryAtHomeNeverTriggersFollow() {
        val list = (0..180 step 2).map {
            // 実測どおりGPSが揺れる範囲で往復させる
            val f = (it % 4) / 3.0
            obs(homeLat + (awayLat - homeLat) * f, homeLon + (awayLon - homeLon) * f, it.toLong())
        }
        val f = TrackerFollowDetector.assess(list)
        assertTrue("自分が動いていないので判定不能であること", f.undecidable)
        assertFalse("追尾成立にしてはいけない", f.following)
        assertEquals("スコアは0", 0.0, TrackerFollowDetector.score(f), 0.001)
    }

    /** 3時間居座っても、移動が無ければ成立しない(旧実装はここで成立していた)。 */
    @Test
    fun longDwellWithoutMovementIsNotFollowing() {
        val list = (0..200 step 1).map { obs(homeLat, homeLon, it.toLong()) }
        val f = TrackerFollowDetector.assess(list)
        assertTrue(f.longestMin >= 15)          // 居座り自体は検出されている
        assertFalse("居座りだけで追尾成立にしない", f.following)
        assertEquals(0.0, TrackerFollowDetector.score(f), 0.001)
    }

    /** 実際に離れた3地点を一緒に移動したら成立する(検知能力は落としていない)。 */
    @Test
    fun movingTogetherAcrossThreePlacesDoesTrigger() {
        val list = listOf(
            obs(35.6812, 139.7671, 0), obs(35.6812, 139.7671, 1),
            obs(35.6852, 139.7528, 2), obs(35.6852, 139.7528, 3),   // 約1.5km
            obs(35.7100, 139.8107, 4), obs(35.7100, 139.8107, 5),   // さらに約6km
        )
        val f = TrackerFollowDetector.assess(list)
        assertFalse(f.undecidable)
        assertTrue("移動を伴う3地点は追尾成立", f.following)
        assertTrue(TrackerFollowDetector.score(f) >= 5.0)
    }

    /** 遠い(RSSIが弱い)だけの通りすがりは至近ゲートで落ちる。 */
    @Test
    fun farAwayTrackerIsIgnored() {
        val list = listOf(
            obs(35.6812, 139.7671, 0, rssi = -95),
            obs(35.6852, 139.7528, 2, rssi = -92),
            obs(35.7100, 139.8107, 4, rssi = -90),
        )
        val f = TrackerFollowDetector.assess(list)
        assertFalse(f.following)
    }

    /** 地点数は距離で数える。geohashのセル数で数えると同じ場所が複数地点になる。 */
    @Test
    fun distinctPlacesUsesDistanceNotGeohashCells() {
        // 実機で3つのgeohash-7セルに割れた間隔を、大宮駅を原点に平行移動したもの
        val sameArea = listOf(
            35.906110 to 139.623610,
            35.906727 to 139.624456,
            35.906356 to 139.623983,
        )
        assertEquals("103m四方は1地点", 1, TrackerFollowDetector.distinctPlaces(sameArea))
    }

    /** 尾行スコア側も同じ修正が効いていること。 */
    @Test
    fun stalkerScoreIsZeroWhenStationary() {
        val list = (0..30).map { obs(homeLat, homeLon, it * 60L) }   // 30時間その場に居る
        assertEquals(0.0, StalkerDetector.score(list), 0.001)
    }
}

/**
 * 「戻ってきた場所」を新しい場所として数えないこと。
 *
 * ESP32版では、今いる場所の指紋1つとしか照合していなかったため
 * A→B→A と戻ると3地点と数えていた。地点数は追尾スコアの中心なので
 * これは誤検知に直結する。Android側が同じ穴を持っていないことを固定する。
 */
class ReturnVisitTest {

    private val A = 35.6812 to 139.7671        // 東京駅
    private val B = 35.6852 to 139.7528        // 皇居(約1.5km)
    private val C = 35.7100 to 139.8107        // さらに離れた地点

    private fun obs(p: Pair<Double, Double>, tMin: Long, rssi: Int = -55) =
        Observation(
            clusterId = 1, timestamp = tMin * 60_000L, rssi = rssi, mac = null,
            myLat = p.first, myLon = p.second, myAccuracyM = 10f, rawPayloadHex = ""
        )

    @Test
    fun returningToSamePlaceIsNotCountedTwice() {
        // A → B → A と往復
        val coords = listOf(A, B, A)
        assertEquals("往復しても2地点", 2, TrackerFollowDetector.distinctPlaces(coords))
    }

    @Test
    fun threePlacesWithReturnStillCountsThree() {
        val coords = listOf(A, B, A, C, A)
        assertEquals("A,B,C の3地点", 3, TrackerFollowDetector.distinctPlaces(coords))
    }

    /** 往復だけでは追尾成立にしない(2地点は生活圏の重なりと区別できない)。 */
    @Test
    fun commutingBackAndForthDoesNotTriggerFollow() {
        val list = listOf(
            obs(A, 0), obs(A, 1), obs(B, 2), obs(B, 3), obs(A, 4), obs(A, 5),
        )
        val f = TrackerFollowDetector.assess(list)
        assertEquals("往復は2地点", 2, f.closePlaces)
        assertFalse("通勤の往復を尾行と呼ばない", f.following)
    }

    /** 3地点を回れば成立する(往復の除外が検知能力を殺していないこと)。 */
    @Test
    fun visitingThreePlacesDoesTriggerFollow() {
        val list = listOf(
            obs(A, 0), obs(A, 1), obs(B, 2), obs(B, 3), obs(C, 4), obs(C, 5),
        )
        val f = TrackerFollowDetector.assess(list)
        assertEquals(3, f.closePlaces)
        assertTrue(f.following)
    }
}
