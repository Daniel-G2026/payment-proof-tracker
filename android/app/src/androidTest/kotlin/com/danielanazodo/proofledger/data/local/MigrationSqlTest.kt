package com.danielanazodo.proofledger.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_1_2
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_2_3
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_3_4
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_4_5
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_5_6
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data-preservation test for migrations 1 -> 6. Builds the v1 schema by hand
 * (the same CREATE TABLE Room would have emitted at version 1), inserts sample
 * rows at every version, runs each migration in order, and asserts that:
 *
 *   - no row inserted at an earlier version disappears,
 *   - column additions are NULL for legacy rows (no destructive defaults),
 *   - new tables are queryable after their creating migration.
 *
 * This is intentionally independent of the exported Room JSON schemas — it
 * stays green even before schemas/v*.json exist. Once exportSchema produces
 * those files, add a MigrationTestHelper-based test alongside this one for
 * full Room schema validation on every future bump.
 */
@RunWith(AndroidJUnit4::class)
class MigrationSqlTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V1_CREATE_APP_METADATA)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migrations1Through6_preserveData() {
        // v1: only local_app_metadata exists.
        db.execSQL(
            "INSERT INTO local_app_metadata(key, value, updatedAt) VALUES('selected', 'b1', 100)"
        )

        // -> v2 (profile + business)
        MIGRATION_1_2.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_profile(id, fullName, phone, email, createdAt, updatedAt, syncStatus, lastSyncAttemptAt)
            VALUES('p1', 'Owner', '+234', 'o@x', 1, 1, 'SYNCED', NULL)
            """
        )
        db.execSQL(
            """
            INSERT INTO local_business(id, ownerId, name, country, currency, businessType, plan, createdAt, updatedAt, syncStatus, lastSyncAttemptAt)
            VALUES('b1', 'p1', 'Shop', 'NG', 'NGN', NULL, 'free', 1, 1, 'SYNCED', NULL)
            """
        )

        // -> v3 (transactions)
        MIGRATION_2_3.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_transaction(id, businessId, amountMinor, currency, direction, transactionDate, counterpartyName, category, note, bankName, referenceNumber, source, status, createdAt, updatedAt, deletedAt, syncStatus, lastSyncAttemptAt)
            VALUES('t1', 'b1', 5000, 'NGN', 'money_in', '2026-01-01', 'Alice', NULL, NULL, NULL, NULL, 'manual', 'confirmed', 1, 1, NULL, 'SYNCED', NULL)
            """
        )

        // -> v4 (counterparties + counterpartyId column on transactions)
        MIGRATION_3_4.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_counterparty(id, businessId, name, normalizedName, type, phone, notes, createdAt, updatedAt, deletedAt, syncStatus, lastSyncAttemptAt)
            VALUES('cp1', 'b1', 'Alice', 'alice', 'customer', NULL, NULL, 1, 1, NULL, 'SYNCED', NULL)
            """
        )

        // -> v5 (screenshots)
        MIGRATION_4_5.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_screenshot(id, businessId, transactionId, localOriginalPath, localUploadPath, originalFileSizeBytes, uploadFileSizeBytes, mimeType, width, height, transactionAmountMinor, transactionDate, transactionDirection, counterpartyId, counterpartyName, isImportant, backupStatus, remoteScreenshotId, remoteStorageKey, createdAt, updatedAt, lastBackupAttemptAt, lastBackupError)
            VALUES('s1', 'b1', 't1', '/tmp/a.jpg', NULL, 1024, NULL, 'image/jpeg', 100, 100, 5000, '2026-01-01', 'money_in', NULL, NULL, 0, 'SAVED_ON_PHONE_ONLY', NULL, NULL, 1, 1, NULL, NULL)
            """
        )

        // -> v6 (lastSyncError on transaction/business/counterparty)
        MIGRATION_5_6.migrate(db)

        assertRowCount("local_app_metadata", 1)
        assertRowCount("local_profile", 1)
        assertRowCount("local_business", 1)
        assertRowCount("local_transaction", 1)
        assertRowCount("local_counterparty", 1)
        assertRowCount("local_screenshot", 1)

        // New v6 column exists and is NULL for legacy rows.
        db.query("SELECT lastSyncError FROM local_transaction WHERE id = 't1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }
        db.query("SELECT lastSyncError FROM local_business WHERE id = 'b1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }
        db.query("SELECT lastSyncError FROM local_counterparty WHERE id = 'cp1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
        }
    }

    private fun assertRowCount(table: String, expected: Int) {
        db.query("SELECT COUNT(*) FROM $table").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Row count for $table", expected, c.getInt(0))
        }
    }

    companion object {
        private const val DB_NAME = "migration-sql-test.db"

        // The v1 schema is just local_app_metadata (created in PaymentProofApp's
        // first DB initialization). Everything else arrives via migrations.
        private const val V1_CREATE_APP_METADATA = """
            CREATE TABLE IF NOT EXISTS local_app_metadata (
                key TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """
    }
}
