package com.scrolller.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedPostEntity::class,
        RecentPostEntity::class,
        SavedPostEntity::class,
        CachedSubredditEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ScrolllerDatabase : RoomDatabase() {
    abstract fun scrolllerDao(): ScrolllerDao

    companion object {
        @Volatile
        private var INSTANCE: ScrolllerDatabase? = null

        fun getInstance(context: Context): ScrolllerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ScrolllerDatabase::class.java,
                    "scrolller_offline_cache.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
