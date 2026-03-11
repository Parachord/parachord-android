package com.parachord.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.PlaylistEntity

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ParachordDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        fun create(context: Context): ParachordDatabase =
            Room.databaseBuilder(
                context,
                ParachordDatabase::class.java,
                "parachord.db"
            ).build()
    }
}
