package com.smartdesk.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SmartDeskConnection(
    private val onStatusChanged: (String) -> Unit,
    private val onServerMessage: (JSONObject) -> Unit,
    private val onPingResult: (Long) -> Unit
) {

    companion object {
        private const val TAG = "SmartDeskConnection"
    }

    private val connectionExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestId = AtomicInteger(1)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var connected = false

    @Volatile
    private var sessionToken: String? = null

    @Volatile
    private var pendingPingId: String? = null

    @Volatile
    private var pendingPingStartNs: Long = 0L

    fun connect(host: String, port: Int) {
        if (connected) {
            postStatus("Already connected")
            return
        }

        postStatus("Connecting...")

        connectionExecutor.execute {
            try {
                Log.d(TAG, "Connecting to $host:$port")

                val newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true

                newSocket.connect(
                    InetSocketAddress(host, port),
                    5000
                )

                socket = newSocket

                reader = BufferedReader(
                    InputStreamReader(
                        newSocket.getInputStream(),
                        Charsets.UTF_8
                    )
                )

                writer = BufferedWriter(
                    OutputStreamWriter(
                        newSocket.getOutputStream(),
                        Charsets.UTF_8
                    )
                )

                connected = true

                Log.d(TAG, "TCP connection established")
                postStatus("Authenticating...")

                readLoop()

            } catch (exception: Exception) {
                Log.e(TAG, "Connection error", exception)

                connected = false
                closeInternal()

                postStatus(
                    "Connection failed: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    private fun readLoop() {
        while (connected) {
            try {
                val currentReader = reader

                if (currentReader == null) {
                    connected = false
                    break
                }

                val line = currentReader.readLine()

                if (line == null) {
                    connected = false
                    break
                }

                Log.d(TAG, "Received: $line")
                handleServerMessage(line)

            } catch (exception: Exception) {
                if (connected) {
                    Log.e(TAG, "Reader error", exception)
                }

                connected = false
                break
            }
        }

        closeInternal()
        postStatus("Disconnected")
    }

    private fun handleServerMessage(line: String) {
        try {
            val json = JSONObject(line)

            val type = json.optString("type")
            val state = json.optString("state")

            if (type == "state" && state == "AUTHENTICATING") {
                postStatus("Pairing required")
            }

            val responseStatus = json.optString("status")
            val responseId = json.optString("id")

            if (
                responseStatus == "ok" &&
                responseId == pendingPingId
            ) {
                val startNs = pendingPingStartNs

                if (startNs != 0L) {
                    val elapsedNs =
                        System.nanoTime() - startNs

                    val latencyMs =
                        elapsedNs / 1_000_000L

                    pendingPingId = null
                    pendingPingStartNs = 0L

                    postPingResult(latencyMs)
                }
            }

            if (responseStatus == "ok") {
                val data = json.optJSONObject("data")

                if (data != null) {
                    val token = data.optString("token")
                    val returnedState = data.optString("state")

                    if (token.isNotEmpty()) {
                        sessionToken = token
                    }

                    if (returnedState == "ACTIVE") {
                        postStatus("Connected")
                    }
                }
            }

            if (responseStatus == "error") {
                val error = json.optJSONObject("error")

                val code = error?.optString("code")
                val message = error?.optString("message")

                if (responseId == pendingPingId) {
                    pendingPingId = null
                    pendingPingStartNs = 0L
                }

                postStatus(
                    when (code) {
                        "PAIRING_FAILED" -> "Pairing failed"
                        "HOST_BUSY" -> "Host is busy"
                        "SESSION_EXPIRED" -> "Session expired"
                        "NOT_AUTHENTICATED" -> "Not authenticated"
                        else -> message?.takeIf {
                            it.isNotEmpty()
                        } ?: "Server error"
                    }
                )
            }

            postServerMessage(json)

        } catch (exception: Exception) {
            Log.e(TAG, "Invalid server response", exception)
        }
    }

    fun pair(code: String) {
        val cleanCode = code.trim()

        if (
            cleanCode.length != 6 ||
            !cleanCode.all { it.isDigit() }
        ) {
            postStatus("Pairing code must contain 6 digits")
            return
        }

        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) {
                    postStatus("Not connected")
                    return@execute
                }

                val data = JSONObject()
                data.put("code", cleanCode)

                sendMessage(
                    type = "auth",
                    action = "pair",
                    data = data,
                    includeToken = false
                )

                postStatus("Pairing...")

            } catch (exception: Exception) {
                postStatus(
                    "Pairing failed: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    fun ping() {
        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) {
                    postStatus("Not connected")
                    return@execute
                }

                if (sessionToken.isNullOrEmpty()) {
                    postStatus("No session token")
                    return@execute
                }

                val id = requestId
                    .getAndIncrement()
                    .toString()

                pendingPingId = id
                pendingPingStartNs = System.nanoTime()

                sendMessage(
                    id = id,
                    type = "system",
                    action = "ping",
                    data = JSONObject(),
                    includeToken = true
                )

            } catch (exception: Exception) {
                pendingPingId = null
                pendingPingStartNs = 0L

                postStatus(
                    "Ping failed: ${
                        exception.message
                            ?: exception.javaClass.simpleName
                    }"
                )
            }
        }
    }

    fun leftClick() {
        sendMouseClick("left_click")
    }

    fun rightClick() {
        sendMouseClick("right_click")
    }

    fun doubleClick() {
        sendMouseClick("double_click")
    }

    private fun sendMouseClick(action: String) {
        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) {
                    postStatus("Not connected")
                    return@execute
                }

                sendMessage(
                    type = "mouse",
                    action = action,
                    data = JSONObject(),
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Mouse click failed", exception)
            }
        }
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return

        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                sendMessage(
                    type = "mouse",
                    action = "move",
                    data = JSONObject().apply {
                        put("dx", dx)
                        put("dy", dy)
                    },
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Mouse movement failed", exception)
            }
        }
    }

    fun scroll(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return

        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                sendMessage(
                    type = "mouse",
                    action = "scroll",
                    data = JSONObject().apply {
                        put("dx", dx)
                        put("dy", dy)
                    },
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Scroll failed", exception)
            }
        }
    }

    fun keyCombo(keys: List<String>) {
        if (keys.isEmpty()) return

        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                val pressed = mutableListOf<String>()

                for (key in keys) {
                    sendMessage(
                        type = "keyboard",
                        action = "press",
                        data = JSONObject().apply {
                            put("key", key)
                        },
                        includeToken = true
                    )

                    pressed.add(key)
                }

                for (key in pressed.asReversed()) {
                    sendMessage(
                        type = "keyboard",
                        action = "release",
                        data = JSONObject().apply {
                            put("key", key)
                        },
                        includeToken = true
                    )
                }

            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Keyboard combination failed",
                    exception
                )
            }
        }
    }

    fun keyPress(key: String) {
        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                sendMessage(
                    type = "keyboard",
                    action = "press",
                    data = JSONObject().apply {
                        put("key", key)
                    },
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Key press failed", exception)
            }
        }
    }

    fun keyRelease(key: String) {
        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                sendMessage(
                    type = "keyboard",
                    action = "release",
                    data = JSONObject().apply {
                        put("key", key)
                    },
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Key release failed", exception)
            }
        }
    }

    fun typeText(text: String) {
        if (text.isEmpty()) return

        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                for (character in text) {

                    val key = when (character) {
                        '\n' -> "ENTER"
                        '\r' -> "ENTER"
                        '\t' -> "TAB"
                        else -> character.toString()
                    }

                    sendMessage(
                        type = "keyboard",
                        action = "press",
                        data = JSONObject().apply {
                            put("key", key)
                        },
                        includeToken = true
                    )

                    sendMessage(
                        type = "keyboard",
                        action = "release",
                        data = JSONObject().apply {
                            put("key", key)
                        },
                        includeToken = true
                    )
                }

            } catch (exception: Exception) {
                Log.e(TAG, "Text typing failed", exception)
            }
        }
    }

    fun backspace() {
        keyCombo(
            listOf("BACKSPACE")
        )
    }

    fun volumeUp() {
        sendMedia("volume_up")
    }

    fun volumeDown() {
        sendMedia("volume_down")
    }

    fun playPause() {
        sendMedia("play_pause")
    }

    fun mute() {
        sendMedia("mute")
    }

    private fun sendMedia(action: String) {
        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) return@execute

                sendMessage(
                    type = "media",
                    action = action,
                    data = JSONObject(),
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Media command failed: $action",
                    exception
                )
            }
        }
    }

    fun send(
        type: String,
        action: String,
        data: JSONObject = JSONObject()
    ) {
        sendExecutor.execute {
            try {
                if (!isSocketActuallyConnected()) {
                    postStatus("Not connected")
                    return@execute
                }

                sendMessage(
                    type = type,
                    action = action,
                    data = data,
                    includeToken = true
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Send failed", exception)
            }
        }
    }

    private fun sendMessage(
        id: String = requestId
            .getAndIncrement()
            .toString(),
        type: String,
        action: String,
        data: JSONObject,
        includeToken: Boolean
    ) {
        val currentWriter = writer
            ?: throw IllegalStateException(
                "TCP writer unavailable"
            )

        val message = JSONObject()

        message.put("version", "1.0")
        message.put("id", id)
        message.put("type", type)
        message.put("action", action)

        if (includeToken) {
            val token = sessionToken

            if (token.isNullOrEmpty()) {
                throw IllegalStateException(
                    "Session token unavailable"
                )
            }

            message.put("token", token)
        }

        message.put("data", data)

        synchronized(currentWriter) {
            currentWriter.write(message.toString())
            currentWriter.write("\n")
            currentWriter.flush()
        }
    }

    private fun isSocketActuallyConnected(): Boolean {
        val currentSocket = socket

        return connected &&
                currentSocket != null &&
                currentSocket.isConnected &&
                !currentSocket.isClosed
    }

    fun disconnect() {
        connected = false
        closeInternal()
        postStatus("Disconnected")
    }

    fun isConnected(): Boolean {
        return isSocketActuallyConnected()
    }

    fun hasSession(): Boolean {
        return !sessionToken.isNullOrEmpty()
    }

    private fun closeInternal() {
        try {
            reader?.close()
        } catch (_: Exception) {
        }

        try {
            writer?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        reader = null
        writer = null
        socket = null
    }

    private fun postStatus(status: String) {
        mainHandler.post {
            onStatusChanged(status)
        }
    }

    private fun postServerMessage(message: JSONObject) {
        mainHandler.post {
            onServerMessage(message)
        }
    }

    private fun postPingResult(latencyMs: Long) {
        mainHandler.post {
            onPingResult(latencyMs)
        }
    }
}