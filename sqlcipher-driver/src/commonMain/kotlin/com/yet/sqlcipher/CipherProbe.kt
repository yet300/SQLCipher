package com.yet.sqlcipher

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Asserts the connection actually runs SQLCipher. A binary accidentally linked against a plain
 * SQLite ignores `PRAGMA key` and would read/write the file in plaintext; there `cipher_version`
 * returns an empty result, which this turns into a hard [MissingSqlCipherException].
 */
internal fun SqlDriver.assertCipherPresent() {
    val cipherVersion = executeQuery(
        identifier = null,
        sql = "PRAGMA cipher_version;",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        },
        parameters = 0,
    ).value
    if (cipherVersion.isNullOrEmpty()) {
        throw MissingSqlCipherException(
            "SQLCipher is not linked (PRAGMA cipher_version returned nothing) — the database would be plaintext",
        )
    }
}

/** Lowercase hex armor for key bytes (SQLCipher passphrase-string keying on native). */
internal fun ByteArray.hexEncoded(): String = buildString(size * 2) {
    for (b in this@hexEncoded) {
        val v = b.toInt() and 0xFF
        append(HEX_DIGITS[v ushr 4])
        append(HEX_DIGITS[v and 0x0F])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
