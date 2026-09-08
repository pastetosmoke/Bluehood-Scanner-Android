package com.faker.bluehood.detect

import com.faker.bluehood.data.AttackEvent

/**
 * Cerberus Blue各攻撃モジュールに対応するテストケース。
 * DBに合成AttackEventを注入することで「本物の検出イベントがどう見えるか」を確認できる。
 * isTest=trueで実検出と区別する。
 */
object AttackTestInjector {

    data class TestCase(
        val sig: AttackSignature,
        val label: String,
        val cerberusModule: String,
        val evidenceHint: String,
    )

    val CASES: List<TestCase> = listOf(
        TestCase(
            AttackSignature.MALFORMED_AD,
            "Fuzzingテスト: ADレコード長オーバーフロー",
            "Cerberus Blue > Fuzzing > malformed-length",
            "len=0x3F remaining=4 offset=0"
        ),
        TestCase(
            AttackSignature.DUPLICATE_AD_TYPE,
            "Fuzzingテスト: 重複ADタイプ",
            "Cerberus Blue > Fuzzing > duplicate-type",
            "type=0x09(Complete Local Name) 重複 at offset=6"
        ),
        TestCase(
            AttackSignature.PATTERN_FILL,
            "Fuzzingテスト: 0xFF埋めManufacturer Data",
            "Cerberus Blue > Fuzzing > pattern-fill",
            "pattern=0xFF len=16 company_id=0x0001"
        ),
        TestCase(
            AttackSignature.INVALID_COMPANY_ID,
            "Spoofingテスト: Company ID 0xFFFF(テストマーカー)",
            "Cerberus Blue > Spoofing > invalid-cid",
            "company_id=0xFFFF"
        ),
        TestCase(
            AttackSignature.ZERO_LENGTH_AD,
            "Fuzzingテスト: ゼロ長ADレコード",
            "Cerberus Blue > Fuzzing > zero-length",
            "offset=0 len=0 (パケット先頭)"
        ),
        TestCase(
            AttackSignature.AD_FLOODING,
            "DoSテスト: BLE広告フラッディング (CVE-2024-0230)",
            "Cerberus Blue > DoS > ble-flood",
            "模擬 300件/10秒 同一フィンガープリント"
        ),
        TestCase(
            AttackSignature.IDENTITY_SPOOFING,
            "Spoofingテスト: 「AirTag」を名乗るがApple company IDなし",
            "Cerberus Blue > Spoofing > device-impersonation",
            "localName=AirTag company_id=0x0059(Nordic Semiconductor)"
        ),
    )

    fun makeEvent(tc: TestCase): AttackEvent = AttackEvent(
        timestamp = System.currentTimeMillis(),
        signatureId = tc.sig.name,
        title = tc.sig.title,
        cve = tc.sig.cve,
        mac = "00:00:00:00:00:00",
        rssi = -60,
        evidence = "${tc.evidenceHint} [テストデータ: ${tc.cerberusModule}]",
        isTest = true,
    )
}
