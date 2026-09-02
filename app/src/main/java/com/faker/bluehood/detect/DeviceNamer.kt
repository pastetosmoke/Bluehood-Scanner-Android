package com.faker.bluehood.detect

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult

/** 広告から読める/類推できる素性。スマホは名前を出さないので vendor 止まり(個体特定はしない)。 */
data class Identity(val name: String?, val vendor: String?, val type: String?)

object DeviceNamer {

    fun identify(result: ScanResult): Identity {
        val rec = result.scanRecord
        val name = rec?.deviceName?.trim()?.takeIf { it.isNotEmpty() }   // 広告ローカル名(直接)
        return Identity(name, vendorOf(rec), typeOf(rec))
    }

    /** Manufacturer Specific Data の会社IDからベンダーを推定。未知は "Co.0xNNNN"(嘘をつかない)。 */
    private fun vendorOf(rec: ScanRecord?): String? {
        val msd = rec?.manufacturerSpecificData ?: return null
        if (msd.size() == 0) return null
        val cid = msd.keyAt(0)
        return VENDORS[cid] ?: "Co.0x%04X".format(cid)
    }

    /** 標準サービスUUIDから種別を推定。 */
    private fun typeOf(rec: ScanRecord?): String? {
        val shorts = HashSet<Int>()
        rec?.serviceUuids?.forEach { shorts.add(shortUuid(it.uuid)) }
        rec?.serviceData?.keys?.forEach { shorts.add(shortUuid(it.uuid)) }
        for ((uuid, label) in TYPES) if (uuid in shorts) return label
        return null
    }

    // 会社ID→ベンダー(確度の高いものだけ。未知は生の16進で返す)
    private val VENDORS = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x00E0 to "Google",
        0x0075 to "Samsung",
        0x0059 to "Nordic",
        0x0157 to "Xiaomi/Huami",
    )

    // サービスUUID(16bit)→種別
    private val TYPES = linkedMapOf(
        0x1812 to "HID(キーボード/マウス)",
        0x180D to "心拍/フィットネス",
        0xFE2C to "Google Fast Pair",
        0xFD5A to "Samsung SmartTag",
        0xFEED to "Tile",
        0xFEAA to "Eddystone/Google",
        0x180F to "バッテリーサービス",
    )

    private fun shortUuid(u: java.util.UUID): Int =
        ((u.mostSignificantBits ushr 32) and 0xFFFF).toInt()
}
