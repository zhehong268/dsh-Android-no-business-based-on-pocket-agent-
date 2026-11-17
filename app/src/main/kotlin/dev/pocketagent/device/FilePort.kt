package dev.pocketagent.device

/**
 * The user's files, reached through the folders they granted. Paths are written as `folder/inside/file.txt`, where
 * `folder` is the name of a granted folder; with a single granted folder the name may be left out.
 *
 * Every call is a single file or folder: implementations do not walk trees on their own, so a path the user did not
 * grant cannot be reached by guessing.
 */
interface FilePort {
    /** True when at least one folder has been granted, so the file tools are worth offering. */
    val hasFolders: Boolean

    fun list(path: String): FileResult

    fun read(path: String, maxChars: Int = DEFAULT_MAX_CHARS): FileResult

    fun write(path: String, text: String): FileResult

    companion object {
        const val DEFAULT_MAX_CHARS = 60_000

        /** The longest path handed to the tools, in characters. */
        const val MAX_PATH = 300
    }
}

sealed interface FileResult {
    data class Ok(val text: String) : FileResult

    data class Failed(val reason: String) : FileResult
}
