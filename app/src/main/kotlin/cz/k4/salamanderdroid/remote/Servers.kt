package cz.k4.salamanderdroid.remote

import java.io.File
import java.security.SecureRandom

enum class AuthMethod(val key: String, val label: String) {
    /** Key when one is set, then password. */
    Auto("auto", "Automaticky (klíč, pak heslo)"),
    Password("password", "Heslo"),
    Key("key", "Soukromý klíč");

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.key == text } ?: Auto
    }
}

enum class Protocol(val key: String, val label: String, val defaultPort: Int) {
    Sftp("sftp", "SFTP (SSH)", 22),
    Smb("smb", "SMB (Windows, NAS)", 445);

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.key == text } ?: Sftp
    }
}

/** Remote server; the file format matches Salamander RS, so the list can be copied over. */
data class Server(
    val id: String = randomHex(8),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val auth: AuthMethod = AuthMethod.Auto,
    /** Path of the private key file. */
    val identity: String? = null,
    val initialDir: String = "",
    /** Extra ssh arguments of the desktop version; kept for file compatibility, unused here. */
    val options: String = "",
    /** Offer to keep the password in the vault. */
    val savePassword: Boolean = false,
    val protocol: Protocol = Protocol.Sftp,
    /** SMB: name of the shared folder. */
    val share: String = "",
    /** SMB: Windows domain or workgroup (optional). */
    val domain: String = "",
) {
    /** `user@host` or `user@host:port` for non-default ports. */
    fun label(): String {
        val prefix = if (user.isEmpty()) "" else "$user@"
        return if (port == protocol.defaultPort) "$prefix$host" else "$prefix$host:$port"
    }

    /** Address shown in panel paths, e.g. `sftp://user@host` or `smb://user@nas/share`. */
    fun url(): String = if (protocol == Protocol.Smb) "smb://${label()}/$share" else "sftp://${label()}"

    fun displayName(): String = name.ifEmpty { label() }

    fun validate(): String? {
        fun plain(value: String, what: String): String? =
            if (value.startsWith("-") || value.any { it.isWhitespace() || it.isISOControl() }) {
                "$what nesmí začínat „-“ ani obsahovat mezery"
            } else null
        if (host.isEmpty()) return "Zadejte adresu serveru"
        plain(host, "Adresa serveru")?.let { return it }
        plain(user, "Uživatelské jméno")?.let { return it }
        if (protocol == Protocol.Smb) {
            if (share.isEmpty()) return "Zadejte název sdílené složky"
            if (share.any { it == '/' || it == '\\' || it.isISOControl() }) return "Název sdílené složky nesmí obsahovat lomítka"
            if (domain.any { it.isWhitespace() || it.isISOControl() }) return "Doména nesmí obsahovat mezery"
        } else if (user.isEmpty()) {
            return "Zadejte uživatelské jméno"
        }
        if (port !in 1..65535) return "Neplatný port"
        for ((value, what) in listOf(name to "Název", initialDir to "Počáteční adresář", identity.orEmpty() to "Klíč")) {
            if (value.contains('\n') || value.contains('\r')) return "$what nesmí obsahovat konec řádku"
        }
        if (protocol == Protocol.Sftp && auth == AuthMethod.Key && identity.isNullOrEmpty()) {
            return "Pro ověření klíčem zadejte soubor se soukromým klíčem"
        }
        return null
    }
}

fun randomHex(bytes: Int): String {
    val data = ByteArray(bytes)
    SecureRandom().nextBytes(data)
    return data.toHex()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return try {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (_: NumberFormatException) {
        null
    }
}

object Servers {
    fun parse(text: String): List<Server> {
        val servers = ArrayList<Server>()
        var current: Server? = null
        for (rawLine in text.lines()) {
            val line = rawLine.trimEnd('\r')
            if (line == "[server]") {
                current?.let { servers += it }
                current = Server(id = "")
                continue
            }
            val server = current ?: continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val value = line.substring(eq + 1)
            current = when (line.substring(0, eq)) {
                "id" -> server.copy(id = value)
                "name" -> server.copy(name = value)
                "host" -> server.copy(host = value)
                "port" -> server.copy(port = value.toIntOrNull() ?: 22)
                "user" -> server.copy(user = value)
                "auth" -> server.copy(auth = AuthMethod.parse(value))
                "identity" -> server.copy(identity = value.takeIf { it.isNotEmpty() })
                "dir" -> server.copy(initialDir = value)
                "options" -> server.copy(options = value)
                "save_password" -> server.copy(savePassword = value == "true")
                "protocol" -> server.copy(protocol = Protocol.parse(value))
                "share" -> server.copy(share = value)
                "domain" -> server.copy(domain = value)
                else -> server
            }
        }
        current?.let { servers += it }
        return servers.filter { it.id.isNotEmpty() && it.host.isNotEmpty() }
    }

    fun serialize(servers: List<Server>): String {
        val text = StringBuilder("# Salamander — vzdálené servery (hesla jsou v šifrovaném trezoru)\n")
        for (server in servers) {
            text.append(
                "\n[server]\nid=${server.id}\nname=${server.name}\nhost=${server.host}\nport=${server.port}\n" +
                    "user=${server.user}\nauth=${server.auth.key}\nidentity=${server.identity.orEmpty()}\n" +
                    "dir=${server.initialDir}\noptions=${server.options}\nsave_password=${server.savePassword}\n" +
                    (if (server.protocol == Protocol.Smb) "protocol=smb\nshare=${server.share}\ndomain=${server.domain}\n" else "")
            )
        }
        return text.toString()
    }

    fun load(file: File): List<Server> = try {
        parse(file.readText())
    } catch (_: Exception) {
        emptyList()
    }

    fun save(file: File, servers: List<Server>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(serialize(servers))
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw java.io.IOException("Nelze uložit seznam serverů")
        }
    }
}
