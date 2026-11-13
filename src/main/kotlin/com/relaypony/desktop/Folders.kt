package com.relaypony.desktop

import com.relaypony.session.FileZipSource
import com.relaypony.session.FolderZip
import com.relaypony.session.SafeZip
import com.relaypony.session.TransferLimits
import com.relaypony.session.UnsafeArchiveException
import com.relaypony.session.ZipRefusal
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** A folder picked to send, walked into the items [FolderZip] archives (PROTOCOL_v3 section 12.1). */
class PickedFolder(val name: String, val items: List<FolderZip.Item>) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val fileCount: Int get() = items.count { !it.isDir }

    class TooManyItemsException : Exception("folder has too many items")

    companion object {
        /**
         * Walks [root]. Symbolic links are left out rather than followed, so a link can't pull in
         * files from outside the folder. Throws [TooManyItemsException] past [maxItems].
         */
        fun walk(root: File, maxItems: Int): PickedFolder {
            val items = ArrayList<FolderZip.Item>()

            fun visit(dir: File, prefix: String, depth: Int) {
                if (depth > SafeZip.MAX_DEPTH) throw IOException("folder is nested too deeply")
                val children = dir.listFiles()?.sortedBy { it.name } ?: throw IOException("cannot list ${dir.name}")
                var any = false
                for (child in children) {
                    if (Files.isSymbolicLink(child.toPath())) continue
                    if (child.name == ".DS_Store") continue
                    any = true
                    val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    if (Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        visit(child, path, depth + 1)
                    } else if (Files.isRegularFile(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        items.add(FolderZip.Item(path, child.length()) { child.inputStream() })
                    }
                    // The receiver counts every entry, the root and empty folders included.
                    if (items.size + 1 > maxItems) throw TooManyItemsException()
                }
                if (!any && prefix.isNotEmpty()) {
                    items.add(FolderZip.Item(prefix, 0, isDir = true) { throw IOException("directory") })
                }
            }
            visit(root, "", 1)
            return PickedFolder(root.name.ifEmpty { "Folder" }, items)
        }
    }
}

/**
 * Extracts a received .zip into Downloads/RelayPony/<folder> (PROTOCOL_v3 section 12.4). [SafeZip]
 * checks the whole archive before anything is written; this only decides where each path lands.
 */
object FolderExtractor {
    data class Result(val folder: File, val files: Int, val skipped: Int)

    class EmptyArchiveException : Exception("nothing to extract")

    fun isArchive(name: String): Boolean = name.lowercase().endsWith(".zip")

    fun parentDir(): File {
        val downloads = File(System.getProperty("user.home"), "Downloads")
        return File(if (downloads.isDirectory) downloads else Config.defaultInbox(), "RelayPony")
    }

    fun extract(zip: File, archiveName: String): Result {
        FileZipSource(zip).use { source ->
            val parent = parentDir().apply { mkdirs() }
            val limits = TransferLimits(freeBytes = { parent.usableSpace })
            val plan = SafeZip.plan(source, archiveName, limits)
            if (plan.files.isEmpty()) throw EmptyArchiveException()
            val dir = freeFolder(parent, plan.root)
            val base = dir.canonicalPath + File.separator
            val written = ArrayList<File>()
            try {
                plan.dirs.forEach { d -> File(dir, d).takeIf { it.canonicalPath.startsWith(base) }?.mkdirs() }
                SafeZip.extract(source, plan) { entry ->
                    val target = File(dir, entry.path)
                    // SafeZip already refuses anything that leaves the root; this is the backstop.
                    if (!target.canonicalPath.startsWith(base)) {
                        throw UnsafeArchiveException(ZipRefusal.UNSAFE_PATH, "outside the folder")
                    }
                    target.parentFile?.mkdirs()
                    written.add(target)
                    target.outputStream()
                }
            } catch (t: Throwable) {
                written.forEach { runCatching { it.delete() } }
                runCatching { dir.deleteRecursively() }
                throw t
            }
            return Result(dir, plan.files.size, plan.skipped)
        }
    }

    /** "Photos", or "Photos (2)" and up when a folder by that name is already there. */
    private fun freeFolder(parent: File, root: String): File {
        for (n in 1..999) {
            val candidate = File(parent, if (n == 1) root else "$root ($n)")
            if (!candidate.exists()) return candidate
        }
        return File(parent, "$root (${System.currentTimeMillis()})")
    }
}
