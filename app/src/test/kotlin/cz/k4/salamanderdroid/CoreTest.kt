package cz.k4.salamanderdroid

import cz.k4.salamanderdroid.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ModelTest {
    @Test
    fun wildcardAndMaskMatching() {
        assertTrue(wildcardMatch("*.rs", "main.rs"))
        assertTrue(wildcardMatch("ma?n*", "main.rs"))
        assertFalse(wildcardMatch("*.rs", "main.rc"))
        assertTrue(wildcardMatch("*", ""))
        val mask = Mask.parse("*.RS; *.toml | target*")
        assertTrue(mask.matches("Main.rs"))
        assertTrue(mask.matches("Cargo.TOML"))
        assertFalse(mask.matches("target.rs"))
        assertFalse(mask.matches("readme.md"))
        assertTrue(Mask.parse("*.*").matches("noextension"))
        assertTrue(Mask.parse("").matches("anything"))
    }

    @Test
    fun formatsSizesModesAndExtensions() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 MiB", formatBytes(1024 * 1024 * 3 / 2))
        assertEquals("1 234 567", formatBytesExact(1234567))
        assertEquals("drwxr-sr-t", formatMode("3755".toInt(8), EntryKind.Directory))
        assertEquals("-rwSr--r-T", formatMode("5644".toInt(8), EntryKind.File))
        assertEquals("0644", formatOctal("644".toInt(8)))
        assertEquals("755".toInt(8), parseOctal("755"))
        assertNull(parseOctal("9"))
        assertEquals("gz", extensionOf("a.tar.gz"))
        assertEquals("", extensionOf(".bashrc"))
        assertEquals("", extensionOf("Makefile"))
    }

    @Test
    fun parsesLocalTime() {
        val time = parseLocalTime("2024-03-05 14:30:15")!!
        assertEquals("2024-03-05 14:30:15", formatTime(time, "yyyy-MM-dd HH:mm:ss"))
        assertNotNull(parseLocalTime("2024-03-05 14:30"))
        assertNull(parseLocalTime("nonsense"))
        assertNull(parseLocalTime("2024-13-05 14:30"))
    }

    @Test
    fun comparesListingsByPresenceTimeAndSize() {
        val left = listOf(
            CompareItem("only-left", 1, 1000), CompareItem("newer-left", 1, 10_000),
            CompareItem("same", 5, 5000), CompareItem("size", 1, 5000), CompareItem("close", 1, 5000),
        )
        val right = listOf(
            CompareItem("only-right", 1, 1000), CompareItem("newer-left", 1, 1000),
            CompareItem("same", 5, 5000), CompareItem("size", 2, 5000), CompareItem("close", 1, 6500),
        )
        val (l, r) = compareListings(left, right)
        assertEquals(setOf("only-left", "newer-left", "size"), l)
        assertEquals(setOf("only-right", "size"), r)
    }

    @Test
    fun sortsDirectoriesFirst() {
        val entries = listOf(
            FileEntry("b.txt", EntryKind.File, 5), FileEntry("Zdir", EntryKind.Directory),
            FileEntry("a.txt", EntryKind.File, 9), FileEntry("..", EntryKind.Parent),
        )
        assertEquals(listOf("..", "Zdir", "a.txt", "b.txt"), entries.sortedWith(entryComparator(SortKey.Name, false)).map { it.name })
        assertEquals(listOf("..", "Zdir", "a.txt", "b.txt"), entries.sortedWith(entryComparator(SortKey.Size, true)).map { it.name })
    }

    @Test
    fun pathHelpers() {
        assertEquals("/a/b", joinPath("/a", "b"))
        assertEquals("/b", joinPath("/", "b"))
        assertEquals("/a", parentPath("/a/b"))
        assertEquals("/", parentPath("/a"))
        assertNull(parentPath("/"))
        assertEquals("/a/c", normalizePath("/a/./b/../c//"))
        assertEquals("b", baseName("/a/b/"))
    }

    @Test
    fun configRoundTrips() {
        val config = Config(
            leftDir = "/storage/emulated/0", rightDir = "sftp://x/y", showHidden = true, showExtColumn = true,
            showParentEntry = false, fontSize = 18, hotpaths = List(HOTPATH_COUNT) { if (it == 3) "/a=b" else null },
        )
        assertEquals(config, Config.parse(config.serialize()))
        assertEquals(Config(), Config.parse("garbage\nfont_size=abc\nhotpath99=/x\n"))
    }
}

class OpsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun transfer(resolver: (Conflict) -> ConflictAction = { fail("unexpected conflict"); ConflictAction.Abort }) =
        Transfer(LocalFs, object : OpListener {
            override fun conflict(conflict: Conflict) = resolver(conflict)
        }, AtomicBoolean(false))

    private fun tree(): File {
        val src = temp.newFolder("src")
        File(src, "a.txt").writeText("alpha")
        File(src, "sub/deep").mkdirs()
        File(src, "sub/b.txt").writeText("beta")
        File(src, "sub/deep/c.bin").writeBytes(ByteArray(300_000) { it.toByte() })
        File(src, "a.txt").setLastModified(1_600_000_000_000)
        return src
    }

    @Test
    fun recursiveCopyPreservesTreeAndTimes() {
        val src = tree()
        val dst = temp.newFolder("dst")
        val result = transfer().copyTree(LocalFs, listOf(src.path), dst.path, false)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals("alpha", File(dst, "src/a.txt").readText())
        assertEquals("beta", File(dst, "src/sub/b.txt").readText())
        assertEquals(300_000, File(dst, "src/sub/deep/c.bin").length())
        assertEquals(1_600_000_000_000, File(dst, "src/a.txt").lastModified())
        assertTrue(File(src, "a.txt").exists())
        assertFalse(File(dst, "src/a.txt$PART_SUFFIX").exists())
    }

    @Test
    fun conflictSkipAndOverwrite() {
        val src = temp.newFolder("s")
        val dst = temp.newFolder("d")
        File(src, "f").writeText("new")
        File(dst, "f").writeText("old")
        transfer { ConflictAction.Skip }.copyTree(LocalFs, listOf(File(src, "f").path), dst.path, false)
        assertEquals("old", File(dst, "f").readText())
        transfer { ConflictAction.Overwrite }.copyTree(LocalFs, listOf(File(src, "f").path), dst.path, false)
        assertEquals("new", File(dst, "f").readText())
    }

    @Test
    fun skipAllAsksOnce() {
        val src = temp.newFolder("s")
        val dst = temp.newFolder("d")
        for (name in listOf("a", "b", "c")) {
            File(src, name).writeText("new")
            File(dst, name).writeText("old")
        }
        var asked = 0
        transfer { asked++; ConflictAction.SkipAll }
            .copyTree(LocalFs, src.listFiles()!!.map { it.path }, dst.path, false)
        assertEquals(1, asked)
        assertEquals("old", File(dst, "b").readText())
    }

    @Test
    fun moveRenamesAndMergesKeepingSkippedSources() {
        val src = tree()
        val dst = temp.newFolder("dst")
        assertTrue(transfer().copyTree(LocalFs, listOf(src.path), dst.path, true).errors.isEmpty())
        assertFalse(src.exists())
        assertEquals("beta", File(dst, "src/sub/b.txt").readText())

        // Merge into an existing directory; a skipped conflict keeps its source.
        val again = temp.newFolder("again", "src")
        File(again, "a.txt").writeText("other")
        File(again, "new.txt").writeText("n")
        transfer { ConflictAction.Skip }.copyTree(LocalFs, listOf(again.path), dst.path, true)
        assertEquals("alpha", File(dst, "src/a.txt").readText())
        assertEquals("n", File(dst, "src/new.txt").readText())
        assertTrue(File(again, "a.txt").exists())
        assertFalse(File(again, "new.txt").exists())
    }

    @Test
    fun rejectsCopyOntoItselfAndIntoOwnSubtree() {
        val src = tree()
        val self = transfer().copyTree(LocalFs, listOf(File(src, "a.txt").path), src.path, false)
        assertEquals(1, self.errors.size)
        val into = transfer().copyTree(LocalFs, listOf(src.path), File(src, "sub").path, true)
        assertEquals(1, into.errors.size)
        assertEquals("alpha", File(src, "a.txt").readText())
    }

    @Test
    fun presetCancelChangesNothing() {
        val src = tree()
        val dst = temp.newFolder("dst")
        val result = Transfer(LocalFs, object : OpListener {}, AtomicBoolean(true)).copyTree(LocalFs, listOf(src.path), dst.path, false)
        assertTrue(result.cancelled)
        assertEquals(0, dst.list()!!.size)
    }

    @Test
    fun failedReadKeepsExistingTarget() {
        val dst = temp.newFolder("d")
        File(dst, "f").writeText("old")
        val t = transfer { ConflictAction.Overwrite }
        val ok = t.putFile("src", File(dst, "f").path, 3, null, null) { throw java.io.IOException("boom") }
        assertFalse(ok)
        assertEquals("old", File(dst, "f").readText())
        assertEquals(listOf("f"), dst.list()!!.toList())
        assertEquals(1, t.errors.size)
    }

    @Test
    fun deleteAndMeasure() {
        val src = tree()
        val m = measure(LocalFs, src.path, { false })
        assertEquals(3, m.files)
        assertEquals(3, m.dirs)
        assertEquals(300_009, m.bytes)
        assertTrue(deleteTrees(LocalFs, listOf(src.path), AtomicBoolean(false)).isEmpty())
        assertFalse(src.exists())
    }

    @Test
    fun trashMovesAndRecordsOrigin() {
        val root = temp.newFolder("vol")
        val file = File(root, "docs/x.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        val twin = File(root, "x.txt").apply { writeText("y") }
        assertTrue(Trash.trash(listOf(file.path, twin.path)) { root.path }.isEmpty())
        assertFalse(file.exists())
        val files = File(Trash.trashDir(root.path), "files")
        assertEquals(setOf("x.txt", "x.txt.2"), files.list()!!.toSet())
        assertEquals(file.path, Trash.originalPath(root.path, "x.txt"))
        assertEquals(1, Trash.trash(listOf(File(files, "x.txt").path)) { root.path }.size)
        assertEquals(1, Trash.trash(listOf(root.path)) { null }.size)
    }
}

class ArchiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val quiet = object : Archives.PackListener {}

    private fun sources(): File {
        val src = temp.newFolder("data")
        File(src, "a.txt").writeText("alpha")
        File(src, "sub/deep").mkdirs()
        File(src, "sub/b.txt").writeText("beta")
        File(src, "sub/deep/c.bin").writeBytes(ByteArray(200_000) { (it * 7).toByte() })
        File(src, "empty").mkdirs()
        return src
    }

    @Test
    fun packIndexExtractAndUpdateEveryWritableFormat() {
        val src = sources()
        for (format in ArchiveFormat.PACKABLE) {
            val archive = File(temp.root, "out-${format.name}.${format.label}")
            assertEquals(format, ArchiveFormat.of(archive.name))
            val errors = Archives.pack(archive, listOf(src), quiet, AtomicBoolean(false))
            assertTrue("$format $errors", errors.isEmpty())

            val index = Archives.index(archive)
            assertEquals("$format", listOf("data"), index.list("")!!.map { it.name })
            assertEquals("$format", setOf("a.txt", "sub", "empty"), index.list("data")!!.map { it.name }.toSet())
            assertEquals("$format", 200_000L, index.list("data/sub/deep")!!.single().size)

            // Extract one directory and one file.
            val out = temp.newFolder("x-${format.name}")
            val transfer = Transfer(LocalFs, object : OpListener {}, AtomicBoolean(false))
            Archives.extract(archive, listOf("data/sub", "data/a.txt"), transfer, out.path)
            assertTrue("$format ${transfer.errors}", transfer.errors.isEmpty())
            assertEquals("alpha", File(out, "a.txt").readText())
            assertEquals("beta", File(out, "sub/b.txt").readText())
            assertArrayEquals(File(src, "sub/deep/c.bin").readBytes(), File(out, "sub/deep/c.bin").readBytes())
            assertEquals(2, transfer.totalFiles - 1)

            // Add a file (replacing a.txt) into data/, remove data/sub.
            val extra = temp.newFolder("extra-${format.name}")
            val replacement = File(extra, "a.txt").apply { writeText("ALPHA2") }
            val added = File(extra, "new.txt").apply { writeText("new") }
            Archives.update(archive, listOf(replacement, added), "data", listOf("data/sub"), quiet, AtomicBoolean(false))
            val updated = Archives.index(archive)
            assertEquals("$format", setOf("a.txt", "new.txt", "empty"), updated.list("data")!!.map { it.name }.toSet())
            assertNull(updated.list("data/sub"))
            val single = File(temp.root, "single-${format.name}")
            Archives.extractFile(archive, "data/a.txt", single)
            assertEquals("ALPHA2", single.readText())
            assertFalse(File(archive.path + PART_SUFFIX).exists())
        }
    }

    @Test
    fun rejectsZipSlipNames() {
        assertNull(Archives.safeInnerPath("../evil"))
        assertNull(Archives.safeInnerPath("a/../../evil"))
        assertEquals("etc/passwd", Archives.safeInnerPath("/etc/passwd"))
        assertEquals("a/b", Archives.safeInnerPath("./a//b/"))
        val archive = File(temp.root, "slip.zip")
        org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(archive).use { out ->
            for (name in listOf("../escape.txt", "ok.txt")) {
                out.putArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name))
                out.write("x".toByteArray())
                out.closeArchiveEntry()
            }
        }
        val out = temp.newFolder("slip-out")
        val transfer = Transfer(LocalFs, object : OpListener {}, AtomicBoolean(false))
        Archives.extract(archive, listOf(""), transfer, out.path)
        assertEquals(listOf("ok.txt"), out.list()!!.toList())
        assertFalse(File(temp.root, "escape.txt").exists())
    }

    @Test
    fun formatDetection() {
        assertEquals(ArchiveFormat.TarGz, ArchiveFormat.of("X.TGZ"))
        assertEquals(ArchiveFormat.Tar, ArchiveFormat.of("x.tar"))
        assertNull(ArchiveFormat.of("x.txt"))
        assertNull(ArchiveFormat.of(".zip"))
        assertEquals("data", ArchiveFormat.stem("data.tar.gz"))
    }
}

class FindAndViewerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun run(query: FindQuery): List<String> {
        val found = ArrayList<String>()
        var finished = 0
        findFiles(query, AtomicBoolean(false)) { event ->
            if (event is FindEvent.Found) found += event.path.removePrefix(query.root + "/")
            if (event is FindEvent.Finished) finished++
            true
        }
        assertEquals(1, finished)
        return found
    }

    @Test
    fun findsByMaskAndContent() {
        val root = temp.newFolder("r")
        File(root, "a.txt").writeText("Hello World")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("x".repeat(70_000) + "needLE" + "y".repeat(10))
        File(root, ".hidden").mkdirs()
        File(root, ".hidden/c.txt").writeText("hello")
        assertEquals(listOf("a.txt", "sub/b.txt"), run(FindQuery(root.path, Mask.parse("*.txt"))))
        assertEquals(3, run(FindQuery(root.path, Mask.parse("*.txt"), includeHidden = true)).size)
        assertEquals(listOf("sub/b.txt"), run(FindQuery(root.path, Mask.parse("*"), "needle".toByteArray())))
        assertEquals(emptyList<String>(), run(FindQuery(root.path, Mask.parse("*"), "needle".toByteArray(), caseSensitive = true)))
        assertEquals(listOf("sub"), run(FindQuery(root.path, Mask.parse("su*"))))
    }

    @Test
    fun viewerRendering() {
        assertEquals("00000000  68 65 6c 6c 6f 00 ff                              |hello..|", hexLine("hello".toByteArray() + byteArrayOf(0, -1), 0))
        assertEquals(VIEW_LIMIT + 1, readLimited(object : java.io.InputStream() {
            override fun read() = 65
        }).size)
        assertTrue(renderContent("příliš žluťoučký".toByteArray()).isText)
        assertFalse(renderContent(byteArrayOf(1, 0, 2)).isText)
        assertFalse(renderContent(byteArrayOf(-1, -2)).isText)
        // A multi-byte character cut by the limit keeps the valid prefix as text.
        val bytes = "ab€".toByteArray()
        val cut = renderContent(bytes.copyOf(4), limit = 3)
        assertTrue(cut.truncated)
        assertEquals("ab", cut.text)
        assertEquals(6, indexOfBytes("Hello World".toByteArray(), "WORLD".toByteArray(), 0, true))
        assertEquals(-1, indexOfBytes("Hello World".toByteArray(), "WORLD".toByteArray(), 0, false))
    }
}
