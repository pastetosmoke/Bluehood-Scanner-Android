package com.faker.bluehood.detect

import com.faker.bluehood.data.Observation

/**
 * 「今どこか」ではなく「さっきと同じ場所か」を、周囲のWiFi APの集合で判定する。
 *
 * GPSの代わりではなく、GPSより適した場面がある。屋内でGPSが数十m揺れた結果
 * 「103m四方から出ていないのに3地点」と誤判定し、誤警告が40件出た(2026-08-08)。
 * ルーターは動かず、BSSIDは回転せず、到達距離が長いので少し歩いた程度では集合が壊れない。
 *
 * さらに重要なのは **GPSが取れない屋内でも場所が数えられる** こと。
 * 従来は myLat が null の観測をスコア計算から丸ごと捨てていたため、
 * 近隣一覧は埋まるのに判定は永久に0.0(=安全と誤認)になる経路があった。
 * WiFi指紋はその穴を塞ぐ。
 *
 * ESP32版 src/place.cpp からの移植。閾値もあちらの実測値をそのまま使う。
 */
object PlaceFingerprint {

    /**
     * 既知の場所と一致したとみなす下限。0.30 は敏感すぎた(ESP32実機 2026-08-09)。
     * AP9局のうち数局が入れ替わるだけで0.30を割り、その場に居るのに移動と判定された。
     * 弱いAPはスキャンごとに明滅するので、閾値はそのノイズより下に置く必要がある。
     */
    const val MATCH_JACCARD = 0.18

    /** 場所の指紋として採用する最小AP数。1〜2局だと1台の電源断で別の場所に見える。 */
    const val MIN_APS = 3

    /** WiFi指紋が無い観測を座標で束ねるときの距離。StalkerDetector の文脈分割と揃える。 */
    const val COORD_RADIUS_M = 200.0

    /** 共通 / 和集合。0=全く別、1=完全一致。 */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        val union = a.size + b.size - common
        return if (union == 0) 0.0 else common.toDouble() / union
    }

    fun parse(fp: String?): Set<String> =
        fp?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private class Place {
        val wifi = HashSet<String>()
        var latSum = 0.0
        var lonSum = 0.0
        var n = 0
        val hasCoord get() = n > 0
        val lat get() = latSum / n
        val lon get() = lonSum / n
        fun addCoord(la: Double, lo: Double) { latSum += la; lonSum += lo; n++ }
    }

    /**
     * 観測列を場所IDに割り当てる。WiFi指紋を優先し、無ければ座標で束ねる。
     *
     * 指紋の突き合わせは観測単位ではなく **指紋の値ごと** に行う。
     * 1回のスキャン結果が数十件の観測に付くので、観測単位で回すと同じ判定を無駄に繰り返す。
     *
     * 戻り値は observations と同じ順序・同じ長さの場所ID列。
     * 場所を特定できなかった観測は -1。
     */
    fun assignPlaces(observations: List<Observation>): List<Int> {
        val order = observations.indices.sortedBy { observations[it].timestamp }
        val places = ArrayList<Place>()
        val result = IntArray(observations.size) { -1 }
        val fpCache = HashMap<String, Int>()      // 同じ指紋文字列は同じ場所

        for (i in order) {
            val o = observations[i]
            val fp = parse(o.wifiFp)

            if (fp.size >= MIN_APS) {
                val cached = fpCache[o.wifiFp]
                val pid = if (cached != null) cached else {
                    var best = -1
                    var bestSim = MATCH_JACCARD
                    for (p in places.indices) {
                        val sim = jaccard(places[p].wifi, fp)
                        if (sim > bestSim) { bestSim = sim; best = p }
                    }
                    if (best < 0) { places.add(Place()); best = places.size - 1 }
                    // 一致した場所の指紋に新しいAPを取り込む。置き換えると、その回に
                    // 取りこぼしたAPが指紋から消えて次回の一致率が下がり、
                    // 「その場に居るのに一致しなくなる」自己増悪を起こす。
                    places[best].wifi.addAll(fp)
                    fpCache[o.wifiFp!!] = best
                    best
                }
                result[i] = pid
                if (o.myLat != null && o.myLon != null) places[pid].addCoord(o.myLat, o.myLon)
                continue
            }

            // WiFi指紋なし。座標で既知の場所に寄せる
            val la = o.myLat; val lo = o.myLon
            if (la == null || lo == null) continue          // 場所不明のまま(-1)
            var best = -1
            var bestD = COORD_RADIUS_M
            for (p in places.indices) {
                if (!places[p].hasCoord) continue
                val d = StalkerDetector.distanceM(places[p].lat, places[p].lon, la, lo)
                if (d < bestD) { bestD = d; best = p }
            }
            if (best < 0) { places.add(Place()); best = places.size - 1 }
            places[best].addCoord(la, lo)
            result[i] = best
        }
        return result.toList()
    }
}
