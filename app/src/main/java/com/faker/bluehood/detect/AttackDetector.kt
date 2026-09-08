package com.faker.bluehood.detect

import android.bluetooth.le.ScanResult
import com.faker.bluehood.ble.BleFingerprint

/**
 * BLE広告パケットからCerberus Blue等のBTペンテストツールのシグネチャを検出する。
 *
 * 検出できるもの: 広告ペイロード層の異常(Fuzzing・会社ID偽装・偽デバイス名)・フラッディング(DoS)
 * 検出できないもの: KNOB/BlueBorne/BlueFrag/BleedingTooth/BLE MITM
 *   — 接続確立後のHCIレイヤで発生するため、受動BLE広告スキャンでは不可視。
 *
 * 同じ(シグネチャ×デバイスキー)はREPORT_THROTTLE_MS(60秒)に1回だけ報告し、
 * 連続する不正パケットでDBが埋まるのを防ぐ。
 * フラッディングは窓がリセットされるたびに報告する(1回/10秒が上限)。
 */
object AttackDetector {

    data class Finding(val sig: AttackSignature, val evidence: String)

    // フラッディング: キー -> (窓開始ms, 件数)
    private val floodWindow = HashMap<String, Pair<Long, Int>>()
    private const val FLOOD_WINDOW_MS = 10_000L
    // 正常BLE広告は最短100ms間隔 = 10秒で最大100件。300件は明らかな異常。
    private const val FLOOD_THRESHOLD = 300

    // 静的シグネチャのスロットル: "sigName:stableKeyPrefix8" -> 最終報告ms
    private val reportThrottle = HashMap<String, Long>()
    private const val REPORT_THROTTLE_MS = 60_000L

    fun analyze(fp: BleFingerprint, result: ScanResult): List<Finding> {
        val out = mutableListOf<Finding>()
        val rawBytes = result.scanRecord?.bytes
        val key = fp.stableKey()

        rawBytes?.let { parseAdStructure(it, key, out) }
        checkCompanyIds(fp, key, out)
        checkSpoofing(result, fp, key, out)
        checkFlooding(key, out)

        return out
    }

    // -------- ADペイロード静的解析 --------

    private fun parseAdStructure(payload: ByteArray, key: String, out: MutableList<Finding>) {
        val seenTypes = mutableSetOf<Int>()
        var i = 0
        while (i < payload.size) {
            val len = payload[i].toInt() and 0xFF
            if (len == 0) {
                // Core Spec上、長さ0はパディング終端として許容されるが、
                // パケット先頭か中間での出現はファジングの痕跡。
                val afterPadding = (i + 1 until payload.size).any { payload[it].toInt() and 0xFF != 0 }
                if (i == 0 || afterPadding) {
                    emit(AttackSignature.ZERO_LENGTH_AD, key,
                        "offset=$i payload先頭8B=${payload.take(8).hex()}", out)
                }
                break
            }
            // 長さがバッファをはみ出す
            if (i + len >= payload.size) {
                emit(AttackSignature.MALFORMED_AD, key,
                    "offset=$i len=$len 残=${payload.size - i - 1}バイト", out)
                break
            }

            val type = payload[i + 1].toInt() and 0xFF
            if (type in seenTypes) {
                emit(AttackSignature.DUPLICATE_AD_TYPE, key,
                    "type=0x${"%02X".format(type)} 重複 offset=$i", out)
            }
            seenTypes += type

            val data = payload.copyOfRange(i + 2, i + 1 + len)
            if (data.size >= 4) checkPatternFill(data, type, i, key, out)

            i += len + 1
        }
    }

    private fun checkPatternFill(data: ByteArray, type: Int, offset: Int, key: String, out: MutableList<Finding>) {
        val all00 = data.all { it == 0x00.toByte() }
        val allFF = data.all { it == 0xFF.toByte() }
        val isInc = data.size >= 4 && (0 until data.size - 1).all {
            (data[it + 1].toInt() and 0xFF) == ((data[it].toInt() and 0xFF) + 1) and 0xFF
        }
        if (all00 || allFF || isInc) {
            val pattern = when { all00 -> "0x00"; allFF -> "0xFF"; else -> "incremental" }
            emit(AttackSignature.PATTERN_FILL, key,
                "type=0x${"%02X".format(type)} offset=$offset pattern=$pattern len=${data.size}", out)
        }
    }

    // -------- Company ID検証 --------

    private fun checkCompanyIds(fp: BleFingerprint, key: String, out: MutableList<Finding>) {
        for (cid in fp.companyIds) {
            if (cid == 0x0000 || cid == 0xFFFF) {
                emit(AttackSignature.INVALID_COMPANY_ID, key,
                    "company_id=0x${"%04X".format(cid)}", out)
            }
        }
    }

    // -------- 偽装検知 --------

    private val APPLE_KEYWORDS = listOf("AirTag", "iPhone", "iPad", "iPod", "AirPods", "iWatch", "HomePod")
    private val SAMSUNG_KEYWORDS = listOf("Galaxy")

    private fun checkSpoofing(result: ScanResult, fp: BleFingerprint, key: String, out: MutableList<Finding>) {
        val name = result.scanRecord?.deviceName ?: return
        for (kw in APPLE_KEYWORDS) {
            if (name.contains(kw, ignoreCase = true) && 0x004C !in fp.companyIds) {
                emit(AttackSignature.IDENTITY_SPOOFING, key,
                    "localName=\"$name\" がAppleデバイスを名乗るがcompany ID 0x004Cなし", out)
                return
            }
        }
        for (kw in SAMSUNG_KEYWORDS) {
            if (name.contains(kw, ignoreCase = true) && 0x0075 !in fp.companyIds) {
                emit(AttackSignature.IDENTITY_SPOOFING, key,
                    "localName=\"$name\" がSamsungデバイスを名乗るがcompany ID 0x0075なし", out)
                return
            }
        }
    }

    // -------- フラッディング検知 --------

    private fun checkFlooding(key: String, out: MutableList<Finding>) {
        val now = System.currentTimeMillis()
        val (wStart, cnt) = floodWindow[key] ?: Pair(now, 0)
        val elapsed = now - wStart
        val newCnt: Int
        val newStart: Long
        if (elapsed > FLOOD_WINDOW_MS) {
            newCnt = 1; newStart = now
        } else {
            newCnt = cnt + 1; newStart = wStart
        }
        floodWindow[key] = Pair(newStart, newCnt)
        // 窓内でちょうど閾値を踏んだ瞬間に1回だけ報告(>=にすると毎件報告になる)
        if (newCnt == FLOOD_THRESHOLD) {
            out += Finding(
                AttackSignature.AD_FLOODING,
                "${FLOOD_THRESHOLD}件/${FLOOD_WINDOW_MS / 1000}秒 key=${key.take(8)}"
            )
        }
    }

    // -------- スロットルつき出力 --------

    private fun emit(sig: AttackSignature, key: String, evidence: String, out: MutableList<Finding>) {
        val throttleKey = "${sig.name}:${key.take(8)}"
        val now = System.currentTimeMillis()
        if (now - (reportThrottle[throttleKey] ?: 0L) < REPORT_THROTTLE_MS) return
        reportThrottle[throttleKey] = now
        out += Finding(sig, evidence)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private fun List<Byte>.hex(): String = joinToString("") { "%02x".format(it) }
}
