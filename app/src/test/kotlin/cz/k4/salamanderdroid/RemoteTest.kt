package cz.k4.salamanderdroid

import cz.k4.salamanderdroid.core.*
import cz.k4.salamanderdroid.remote.*
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class ServersAndVaultTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun serversRoundTripAndValidate() {
        val servers = listOf(
            Server(name = "Web", host = "example.org", port = 2222, user = "root", auth = AuthMethod.Key, identity = "/k/id", initialDir = "/var/www", savePassword = true),
            Server(host = "h", user = "u"),
        )
        assertEquals(servers, Servers.parse(Servers.serialize(servers)))
        assertEquals("root@example.org:2222", servers[0].label())
        assertEquals("u@h", servers[1].displayName())
        assertNull(servers[0].validate())
        assertNotNull(Server(host = "-oProxyCommand=x", user = "u").validate())
        assertNotNull(Server(host = "h", user = "u", auth = AuthMethod.Key).validate())
        assertNotNull(Server(host = "", user = "u").validate())
        // Desktop file with ssh options still loads.
        val desktop = "[server]\nid=ab\nname=\nhost=h\nport=22\nuser=u\nauth=auto\nidentity=\ndir=\noptions=-o ProxyJump=b\nsave_password=false\n"
        assertEquals("-o ProxyJump=b", Servers.parse(desktop).single().options)
    }

    @Test
    fun vaultSealsPerServerAndChangesMaster() {
        val file = File(temp.root, "vault")
        assertNull(Vault.load(file))
        val (vault, key) = Vault.create(file, "correct horse".toCharArray(), KdfParams.TEST)
        vault.set(key, "srv1", "tajné heslo".toCharArray())
        vault.set(key, "srv2", "other".toCharArray())
        assertFalse(file.readText().contains("tajné"))

        val loaded = Vault.load(file)!!
        assertNull(loaded.unlock("wrong".toCharArray()))
        val unlocked = loaded.unlock("correct horse".toCharArray())!!
        assertEquals("tajné heslo", String(loaded.get(unlocked, "srv1")!!))
        assertNull(loaded.get(unlocked, "missing"))

        // An entry moved under another id must not decrypt.
        val swapped = file.readText().replace("entry.srv1=", "entry.tmp=").replace("entry.srv2=", "entry.srv1=")
        val tampered = File(temp.root, "tampered").apply { writeText(swapped) }
        val bad = Vault.load(tampered)!!
        try {
            bad.get(bad.unlock("correct horse".toCharArray())!!, "srv1")
            fail("tampered entry decrypted")
        } catch (_: IOException) {
        }

        val newKey = loaded.changeMaster(unlocked, "new master pw".toCharArray())
        assertEquals("other", String(loaded.get(newKey, "srv2")!!))
        val reloaded = Vault.load(file)!!
        assertNull(reloaded.unlock("correct horse".toCharArray()))
        assertEquals("tajné heslo", String(reloaded.get(reloaded.unlock("new master pw".toCharArray())!!, "srv1")!!))
        reloaded.remove("srv1")
        assertFalse(Vault.load(file)!!.contains("srv1"))
    }

    @Test
    fun logRotatesAndTails() {
        val log = RemoteLog(File(temp.root, "remote.log"))
        log.log("u@h", "připojeno", "/home\nx")
        assertTrue(log.tail().contains("u@h  připojeno  /home x"))
    }
}

class SftpTest {
    @get:Rule
    val temp = TemporaryFolder()
    private lateinit var sshd: SshServer
    private lateinit var root: File
    private var hostKeyAnswers = ArrayList<String>()
    private var passwords = ArrayDeque<String>()
    private var stored: String? = null
    private var trust = true

    private val prompts = object : RemotePrompts {
        override fun confirmHostKey(server: Server, keyType: String, fingerprint: String): Boolean {
            hostKeyAnswers += fingerprint
            return trust
        }

        override fun password(server: Server, failed: Boolean): PasswordAnswer? =
            passwords.removeFirstOrNull()?.let { PasswordAnswer(it.toCharArray(), true) }

        override fun passphrase(server: Server, keyFile: String): CharArray? = null
        override fun storedPassword(server: Server): CharArray? = stored?.toCharArray()
        override fun storePassword(server: Server, password: CharArray) {
            stored = String(password)
        }
    }

