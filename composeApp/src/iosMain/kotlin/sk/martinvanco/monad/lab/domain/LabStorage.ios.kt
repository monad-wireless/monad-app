package sk.martinvanco.monad.lab.domain

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber

/**
 * `NSFileManager`'s file-system attributes for the app's home directory.
 *
 * The dictionary is bridged as `Map<Any?, *>`, and the values arrive as `NSNumber` — hence the
 * explicit narrowing. Anything unexpected returns 0, which the pre-flight reports as "unknown"
 * rather than as "full": inventing a zero-free-space failure would be worse than admitting the
 * platform did not answer.
 */
@OptIn(ExperimentalForeignApi::class)
private fun fileSystemAttribute(key: String?): Long {
    if (key == null) return 0L
    val attributes = runCatching {
        NSFileManager.defaultManager.attributesOfFileSystemForPath(NSHomeDirectory(), null)
    }.getOrNull() ?: return 0L
    return when (val value = attributes[key]) {
        is NSNumber -> value.longLongValue
        is Long -> value
        is Int -> value.toLong()
        else -> 0L
    }
}

actual fun availableStorageBytes(): Long = fileSystemAttribute(NSFileSystemFreeSize)

actual fun totalStorageBytes(): Long = fileSystemAttribute(NSFileSystemSize)
