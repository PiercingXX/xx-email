package dev.xxemail.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        XxEmailDb::class.java,
    )

    private fun seedV1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `accounts` " +
                "(`email`, `displayName`, `historyId`, `lastSyncAt`) VALUES " +
                "('user@dev.xxemail', 'Dev User', 9223372036854775807, 1700000000000)",
        )
        db.execSQL(
            "INSERT INTO `outbox` " +
                "(`accountEmail`, `kind`, `threadId`, `rfc822Base64`, `subject`, " +
                "`targetAt`, `state`, `attempts`, `error`, `createdAt`) VALUES " +
                "('user@dev.xxemail', 'SEND', 'thread-queued', '$QUEUED_RFC822_B64', 'Re: quarterly report', " +
                "1700000036000, 'QUEUED', 0, NULL, 1700000018000)",
        )
        db.execSQL(
            "INSERT INTO `threads` " +
                "(`accountEmail`, `id`, `snippet`, `subject`, `fromAddress`, `fromName`, `date`, " +
                "`messageCount`, `unreadCount`, `hasAttachments`, `starred`, `inInbox`, " +
                "`categories`, `labelsCsv`, `snoozedUntil`) VALUES " +
                "('user@dev.xxemail', 't-snoozed', 'zephyrquartz draft notes', 'Snoozed thread', " +
                "'sender@example.com', 'Sender', 1699999999000, 2, 1, 1, 0, 0, '', 'INBOX', " +
                "$SNOOZED_UNTIL)",
        )
        db.execSQL(
            "INSERT INTO `messages` " +
                "(`accountEmail`, `id`, `threadId`, `subject`, `fromAddress`, `toCsv`, `ccCsv`, " +
                "`date`, `snippet`, `read`, `starred`, `hasAttachments`, `labelsCsv`, " +
                "`messageIdHeader`, `bodyHtml`, `bodyPlain`, `bodyFetched`) VALUES " +
                "('user@dev.xxemail', 'm-1', 't-snoozed', 'Snoozed thread', 'sender@example.com', " +
                "'user@dev.xxemail', '', 1699999999000, 'zephyrquartz draft notes', 0, 0, 1, 'INBOX', " +
                "'<m-1@example.com>', '<p>body</p>', 'body', 1)",
        )
    }

    private fun assertQueuedSendSurvived(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT `kind`, `threadId`, `rfc822Base64`, `subject`, `state`, `attempts`, " +
                "`path`, `size` FROM `outbox` WHERE `state` = 'QUEUED'",
        ).use { c ->
            assertTrue("queued-send row must survive migration", c.moveToFirst())
            assertEquals(1, c.count)
            assertEquals("SEND", c.getString(0))
            assertEquals("thread-queued", c.getString(1))
            assertEquals(QUEUED_RFC822_B64, c.getString(2))
            assertEquals("Re: quarterly report", c.getString(3))
            assertEquals("QUEUED", c.getString(4))
            assertEquals(0, c.getInt(5))
            assertTrue(c.isNull(6))
            assertEquals(0L, c.getLong(7))
        }
    }

    private fun assertSnoozedThreadSurvived(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT `snoozedUntil`, `subject`, `labelsCsv` FROM `threads` " +
                "WHERE `id` = 't-snoozed' AND `accountEmail` = 'user@dev.xxemail'",
        ).use { c ->
            assertTrue("snoozed thread must survive migration", c.moveToFirst())
            assertEquals(SNOOZED_UNTIL, c.getLong(0))
            assertFalse(c.isNull(0))
            assertEquals("Snoozed thread", c.getString(1))
            assertEquals("INBOX", c.getString(2))
        }
    }

    private fun assertHistoryIdBecameText(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT typeof(`historyId`), `historyId` FROM `accounts` " +
                "WHERE `email` = 'user@dev.xxemail'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("text", c.getString(0))
            assertEquals("9223372036854775807", c.getString(1))
        }
    }

    private fun assertMessageBodySurvived(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT `bodyHtml`, `attachmentsJson`, `messageIdHeader` FROM `messages` " +
                "WHERE `id` = 'm-1' AND `accountEmail` = 'user@dev.xxemail'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("<p>body</p>", c.getString(0))
            assertTrue(c.isNull(1))
            assertEquals("<m-1@example.com>", c.getString(2))
        }
    }

    private fun assertFtsIndexStillMatchesSeededMessage(db: SupportSQLiteDatabase) {
        db.query("SELECT docid FROM `messages_fts` WHERE `messages_fts` MATCH 'zephyrquartz'").use { c ->
            assertEquals(1, c.count)
        }
    }

    @Test
    fun migrate1To2_historyIdBecomesText_queuedSendAndSnoozeSurvive() {
        helper.createDatabase(TEST_DB, 1).use { seedV1(it) }

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, XxEmailDb.MIGRATION_1_2)

        assertHistoryIdBecameText(v2)
        assertQueuedSendSurvived(v2)
        assertSnoozedThreadSurvived(v2)

        v2.query(
            "SELECT COUNT(*) FROM `sqlite_master` WHERE type = 'table' AND name = 'snooze_wakes'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }

    @Test
    fun migrate1To3_folderPagesCreated_attachmentsColumnAdded_allRowsSurvive() {
        helper.createDatabase(TEST_DB, 1).use { seedV1(it) }

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, XxEmailDb.MIGRATION_1_2)
        v2.execSQL(
            "INSERT INTO `snooze_wakes` " +
                "(`accountEmail`, `threadId`, `targetAt`, `createdAt`) VALUES " +
                "('user@dev.xxemail', 't-snoozed', 1893456001000, 1700000020000)",
        )
        v2.execSQL(
            "INSERT INTO `outbox` " +
                "(`accountEmail`, `kind`, `path`, `size`, `subject`, `targetAt`, `state`, " +
                "`attempts`, `createdAt`) VALUES " +
                "('user@dev.xxemail', 'SCHEDULED_SEND', 'outbox/99.eml', 2048, 'v2 file-backed send', " +
                "1700003600000, 'QUEUED', 0, 1700000021000)",
        )
        v2.close()

        val v3 = helper.runMigrationsAndValidate(TEST_DB, 3, true, XxEmailDb.MIGRATION_2_3)

        v3.query(
            "SELECT COUNT(*) FROM `sqlite_master` WHERE type = 'table' AND name = 'folder_pages'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        v3.query("PRAGMA table_info(`messages`)").use { c ->
            val names = mutableSetOf<String>()
            while (c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            assertTrue(names.containsAll(listOf("attachmentsJson", "bodyHtml", "labelsCsv")))
        }

        v3.execSQL(
            "INSERT INTO `folder_pages` (`accountEmail`, `folderKey`, `nextPageToken`) VALUES " +
                "('user@dev.xxemail', 'INBOX', 'tok-42')",
        )
        v3.query(
            "SELECT `nextPageToken` FROM `folder_pages` " +
                "WHERE `accountEmail` = 'user@dev.xxemail' AND `folderKey` = 'INBOX'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("tok-42", c.getString(0))
        }

        assertHistoryIdBecameText(v3)
        assertQueuedSendSurvived(v3)
        assertSnoozedThreadSurvived(v3)
        assertMessageBodySurvived(v3)
        assertFtsIndexStillMatchesSeededMessage(v3)

        v3.query(
            "SELECT `targetAt` FROM `snooze_wakes` " +
                "WHERE `accountEmail` = 'user@dev.xxemail' AND `threadId` = 't-snoozed'",
        ).use { c ->
            assertTrue("v2 snooze_wakes row must survive to v3", c.moveToFirst())
            assertEquals(1893456001000L, c.getLong(0))
        }

        v3.query(
            "SELECT `path`, `size`, `rfc822Base64` FROM `outbox` " +
                "WHERE `kind` = 'SCHEDULED_SEND' AND `state` = 'QUEUED'",
        ).use { c ->
            assertTrue("v2 file-backed outbox row must survive to v3", c.moveToFirst())
            assertEquals("outbox/99.eml", c.getString(0))
            assertEquals(2048L, c.getLong(1))
            assertTrue(c.isNull(2))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test"
        private const val SNOOZED_UNTIL = 1893456000000L
        private const val QUEUED_RFC822_B64 = "RnJvbTogc2VuZGVyQGV4YW1wbGUuY29tDQpUbzogdXNlckBkZXYueHhlbWFpbA0KU3ViamVjdDogUXVhcnRlcmx5IHJlcG9ydA0KDQpRdWV1ZWQgcGF5bG9hZA0K"
    }
}
