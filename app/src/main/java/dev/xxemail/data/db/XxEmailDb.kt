package dev.xxemail.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        LabelEntity::class,
        ThreadEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        OutboxEntity::class,
        SnoozeWakeEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class XxEmailDb : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun labelDao(): LabelDao
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun snoozeWakeDao(): SnoozeWakeDao

    companion object {

        /**
         * v1 → v2 (never destructive — queued sends and snoozes must survive):
         *  - accounts.historyId INTEGER → TEXT: SQLite cannot ALTER a column type, so use the
         *    create-new/copy/drop/rename pattern with CAST. Values are uint64 strings; CAST keeps
         *    every stored id readable as text.
         *  - outbox gains file-backed payload columns (`path` nullable, `size` NOT NULL DEFAULT 0);
         *    legacy `rfc822Base64` stays so pre-upgrade QUEUED sends remain sendable.
         *  - new `snooze_wakes` table for durable snooze state.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `accounts_new` (" +
                        "`email` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                        "`historyId` TEXT, `lastSyncAt` INTEGER, PRIMARY KEY(`email`))",
                )
                db.execSQL(
                    "INSERT INTO `accounts_new` (`email`, `displayName`, `historyId`, `lastSyncAt`) " +
                        "SELECT `email`, `displayName`, CAST(`historyId` AS TEXT), `lastSyncAt` FROM `accounts`",
                )
                db.execSQL("DROP TABLE `accounts`")
                db.execSQL("ALTER TABLE `accounts_new` RENAME TO `accounts`")

                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `path` TEXT")
                db.execSQL("ALTER TABLE `outbox` ADD COLUMN `size` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `snooze_wakes` (" +
                        "`accountEmail` TEXT NOT NULL, `threadId` TEXT NOT NULL, " +
                        "`targetAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountEmail`, `threadId`))",
                )
            }
        }

        /**
         * v0.1 ships plaintext storage, relying on Android file-based encryption + app sandbox.
         * Injection point kept here so SQLCipher (`sqlcipher-android`) can be added as an
         * opt-in hardening layer without touching call sites.
         */
        fun build(context: Context): XxEmailDb =
            Room.databaseBuilder(context, XxEmailDb::class.java, "xxemail.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
