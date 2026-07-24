package com.relaypony.desktop

import com.relaypony.crypto.AgeProvider
import com.relaypony.crypto.Identity
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

/**
 * Persists this device's age identity to a file so its handle is stable across runs — essential,
 * since phones pin the handle when they pair. The JVM counterpart of the Android
 * KeystoreIdentityStore; here the file is restricted to the owner (0600 on POSIX) rather than
 * wrapped by a hardware keystore. Same lifecycle: load if present, else generate and persist.
 */
class FileIdentityStore(private val file: File) {

    fun loadOrCreate(provider: AgeProvider): Identity {
        if (file.exists()) {
            runCatching { return provider.identityFromString(file.readText().trim()) }
        }
        val identity = provider.generateIdentity()
        save(provider.identityToString(identity))
        return identity
    }

    /** Overwrite the stored identity with [secret] (an `AGE-SECRET-KEY-1…` string), 0600 perms.
     *  Used when importing an identity backup so the imported handle survives the next launch. */
    fun save(secret: String) {
        file.parentFile?.mkdirs()
        file.writeText(secret.trim())
        runCatching { Files.setPosixFilePermissions(file.toPath(), setOf(OWNER_READ, OWNER_WRITE)) }
    }
}
