package com.faker.bluehood.evidence

import com.faker.bluehood.data.DeviceCluster
import com.faker.bluehood.data.Observation
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 尾行アラート時の証拠パッケージ。追跡ではなく記録。
 * 出力は「自分がいつ・どこで・この指紋と何メートルで共起したか」の連続性のみ。
 * 相手の推定座標は一切含めない(そもそも DB に持っていない)。
 * ハッシュチェーンで事後改ざんを検出可能にして、警察提出に耐える形にする。
 */
object EvidenceExporter {

    fun export(cluster: DeviceCluster, observations: List<Observation>): String {
        val events = JSONArray()
        var prevHash = "genesis"
        for (o in observations.sortedBy { it.timestamp }) {
            val ev = JSONObject().apply {
                put("ts", o.timestamp)
                put("ts_jst", formatJst(o.timestamp))
                put("my_lat", o.myLat ?: JSONObject.NULL)          // 自分の座標(未取得なら null)
                put("my_lon", o.myLon ?: JSONObject.NULL)
                put("accuracy_m", o.myAccuracyM ?: JSONObject.NULL)
                put("rssi", o.rssi)
                put("raw", o.rawPayloadHex)
                put("prev", prevHash)
            }
            prevHash = sha256(ev.toString())     // 改ざん検出用チェーン
            ev.put("hash", prevHash)
            events.put(ev)
        }

        return JSONObject().apply {
            put("schema", "bluehood.evidence.v1")
            put("note", "self-centered co-presence log; contains reporter's own GPS only")
            put("fingerprint_key", cluster.stableKey)
            put("tracker_type", cluster.trackerType ?: JSONObject.NULL)
            put("stalker_score", cluster.stalkerScore)
            put("first_seen", cluster.firstSeen)
            put("first_seen_jst", formatJst(cluster.firstSeen))
            put("last_seen", cluster.lastSeen)
            put("last_seen_jst", formatJst(cluster.lastSeen))
            put("observation_count", observations.size)
            put("events", events)
            put("chain_tip", prevHash)
        }.toString(2)
    }

    private fun formatJst(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        return sdf.format(Date(epochMs))
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
