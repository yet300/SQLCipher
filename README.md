# SqlCipherDriver

SQLCipher-encrypted [SQLDelight](https://github.com/sqldelight/sqldelight) drivers for Kotlin
Multiplatform — one dependency, both platforms:

- **Android**: wraps the official `net.zetetic:sqlcipher-android` binaries.
- **Apple** (`iosArm64`, `iosSimulatorArm64`, `macosArm64`, `macosX64`): the stock
  SQLDelight/SQLiter native driver, linked
  against a **static SQLCipher bundled inside this library's klib** (CommonCrypto provider, same
  as Zetetic's official iOS builds). Consumers need **no** SPM package, no vendored xcframework,
  and no linker flags.

```kotlin
dependencies {
    implementation("io.github.yet300:sqlcipher-driver:<version>")
}
```

## Usage

```kotlin
// Platform side (DI): AndroidSqlCipherDriverFactory(context) or NativeSqlCipherDriverFactory()
val factory: SqlCipherDriverFactory = NativeSqlCipherDriverFactory()

val driver = factory.create(
    schema = MyDatabase.Schema,
    config = SqlCipherConfig(
        name = "app.db",
        key = SqlCipherKey { keyStore.fetchPassphraseBytes() }, // fresh copy per call
        recovery = SqlCipherRecovery.Fail, // or .Recreate to drop-and-retry on wrong key
    ),
)
```

Every open is probed eagerly: a wrong key or corrupt file fails inside `create` (where
`SqlCipherRecovery` can act), and `PRAGMA cipher_version` is asserted non-empty so a mislinked
plain-SQLite binary throws `MissingSqlCipherException` instead of silently writing plaintext.

`deleteDatabase(name)` deletes the file the same way the platform driver resolves it
(crypto-erase / panic-wipe hook).

### Key semantics (fixed, on purpose)

`SqlCipherKey.passphrase()` returns raw key bytes. Android passes them to SQLCipher as a raw
passphrase (the zetetic factory zeroes the array after open); iOS hex-armors them into a
passphrase string for SQLiter's `PRAGMA key`. The two file formats therefore intentionally
diverge — encrypted databases are per-device and not portable across platforms.

## Consumer requirements (Apple targets)

Exactly **one** `sqlite3_*` exporter may exist in the final link — the SQLCipher bundled here:

- Set SQLDelight's `linkSqlite.set(false)` so `-lsqlite3` (the plain system SQLite) is never
  added to your link.
- Any other dependency that bundles its own SQLite must rename/hide its `sqlite3_*` symbols
  (e.g. `io.github.yet300:tor` does this from 0.1.2). With two exporters, which one wins is
  per-symbol link order — silently wrong.
- Verify in CI: link a native test binary and check `nm`: no `T _sqlite3_open` from anything but
  this library, plus the built-in `cipher_version` probe at runtime.

## Building the native SQLCipher (maintainers)

`sqlcipher-driver/native/build-sqlcipher.sh` downloads the pinned SQLCipher source, generates
the amalgamation, and produces `native/libs/<slice>/libsqlcipher.a` for each published target.
The archives are committed so consumers and CI never rebuild them. Keep the script's
`SQLCIPHER_VERSION` in lockstep with `sqlcipher-android` in `gradle/libs.versions.toml` — one
library version means one SQLCipher version on both platforms.

## Publishing

Same flow as [ArtiTor](https://github.com/yet300/ArtiTor): vanniktech maven-publish to the
Central Portal (`central.sonatype.com` tokens). Signing activates only when a key is configured,
so `publishToMavenLocal` works without GPG.

## License

Apache-2.0 for this library. The bundled SQLCipher is © Zetetic LLC under its BSD-style license
(see `NOTICE`); the Android binaries come from Zetetic's own `sqlcipher-android` artifact.
