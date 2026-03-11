package com.parachord.android.app

import com.parachord.android.data.db.ParachordDatabase
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    fun provideTrackDao(database: ParachordDatabase): TrackDao = database.trackDao()

    @Provides
    fun provideAlbumDao(database: ParachordDatabase): AlbumDao = database.albumDao()

    @Provides
    fun providePlaylistDao(database: ParachordDatabase): PlaylistDao = database.playlistDao()
}
