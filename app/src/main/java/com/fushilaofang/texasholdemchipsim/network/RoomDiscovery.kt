package com.fushilaofang.texasholdemchipsim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private const val DISCOVERY_PORT = 45455
private const val BROADCAST_INTERVAL_MS = 2000L
private const val ROOM_EXPIRE_MS = 6000L

/**
 * 被局域网搜索发现的房间信息
 */
@Serializable
data class DiscoveredRoom(
    val roomName: String,
    val hostIp: String,
    val tcpPort: Int,
    val playerCount: Int,
    val hostName: String,
    val gameStarted: Boolean = false,
    val allowMidGameJoin: Boolean = false
) {
    /** 最后收到广播的时间（本机时间戳，不序列化） */
    @kotlinx.serialization.Transient
    var lastSeen: Long = System.currentTimeMillis()
}

/**
 * 房间广播数据（主持人每隔 2 秒发一次 UDP 广播）
 */
@Serializable
private data class RoomBroadcast(
    val roomName: String,
    val tcpPort: Int,
    val playerCount: Int,
    val hostName: String,
    val gameStarted: Boolean = false,
    val allowMidGameJoin: Boolean = false
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * 主持人端：定时 UDP 广播房间信息
 */
class RoomAdvertiser {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null

    fun startBroadcast(
        roomName: String,
        tcpPort: Int,
        hostName: String,
        playerCountProvider: () -> Int,
        gameStartedProvider: () -> Boolean = { false },
        allowMidGameJoinProvider: () -> Boolean = { false }
    ) {
        stopBroadcast()
        broadcastJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                val broadcastAddress = InetAddress.getByName("255.255.255.255")

                while (isActive) {
                    val packet = RoomBroadcast(
                        roomName = roomName,
                        tcpPort = tcpPort,
                        playerCount = playerCountProvider(),
                        hostName = hostName,
                        gameStarted = gameStartedProvider(),
                        allowMidGameJoin = allowMidGameJoinProvider()
                    )
                    val data = json.encodeToString(RoomBroadcast.serializer(), packet).toByteArray()
                    val dgram = DatagramPacket(data, data.size, broadcastAddress, DISCOVERY_PORT)
                    runCatching { socket.send(dgram) }
                    delay(BROADCAST_INTERVAL_MS)
                }
            } catch (_: Exception) {
                // 忽略广播异常
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    fun stopBroadcast() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    fun close() {
        stopBroadcast()
        scope.cancel()
    }
}

/**
 * 客户端：监听 UDP 广播，收集局域网内可加入的房间
 */
class RoomScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenJob: Job? = null

    fun startScan(onRoomsUpdated: (List<DiscoveredRoom>) -> Unit) {
        stopScan()
        listenJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 3000
                }
                val rooms = mutableMapOf<String, DiscoveredRoom>() // key = hostIp:roomName
                val buf = ByteArray(2048)

                while (isActive) {
                    val dgram = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(dgram)
                        val text = String(dgram.data, 0, dgram.length)
                        val broadcast = json.decodeFromString(RoomBroadcast.serializer(), text)
                        val hostIp = dgram.address.hostAddress ?: continue

                        val key = "$hostIp:${broadcast.roomName}"
                        val room = DiscoveredRoom(
                            roomName = broadcast.roomName,
                            hostIp = hostIp,
                            tcpPort = broadcast.tcpPort,
                            playerCount = broadcast.playerCount,
                            hostName = broadcast.hostName,
                            gameStarted = broadcast.gameStarted,
                            allowMidGameJoin = broadcast.allowMidGameJoin
                        ).also { it.lastSeen = System.currentTimeMillis() }
                        rooms[key] = room
                    } catch (_: java.net.SocketTimeoutException) {
                        // 超时继续，清理过期房间
                    }

                    // 清理过期
                    val now = System.currentTimeMillis()
                    rooms.entries.removeAll { now - it.value.lastSeen > ROOM_EXPIRE_MS }
                    onRoomsUpdated(rooms.values.toList())
                }
            } catch (_: Exception) {
                // 忽略
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    fun stopScan() {
        listenJob?.cancel()
        listenJob = null
    }

    fun close() {
        stopScan()
        scope.cancel()
    }
}
