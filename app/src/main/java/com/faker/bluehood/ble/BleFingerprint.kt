package com.faker.bluehood.ble

import android.bluetooth.le.ScanResult
import java.security.MessageDigest

/**
 * 回転をまたぐ合成指紋。MAC(RPAは約15分で回る)は意図的に主キーにしない。
 * 匿名化が効いているデバイス(Fast Pair の回転ID等)は stableKey が低エントロピーになり、
 * link() 側で Untrackable に落とす。それが正しい挙動。
 */
data class BleFingerprint(
    val serviceUuids: Set<String>,
    val companyIds: Set<Int>,
    val msdStablePrefix: String,
    val serviceDataKeys: Set<String>,
    val txPower: Int?,
    val advIntervalBucket: Int,
    val primaryPhy: Int,
) {
    /** エントロピーが低い = 追跡不能。名寄せしてはいけない。 */
    val isLowEntropy: Boolean
        get() = serviceUuids.isEmpty() && msdStablePrefix.isBlank() && serviceDataKeys.isEmpty()

    fun stableKey(): String {
        val raw = buildString {
            append(serviceUuids.sorted().joinToString(","))
            append("|"); append(companyIds.sorted().joinToString(","))
            append("|"); append(msdStablePrefix)
            append("|"); append(serviceDataKeys.sorted().joinToString(","))
            append("|"); append(txPower ?: "?")
            append("|"); append(advIntervalBucket)
            append("|"); append(primaryPhy)
        }
        return sha256(raw)
    }

    companion object {
        fun from(result: ScanResult, advIntervalMs: Long?): BleFingerprint {
            val rec = result.scanRecord
            val serviceUuids = rec?.serviceUuids?.map { it.uuid.toString() }?.toSet() ?: emptySet()
            val serviceData = rec?.serviceData?.keys?.map { it.uuid.toString() }?.toSet() ?: emptySet()

            val msd = rec?.manufacturerSpecificData
            val companyIds = mutableSetOf<Int>()
            val prefixes = StringBuilder()
            if (msd != null) {
                for (i in 0 until msd.size()) {
                    val cid = msd.keyAt(i)
                    companyIds.add(cid)
                    prefixes.append(msdStablePrefix(cid, msd.valueAt(i)))
                }
            }

            return BleFingerprint(
                serviceUuids = serviceUuids,
                companyIds = companyIds,
                msdStablePrefix = prefixes.toString(),
                serviceDataKeys = serviceData,
                txPower = rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
                advIntervalBucket = ((advIntervalMs ?: 0L) / 100L).toInt(),
                primaryPhy = result.primaryPhy,
            )
        }

        /**
         * ベンダー別に「回転しない先頭バイト長」を切る。ここが derandomization 精度を決める。
         * Apple/Microsoft の後続バイト(Continuity/Swift Pair)や Google Fast Pair の
         * account-key ローテーション部は意図的に除外する。
         */
        private fun msdStablePrefix(companyId: Int, data: ByteArray?): String {
            if (data == null) return ""
            return when (companyId) {
                0x004C -> data.take(2).toHex()   // Apple: type+len まで
                0x0006 -> data.take(1).toHex()   // Microsoft Swift Pair
                0x00E0 -> ""                     // Google Fast Pair: 匿名化設計、繋がない
                else   -> data.take(6).toHex()   // 安物IoTは前半固定が多い
            }
        }

        // take(n) は List<Byte> を返すので Iterable<Byte> を受け取る
        private fun Iterable<Byte>.toHex(): String =
            joinToString("") { "%02x".format(it) }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
