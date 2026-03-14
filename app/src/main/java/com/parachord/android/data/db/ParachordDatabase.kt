package com.parachord.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.ChatMessageDao
import com.parachord.android.data.db.dao.FriendDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.SearchHistoryDao
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ChatMessageEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.SearchHistoryEntity
import com.parachord.android.data.db.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        SearchHistoryEntity::class,
        FriendEntity::class,
        ChatMessageEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ParachordDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun friendDao(): FriendDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        fun create(context: Context): ParachordDatabase =
            Room.databaseBuilder(
                context,
                ParachordDatabase::class.java,
                "parachord.db"
            ).fallbackToDestructiveMigration().build()
    }
}
