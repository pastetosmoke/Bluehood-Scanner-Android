# Bluehood Web 配布ページ + アクセスログ — Kali機デプロイマニュアル

## 構成

```
Kali <KALI_HOST>
├─ <HOME>/bluehood-web/           (このディレクトリ)
│  ├─ index.html                    (配布ページ)
│  ├─ access_log.html               (アクセスログダッシュボード)
│  ├─ access_log_server.py          (Flask ログサーバー)
│  ├─ dist/Bluehood-Scanner-*.apk   (配布APK)
│  ├─ access_log.jsonl              (ログファイル、自動作成)
│  └─ docker-compose.yml            (コンテナ定義)
│
├─ cloudflared トンネル 
│  └─ https://bluehood-XXXXX.<TAILNET>.ts.net/
│     ├─ /                   → index.html
│     ├─ /access_log.html    → ダッシュボード
│     └─ /api/access-log.json → ログAPI
```

## デプロイ手順

### 1. Windows機からKali機にファイル転送

```bash
# Windows PowerShell
$files = @(
    "C:\Users\Faker\bluehood-android\web\index.html",
    "C:\Users\Faker\bluehood-android\web\access_log.html",
    "C:\Users\Faker\bluehood-android\web\access_log_server.py",
    "C:\Users\Faker\bluehood-android\dist\Bluehood-Scanner-0.2.apk"
)

foreach ($file in $files) {
    # SCP で転送（paramiko or openssh 要）
    # または Git で管理
}
```

### 2. Kali機のセットアップ

Kali機（<KALI_HOST>）で：

```bash
# ディレクトリ作成
mkdir -p <HOME>/bluehood-web
cd <HOME>/bluehood-web

# ファイル配置
# → index.html, access_log.html, access_log_server.py を配置
# → dist/ ディレクトリに APK を配置

# Python依存インストール
pip install flask requests

# テスト実行（ポート 8888）
python access_log_server.py --port 8888

# → ブラウザで http://<KALI_HOST>:8888 にアクセス
```

### 3. Docker化（推奨：<other-service> と同じ構成）

Kali機の `<HOME>/<other-service>/` と同じように Docker で管理：

**docker-compose.yml** (Kali機の `<HOME>/bluehood-web/`)：

```yaml
version: '3'
services:
  bluehood-web:
    image: python:3.11-slim
    working_dir: /app
    volumes:
      - .:/app
    ports:
      - "8889:8888"  # ホストの 8889 → コンテナの 8888
    environment:
      - PORT=8888
    command: sh -c "pip install flask requests && python access_log_server.py"
    restart: unless-stopped
```

起動：

```bash
cd <HOME>/bluehood-web
docker-compose up -d
docker-compose logs -f  # ログ確認
```

### 4. cloudflared トンネル設定

Kali機の既存 cloudflared 設定に Bluehood ルートを追加：

**~/.cloudflared/config.yml** (既存のreadsb/<other-service>に追加)：

```yaml
tunnel: <tunnel-uuid>
credentials-file: <HOME>/.cloudflared/<tunnel-uuid>.json

ingress:
  # 既存のreadsb
  - hostname: <other-service>-*.<TAILNET>.ts.net
    service: http://localhost:8080/
    path: /view
    access:
      rules:
        - rule: <ACCESS_RULE_TOKEN>  # token

  # Bluehood 配布ページ + ログ
  - hostname: bluehood-*.<TAILNET>.ts.net
    service: http://localhost:8889/          # docker-compose ポート
    access:
      rules:
        - rule: <ACCESS_RULE_TOKEN>  # token (同じ token)

  - service: http_status:404
```

再起動：

```bash
# cloudflared サービス再起動（systemd で管理している場合）
sudo systemctl restart cloudflared

# または手動確認
cloudflared tunnel info <tunnel-name>
```

### 5. テスト

ローカル（Kali機内）：
```bash
curl http://<KALI_HOST>:8889/
curl http://<KALI_HOST>:8889/access_log.html
curl http://<KALI_HOST>:8889/api/access-log.json
```

外部（トンネル経由）：
```
https://bluehood-<NODE>.<TAILNET>.ts.net/
https://bluehood-<NODE>.<TAILNET>.ts.net/access_log.html
```

ブラウザで開く（トークン認証を要求される可能性）。

## ログファイル

### access_log.jsonl（自動生成）

```json
{"timestamp": 1725534512345, "action": "view", "file": "index.html", "ip": "203.0.113.45", "user_agent": "Mozilla/5.0..."}
{"timestamp": 1725534521567, "action": "download", "file": "Bluehood-Scanner-0.2.apk", "ip": "203.0.113.45", "user_agent": "..."}
```

### access_log_stats.json（自動生成・更新）

```json
{
  "total_visits": 42,
  "total_downloads": 8,
  "unique_visitors": ["203.0.113.45", "192.0.2.10"],
  "last_24h_visits": 12,
  "today_downloads": 2,
  "last_visitor_at": 1725534521567
}
```

## トラブルシューティング

### ポート 8889 が既に使われている

```bash
# 使用中のプロセス確認
sudo lsof -i :8889

# 別のポートに変更（docker-compose.yml で "8890:8888" に変更）
```

### cloudflared トンネルに接続できない

```bash
# cloudflared ステータス確認
cloudflared tunnel list
cloudflared tunnel info <tunnel-name>

# ログ確認
tail -f /var/log/cloudflared/*.log
```

### アクセスログが記録されない

```bash
# ログファイルの権限確認
ls -la <HOME>/bluehood-web/access_log.jsonl
chmod 666 <HOME>/bluehood-web/access_log.json*

# Flaskサーバーログ確認
docker-compose logs bluehood-web
```

## 注意

- ログファイルはプレーンテキスト（JSON Lines） — サイズが増加したら定期的にローテーション
- IP は cloudflared ヘッダから取得（X-Forwarded-For）
- User-Agent は長い場合はアクセスログHTMLで切り詰め表示
