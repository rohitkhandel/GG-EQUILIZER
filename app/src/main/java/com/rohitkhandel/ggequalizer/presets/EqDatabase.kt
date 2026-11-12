package com.rohitkhandel.ggequalizer.presets

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [PresetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class EqDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao

    companion object {
        private const val DATABASE_NAME = "gg_equalizer_presets.db"

        @Volatile
        private var instance: EqDatabase? = null

        private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): EqDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): EqDatabase {
            return Room.databaseBuilder(
                context,
                EqDatabase::class.java,
                DATABASE_NAME
            )
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(database: SupportSQLiteDatabase) {
                        super.onCreate(database)
                        seedScope.launch {
                            runCatching {
                                val dao = getInstance(context).presetDao()
                                if (dao.countAll() == 0) {
                                    dao.upsertAll(BuiltInPresetFactory.createAll())
                                }
                            }
                        }
                    }
                })
                .build()
        }
    }
}
