# Bluehood Scanner v0.2

Android 13+ (API 33). Verified on GrapheneOS / Pixel 8 Pro.

Android 13以降。GrapheneOS（Pixel 8 Pro）で動作確認。

---

## What's new in 0.2 / 0.2 の変更点

**Follow detection now works indoors.** Until now the score was computed only from
observations that carried GPS coordinates. Indoors, where no fix is available, the
neighbourhood list filled up while the verdict stayed at 0.0 forever — the app looked
safe because it was blind, and did not say so. Places are now identified by the set of
surrounding WiFi access points, which works without GPS and does not drift the way an
indoor fix does.

**尾行判定が屋内でも動作します。** これまでスコアは座標のある観測だけで計算しており、
測位できない屋内では近隣一覧が埋まるのに判定は永久に0.0のままでした。
アプリは「見えていない」ことを告げずに安全に見えていたわけです。
場所の同定を周囲のWiFi APの集合で行うようにしたため、GPS無しでも成立し、
屋内測位の揺れで同じ場所が複数地点に割れることもなくなりました。

The app never calls `startScan()` for WiFi — an active scan transmits probe requests
carrying your own MAC, and a counter-surveillance tool that leaks your own trail defeats
its purpose. It only reads the cache the OS has already collected. BSSIDs are never
stored raw either: a public geolocation database can turn a BSSID back into coordinates,
so they are hashed with a per-device salt. Set membership still compares correctly.

WiFiの`startScan()`は呼びません。アクティブスキャンは自分のMACを載せたプローブ要求を
送信するため、対監視の道具が自分の足跡を撒くことになるからです。OSが既に集めた
キャッシュを読むだけです。BSSIDも生では保存しません。公開のジオロケーションDBで
座標に復元できてしまうので、端末固有ソルト付きのハッシュにしています。

**"That one's mine" marking.** Your own earbuds and watch follow you everywhere by
definition, so they satisfy the follow criteria more cleanly than an actual stalker would.
Left unfiltered, a real warning drowns in your own hardware. Only the owner can tell the
difference, so it is a manual declaration: tap the chip on a device in the neighbourhood
list and it is excluded from scoring.

**「自分の機器」の申告。** 自分のイヤホンや時計は定義上どこにでも付いてくるので、
本物の尾行より綺麗に尾行条件を満たしてしまいます。除外しないと本物の警告が
自分の持ち物に埋もれます。持ち主にしか区別できないため申告制です。
近隣一覧のチップをタップすると判定から除外されます。

**Hunt tab.** Locates a tag that is already following you, by relative signal strength
(hotter / colder). It does not track anyone: the only thing it has is the strength at
your own hand, and nothing about the tab is written to storage. Turning RSSI into a
distance in metres would be a lie — the environment dominates — so it shows relative
strength and a direction of change only.

**探索タブ。** 既に自分を追尾していると判定されたタグを、電波強度の相対変化
（近い/遠い）で物理的に探します。相手を追う機能ではありません。得られるのは
自分の手元での強度だけで、この画面の状態は一切保存されません。RSSIをメートルに
換算するのは環境依存が大きすぎて嘘になるため、相対的な強さと変化の向きだけを表示します。

**Your observation log now survives updates.** Earlier versions rebuilt the database on
every schema change. From this release the log is evidence, so it is migrated rather than
silently discarded.

**観測ログが更新をまたいで残ります。** 以前はスキーマ変更のたびにDBを作り直していました。
この版から観測ログは証拠として意味を持つため、黙って消さずマイグレーションします。

---

## Verify before you install / インストール前に検証する

This APK is **self-signed**, so Android will warn you about an unknown source. That
warning is expected and cannot be removed without a Play Store listing. The hashes
below are therefore the only way to tell a genuine build from a forgery — check them.

自己署名のため「提供元不明」の警告が出ます。これは正常で、消す手段はありません。
したがって以下のハッシュが**本物と偽物を見分ける唯一の手段**です。必ず照合してください。

| | |
|---|---|
| File | `Bluehood-Scanner-0.2.apk` |
| Size | 2,156,964 bytes (2.1 MB) |
| APK SHA-256 | `885fefdf7b98d0b91a136c870730141aaa32aebe69e2c62ac5360c72845c9c73` |
| Signing certificate SHA-256 | `797bf4fe07c8b352090b2914491149c6826870429831828d5aca1505c88d8092` |
| Package | `com.faker.bluehood` |
| versionCode / versionName | 2 / 0.2 |

**The signing certificate hash is the value that matters across releases.** The APK
hash changes with every build; the certificate hash must never change. If a future
release shows a different certificate, it was not built by this project — do not
install it, and open an issue.

**リリースを跨いで意味を持つのは署名証明書のハッシュです。** APKのハッシュはビルドごとに
変わりますが、証明書のハッシュは変わってはいけません。将来のリリースで値が違っていたら、
それはこのプロジェクトの配布物ではありません。インストールせず、Issueで報告してください。

### Checking the APK hash / APKハッシュの照合

```bash
sha256sum Bluehood-Scanner-0.2.apk
```

```powershell
Get-FileHash Bluehood-Scanner-0.2.apk -Algorithm SHA256
```

### Checking the signature / 署名の照合

With the Android SDK build-tools installed:

```bash
apksigner verify --print-certs -v Bluehood-Scanner-0.2.apk
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
