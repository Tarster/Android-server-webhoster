package com.example.webhoster.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.zip.ZipInputStream

class FileManager(private val context: Context) {

    /**
     * Unzips a file from a Uri into the app's filesDir/www folder.
     */
    suspend fun unzipToInternalStorage(uri: Uri): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "www").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    val outFile = File(outputDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
                        entry = zipStream.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            zipStream.copyTo(output)
                        }
                    }
                    entry = zipStream.nextEntry
                }
            }
        }
        return@withContext outputDir
    }

    /**
     * Find a file in a local directory.
     */
    fun findFileInLocalDir(rootDir: File, path: String): File? {
        var cleanPath = path.trimStart('/')
        if (cleanPath.isEmpty()) cleanPath = "index.html"
        
        val file = File(rootDir, cleanPath)
        if (!file.exists()) return null
        
        return if (file.isDirectory) File(file, "index.html").takeIf { it.exists() } else file
    }

    /**
     * Find a file in a DocumentTree by path.
     * Path is like "/images/logo.png"
     */
    fun findFileInTree(treeUri: Uri, path: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        var current: DocumentFile = root
        
        // Clean the path and split it into parts
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        
        // If path is empty, we look for index.html in the root
        if (parts.isEmpty()) return root.findFile("index.html")

        for (part in parts) {
            val nextFile = current.findFile(part) ?: return null
            current = nextFile
        }
        
        // If the resolved path is a directory, look for index.html inside it
        return if (current.isDirectory) current.findFile("index.html") else current
    }

    fun openStream(file: DocumentFile): InputStream? {
        return context.contentResolver.openInputStream(file.uri)
    }

    fun getMimeType(file: DocumentFile): String {
        return file.type ?: "application/octet-stream"
    }
}
