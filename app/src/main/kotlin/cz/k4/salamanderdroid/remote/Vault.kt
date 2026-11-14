package cz.k4.salamanderdroid.remote

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Argon2id cost: memory in KiB and passes. */
data class KdfParams(val memoryKib: Int = 64 * 1024, val iterations: Int = 3) {
    companion object {
        /** Cheap parameters for tests only. */
        val TEST = KdfParams(256, 1)
    }
}

/**
 * Encrypted password store. Every password is sealed separately with AES-256-GCM and bound
 * to its server id; the key is derived from the master password with Argon2id. The master
 * password is never stored — a sealed check value tells whether it was right.
 */
class Vault private constructor(
    private val file: File,
    private var params: KdfParams,
    private var salt: ByteArray,
    private var check: ByteArray,
    private val entries: LinkedHashMap<String, ByteArray>,
) {
    class Key internal constructor(internal val bytes: ByteArray) {
        /** Overwrites the key material. */
        fun wipe() = bytes.fill(0)
    }

    fun contains(id: String): Boolean = entries.containsKey(id)

    /** Ids of the servers that have a password stored. */
    fun ids(): Set<String> = entries.keys.toSet()

    /** Null when the master password is wrong. */
    fun unlock(master: CharArray): Key? {
        val key = derive(master, salt, params)
        return try {
            if (open(key, check, CHECK_AD).contentEquals(CHECK_PLAINTEXT)) Key(key) else null
        } catch (_: AEADBadTagException) {
            key.fill(0)
            null
        }
    }

    fun get(key: Key, id: String): CharArray? {
        val sealed = entries[id] ?: return null
        val plain = try {
            open(key.bytes, sealed, id.toByteArray())
        } catch (_: AEADBadTagException) {
            throw IOException("Uložené heslo je poškozené")
        }
        return String(plain, Charsets.UTF_8).toCharArray().also { plain.fill(0) }
    }

    fun set(key: Key, id: String, secret: CharArray) {
        entries[id] = seal(key.bytes, String(secret).toByteArray(), id.toByteArray())
        save()
    }

    fun remove(id: String) {
        if (entries.remove(id) != null) save()
    }

    /** Re-encrypts everything under a key derived from the new master password. */
    fun changeMaster(key: Key, newMaster: CharArray): Key {
        val plain = entries.mapValues { (id, sealed) -> open(key.bytes, sealed, id.toByteArray()) }
        val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val newKey = derive(newMaster, newSalt, params)
        salt = newSalt
        check = seal(newKey, CHECK_PLAINTEXT, CHECK_AD)
        for ((id, secret) in plain) {
            entries[id] = seal(newKey, secret, id.toByteArray())
            secret.fill(0)
        }
        save()
        return Key(newKey)
    }

    private fun save() {
        val text = StringBuilder("$HEADER\n")
        text.append("memory_kib=${params.memoryKib}\niterations=${params.iterations}\n")
        text.append("salt=${salt.toHex()}\ncheck=${check.toHex()}\n")
        for ((id, sealed) in entries) text.append("entry.$id=${sealed.toHex()}\n")
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeText(text.toString())
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IOException("Trezor nelze uložit")
        }
    }

    companion object {
        private const val HEADER = "salamander-droid-vault 1"
        private val CHECK_PLAINTEXT = "salamander-droid-vault-ok".toByteArray()
        private val CHECK_AD = "salamander-droid-vault-check".toByteArray()
        const val MIN_MASTER_LENGTH = 8

        /** Null when no vault exists yet; throws when the file is damaged. */
        fun load(file: File): Vault? {
            if (!file.exists()) return null
            val lines = file.readLines()
            if (lines.firstOrNull() != HEADER) throw IOException("Soubor trezoru má neznámý formát")
            var memory = 0
            var iterations = 0
            var salt: ByteArray? = null
            var check: ByteArray? = null
            val entries = LinkedHashMap<String, ByteArray>()
            for (line in lines.drop(1)) {
                val eq = line.indexOf('=')
                if (eq < 0) continue
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                when {
                    key == "memory_kib" -> memory = value.toIntOrNull() ?: 0
                    key == "iterations" -> iterations = value.toIntOrNull() ?: 0
                    key == "salt" -> salt = value.hexToBytes()
                    key == "check" -> check = value.hexToBytes()
                    key.startsWith("entry.") -> value.hexToBytes()?.let { entries[key.removePrefix("entry.")] = it }
                }
            }
            if (memory !in 8..(1 shl 21) || iterations !in 1..64 || salt == null || check == null) {
                throw IOException("Soubor trezoru je poškozený")
            }
            return Vault(file, KdfParams(memory, iterations), salt, check, entries)
        }

        fun create(file: File, master: CharArray, params: KdfParams = KdfParams()): Pair<Vault, Key> {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = derive(master, salt, params)
            val vault = Vault(file, params, salt, seal(key, CHECK_PLAINTEXT, CHECK_AD), LinkedHashMap())
            vault.save()
            return vault to Key(key)
        }

        private fun derive(master: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withSalt(salt)
                    .withMemoryAsKB(params.memoryKib)
                    .withIterations(params.iterations)
                    .withParallelism(1)
                    .build()
            )
            val key = ByteArray(32)
            generator.generateBytes(master, key)
            return key
        }

        private fun seal(key: ByteArray, plain: ByteArray, ad: ByteArray): ByteArray {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(ad)
            return nonce + cipher.doFinal(plain)
        }

        private fun open(key: ByteArray, sealed: ByteArray, ad: ByteArray): ByteArray {
            if (sealed.size < 12 + 16) throw AEADBadTagException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
            cipher.updateAAD(ad)
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }
}
