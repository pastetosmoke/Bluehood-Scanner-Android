package com.faker.bluehood.detect

import com.faker.bluehood.data.Observation

/**
 * トラッカーの「窓またぎ追尾」を、種別＋至近＋連続性＋**自分の移動**で判定する。
 *
 * 追尾とは「自分が移動したのに、同じものが付いてくる」ことである。
 * したがって **自分が動いていなければ、追尾は原理的に判定できない**。
 * 自宅に留まったまま近くにタグがある状態は、追尾ではなく「家にタグがある」だけで、
 * 家族の持ち物なのか仕掛けられたものなのかを電波から区別する方法は無い。
 *
 * 実機で判明した誤検知(2026-08-09):
 *  - geohash のセル数で地点を数えていたため、103m四方から出ていないのに3地点と判定された。
 *    geohash は距離の尺度ではない。10m離れただけで別セルになることも、
 *    150m離れて同じセルに入ることもある。距離で数えるように修正。
 *  - 「至近で15分以上」だけで追尾成立としていたため、自宅で座っているだけで成立した。
 *    滞在時間は移動の代わりにならない。移動を必須条件にした。
 */
object TrackerFollowDetector {
    const val RSSI_NEAR = -65                 // これ以上強い=至近(カバン/ポケット/車内)。通りすがりを除外
    const val CONTINUITY_GAP_MS = 3 * 60_000L // 至近観測が3分以上途切れたら別セッション
    const val PLACE_SEPARATION_M = 200.0      // これ以上離れて初めて「別の場所」と数える
    const val MIN_USER_TRAVEL_M = 200.0       // 自分がこれ以上動いていないと追尾は判定できない

    data class Follow(
        val closePlaces: Int,
        val longestMin: Long,
        val userTravelM: Double,
        val following: Boolean,
        /** 自分が動いていないため判定不能。「安全」ではないことをUIで区別するために持つ。 */
        val undecidable: Boolean,
    )

    /** 同一種別の全観測(複数のローテーション窓クラスタを横断)を渡す。 */
    fun assess(obs: List<Observation>): Follow {
        val near = obs.filter { it.rssi >= RSSI_NEAR && it.myLat != null && it.myLon != null }
            .sortedBy { it.timestamp }
        if (near.isEmpty()) return Follow(0, 0, 0.0, following = false, undecidable = false)

        val coords = near.map { it.myLat!! to it.myLon!! }
        val travel = maxSpread(coords)

        // 自分が動いていないなら、付いてきたのか元からそこに在るのかを区別できない。
        // ここで低いスコアを返して「安全」に見せるのではなく、判定不能として明示する。
        if (travel < MIN_USER_TRAVEL_M) {
            return Follow(1, dwellMinutes(near), travel, following = false, undecidable = true)
        }

        var bestPlaces = 0
        var bestMin = 0L
        var sessionStart = near.first().timestamp
        var prev = near.first().timestamp
        var sessionCoords = mutableListOf<Pair<Double, Double>>()

        for (o in near) {
            if (o.timestamp - prev > CONTINUITY_GAP_MS) {           // セッション切れ
                bestPlaces = maxOf(bestPlaces, distinctPlaces(sessionCoords))
                bestMin = maxOf(bestMin, (prev - sessionStart) / 60_000L)
                sessionCoords = mutableListOf(); sessionStart = o.timestamp
            }
            sessionCoords.add(o.myLat!! to o.myLon!!)
            prev = o.timestamp
        }
        bestPlaces = maxOf(bestPlaces, distinctPlaces(sessionCoords))
        bestMin = maxOf(bestMin, (prev - sessionStart) / 60_000L)

        // 追尾成立: 至近のまま連続して3地点以上を一緒に移動したこと。
        // 滞在時間はこれを補強するだけで、単独では成立させない。
        val following = bestPlaces >= 3
        return Follow(bestPlaces, bestMin, travel, following, undecidable = false)
    }

    /** UI用の追尾スコア(0-10)。判定不能時は0を返すが、UIでは「安全」と表示してはならない。 */
    fun score(f: Follow): Double = when {
        f.undecidable -> 0.0
        f.following -> maxOf(5.0, f.closePlaces * 2.0).coerceAtMost(10.0)
        else -> (f.closePlaces * 1.0).coerceAtMost(4.0)
    }

    /** 至近で居座った最長時間(分)。移動が無い場合の参考値。 */
    private fun dwellMinutes(near: List<Observation>): Long {
        if (near.size < 2) return 0
        return (near.last().timestamp - near.first().timestamp) / 60_000L
    }

    /** 点群の最大直径(m)。自分がどれだけ動いたかの指標。 */
    fun maxSpread(coords: List<Pair<Double, Double>>): Double {
        if (coords.size < 2) return 0.0
        // 全組み合わせは重いので、緯度経度それぞれの端点だけで近似する(用途には十分)
        val minLa = coords.minOf { it.first }; val maxLa = coords.maxOf { it.first }
        val minLo = coords.minOf { it.second }; val maxLo = coords.maxOf { it.second }
        return StalkerDetector.distanceM(minLa, minLo, maxLa, maxLo)
    }

    /**
     * 200m以上離れた地点をいくつ通ったか。
     * geohash のセル数で数えてはいけない — セル境界は距離と無関係な位置にあるため、
     * 同じ場所に留まっていても複数セルにまたがる(実機で確認済み)。
     */
    fun distinctPlaces(coords: List<Pair<Double, Double>>): Int {
        val centers = mutableListOf<Pair<Double, Double>>()
        for (c in coords) {
            val isNew = centers.all {
                StalkerDetector.distanceM(it.first, it.second, c.first, c.second) >= PLACE_SEPARATION_M
            }
            if (isNew) centers.add(c)
        }
        return centers.size
    }
}
