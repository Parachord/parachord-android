package com.parachord.android.app

import com.parachord.android.data.db.ParachordDatabase
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.ArtistDao
import com.parachord.android.data.db.dao.ChatMessageDao
import com.parachord.android.data.db.dao.FriendDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.SearchHistoryDao
import com.parachord.android.data.db.dao.SyncSourceDao
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

    @Provides
    fun providePlaylistTrackDao(database: ParachordDatabase): PlaylistTrackDao = database.playlistTrackDao()

    @Provides
    fun provideSearchHistoryDao(database: ParachordDatabase): SearchHistoryDao = database.searchHistoryDao()

    @Provides
    fun provideFriendDao(database: ParachordDatabase): FriendDao = database.friendDao()

    @Provides
    fun provideChatMessageDao(database: ParachordDatabase): ChatMessageDao = database.chatMessageDao()

    @Provides
    fun provideSyncSourceDao(database: ParachordDatabase): SyncSourceDao = database.syncSourceDao()

    @Provides
    fun provideArtistDao(database: ParachordDatabase): ArtistDao = database.artistDao()
}
