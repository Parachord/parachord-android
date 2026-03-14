package com.parachord.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.parachord.android.data.db.dao.AlbumDao
import com.parachord.android.data.db.dao.ChatMessageDao
import com.parachord.android.data.db.dao.FriendDao
import com.parachord.android.data.db.dao.PlaylistDao
import com.parachord.android.data.db.dao.PlaylistTrackDao
import com.parachord.android.data.db.dao.SearchHistoryDao
import com.parachord.android.data.db.dao.ArtistDao
import com.parachord.android.data.db.dao.SyncSourceDao
import com.parachord.android.data.db.dao.TrackDao
import com.parachord.android.data.db.entity.AlbumEntity
import com.parachord.android.data.db.entity.ArtistEntity
import com.parachord.android.data.db.entity.ChatMessageEntity
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.data.db.entity.PlaylistEntity
import com.parachord.android.data.db.entity.PlaylistTrackEntity
import com.parachord.android.data.db.entity.SearchHistoryEntity
import com.parachord.android.data.db.entity.SyncSourceEntity
import com.parachord.android.data.db.entity.TrackEntity

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        SearchHistoryEntity::class,
        FriendEntity::class,
        ChatMessageEntity::class,
        SyncSourceEntity::class,
        ArtistEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class ParachordDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistTrackDao(): PlaylistTrackDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun friendDao(): FriendDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun syncSourceDao(): SyncSourceDao
    abstract fun artistDao(): ArtistDao

    companion object {
        /**
         * Migration from v4 → v5: add friends and chat_messages tables.
         * Previously used fallbackToDestructiveMigration() which wiped all data
         * on version bumps — explicit migrations preserve existing data.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `friends` (
                        `id` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `service` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `avatarUrl` TEXT,
                        `addedAt` INTEGER NOT NULL,
                        `lastFetchedAt` INTEGER NOT NULL DEFAULT 0,
                        `cachedTrackName` TEXT,
                        `cachedTrackArtist` TEXT,
                        `cachedTrackAlbum` TEXT,
                        `cachedTrackTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `cachedTrackArtworkUrl` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `toolCallsJson` TEXT,
                        `toolCallId` TEXT,
                        `toolName` TEXT,
                        `timestamp` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration from v5 → v6: add playlist_tracks junction table.
         * Playlists now store their tracks in a separate table instead of
         * dumping them into the Collection tracks table.
         */
        /**
         * Migration from v5 → v6: add playlist_tracks junction table and clean
         * up tracks that were incorrectly added to Collection by old
         * create_playlist tool (which stored playlist tracks in the main tracks
         * table instead of a separate junction table).
         *
         * Cleanup heuristic: remove tracks with no albumId and no sourceType
         * that are NOT part of any album — these are metadata stubs from the AI
         * playlist tool. Real user-imported tracks always have albumId set.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_sources` (
                        `itemId` TEXT NOT NULL,
                        `itemType` TEXT NOT NULL,
                        `providerId` TEXT NOT NULL,
                        `externalId` TEXT,
                        `addedAt` INTEGER NOT NULL DEFAULT 0,
                        `syncedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`itemId`, `itemType`, `providerId`)
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `imageUrl` TEXT,
                        `spotifyId` TEXT,
                        `genres` TEXT,
                        `addedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())

                db.execSQL("ALTER TABLE `tracks` ADD COLUMN `spotifyId` TEXT")
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `spotifyId` TEXT")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `spotifyId` TEXT")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `snapshotId` TEXT")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `lastModified` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `locallyModified` INTEGER NOT NULL DEFAULT 0")

                // Backfill: create 'manual' sync sources for existing collection items
                db.execSQL("""
                    INSERT OR IGNORE INTO sync_sources (itemId, itemType, providerId, addedAt, syncedAt)
                    SELECT id, 'track', 'manual', addedAt, addedAt FROM tracks
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO sync_sources (itemId, itemType, providerId, addedAt, syncedAt)
                    SELECT id, 'album', 'manual', addedAt, addedAt FROM albums
                """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Clean up orphaned playlist tracks from the old broken behavior
                db.execSQL(
                    "DELETE FROM tracks WHERE albumId IS NULL AND sourceType IS NULL"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_tracks` (
                        `playlistId` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `trackTitle` TEXT NOT NULL,
                        `trackArtist` TEXT NOT NULL,
                        `trackAlbum` TEXT,
                        `trackDuration` INTEGER,
                        `trackArtworkUrl` TEXT,
                        `trackSourceUrl` TEXT,
                        `trackResolver` TEXT,
                        `trackSpotifyUri` TEXT,
                        `trackSoundcloudId` TEXT,
                        `addedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`playlistId`, `position`),
                        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_tracks_playlistId` ON `playlist_tracks` (`playlistId`)"
                )
            }
        }

        /**
         * Migration from v7 → v8: add ownerName column to playlists table
         * for displaying the playlist author in the UI.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `ownerName` TEXT")
            }
        }

        /**
         * Migration from v8 → v9: add pinnedToSidebar and autoPinned columns
         * to friends table for sidebar pinning and auto-pin behavior.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `friends` ADD COLUMN `pinnedToSidebar` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `friends` ADD COLUMN `autoPinned` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): ParachordDatabase =
            Room.databaseBuilder(
                context,
                ParachordDatabase::class.java,
                "parachord.db"
            )
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                // Only fall back to destructive for very old versions (pre-v4)
                // that we can't reasonably migrate from
                .fallbackToDestructiveMigrationFrom(1, 2, 3)
                .build()
    }
}
