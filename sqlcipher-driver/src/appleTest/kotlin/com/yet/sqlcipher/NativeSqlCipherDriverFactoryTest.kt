package com.yet.sqlcipher

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private object TestSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, "CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT NOT NULL);", 0)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

/**
 * On-simulator E2E proof that the bundled static SQLCipher is the SQLite actually linked:
 * `PRAGMA cipher_version` must be non-empty (asserted inside every [SqlCipherDriverFactory.create])
 * and a wrong key must fail the open — plain SQLite would ignore `PRAGMA key` and open anything.
 */
class NativeSqlCipherDriverFactoryTest {

    private val factory = NativeSqlCipherDriverFactory()

    private fun config(
        name: String,
        keyByte: Byte,
        recovery: SqlCipherRecovery = SqlCipherRecovery.Fail,
    ) = SqlCipherConfig(
        name = name,
        key = { ByteArray(32) { keyByte } },
        recovery = recovery,
    )

    private fun freshName() = "sqlcipher-driver-test-${Random.nextLong().toString(16)}.db"

    @Test
    fun opensWritesAndReopensWithSameKey() = runBlocking {
        val name = freshName()
        try {
            factory.create(TestSchema, config(name, keyByte = 1)).use { driver ->
                driver.execute(null, "INSERT INTO kv(k, v) VALUES ('a', 'b');", 0)
            }
            factory.create(TestSchema, config(name, keyByte = 1)).use { driver ->
                val v = driver.executeQuery(
                    identifier = null,
                    sql = "SELECT v FROM kv WHERE k = 'a';",
                    mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                    parameters = 0,
                ).value
                assertEquals("b", v)
            }
        } finally {
            factory.deleteDatabase(name)
        }
    }

    @Test
    fun wrongKeyFailsWithoutRecovery() = runBlocking {
        val name = freshName()
        try {
            factory.create(TestSchema, config(name, keyByte = 1)).close()
            assertFailsWith<Exception> {
                factory.create(TestSchema, config(name, keyByte = 2)).close()
            }
            Unit
        } finally {
            factory.deleteDatabase(name)
        }
    }

    @Test
    fun wrongKeyRecreatesUnderRecreatePolicy() = runBlocking {
        val name = freshName()
        try {
            factory.create(TestSchema, config(name, keyByte = 1)).use { driver ->
                driver.execute(null, "INSERT INTO kv(k, v) VALUES ('a', 'b');", 0)
            }
            factory.create(TestSchema, config(name, keyByte = 2, recovery = SqlCipherRecovery.Recreate))
                .use { driver ->
                    val rows = driver.executeQuery(
                        identifier = null,
                        sql = "SELECT count(*) FROM kv;",
                        mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
                        parameters = 0,
                    ).value
                    assertEquals(0L, rows)
                }
        } finally {
            factory.deleteDatabase(name)
        }
    }

    @Test
    fun cipherVersionIsPresent() = runBlocking {
        val name = freshName()
        try {
            factory.create(TestSchema, config(name, keyByte = 1)).use { driver ->
                val cipherVersion = driver.executeQuery(
                    identifier = null,
                    sql = "PRAGMA cipher_version;",
                    mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                    parameters = 0,
                ).value
                assertTrue(!cipherVersion.isNullOrEmpty(), "cipher_version must be non-empty")
            }
        } finally {
            factory.deleteDatabase(name)
        }
    }

    private inline fun SqlDriver.use(block: (SqlDriver) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
