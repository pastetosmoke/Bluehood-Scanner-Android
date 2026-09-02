package com.faker.bluehood.detect

import android.bluetooth.le.ScanResult

/**
 * 匿名化トラッカーの「種別」を広告シグネチャから判定する。
 * 個体de-anonymization(任意端末の追跡)ではなく、種別検知＋共起で「自分を尾けるトラッカー」を炙り出すためのもの。
 * DULT(Detecting Unwanted Location Trackers)/AirGuard と同じ発想。
 */
enum class TrackerType(val label: String) {
    APPLE_FIND_MY("Apple Find My/AirTag"),
    TILE("Tile"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    GOOGLE_FIND_MY("Google Find My Device"),
}

object TrackerClassifier {

    fun classify(result: ScanResult): TrackerType? {
        val rec = result.scanRecord ?: return null

        val msd = rec.manufacturerSpecificData
        if (msd != null) {
            // Apple Find My: 会社ID 0x004C, 先頭バイト 0x12 = offline finding(オーナーと分離した状態)
            val apple = msd.get(0x004C)
            if (apple != null && apple.isNotEmpty() && (apple[0].toInt() and 0xFF) == 0x12) {
                return TrackerType.APPLE_FIND_MY
            }
            // Samsung SmartTag: 会社ID 0x0075
            if (msd.get(0x0075) != null) return TrackerType.SAMSUNG_SMARTTAG
        }

        // 16bit サービスUUID で判定
        val shorts = mutableSetOf<Int>()
        rec.serviceUuids?.forEach { shorts.add(shortUuid(it.uuid)) }
        rec.serviceData?.keys?.forEach { shorts.add(shortUuid(it.uuid)) }
        if (0xFD5A in shorts) return TrackerType.SAMSUNG_SMARTTAG   // SmartThings Find offline finding
        if (0xFEED in shorts) return TrackerType.TILE
        if (0xFEAA in shorts) return TrackerType.GOOGLE_FIND_MY     // Eddystone / Find My Device network(暫定)
        return null
    }

    /** 128bit UUID から 16bit ショートUUID(0000xxxx-...)を取り出す。 */
    private fun shortUuid(u: java.util.UUID): Int =
        ((u.mostSignificantBits ushr 32) and 0xFFFF).toInt()
}
