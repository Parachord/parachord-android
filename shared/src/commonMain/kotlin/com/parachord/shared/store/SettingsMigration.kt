package com.parachord.shared.store

/**
 * One-shot migration hook invoked the first time [com.parachord.shared.settings.SettingsStore]
 * accesses [KvStore]. Lets each platform port pre-existing preferences
 * into the new KvStore-backed file before any reads happen.
 *
 * - **Android**: `AndroidDataStoreMigration` reads every key from the
 *   legacy Jetpack DataStore (`parachord_settings`) and re-writes it
 *   through `KvStore` (`parachord_kmp_prefs`). After the migration
 *   marker (`_migration_v1`) is set, subsequent launches skip this
 *   class entirely.
 * - **iOS**: [NoOp] — iOS never had a DataStore so there's nothing to
 *   pre-load. The first launch starts with an empty KvStore.
 *
 * The migration runs once per install. Idempotent re-invocations are
 * cheap (the marker check returns immediately), so tests can re-create
 * a `SettingsStore` without orchestrating teardown.
 */
interface SettingsMigration {

    /**
     * Copy any pre-existing settings into [kv]. Implementations must be
     * idempotent — `SettingsStore` only invokes this when the marker
     * key is absent, but a re-run on a partially-populated store should
     * still complete cleanly.
     */
    suspend fun migrate(kv: KvStore)

    /**
     * Default no-op migration. Used on iOS where there's no legacy
     * preference store to import from.
     */
    object NoOp : SettingsMigration {
        override suspend fun migrate(kv: KvStore) {}
    }
}
