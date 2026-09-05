#!/usr/bin/env python3
"""
Bluehoodの配布ページ・APKダウンロードのアクセスログを記録。
Flask + ファイルベース（JSON）で、手軽にKali機にデプロイ可能。

用法：
  python access_log_server.py [--port 8888]

Kali機では docker-compose やsystemd unitで常駐。
"""

import json
import logging
import os
import time
from datetime import datetime, timedelta
from pathlib import Path
from threading import Lock
from typing import Dict, Any

from flask import Flask, request, jsonify, send_file, send_from_directory
from werkzeug.serving import run_simple

app = Flask(__name__, static_folder='.', static_url_path='')
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger('bluehood')

# ロック（複数リクエスト時の競合回避）
log_lock = Lock()

# ログファイルパス
LOG_FILE = Path('./access_log.jsonl')  # JSON Lines format
STATS_FILE = Path('./access_log_stats.json')

def init_files():
    """ログファイルを初期化（なければ作成）"""
    if not LOG_FILE.exists():
        LOG_FILE.touch()
    if not STATS_FILE.exists():
        STATS_FILE.write_text(json.dumps({
            'total_visits': 0,
            'total_downloads': 0,
            'unique_visitors': set(),
        }, indent=2, default=str))

def get_client_ip():
    """クライアントIPを取得（プロキシ対応）"""
    if request.headers.getlist('X-Forwarded-For'):
        return request.headers.getlist('X-Forwarded-For')[0]
    return request.remote_addr or '0.0.0.0'

def log_access(action: str, file: str = None, ip: str = None):
    """アクセスをログに記録"""
    ip = ip or get_client_ip()
    entry = {
        'timestamp': int(time.time() * 1000),  # ミリ秒
        'action': action,  # 'view' or 'download'
        'file': file,
        'ip': ip,
        'user_agent': request.headers.get('User-Agent', ''),
    }

    with log_lock:
        # JSONL 追記
        with open(LOG_FILE, 'a') as f:
            f.write(json.dumps(entry) + '\n')

        # 統計更新（簡易的）
        stats = json.loads(STATS_FILE.read_text())
        if action == 'view':
            stats['total_visits'] = stats.get('total_visits', 0) + 1
        elif action == 'download':
            stats['total_downloads'] = stats.get('total_downloads', 0) + 1

        # ユニークIP（set は JSON 不可なので list に変換）
        if isinstance(stats.get('unique_visitors'), list):
            unique_ips = set(stats['unique_visitors'])
        else:
            unique_ips = set()
        unique_ips.add(ip)
        stats['unique_visitors'] = list(unique_ips)

        STATS_FILE.write_text(json.dumps(stats, indent=2, default=str))

@app.route('/')
def index():
    """ページビュー記録 + index.html配信"""
    log_access('view', file='index.html')
    return send_file('index.html', mimetype='text/html')

@app.route('/access_log.html')
def access_log_page():
    """ログページビュー記録 + access_log.html配信"""
    log_access('view', file='access_log.html')
    return send_file('access_log.html', mimetype='text/html')

@app.route('/api/access-log.json')
def api_access_log():
    """
    ログAPI（access_log.html の JS が使用）。
    統計情報と最新ログ100件を返す。
    """
    with log_lock:
        # 統計
        stats = json.loads(STATS_FILE.read_text())
        stats['unique_visitors'] = len(stats.get('unique_visitors', []))

        # 過去24時間の集計
        now_ms = int(time.time() * 1000)
        day_ago_ms = now_ms - (24 * 3600 * 1000)

        entries = []
        if LOG_FILE.exists():
            entries = [json.loads(line) for line in LOG_FILE.read_text().strip().split('\n') if line]

        last_24h_views = sum(1 for e in entries if e['action'] == 'view' and e['timestamp'] > day_ago_ms)
        today_downloads = sum(1 for e in entries if e['action'] == 'download' and e['timestamp'] > day_ago_ms)
        last_visitor = max((e['timestamp'] for e in entries), default=None)

        stats.update({
            'last_24h_visits': last_24h_views,
            'today_downloads': today_downloads,
            'last_visitor_at': last_visitor,
        })

        # 最新100件（逆順）
        recent = sorted(entries, key=lambda e: e['timestamp'], reverse=True)[:100]

    return jsonify({
        'stats': stats,
        'entries': recent,
    })

@app.route('/<path:filename>', methods=['GET'])
def serve_file(filename):
    """
    静的ファイル配信（APK/HTML/JS/CSS）。
    APKダウンロードは記録。
    """
    # APKダウンロード記録
    if filename.endswith('.apk'):
        log_access('download', file=filename)
        logger.info(f'APK download: {filename} from {get_client_ip()}')

    try:
        return send_from_directory('.', filename)
    except Exception as e:
        logger.warning(f'File not found: {filename} - {e}')
        return jsonify({'error': 'Not found'}), 404

@app.errorhandler(404)
def not_found(e):
    """404はログ記録なし"""
    return jsonify({'error': 'Not found'}), 404

if __name__ == '__main__':
    init_files()
    port = int(os.environ.get('PORT', 8888))
    logger.info(f'Starting Bluehood access log server on port {port}')
    app.run(host='0.0.0.0', port=port, debug=False)
