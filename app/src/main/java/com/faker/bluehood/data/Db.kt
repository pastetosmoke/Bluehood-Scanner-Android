package com.faker.bluehood.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 「デバイス(安定キー)」と「観測イベント」を分離する。
 * 証拠化の設計思想: 保存するのは常に "自分の座標 + そこで観測した指紋"。
 * 相手の推定座標は保存しない(相手中心のデータを作らない = コード上の防御線)。
 */
@Entity(tableName = "clusters", indices = [Index(value = ["stableKey"], unique = true)])
data class DeviceCluster(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stableKey: String,
    val label: String? = null,        // ユーザーが付ける名前("自分のイヤホン"等)
    val firstSeen: Long,
    val lastSeen: Long,
    val stalkerScore: Double = 0.0,
    val untrackable: Boolean = false, // 匿名化が効いていて名寄せ不能
    val trackerType: String? = null,  // AirTag/Tile/SmartTag等のトラッカー種別(匿名化対抗)
    val name: String? = null,         // 広告されたローカル名(あれば直接)
    val vendor: String? = null,       // 会社IDから推定したベンダー
    val deviceType: String? = null,   // サービスUUIDから推定した種別
)

@Entity(
    tableName = "observations",
    foreignKeys = [ForeignKey(
        entity = DeviceCluster::class,
        parentColumns = ["id"], childColumns = ["clusterId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("clusterId")]
)
data class Observation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clusterId: Long,
    val timestamp: Long,
    val rssi: Int,
    val mac: String?,                 // 生MAC(randomizeされるため補助情報。固定MAC機器でのみ有用)
    // 自分の座標。相手の座標ではない。位置未取得(屋内でGPS未fix等)なら null。
    val myLat: Double?,
    val myLon: Double?,
    val myAccuracyM: Float?,
    val rawPayloadHex: String,        // 生ペイロード(証拠の再検証用)
)

@Dao
interface BluehoodDao {
    @Query("SELECT * FROM clusters ORDER BY lastSeen DESC")
    fun clusters(): Flow<List<DeviceCluster>>

    @Query("SELECT * FROM clusters WHERE stalkerScore >= :threshold ORDER BY stalkerScore DESC")
    fun stalkerCandidates(threshold: Double): Flow<List<DeviceCluster>>

    // 匿名化トラッカー(種別判定できたもの)。滞留時間・スコアで追尾判定
    @Query("SELECT * FROM clusters WHERE trackerType IS NOT NULL ORDER BY stalkerScore DESC, lastSeen DESC")
    fun trackers(): Flow<List<DeviceCluster>>

    @Query("SELECT * FROM clusters WHERE stableKey = :key LIMIT 1")
    suspend fun findByKey(key: String): DeviceCluster?

    @Query("SELECT * FROM clusters WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DeviceCluster?

    @Query("SELECT * FROM observations WHERE clusterId = :clusterId ORDER BY timestamp")
    suspend fun observationsFor(clusterId: Long): List<Observation>

    // 地図用: 位置が取れた観測だけ(自分中心のウォードライブ点群)
    @Query("SELECT * FROM observations WHERE myLat IS NOT NULL AND myLon IS NOT NULL ORDER BY timestamp DESC LIMIT 2000")
    fun locatedObservations(): Flow<List<Observation>>

    // トラッカー種別ごとの全観測(複数ローテーション窓を横断して追尾判定するため)
    @Query("SELECT o.* FROM observations o INNER JOIN clusters c ON o.clusterId = c.id WHERE c.trackerType = :type ORDER BY o.timestamp")
    suspend fun trackerObservations(type: String): List<Observation>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCluster(c: DeviceCluster): Long

    @Update suspend fun updateCluster(c: DeviceCluster)

    /**
     * 追尾判定は「種別」単位で行う(ローテーションで個体キーが変わるため)。
     * にもかかわらず観測中の1クラスタにしか書かないと、
     * ローテーションで二度と現れない古いクラスタに誤ったスコアが永久に残る。
     * 判定単位と書き込み単位を一致させる。
     */
    @Query("UPDATE clusters SET stalkerScore = :score WHERE trackerType = :type")
    suspend fun updateTrackerScores(type: String, score: Double)
    @Insert suspend fun insertObservation(o: Observation)
}

@Database(entities = [DeviceCluster::class, Observation::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BluehoodDao

    companion object {
        // ScanService と ViewModel で同一インスタンスを共有しないと、
        // 片方の書き込みがもう片方の Flow に伝播しない(InvalidationTrackerはインスタンス単位)。
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "bluehood"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
