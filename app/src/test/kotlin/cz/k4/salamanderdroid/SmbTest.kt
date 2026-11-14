package cz.k4.salamanderdroid

import cz.k4.salamanderdroid.core.Conflict
import cz.k4.salamanderdroid.core.ConflictAction
import cz.k4.salamanderdroid.core.EntryKind
import cz.k4.salamanderdroid.core.LocalFs
import cz.k4.salamanderdroid.core.OpListener
import cz.k4.salamanderdroid.core.Transfer
import cz.k4.salamanderdroid.core.deleteTrees
import cz.k4.salamanderdroid.remote.PasswordAnswer
import cz.k4.salamanderdroid.remote.Protocol
import cz.k4.salamanderdroid.remote.RemoteLog
import cz.k4.salamanderdroid.remote.RemotePrompts
import cz.k4.salamanderdroid.remote.Server
import cz.k4.salamanderdroid.remote.Servers
import cz.k4.salamanderdroid.remote.SmbConnections
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs against a real Samba server started for the test. Needs `smbd` and root (to create
 * the account the server authenticates against); skipped elsewhere.
 */
class SmbTest {
    @get:Rule
    val temp = TemporaryFolder()
    private var smbd: Process? = null
    private lateinit var share: File
    private val port = 44500 + (System.nanoTime() % 400).toInt()
    private val passwords = ArrayDeque<String>()
    private var stored: String? = null

    private val prompts = object : RemotePrompts {
        override fun confirmHostKey(server: Server, keyType: String, fingerprint: String) = true
        override fun password(server: Server, failed: Boolean): PasswordAnswer? =
            passwords.removeFirstOrNull()?.let { PasswordAnswer(it.toCharArray(), true) }

        override fun passphrase(server: Server, keyFile: String): CharArray? = null
        override fun storedPassword(server: Server): CharArray? = stored?.toCharArray()
        override fun storePassword(server: Server, password: CharArray) {
            stored = String(password)
        }
    }

    private fun sh(command: String, input: String = ""): Int {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        process.outputStream.use { it.write(input.toByteArray()) }
        process.inputStream.readBytes()
        return process.waitFor()
    }