    @Before
    fun start() {
        root = temp.newFolder("remote")
        sshd = SshServer.setUpDefaultServer()
        sshd.host = "127.0.0.1"
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File(temp.root, "hostkey").toPath())
        sshd.setPasswordAuthenticator { user, password, _ -> user == "tester" && password == "secret" }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        sshd.start()
    }

    @After
    fun stop() {
        sshd.stop(true)
    }

    private fun connections() = Connections(KnownHosts(File(temp.root, "known_hosts")), RemoteLog(File(temp.root, "log")), prompts)
    private fun server() = Server(id = "s1", host = "127.0.0.1", port = sshd.port, user = "tester", savePassword = true)

    @Test
    fun connectsTransfersAndManagesFiles() {
        val connections = connections()
        passwords += listOf("wrong", "secret")
        val fs = connections.fs(server())
        assertEquals(emptyList<FileEntry>(), fs.list("/"))
        assertEquals(1, hostKeyAnswers.size)
        assertTrue(hostKeyAnswers[0].startsWith("SHA256:"))
        assertEquals("secret", stored)

        // Upload a tree, then download it back.
        val local = temp.newFolder("local")
        File(local, "dir/sub").mkdirs()
        File(local, "dir/a.txt").writeText("alpha")
        File(local, "dir/sub/big.bin").writeBytes(ByteArray(500_000) { (it % 251).toByte() })
        File(local, "dir/a.txt").setLastModified(1_600_000_000_000)
        val up = Transfer(fs, object : OpListener {}, AtomicBoolean(false)).copyTree(LocalFs, listOf(File(local, "dir").path), "/", false)
        assertTrue(up.errors.toString(), up.errors.isEmpty())
        assertEquals("alpha", File(root, "dir/a.txt").readText())
        assertEquals(500_000, File(root, "dir/sub/big.bin").length())
        val listed = fs.list("/dir").associateBy { it.name }
        assertEquals(EntryKind.Directory, listed.getValue("sub").kind)
        assertEquals(5L, listed.getValue("a.txt").size)
        assertEquals(1_600_000_000_000, listed.getValue("a.txt").modified)

        val back = temp.newFolder("back")
        val down = Transfer(LocalFs, object : OpListener {}, AtomicBoolean(false)).copyTree(fs, listOf("/dir"), back.path, false)
        assertTrue(down.errors.toString(), down.errors.isEmpty())
        assertArrayEquals(File(local, "dir/sub/big.bin").readBytes(), File(back, "dir/sub/big.bin").readBytes())

        // Remote move within the server is a rename; then delete.
        fs.mkdir("/moved")
        assertTrue(Transfer(fs, object : OpListener {}, AtomicBoolean(false)).copyTree(fs, listOf("/dir/a.txt"), "/moved", true).errors.isEmpty())
        assertNull(fs.stat("/dir/a.txt"))
        assertEquals("alpha", File(root, "moved/a.txt").readText())
        fs.setMode("/moved/a.txt", "600".toInt(8))
        assertEquals("600".toInt(8), fs.stat("/moved/a.txt")!!.mode)
        assertTrue(deleteTrees(fs, listOf("/dir", "/moved"), AtomicBoolean(false)).isEmpty())
        assertEquals(0, root.list()!!.size)
        assertNull(fs.stat("/nothing"))

        // Second session: known host key, stored password, no questions.
        connections.disconnect(server())
        hostKeyAnswers.clear()
        assertEquals(emptyList<FileEntry>(), connections().fs(server()).list("/"))
        assertTrue(hostKeyAnswers.isEmpty())
        connections.disconnectAll()
    }

    @Test
    fun rejectsUntrustedAndChangedHostKeys() {
        trust = false
        try {
            connections().fs(server()).list("/")
            fail("connected to untrusted host")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("nebyl potvrzen"))
        }
        // A different key recorded for the host means the key changed.
        val known = KnownHosts(File(temp.root, "known_hosts"))
        trust = true
        passwords += "secret"
        connections().fs(server()).list("/")
        val type = known.keysOf("127.0.0.1", sshd.port).keys.single()
        known.forget("127.0.0.1", sshd.port)
        known.add("127.0.0.1", sshd.port, type, "SHA256:somethingelse")
        hostKeyAnswers.clear()
        try {
            connections().fs(server()).list("/")
            fail("accepted changed host key")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("ZMĚNIL"))
        }
        assertTrue(hostKeyAnswers.isEmpty())
    }

    @Test
    fun cancelledPasswordPromptAbortsConnection() {
        try {
            connections().fs(server()).list("/")
            fail("connected without a password")
        } catch (error: IOException) {
            assertTrue(error is ConnectCancelled)
        }
    }
}
