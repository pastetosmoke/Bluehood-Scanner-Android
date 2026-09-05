package com.faker.bluehood.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探索の判定は純関数側に閉じてあるので、端末なしで全分岐を検証できる。
 *
 * StalkerDetector のスコア誤検知はテストが無かったせいで実機まで残ったので、
 * 同じ轍を踏まないよう、特に「受信途絶」を「遠い」と誤表示しないことを固定する。
 */
class HuntStateTest {

    private val target = HuntState.Target(trackerType = "Apple Find My/AirTag", label = "t")

    private fun state(vararg samples: Pair<Long, Int>, target: HuntState.Target? = this.target) =
        HuntState.State(
            target = target,
            samples = samples.map { HuntState.Sample(it.first, it.second) },
            bestRssi = samples.maxOfOrNull { it.second },
            lastSeen = samples.maxOfOrNull { it.first } ?: 0L,
        )

    @Test fun `受信が途絶えたらstale`() {
        val now = 100_000L
        val s = state(90_000L to -60)          // 10秒前が最後 (> STALE_MS 8秒)
        assertTrue(s.isStale(now))
    }

    @Test fun `直近に受信があればstaleではない`() {
        val now = 100_000L
        val s = state(97_000L to -60)          // 3秒前
        assertFalse(s.isStale(now))
    }

    @Test fun `探索していなければstaleにならない`() {
        val now = 100_000L
        assertFalse(state(target = null).isStale(now))
    }

    @Test fun `弱い電波と受信途絶は別物`() {
        val now = 100_000L
        // 「弱いが届いている」= staleではない。ここを混同すると鞄の中を探し続けてしまう。
        val weak = state(99_000L to -95, 98_500L to -96)
        assertFalse(weak.isStale(now))
        assertEquals(-95, weak.smoothedRssi(now))
    }

    @Test fun `近づくとCLOSER`() {
        val now = 100_000L
        val s = state(
            95_000L to -80, 94_500L to -80,    // 少し前(5秒前)は弱い
            98_500L to -60, 99_000L to -60,    // 直近は強い
        )
        assertEquals(HuntState.Trend.CLOSER, s.trend(now))
    }

    @Test fun `離れるとFARTHER`() {
        val now = 100_000L
        val s = state(
            95_000L to -55, 94_500L to -55,
            98_500L to -75, 99_000L to -75,
        )
        assertEquals(HuntState.Trend.FARTHER, s.trend(now))
    }

    @Test fun `2dBの揺れではトレンドを出さない`() {
        val now = 100_000L
        // RSSIは体の向きだけで数dB動く。閾値未満をCLOSERにすると矢印が暴れて使えない。
        val s = state(
            95_000L to -62, 94_500L to -62,
            98_500L to -60, 99_000L to -60,
        )
        assertEquals(HuntState.Trend.FLAT, s.trend(now))
    }

    @Test fun `比較材料が足りなければUNKNOWN`() {
        val now = 100_000L
        assertEquals(HuntState.Trend.UNKNOWN, state(99_000L to -60).trend(now))
    }

    @Test fun `直近に受信が無ければ代表値はnull`() {
        val now = 100_000L
        assertNull(state(90_000L to -60).smoothedRssi(now))
    }

    @Test fun `heatは強さに単調で範囲内`() {
        val now = 100_000L
        val far = state(99_000L to -100).heat(now)!!
        val near = state(99_000L to -40).heat(now)!!
        assertEquals(0f, far, 0.001f)
        assertEquals(1f, near, 0.001f)
        assertTrue(near > far)
    }
}