    @Before
    fun start() {
        assumeTrue("smbd is not installed", File("/usr/sbin/smbd").canExecute())
        assumeTrue("needs root", System.getProperty("user.name") == "root")
        share = temp.newFolder("share")
        sh("chmod 777 '${share.path}'")
        val state = temp.newFolder("state")
        val conf = File(temp.root, "smb.conf")
        conf.writeText(
            """
            [global]
            smb ports = $port
            interfaces = 127.0.0.1/8
            server min protocol = SMB2
            map to guest = never
            passdb backend = tdbsam:${state.path}/passdb.tdb
            pid directory = ${state.path}
            lock directory = ${state.path}
            state directory = ${state.path}
            cache directory = ${state.path}
            private dir = ${state.path}
            ncalrpc dir = ${state.path}/ncalrpc
            log file = ${state.path}/log
            [data]
            path = ${share.path}
            read only = no
            force user = root
            """.trimIndent() + "\n"
        )
        sh("id smbtestuser >/dev/null 2>&1 || useradd -M -s /usr/sbin/nologin smbtestuser")
        assertEquals(0, sh("smbpasswd -c '${conf.path}' -a -s smbtestuser", "tajne-Heslo1\ntajne-Heslo1\n"))
        smbd = ProcessBuilder("setsid", "/usr/sbin/smbd", "--foreground", "--no-process-group", "-s", conf.path)
            .redirectErrorStream(true).redirectOutput(File(state, "out")).start()
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            try {
                Socket("127.0.0.1", port).close()
                break
            } catch (_: IOException) {
                if (System.currentTimeMillis() > deadline) fail("smbd did not start: " + File(state, "out").readText())
                Thread.sleep(200)
            }
        }
    }

    @After
    fun stop() {
        smbd?.destroy()
        smbd?.waitFor(5, TimeUnit.SECONDS)
        smbd?.destroyForcibly()
        sh("pkill -f 'smbd.*${temp.root.path}'")
    }

    private fun server() = Server(
        id = "smb1", host = "127.0.0.1", port = port, user = "smbtestuser", savePassword = true,
        protocol = Protocol.Smb, share = "data",
    )

    private fun quiet() = object : OpListener {
        override fun conflict(conflict: Conflict) = ConflictAction.Overwrite
    }

    @Test
    fun connectsTransfersAndManagesFiles() {
        val connections = SmbConnections(RemoteLog(File(temp.root, "log")), prompts)
        passwords += listOf("spatne", "tajne-Heslo1")
        val fs = connections.fs(server())
        assertEquals(emptyList<String>(), fs.list("/").map { it.name })
        assertEquals("tajne-Heslo1", stored)
        assertNull(fs.stat("/missing"))
        assertNotNull(fs.space())

        val local = temp.newFolder("local")
        File(local, "dir/sub").mkdirs()
        File(local, "dir/a.txt").writeText("alpha")
        File(local, "dir/Žluťoučký kůň.txt").writeText("diakritika")
        val big = ByteArray(3_000_000) { (it % 253).toByte() }
        File(local, "dir/sub/big.bin").writeBytes(big)
        File(local, "dir/a.txt").setLastModified(1_600_000_000_000)

        val up = Transfer(fs, quiet(), AtomicBoolean(false)).copyTree(LocalFs, listOf(File(local, "dir").path), "/", false)
        assertTrue(up.errors.toString(), up.errors.isEmpty())
        assertEquals("alpha", File(share, "dir/a.txt").readText())
        assertArrayEquals(big, File(share, "dir/sub/big.bin").readBytes())
        val listed = fs.list("/dir").associateBy { it.name }
        assertEquals(setOf("a.txt", "Žluťoučký kůň.txt", "sub"), listed.keys)
        assertEquals(EntryKind.Directory, listed.getValue("sub").kind)
        assertEquals(5L, listed.getValue("a.txt").size)
        assertEquals(1_600_000_000_000, listed.getValue("a.txt").modified)
        assertEquals(1_600_000_000_000, fs.stat("/dir/a.txt")!!.modified)

        val back = temp.newFolder("back")
        val down = Transfer(LocalFs, quiet(), AtomicBoolean(false)).copyTree(fs, listOf("/dir"), back.path, false)
        assertTrue(down.errors.toString(), down.errors.isEmpty())
        assertArrayEquals(big, File(back, "dir/sub/big.bin").readBytes())
        assertEquals("diakritika", File(back, "dir/Žluťoučký kůň.txt").readText())

        // Overwrite, rename, move between folders, delete.
        val newer = File(local, "a.txt").apply { writeText("NEW") }
        assertTrue(Transfer(fs, quiet(), AtomicBoolean(false)).copyTree(LocalFs, listOf(newer.path), "/dir", false).errors.isEmpty())
        assertEquals("NEW", File(share, "dir/a.txt").readText())
        fs.rename("/dir/a.txt", "/dir/b.txt")
        fs.mkdir("/moved")
        assertTrue(Transfer(fs, quiet(), AtomicBoolean(false)).copyTree(fs, listOf("/dir/b.txt", "/dir/sub"), "/moved", true).errors.isEmpty())
        assertEquals("NEW", File(share, "moved/b.txt").readText())
        assertTrue(File(share, "moved/sub/big.bin").isFile && !File(share, "dir/sub").exists())
        assertTrue(deleteTrees(fs, listOf("/dir", "/moved"), AtomicBoolean(false)).isEmpty())
        assertEquals(0, share.list()!!.size)

        // A fresh connection uses the stored password without asking.
        connections.disconnect(server())
        assertEquals(emptyList<String>(), SmbConnections(RemoteLog(File(temp.root, "log")), prompts).fs(server()).list("/").map { it.name })
        connections.disconnectAll()
    }

    @Test
    fun reportsWrongShareAndRejectedPassword() {
        stored = "tajne-Heslo1"
        try {
            SmbConnections(RemoteLog(File(temp.root, "log")), prompts).fs(server().copy(share = "nothing")).list("/")
            fail("opened a missing share")
        } catch (error: IOException) {
            assertTrue(error.message, error.message!!.contains("neexistuje"))
        }
        stored = null
        passwords += listOf("a", "b", "c")
        try {
            SmbConnections(RemoteLog(File(temp.root, "log")), prompts).fs(server()).list("/")
            fail("connected with wrong passwords")
        } catch (error: IOException) {
            assertTrue(error.message, error.message!!.contains("odmítl"))
        }
    }

    @Test
    fun serverFormatKeepsSmbFields() {
        val servers = listOf(server().copy(domain = "FIRMA", initialDir = "projekty"), Server(host = "h", user = "u"))
        assertEquals(servers, Servers.parse(Servers.serialize(servers)))
        assertEquals("smb://smbtestuser@127.0.0.1:$port/data", servers[0].url())
        assertEquals("sftp://u@h", servers[1].url())
        assertNotNull(server().copy(share = "").validate())
        assertNull(server().copy(user = "").validate())
    }
}
