package dev.xxemail.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        LabelEntity::class,
        ThreadEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class XxEmailDb : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun labelDao(): LabelDao
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        /**
         * v0.1 ships plaintext storage, relying on Android file-based encryption + app sandbox.
         * Injection point kept here so SQLCipher (`sqlcipher-android`) can be added as an
         * opt-in hardening layer without touching call sites.
         */
        fun build(context: Context): XxEmailDb =
            Room.databaseBuilder(context, XxEmailDb::class.java, "xxemail.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
