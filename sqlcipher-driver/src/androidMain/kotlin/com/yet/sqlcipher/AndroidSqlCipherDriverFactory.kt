package com.yet.sqlcipher

import android.content.Context
import android.database.sqlite.SQLiteException
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher-encrypted Android driver over `net.zetetic:sqlcipher-android`. The key bytes are
 * handed to SQLCipher as a raw passphrase; [SupportOpenHelperFactory] takes ownership of the
 * array and zeroes it after the open, so a fresh copy is fetched from [SqlCipherKey] on every
 * attempt.
 */
class AndroidSqlCipherDriverFactory(
    private val context: Context,
) : SqlCipherDriverFactory {

    override suspend fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        config: SqlCipherConfig,
    ): SqlDriver {
        System.loadLibrary("sqlcipher")
        return try {
            openProbed(schema, config)
        } catch (e: MissingSqlCipherException) {
            throw e
        } catch (e: SQLiteException) {
            if (!config.recovery.onOpenFailed(config.name, e)) throw e
            context.deleteDatabase(config.name)
            openProbed(schema, config)
        }
    }

    override fun deleteDatabase(name: String) {
        context.deleteDatabase(name)
    }

    /**
     * Build the driver and force the SQLCipher open NOW (value-returning PRAGMAs must go through
     * the rawQuery path, hence executeQuery) so a wrong-key / corrupt-file failure surfaces here
     * — where recovery can act — instead of lazily on the first query.
     */
    private suspend fun openProbed(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        config: SqlCipherConfig,
    ): SqlDriver {
        val factory = SupportOpenHelperFactory(config.key.passphrase())
        val driver = AndroidSqliteDriver(
            schema = schema,
            context = context,
            name = config.name,
            factory = factory,
        )
        try {
            driver.assertCipherPresent()
            // Forces the key check against the file contents now.
            driver.executeQuery(null, "PRAGMA user_version;", { QueryResult.Value(Unit) }, 0)
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }
}
