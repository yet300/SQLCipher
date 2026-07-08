package com.yet.sqlcipher

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext

/**
 * SQLCipher-encrypted iOS driver. The stock SQLiter stack is kept — the static SQLCipher archive
 * bundled in this library's cinterop klib is linked in place of the system SQLite, so SQLiter's
 * `DatabaseConfiguration.Encryption` applies the key as `PRAGMA key` on every connection open.
 *
 * SQLiter takes the key as a SQL string, so the raw key bytes are hex-armored here; SQLCipher
 * then treats that string as a passphrase (PBKDF2). Android keys the DB with the raw bytes
 * instead — the two file formats intentionally diverge (per-device databases).
 */
class NativeSqlCipherDriverFactory : SqlCipherDriverFactory {

    override suspend fun create(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        config: SqlCipherConfig,
    ): SqlDriver {
        val key = config.key.passphrase().hexEncoded()
        return try {
            openProbed(schema, config, key)
        } catch (e: MissingSqlCipherException) {
            // Build/link defect, not a data problem — never wipe the user's DB for it.
            throw e
        } catch (e: Exception) {
            if (!config.recovery.onOpenFailed(config.name, e)) throw e
            deleteDatabase(config.name)
            openProbed(schema, config, key)
        }
    }

    override fun deleteDatabase(name: String) {
        // SQLiter owns the default database directory resolution (Application Support), so
        // deletion must go through it too — a hand-built path would silently miss the file.
        DatabaseFileContext.deleteDatabase(name, basePath = null)
    }

    /**
     * Build the driver and force the SQLCipher open NOW so a wrong-key / corrupt-file failure
     * surfaces here — where recovery can act — instead of lazily on the first query.
     */
    private fun openProbed(
        schema: SqlSchema<QueryResult.Value<Unit>>,
        config: SqlCipherConfig,
        key: String,
    ): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = schema,
            name = config.name,
            onConfiguration = { cfg ->
                cfg.copy(encryptionConfig = DatabaseConfiguration.Encryption(key = key))
            },
        )
        try {
            driver.assertCipherPresent()
            // A value-returning PRAGMA on real table data forces the key check against the file.
            driver.executeQuery(null, "PRAGMA user_version;", { QueryResult.Value(Unit) }, 0)
        } catch (e: Exception) {
            driver.close()
            throw e
        }
        return driver
    }
}
