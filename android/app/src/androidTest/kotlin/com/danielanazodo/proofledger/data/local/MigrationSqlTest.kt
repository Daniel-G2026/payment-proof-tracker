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
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_6_7
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_7_8
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_8_9
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_9_10
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_10_11
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_11_12
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_12_13
import com.danielanazodo.proofledger.data.local.migrations.MIGRATION_13_14
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Data-preservation test for migrations 1 -> 10. Builds the v1 schema by hand
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
    fun migrations1Through10_preserveData() {
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

        // -> v7 (raw OCR text on proofs)
        MIGRATION_6_7.migrate(db)

        // -> v8 (server draft status columns on proofs)
        MIGRATION_7_8.migrate(db)

        // -> v9 (proof image hash for duplicate warnings)
        MIGRATION_8_9.migrate(db)

        // -> v10 (local sync/audit events)
        MIGRATION_9_10.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_sync_event(id, businessId, entityType, entityId, action, status, errorMessage, createdAt)
            VALUES('e1', 'b1', 'transaction', 't1', 'create', 'success', NULL, 1)
            """
        )

        assertRowCount("local_app_metadata", 1)
        assertRowCount("local_profile", 1)
        assertRowCount("local_business", 1)
        assertRowCount("local_transaction", 1)
        assertRowCount("local_counterparty", 1)
        assertRowCount("local_screenshot", 1)
        assertRowCount("local_sync_event", 1)

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
        db.query(
            """
            SELECT ocrText, serverDraftStatus, serverDraftFetchedAt, serverDraftError, imageSha256
            FROM local_screenshot
            WHERE id = 's1'
            """
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
            assertNull(c.getString(1))
            assertNull(c.getString(2))
            assertNull(c.getString(3))
            assertNull(c.getString(4))
        }
    }

    @Test
    fun migrations1Through14_addProjectsAndUnitsAndTransactionColumns() {
        // Seed a legacy transaction at v3 so we can assert the Phase 13 columns
        // land as NULL on a pre-existing row (additive, no destructive default).
        db.execSQL(
            "INSERT INTO local_app_metadata(key, value, updatedAt) VALUES('selected', 'b1', 100)"
        )
        MIGRATION_1_2.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_business(id, ownerId, name, country, currency, businessType, plan, createdAt, updatedAt, syncStatus, lastSyncAttemptAt)
            VALUES('b1', 'p1', 'Estate Co', 'NG', 'NGN', NULL, 'free', 1, 1, 'SYNCED', NULL)
            """
        )
        MIGRATION_2_3.migrate(db)
        db.execSQL(
            """
            INSERT INTO local_transaction(id, businessId, amountMinor, currency, direction, transactionDate, counterpartyName, category, note, bankName, referenceNumber, source, status, createdAt, updatedAt, deletedAt, syncStatus, lastSyncAttemptAt)
            VALUES('t1', 'b1', 5000, 'NGN', 'money_in', '2026-01-01', 'Alice', NULL, NULL, NULL, NULL, 'manual', 'confirmed', 1, 1, NULL, 'SYNCED', NULL)
            """
        )

        // Run the rest of the chain through the Phase 13 migration.
        MIGRATION_3_4.migrate(db)
        MIGRATION_4_5.migrate(db)
        MIGRATION_5_6.migrate(db)
        MIGRATION_6_7.migrate(db)
        MIGRATION_7_8.migrate(db)
        MIGRATION_8_9.migrate(db)
        MIGRATION_9_10.migrate(db)
        MIGRATION_10_11.migrate(db)
        MIGRATION_11_12.migrate(db)
        MIGRATION_12_13.migrate(db)
        MIGRATION_13_14.migrate(db)

        // New columns exist and are NULL for the legacy transaction.
        db.query("SELECT projectId, unitId FROM local_transaction WHERE id = 't1'").use { c ->
            assertTrue(c.moveToFirst())
            assertNull(c.getString(0))
            assertNull(c.getString(1))
        }

        // New tables are queryable and accept inserts (FK to a project for the unit).
        db.execSQL(
            """
            INSERT INTO local_project(id, businessId, name, note, createdAt, updatedAt, deletedAt, syncStatus, lastSyncAttemptAt)
            VALUES('pr1', 'b1', 'Sunrise Estate', NULL, 1, 1, NULL, 'SYNCED', NULL)
            """
        )
        db.execSQL(
            """
            INSERT INTO local_unit(id, businessId, projectId, label, buyerCounterpartyId, totalPriceMinor, currency, status, createdAt, updatedAt, deletedAt, syncStatus, lastSyncAttemptAt)
            VALUES('u1', 'b1', 'pr1', 'Plot 12', NULL, 200000000, 'NGN', 'available', 1, 1, NULL, 'SYNCED', NULL)
            """
        )
        assertRowCount("local_project", 1)
        assertRowCount("local_unit", 1)

        // The Room-convention indexes the migration created are present.
        assertIndexExists("index_local_project_businessId")
        assertIndexExists("index_local_unit_projectId")
        assertIndexExists("index_local_unit_buyerCounterpartyId")
    }

    private fun assertIndexExists(name: String) {
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(name)).use { c ->
            assertTrue("Expected index $name", c.moveToFirst())
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
