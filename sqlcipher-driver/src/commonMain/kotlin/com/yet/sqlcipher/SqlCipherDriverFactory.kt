package com.yet.sqlcipher

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Supplies the SQLCipher key material. Called once per open attempt; the driver consumes the
 * returned array immediately (on Android the platform factory zeroes it after the open), so
 * implementations should return a fresh copy each call and never retain it.
 *
 * How the bytes are applied is platform-defined and fixed (see the platform factories): Android
 * hands them to SQLCipher as a raw passphrase, iOS hex-armors them into a passphrase string.
 * The resulting file formats intentionally differ per platform — an encrypted database is not
 * portable between an Android and an iOS install.
 */
fun interface SqlCipherKey {
    suspend fun passphrase(): ByteArray
}

/**
 * Policy for a failed open (wrong key, corrupt file). Return true to delete the database file
 * and retry the open once; return false to rethrow [cause]. A missing-cipher link defect
 * ([MissingSqlCipherException]) never reaches this policy — deleting user data cannot fix a
 * build problem.
 */
fun interface SqlCipherRecovery {
    suspend fun onOpenFailed(dbName: String, cause: Throwable): Boolean

    companion object {
        /** Never delete; open failures propagate to the caller. Safe default. */
        val Fail: SqlCipherRecovery = SqlCipherRecovery { _, _ -> false }

        /** Drop the unreadable file and start fresh (per-device DBs that can be re-synced). */
        val Recreate: SqlCipherRecovery = SqlCipherRecovery { _, _ -> true }
    }
}

class SqlCipherConfig(
    /** On-disk database file name, e.g. `app.db`. */
    val name: String,
    val key: SqlCipherKey,
    val recovery: SqlCipherRecovery = SqlCipherRecovery.Fail,
)

/**
 * The binary is linked against a plain SQLite instead of SQLCipher (`PRAGMA cipher_version`
 * returned nothing) — the database would silently be plaintext. This is a build/link defect,
 * never a data problem, so it bypasses [SqlCipherRecovery].
 */
class MissingSqlCipherException(message: String) : IllegalStateException(message)

/**
 * Platform factory for a SQLCipher-encrypted SQLDelight [SqlDriver]. Construct the platform
 * implementation directly where the platform is known (DI): [AndroidSqlCipherDriverFactory] /
 * [NativeSqlCipherDriverFactory].
 *
 * Every open is probed eagerly: the key check runs inside [create] — where [SqlCipherRecovery]
 * can act — instead of failing lazily on the first query. `PRAGMA cipher_version` is asserted
 * non-empty on every open, so a mislinked plain-SQLite binary fails fast as
 * [MissingSqlCipherException] rather than writing a plaintext file.
 */
interface SqlCipherDriverFactory {

    /** Open (creating/migrating the schema as needed) and return the driver. */
    suspend fun create(schema: SqlSchema<QueryResult.Value<Unit>>, config: SqlCipherConfig): SqlDriver

    /**
     * Delete the on-disk database the same way the platform driver resolves it (crypto-erase /
     * panic-wipe hook). No-op if the file does not exist.
     */
    fun deleteDatabase(name: String)
}
