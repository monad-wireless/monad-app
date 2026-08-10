package sk.martinvanco.monad.lab.domain

import android.os.Environment
import android.os.StatFs

/**
 * `StatFs` over the data directory.
 *
 * Deliberately resolved from [Environment] rather than from a `Context.filesDir`: this is called
 * from a pre-flight check that has no reason to hold a Context, and the app's database lives on the
 * data volume either way. `availableBytes` is the space this app may actually use, which is smaller
 * than `freeBytes` on a device with a reserve — the smaller number is the honest one here.
 */
actual fun availableStorageBytes(): Long =
    runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(0L)

actual fun totalStorageBytes(): Long =
    runCatching { StatFs(Environment.getDataDirectory().path).totalBytes }.getOrDefault(0L)
