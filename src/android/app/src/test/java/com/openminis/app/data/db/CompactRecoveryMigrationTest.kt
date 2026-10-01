package com.openminis.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Execute the production migration on SQLite and compare it to Room's exported schema. */
class CompactRecoveryMigrationTest {
    private fun database(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                val entities = JSONObject(File("schemas/com.openminis.app.data.db.AppDatabase/14.json").readText())
                    .getJSONObject("database").getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    statement.execute(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                }
                statement.execute("INSERT INTO sessions (id, model_id, created_at, updated_at, memory_enabled, edit_count) VALUES ('session', 'model', 1, 1, 1, 0)")
                statement.execute("INSERT INTO compact_markers (id, session_id, summary, first_kept_sort_order, compacted_count, created_at, version) VALUES ('marker', 'session', 'original summary', 1, 2, 1, 2)")
            }
        }
    }

    private fun bridge(connection: Connection): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execSQL" -> connection.createStatement().use { it.execute(args!![0] as String); Unit }
            else -> error("Unexpected migration call: ${method.name}")
        }
    } as SupportSQLiteDatabase

    @Test fun migrationKeepsOriginalSummaryAndMatchesExportedColumns() {
        database().use { connection ->
            AppDatabase.MIGRATION_14_15.migrate(bridge(connection))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT summary FROM compact_markers WHERE id='marker'").use {
                    assertTrue(it.next()); assertEquals("original summary", it.getString(1))
                }
                val columns = linkedMapOf<String, String>()
                statement.executeQuery("PRAGMA table_info(compact_recovery)").use {
                    while (it.next()) columns[it.getString("name")] = it.getString("type")
                }
                assertEquals(mapOf("id" to "TEXT", "session_id" to "TEXT", "marker_json" to "TEXT", "sequence" to "INTEGER"), columns)
                statement.executeQuery("PRAGMA index_list(compact_recovery)").use {
                    var found = false
                    while (it.next()) if (it.getString("name") == "index_compact_recovery_session_id") found = true
                    assertTrue(found)
                }
            }
        }
    }

    @Test fun recoveryCascadesOnlyWhenItsSessionIsDeleted() {
        database().use { connection ->
            AppDatabase.MIGRATION_14_15.migrate(bridge(connection))
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO compact_recovery VALUES ('marker', 'session', '{}', 1)")
                statement.execute("DELETE FROM compact_markers WHERE id='marker'")
                statement.executeQuery("SELECT COUNT(*) FROM compact_recovery").use {
                    assertTrue(it.next()); assertEquals(1, it.getInt(1))
                }
                statement.execute("DELETE FROM sessions WHERE id='session'")
                statement.executeQuery("SELECT COUNT(*) FROM compact_recovery").use {
                    assertTrue(it.next()); assertEquals(0, it.getInt(1))
                }
            }
        }
    }

    @Test fun downgradePreservesRecoveryAndOriginalHistory() {
        database().use { connection ->
            val bridge = bridge(connection)
            AppDatabase.MIGRATION_14_15.migrate(bridge)
            connection.createStatement().use { it.execute("INSERT INTO compact_recovery VALUES ('marker', 'session', '{}', 1)") }
            AppDatabase.MIGRATION_15_14.migrate(bridge)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM compact_recovery").use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) }
                statement.executeQuery("SELECT COUNT(*) FROM compact_markers").use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) }
            }
        }
    }
}
