package org.radarbase.android.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.radarbase.android.storage.converter.Converters
import org.radarbase.android.storage.dao.NetworkStatusDao
import org.radarbase.android.storage.dao.SourceStatusDao
import org.radarbase.android.storage.entity.NetworkStatusLog
import org.radarbase.android.storage.entity.SourceStatusLog

/**
 * Database for the Radar Application.
 *
 * This database manages two entities:
 * - [SourceStatusLog]: Logs related to source status.
 * - [NetworkStatusLog]: Logs related to network connection status.
 *
 * Access to these entities is provided via the [sourceStatusDao] and [networkStatusDao] methods.
 *
 * ## Migrations
 *
 * The database currently uses destructive migration ([RoomDatabase::fallbackToDestructiveMigration]).
 * For version 1 this is acceptable, but in the future proper migration paths should be defined
 * to preserve user data. Automated migrations are supported when [Database.exportSchema] is set to true.
 *
 * @see <a href="https://developer.android.com/training/data-storage/room/migrating-db-versions">Migrating Room Databases</a>
 */
@Database(
    version = 1,
    entities = [SourceStatusLog::class, NetworkStatusLog::class],
    exportSchema = true
)
@TypeConverters(Converters::class)
@Suppress("unused")
abstract class RadarApplicationDatabase : RoomDatabase() {

    /**
     * Provides access to the DAO for source status log operations.
     *
     * @return an instance of [SourceStatusDao].
     */
    abstract fun sourceStatusDao(): SourceStatusDao

    /**
     * Provides access to the DAO for network status log operations.
     *
     * @return an instance of [NetworkStatusDao].
     */
    abstract fun networkStatusDao(): NetworkStatusDao

    companion object {
        @Volatile
        private var INSTANCE: RadarApplicationDatabase? = null

        /**
         * Retrieves the singleton instance of [RadarApplicationDatabase].
         *
         * The database is built using a destructive migration strategy ([RoomDatabase::fallbackToDestructiveMigration]).
         * In the future, migration paths can be added with [RoomDatabase::addMigrations] to preserve user data.
         *
         * @param context the application context.
         * @return the singleton instance of [RadarApplicationDatabase].
         */
        fun getInstance(context: Context): RadarApplicationDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RadarApplicationDatabase::class.java,
                    "radar_app_db"
                )
                    // Using fallbackToDestructiveMigration() for now since this is version 1.
                    // In the future, add proper migration paths using .addMigrations(MIGRATION_1_2, ...)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
