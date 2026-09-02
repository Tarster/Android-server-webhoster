package com.example.webhoster.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class FileManagerTest {

    private lateinit var context: Context
    private lateinit var fileManager: FileManager
    private lateinit var rootDocFile: DocumentFile

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        fileManager = FileManager(context)
        rootDocFile = mockk(relaxed = true)

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromTreeUri(any(), any()) } returns rootDocFile
    }

    @Test
    fun `findFileInTree should return index html when path is empty`() {
        val treeUri = mockk<Uri>()
        val indexFile = mockk<DocumentFile>()
        every { indexFile.exists() } returns true
        every { rootDocFile.findFile("index.html") } returns indexFile

        val result = fileManager.findFileInTree(treeUri, "/")

        assertNotNull(result)
        assertEquals(indexFile, result)
    }

    @Test
    fun `findFileInTree should traverse directories correctly`() {
        val treeUri = mockk<Uri>()
        val imgDir = mockk<DocumentFile>()
        val logoFile = mockk<DocumentFile>()
        
        every { rootDocFile.findFile("images") } returns imgDir
        every { imgDir.findFile("logo.png") } returns logoFile
        every { logoFile.exists() } returns true
        every { logoFile.isDirectory } returns false

        val result = fileManager.findFileInTree(treeUri, "/images/logo.png")

        assertNotNull(result)
        assertEquals(logoFile, result)
    }

    @Test
    fun `findFileInLocalDir should return correct file`() {
        val tempDir = File.createTempFile("webhoster", "").apply {
            delete()
            mkdirs()
        }
        val wwwDir = File(tempDir, "www").apply { mkdirs() }
        val indexFile = File(wwwDir, "index.html").apply { writeText("hello") }
        val cssDir = File(wwwDir, "css").apply { mkdirs() }
        val styleFile = File(cssDir, "style.css").apply { writeText("body{}") }

        assertEquals(indexFile.absolutePath, fileManager.findFileInLocalDir(wwwDir, "/")?.absolutePath)
        assertEquals(styleFile.absolutePath, fileManager.findFileInLocalDir(wwwDir, "/css/style.css")?.absolutePath)
        assertNull(fileManager.findFileInLocalDir(wwwDir, "/non-existent"))

        tempDir.deleteRecursively()
    }
}
