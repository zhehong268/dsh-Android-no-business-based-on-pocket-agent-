package dev.pocketagent.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.pocketagent.device.FilePort
import dev.pocketagent.device.FileResult
import java.io.IOException

/**
 * Files reached through folders the user picked with the system folder picker. Access survives restarts because the
 * picker's permission is persisted; nothing outside those folders can be named, because every path is walked from a
 * granted root one segment at a time.
 *
 * The grant itself is the permission: the app never asks Android for broad storage access.
 */
class SafFiles(private val context: Context, private val store: SharedPreferences) : FilePort {
    /** A granted folder: the name shown to the user and the model, and the tree it stands for. */
    data class Folder(val name: String, val uri: String)

    override val hasFolders: Boolean
        get() = folders().isNotEmpty()

    fun folders(): List<Folder> {
        val stored = store.getStringSet(KEY, emptySet()).orEmpty().sorted()
        return stored.mapNotNull { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@mapNotNull null
            val name = documentName(uri) ?: return@mapNotNull null
            Folder(name, raw)
        }
    }

    /** Remembers a folder the picker returned. False when the grant could not be kept. */
    fun add(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val kept = try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: SecurityException) {
            false
        }
        if (!kept) return false
        store.edit().putStringSet(KEY, store.getStringSet(KEY, emptySet()).orEmpty() + uri.toString()).apply()
        return true
    }

    fun remove(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        store.edit().putStringSet(KEY, store.getStringSet(KEY, emptySet()).orEmpty() - uri).apply()
    }

    override fun list(path: String): FileResult = guarded {
        val target = resolve(path, mustExist = true)
        if (!target.isDir) throw FileError("「${target.label}」不是一个文件夹。")
        val children = children(target.folder, target.docId)
        if (children.isEmpty()) return@guarded FileResult.Ok("「${target.label}」是空的。")
        val lines = children.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).joinToString("\n") { child ->
            if (child.isDir) "${child.name}/" else child.name
        }
        FileResult.Ok("「${target.label}」里有 ${children.size} 项：\n$lines")
    }

    override fun read(path: String, maxChars: Int): FileResult = guarded {
        val target = resolve(path, mustExist = true)
        if (target.isDir) throw FileError("「${target.label}」是文件夹，不是文件。")
        val docUri = target.uri ?: throw FileError("读不到「${target.label}」，可能是文件已被移动。")
        val bytes = try {
            context.contentResolver.openInputStream(docUri).use { it?.readBytes() }
        } catch (e: IOException) {
            null
        } ?: throw FileError("读不到「${target.label}」，可能是文件已被移动或没有读权限。")
        if (bytes.size > MAX_BYTES) {
            throw FileError("「${target.label}」太大了（${bytes.size / 1024} KB），只能读 $maxChars 字以内的文本文件。")
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (text.contains('\u0000')) throw FileError("「${target.label}」看起来不是文本文件。")
        val body = if (text.length > maxChars) text.take(maxChars) + "\n…（后面还有内容，已截断）" else text
        FileResult.Ok("「${target.label}」的内容：\n$body")
    }

    override fun write(path: String, text: String): FileResult = guarded {
        val target = resolve(path, mustExist = false)
        if (target.isDir) throw FileError("「${target.label}」是文件夹，不能当成文件写。")
        val existing = target.uri
        val uri: Uri = existing ?: run {
            val parent = target.parentUri ?: throw FileError("找不到「${target.label}」所在的文件夹。")
            try {
                DocumentsContract.createDocument(context.contentResolver, parent, MIME_TEXT, target.name)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } ?: throw FileError("在这个文件夹里新建不了「${target.name}」，可能没有写权限。")
        }
        val wrote = try {
            context.contentResolver.openOutputStream(uri, "wt").use { out ->
                out?.write(text.toByteArray(Charsets.UTF_8))
                out != null
            }
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!wrote) throw FileError("写不进「${target.label}」，可能没有写权限。")
        FileResult.Ok("已把 ${text.length} 个字写入「${target.label}」。")
    }

    // --- resolution ---

    private class Resolved(
        val folder: Folder,
        val uri: Uri?,
        val parentUri: Uri?,
        val docId: String,
        val name: String,
        val isDir: Boolean,
    ) {
        val label: String get() = name
    }

    private class Child(val name: String, val docId: String, val isDir: Boolean)

    private class FileError(message: String) : Exception(message)

    /**
     * Walks [path] from a granted folder. With [mustExist] the last segment has to be there; otherwise it may name a new
     * file in an existing folder.
     */
    private fun resolve(path: String, mustExist: Boolean): Resolved {
        val segments = path.trim().trim('/').split('/').filter { it.isNotEmpty() && it != "." }
        if (path.length > FilePort.MAX_PATH) throw FileError("路径太长了。")
        if (segments.isEmpty()) throw FileError("要给出文件或文件夹的名字，例如「Download/笔记.txt」。")
        if (segments.any { it == ".." }) throw FileError("路径里不能有「..」。")
        val granted = folders()
        if (granted.isEmpty()) throw FileError("还没有授权任何文件夹。")
        val matched = granted.firstOrNull { it.name.equals(segments.first(), ignoreCase = true) }
        val folder = matched ?: granted.singleOrNull() ?: throw FileError(
            "授权的文件夹有 ${granted.size} 个，路径要以其中之一开头：${granted.joinToString("、") { it.name }}。",
        )
        val rest = if (matched == null) segments else segments.drop(1)
        val tree = Uri.parse(folder.uri)
        var docId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: IllegalArgumentException) {
            throw FileError("「${folder.name}」的授权已经失效，请重新选择文件夹。")
        }
        var currentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        if (rest.isEmpty()) return Resolved(folder, currentUri, null, docId, folder.name, isDir = true)

        rest.forEachIndexed { index, segment ->
            val child = children(folder, docId).firstOrNull { it.name == segment }
            val last = index == rest.lastIndex
            if (child == null) {
                if (last && !mustExist) {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    return Resolved(folder, null, parentUri, docId, segment, isDir = false)
                }
                throw FileError("在「${folder.name}」里找不到「${segment}」。")
            }
            if (!last && !child.isDir) throw FileError("「${child.name}」是文件，不是文件夹。")
            docId = child.docId
            currentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            if (last) {
                val parentDocId = rest.dropLast(1).fold(DocumentsContract.getTreeDocumentId(tree)) { id, name ->
                    children(folder, id).firstOrNull { it.name == name }?.docId ?: id
                }
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId)
                return Resolved(folder, currentUri, parentUri, docId, child.name, child.isDir)
            }
        }
        throw FileError("路径无效。")
    }

    private fun children(folder: Folder, docId: String): List<Child> {
        val tree = Uri.parse(folder.uri)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<Child>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    out += Child(name, id, cursor.getString(2) == Document.MIME_TYPE_DIR)
                }
            }
        } catch (e: SecurityException) {
            return out
        } catch (e: IllegalArgumentException) {
            return out
        }
        return out
    }

    private fun documentName(uri: Uri): String? = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(docUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private inline fun guarded(block: () -> FileResult): FileResult = try {
        block()
    } catch (e: FileError) {
        FileResult.Failed(e.message ?: "这个路径用不了。")
    } catch (e: SecurityException) {
        FileResult.Failed("这个文件夹的权限没了，请在设置里重新授权。")
    } catch (e: IOException) {
        FileResult.Failed("读写文件时出错了，请再试一次。")
    }

    companion object {
        private const val KEY = "files.folders"
        private const val MAX_BYTES = 400_000
        private const val MIME_TEXT = "text/plain"

        /** Where the grants live; the settings screen writes the same preference. */
        const val STORE = "dsh-files"

        fun store(context: Context): SharedPreferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    }
}
