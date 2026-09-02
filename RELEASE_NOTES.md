# Bluehood Scanner v0.1

First public release. Android 13+ (API 33). Verified on GrapheneOS / Pixel 8 Pro.

初回公開リリース。Android 13以降。GrapheneOS（Pixel 8 Pro）で動作確認。

---

## Verify before you install / インストール前に検証する

This APK is **self-signed**, so Android will warn you about an unknown source. That
warning is expected and cannot be removed without a Play Store listing. The hashes
below are therefore the only way to tell a genuine build from a forgery — check them.

自己署名のため「提供元不明」の警告が出ます。これは正常で、消す手段はありません。
したがって以下のハッシュが**本物と偽物を見分ける唯一の手段**です。必ず照合してください。

| | |
|---|---|
| File | `Bluehood-Scanner-0.1.apk` |
| Size | 2,107,784 bytes (2.0 MB) |
| APK SHA-256 | `68ac1a82f7a7326df0deaad62e274172f5a7b03d2605476343f1fbe168626082` |
| Signing certificate SHA-256 | `797bf4fe07c8b352090b2914491149c6826870429831828d5aca1505c88d8092` |
| Package | `com.faker.bluehood` |
| versionCode / versionName | 1 / 0.1 |

**The signing certificate hash is the value that matters across releases.** The APK
hash changes with every build; the certificate hash must never change. If a future
release shows a different certificate, it was not built by this project — do not
install it, and open an issue.

**リリースを跨いで意味を持つのは署名証明書のハッシュです。** APKのハッシュはビルドごとに
変わりますが、証明書のハッシュは変わってはいけません。将来のリリースで値が違っていたら、
それはこのプロジェクトの配布物ではありません。インストールせず、Issueで報告してください。

### Checking the APK hash / APKハッシュの照合

```bash
sha256sum Bluehood-Scanner-0.1.apk
```

```powershell
Get-FileHash Bluehood-Scanner-0.1.apk -Algorithm SHA256
```

### Checking the signature / 署名の照合

With the Android SDK build-tools installed:

```bash
apksigner verify --print-certs -v Bluehood-Scanner-0.1.apk
```

Look for `Signer #1 certificate SHA-256 digest` and compare it with the table above.

`Signer #1 certificate SHA-256 digest` の行を上の表と照合してください。

Already installed? Compare what your phone is actually running:

インストール済みの端末で、実際に動いているものを照合する場合:

```bash
adb shell pm list packages -f com.faker.bluehood
```

---

## After installing / インストール後

Three things must be enabled or the app cannot scan. It tells you which one is
missing rather than pretending to work.

以下の3つが必要です。欠けている場合、アプリは動いているふりをせず理由を表示します。

1. **Bluetooth** — a disabled adapter fails *silently* at the Android API level, so
   the app checks `isEnabled` directly.
   Bluetooth。無効時はAndroid APIが無言で失敗するため、アプリが直接確認します。
2. **Location services**, the system-wide toggle — with it off, Android discards
   *all* BLE scan results without an error.
   位置情報サービス（システム全体のトグル）。オフだとBLE結果が無言で全破棄されます。
3. **Precise location** permission — "approximate" is not enough; Android withholds
   scan results from apps holding only coarse location.
   位置情報の権限は**正確な位置**。「おおよそ」ではスキャン結果が渡されません。

`INTERNET` is **not** required. Every core feature — scanning, fingerprinting, follow
detection, evidence export — works with the network permission fully revoked. On
GrapheneOS you can revoke it right away; only online map tiles will be unavailable.
To keep the map too, open it online once and use **Save this area** to pre-cache
tiles, then revoke.

`INTERNET` 権限は**不要**です。スキャン・指紋・尾行検知・証拠出力といった中核機能は
権限を完全に剥奪しても動作します。GrapheneOSでは最初から剥奪して構いません
（地図タイルだけが出なくなります）。地図も使いたい場合は、一度オンラインで開いて
**「この範囲を保存」**でタイルをキャッシュしてから剥奪してください。

---

## Known limits in this release / このリリースの既知の限界

- A device that is fully anonymised *and* changes its payload cannot be re-identified
  across MAC rotations. Waveform-level fingerprinting needs an SDR and is impossible
  on a phone.
  完全に匿名化され広告内容も変わる端末は、MAC回転を跨いで再識別できません。
  波形レベルの指紋にはSDRが必要で、スマートフォンでは不可能です。
- The evidence export's hash chain proves **internal consistency only**, not
  authenticity. Anyone holding this app can construct a consistent chain. Do not
  describe the export as tamper-proof.
  証拠出力のハッシュチェーンが示すのは**内部の整合性だけ**で、真正性ではありません。
  このアプリを持つ者なら整合したチェーンを作れます。「改ざん不可」と説明しないでください。
- Classic Bluetooth is not wired up; BLE only.
  Classic Bluetoothは未対応です。BLEのみ。
- The selected tab resets when the Activity is recreated (e.g. on rotation).
  Activity再生成（画面回転など）でタブ選択が戻ります。

On GrapheneOS specifically: exempt the app from battery optimisation, and be aware
that Android throttles `startScan` to 5 calls per 30 seconds.

GrapheneOS特有の注意: バッテリー最適化から除外してください。また Android は
`startScan` を30秒に5回までに制限します。
