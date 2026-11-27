package com.carlese.simpleproxy.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

class ProxyConverters {
    @TypeConverter
    fun fromProtocol(protocol: ProxyProtocol): String = protocol.name

    @TypeConverter
    fun toProtocol(raw: String): ProxyProtocol =
        runCatching { ProxyProtocol.valueOf(raw) }.getOrDefault(ProxyProtocol.SOCKS5)
}

@Dao
interface ProxyDao {
    @Query("SELECT * FROM proxy_profiles ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProxyProfile>>

    @Query("SELECT * FROM proxy_profiles ORDER BY updatedAt DESC")
    suspend fun getAllOnce(): List<ProxyProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProxyProfile)

    @Query("DELETE FROM proxy_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [ProxyProfile::class], version = 1, exportSchema = false)
@TypeConverters(ProxyConverters::class)
abstract class ProxyDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao

    companion object {
        @Volatile
        private var INSTANCE: ProxyDatabase? = null

        fun getInstance(context: Context): ProxyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProxyDatabase::class.java,
                    "simpleproxy.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
