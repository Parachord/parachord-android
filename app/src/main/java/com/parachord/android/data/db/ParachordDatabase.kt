package com.parachord.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.SearchHistoryDao
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.SearchHistoryEntity

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ParachordDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        fun create(context: Context): ParachordDatabase =
            Room.databaseBuilder(
                context,
                ParachordDatabase::class.java,
                "parachord.db"
            ).fallbackToDestructiveMigration().build()
    }
}
