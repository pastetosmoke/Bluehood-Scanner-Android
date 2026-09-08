package com.faker.bluehood.detect

enum class AttackSignature(
    val title: String,
    val cve: String?,
    val detail: String,
) {
    MALFORMED_AD(
        "不正ADペイロード (Fuzzing)",
        null,
        "BLE ADレコードの長さフィールドが残バッファを超えている。Cerberus Blueのファジングモジュールが生成する典型的シグネチャ。"
    ),
    DUPLICATE_AD_TYPE(
        "重複ADタイプ (Fuzzing)",
        null,
        "同一AD typeが1つの広告パケットに複数存在。仕様(Core Spec 11.1.3)では各typeは1回のみ許可。"
    ),
    PATTERN_FILL(
        "パターンフィルペイロード (Fuzzing)",
        null,
        "0x00/0xFF/インクリメンタル等の構造化パターンで埋めたペイロード。ファジングツールが送るテストデータの特徴。"
    ),
    INVALID_COMPANY_ID(
        "無効 Manufacturer Company ID",
        null,
        "Manufacturer Specific DataのCompany IDが0x0000(未割当)または0xFFFF(Bluetooth SIG内部テストマーカー)。実在するデバイスは使わない値。"
    ),
    ZERO_LENGTH_AD(
        "ゼロ長ADレコード",
        null,
        "長さ0のADレコード(Typeバイトのみ)がパケット先頭または有効レコードの間に存在。仕様外の構造。"
    ),
    AD_FLOODING(
        "BLE広告フラッディング (DoS)",
        "CVE-2024-0230",
        "同一フィンガープリントの広告が10秒間に300件超。正常なBLE広告間隔(最短100ms=最大100件/10秒)を大幅に超えており、意図的な送信以外では発生しない。"
    ),
    IDENTITY_SPOOFING(
        "デバイス偽装 (BIAS/Impersonation)",
        "CVE-2021-0326",
        "ローカル名が既知ブランド(Apple/Samsung等)を名乗っているが、そのブランドに固有のCompany IDまたはサービスUUIDが存在しない。Cerberus Blueのspoofingモジュールが生成。"
    ),
}
