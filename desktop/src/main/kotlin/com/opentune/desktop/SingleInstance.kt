package com.opentune.desktop

import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * One OpenTune at a time. The first to start holds a lock on ~/.opentune/instance.lock and
 * listens on the loopback address; a second start finds the lock taken, hands its arguments
 * (an opentune:// link, or nothing) to the first and quits, and the first comes to the front.
 *
 * The port and a random token are in ~/.opentune/instance.json, readable by this user only;
 * what comes in without that token is ignored, so another user on the computer can't drive it.
 */
internal class SingleInstance(private val dir: File, private val onArgs: (List<String>) -> Unit) {
    private var lock: FileLock? = null
    private var channel: FileChannel? = null
    private var server: ServerSocket? = null
    private val info get() = File(dir, "instance.json")

    /** True when this is the only one running (and now holds the lock); false when another is. */
    fun claim(): Boolean {
        dir.mkdirs()
        val c = FileChannel.open(File(dir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val held = try {
            c.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (held == null) {
            c.close()
            return false
        }
        channel = c
        lock = held
        listen()
        return true
    }

    private fun listen() {
        val s = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = s
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        OwnerOnly.write(info, JSONObject().put("port", s.localPort).put("token", token).toString().toByteArray())
        Thread({
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                runCatching {
                    client.use {
                        it.soTimeout = 2_000
                        val line = it.getInputStream().bufferedReader().readLine()?.take(8_192) ?: return@use
                        val message = JSONObject(line)
                        // Compared in constant time, so the token can't be guessed a character at a time.
                        if (!MessageDigest.isEqual(message.optString("token").toByteArray(), token.toByteArray())) return@use
                        val args = message.optJSONArray("args") ?: JSONArray()
                        onArgs((0 until minOf(args.length(), 8)).map { i -> args.optString(i).take(512) })
                        it.getOutputStream().write("ok\n".toByteArray())
                    }
                }
            }
        }, "single-instance").apply { isDaemon = true; start() }
    }

    /** Hands [args] to the one already running; true when it took them. */
    fun handOff(args: List<String>): Boolean = runCatching {
        val known = JSONObject(info.readText())
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), known.getInt("port")), 2_000)
            s.soTimeout = 3_000
            val message = JSONObject().put("token", known.getString("token")).put("args", JSONArray(args))
            s.getOutputStream().write((message.toString() + "\n").toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine() == "ok"
        }
    }.getOrDefault(false)

    fun release() {
        runCatching { server?.close() }
        runCatching { info.delete() }
        runCatching { lock?.release() }
        runCatching { channel?.close() }
    }
}
