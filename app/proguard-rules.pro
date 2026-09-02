# R8 設定。
#
# 方針: 黙って壊れる箇所を残さない。リフレクションで名前を引く経路だけ明示的に残し、
# それ以外(Compose/Kotlin標準/未使用コード)は削らせる。
# 難読化は「壊れていないこと」を実機で確認できて初めて意味があるので、
# 迷ったら keep 側に倒す。

# ---- osmdroid ----
# DefaultConfigurationProvider は SharedPreferences のキーをフィールド名から
# リフレクションで組み立てる。難読化すると設定の読み書きが静かに壊れ、
# タイルキャッシュのパスが解決できずに地図が真っ白になる。
-keep class org.osmdroid.config.** { *; }
# タイルソースは名前で解決される経路がある(キャッシュDBのキーにも使われる)
-keep class org.osmdroid.tileprovider.tilesource.** { *; }
-keep class org.osmdroid.tileprovider.modules.** { *; }
# View を XML/AndroidView から参照するためコンストラクタを残す
-keep class org.osmdroid.views.** { <init>(...); }
-dontwarn org.osmdroid.**

# ---- Room ----
# 生成された実装は名前で解決される。エンティティはカラム束縛のため構造を保つ。
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.faker.bluehood.data.** { *; }
-dontwarn androidx.room.paging.**

# ---- 証拠JSON ----
# org.json はフィールド名を文字列で扱うので実害はないが、
# 証拠フォーマットは外部と照合する契約なので念のため出力側を保護する。
-keep class com.faker.bluehood.evidence.** { *; }

# ---- デバッグ性 ----
# クラッシュ報告を読めるように行番号は残す(ソースファイル名は伏せる)。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
