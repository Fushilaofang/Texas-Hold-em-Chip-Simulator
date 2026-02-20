package com.fushilaofang.texasholdemchipsim.network

import com.fushilaofang.texasholdemchipsim.model.ChipTransaction
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val DEFAULT_PORT = 45454
private const val HEARTBEAT_INTERVAL_MS = 3_000L   // 每 3 秒发一次 ping
private const val HEARTBEAT_TIMEOUT_MS = 60_000L    // 60 秒无 pong 才视为掉线

class LanTableServer(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private val clients = mutableMapOf<String, ClientConnection>()
    private val clientsLock = Any()

    /** 掉线但尚未超时的玩家，记录掉线时间戳 */
    private val disconnectedPlayers = mutableMapOf<String, Long>()
    private val disconnectedLock = Any()

    sealed class Event {
        data class PlayerJoined(val player: PlayerState) : Event()
        data class PlayerDisconnected(val playerId: String) : Event()
        data class PlayerReconnected(val playerId: String) : Event()
        data class ContributionReceived(val playerId: String, val amount: Int) : Event()
        data class ReadyToggleReceived(val playerId: String, val isReady: Boolean) : Event()
        data class WinToggleReceived(val playerId: String, val isWinner: Boolean) : Event()
        data class FoldReceived(val playerId: String) : Event()
        data class ProfileUpdateReceived(val playerId: String, val newName: String, val avatarBase64: String) : Event()
        /** 同一设备以新昵称/头像重新进入房间，应复用原有玩家槽位 */
        data class PlayerRejoinedByDevice(val playerId: String, val newName: String, val avatarBase64: String) : Event()
        data class Error(val message: String) : Event()
    }

    data class ClientConnection(
        val playerId: String,
        val socket: Socket,
        val writer: BufferedWriter,
        val readerJob: Job,
        @Volatile var lastPongTime: Long = System.currentTimeMillis()
    )

    private var heartbeatJob: Job? = null

    fun start(
        hostPlayersProvider: () -> List<PlayerState>,
        handCounterProvider: () -> Int,
        txProvider: () -> List<ChipTransaction>,
        contributionsProvider: () -> Map<String, Int> = { emptyMap() },
        blindsStateProvider: () -> com.fushilaofang.texasholdemchipsim.blinds.BlindsState = { com.fushilaofang.texasholdemchipsim.blinds.BlindsState() },
        blindsEnabledProvider: () -> Boolean = { true },
        sidePotEnabledProvider: () -> Boolean = { true },
        selectedWinnerIdsProvider: () -> Set<String> = { emptySet() },
        foldedPlayerIdsProvider: () -> Set<String> = { emptySet() },
        gameStartedProvider: () -> Boolean = { false },
        currentRoundProvider: () -> String = { "PRE_FLOP" },
        currentTurnPlayerIdProvider: () -> String = { "" },
        roundContributionsProvider: () -> Map<String, Int> = { emptyMap() },
        actedPlayerIdsProvider: () -> Set<String> = { emptySet() },
        onPlayerJoined: (PlayerState) -> Unit,
        onEvent: (Event) -> Unit
    ) {
        stop()

        // 接受新连接
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(DEFAULT_PORT))
                serverSocket = ss
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(
                            socket = socket,
                            hostPlayersProvider = hostPlayersProvider,
                            handCounterProvider = handCounterProvider,
                            txProvider = txProvider,
                            contributionsProvider = contributionsProvider,
                            blindsStateProvider = blindsStateProvider,
                            blindsEnabledProvider = blindsEnabledProvider,
                            sidePotEnabledProvider = sidePotEnabledProvider,
                            selectedWinnerIdsProvider = selectedWinnerIdsProvider,
                            foldedPlayerIdsProvider = foldedPlayerIdsProvider,
                            gameStartedProvider = gameStartedProvider,
                            currentRoundProvider = currentRoundProvider,
                            currentTurnPlayerIdProvider = currentTurnPlayerIdProvider,
                            roundContributionsProvider = roundContributionsProvider,
                            actedPlayerIdsProvider = actedPlayerIdsProvider,
                            onPlayerJoined = onPlayerJoined,
                            onEvent = onEvent
                        )
                    }
                }
            } catch (ex: Exception) {
                onEvent(Event.Error("服务端异常: ${ex.message ?: "未知错误"}"))
            }
        }

        // 心跳检测循环
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val pingText = json.encodeToString(NetworkMessage.serializer(), NetworkMessage.Ping)
                val stale = mutableListOf<String>()

                synchronized(clientsLock) {
                    clients.forEach { (id, conn) ->
                        // 检查是否超时
                        if (now - conn.lastPongTime > HEARTBEAT_TIMEOUT_MS) {
                            stale.add(id)
                        } else {
                            // 发送 ping
                            try {
                                conn.writer.write(pingText)
                                conn.writer.newLine()
                                conn.writer.flush()
                            } catch (_: Exception) {
                                stale.add(id)
                            }
                        }
                    }
                    stale.forEach { id ->
                        val conn = clients.remove(id)
                        conn?.let {
                            it.readerJob.cancel()
                            runCatching { it.socket.close() }
                        }
                    }
                }
                // 标记为掉线（进入等待重连期）
                stale.forEach { id ->
                    synchronized(disconnectedLock) {
                        disconnectedPlayers[id] = now
                    }
                    onEvent(Event.PlayerDisconnected(id))
                }
            }
        }

    }

    fun broadcastState(
        players: List<PlayerState>,
        handCounter: Int,
        transactions: List<ChipTransaction>,
        contributions: Map<String, Int> = emptyMap(),
        blindsState: com.fushilaofang.texasholdemchipsim.blinds.BlindsState = com.fushilaofang.texasholdemchipsim.blinds.BlindsState(),
        blindsEnabled: Boolean = true,
        sidePotEnabled: Boolean = true,
        selectedWinnerIds: Set<String> = emptySet(),
        foldedPlayerIds: Set<String> = emptySet(),
        gameStarted: Boolean = false,
        currentRound: String = "PRE_FLOP",
        currentTurnPlayerId: String = "",
        roundContributions: Map<String, Int> = emptyMap(),
        actedPlayerIds: Set<String> = emptySet(),
        initialDealerIndex: Int = 0
    ) {
        val message = NetworkMessage.StateSync(
            players = players,
            handCounter = handCounter,
            transactions = transactions.take(50),
            contributions = contributions,
            blindsState = blindsState,
            blindsEnabled = blindsEnabled,
            sidePotEnabled = sidePotEnabled,
            selectedWinnerIds = selectedWinnerIds,
            foldedPlayerIds = foldedPlayerIds,
            gameStarted = gameStarted,
            currentRound = currentRound,
            currentTurnPlayerId = currentTurnPlayerId,
            roundContributions = roundContributions,
            actedPlayerIds = actedPlayerIds,
            initialDealerIndex = initialDealerIndex
        )
        val text = json.encodeToString(NetworkMessage.serializer(), message)
        val stale = mutableListOf<String>()

        synchronized(clientsLock) {
            clients.forEach { (id, conn) ->
                try {
                    conn.writer.write(text)
                    conn.writer.newLine()
                    conn.writer.flush()
                } catch (_: Exception) {
                    stale.add(id)
                }
            }
            stale.forEach { id ->
                val conn = clients.remove(id)
                conn?.let {
                    it.readerJob.cancel()
                    runCatching { it.socket.close() }
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        synchronized(disconnectedLock) { disconnectedPlayers.clear() }
        synchronized(clientsLock) {
            clients.values.forEach {
                runCatching { it.socket.close() }
                it.readerJob.cancel()
            }
            clients.clear()
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun handleClient(
        socket: Socket,
        hostPlayersProvider: () -> List<PlayerState>,
        handCounterProvider: () -> Int,
        txProvider: () -> List<ChipTransaction>,
        contributionsProvider: () -> Map<String, Int>,
        blindsStateProvider: () -> com.fushilaofang.texasholdemchipsim.blinds.BlindsState,
        blindsEnabledProvider: () -> Boolean,
        sidePotEnabledProvider: () -> Boolean,
        selectedWinnerIdsProvider: () -> Set<String>,
        foldedPlayerIdsProvider: () -> Set<String>,
        gameStartedProvider: () -> Boolean,
        currentRoundProvider: () -> String,
        currentTurnPlayerIdProvider: () -> String,
        roundContributionsProvider: () -> Map<String, Int>,
        actedPlayerIdsProvider: () -> Set<String>,
        onPlayerJoined: (PlayerState) -> Unit,
        onEvent: (Event) -> Unit
    ) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        // 启用 TCP keepalive，让 OS 维护连接状态
        runCatching { socket.keepAlive = true }
        var assignedId: String? = null

        val readerJob = scope.launch {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = json.decodeFromString(NetworkMessage.serializer(), line)
                    when (msg) {
                        is NetworkMessage.Pong -> {
                            // 更新最后心跳时间
                            val id = assignedId ?: continue
                            synchronized(clientsLock) {
                                clients[id]?.lastPongTime = System.currentTimeMillis()
                            }
                        }

                        is NetworkMessage.SubmitContribution -> {
                            onEvent(Event.ContributionReceived(msg.playerId, msg.amount))
                        }

                        is NetworkMessage.ReadyToggle -> {
                            onEvent(Event.ReadyToggleReceived(msg.playerId, msg.isReady))
                        }

                        is NetworkMessage.WinToggle -> {
                            onEvent(Event.WinToggleReceived(msg.playerId, msg.isWinner))
                        }

                        is NetworkMessage.Fold -> {
                            onEvent(Event.FoldReceived(msg.playerId))
                        }

                        is NetworkMessage.UpdateProfile -> {
                            onEvent(Event.ProfileUpdateReceived(msg.playerId, msg.newName, msg.avatarBase64))
                        }

                        is NetworkMessage.Reconnect -> {
                            val oldId = msg.playerId
                            // 检查是否在掉线等待列表中
                            val isWaitingReconnect = synchronized(disconnectedLock) {
                                disconnectedPlayers.containsKey(oldId)
                            }
                            // 也检查是否存在于玩家列表
                            val existsInPlayers = hostPlayersProvider().any { it.id == oldId }

                            if (isWaitingReconnect || existsInPlayers) {
                                assignedId = oldId
                                synchronized(disconnectedLock) { disconnectedPlayers.remove(oldId) }

                                // 发送重连成功
                                val accepted = NetworkMessage.ReconnectAccepted(playerId = oldId)
                                writer.write(json.encodeToString(NetworkMessage.serializer(), accepted))
                                writer.newLine()
                                writer.flush()

                                // 发送最新状态
                                val sync = NetworkMessage.StateSync(
                                    players = hostPlayersProvider(),
                                    handCounter = handCounterProvider(),
                                    transactions = txProvider().takeLast(50),
                                    contributions = contributionsProvider(),
                                    blindsState = blindsStateProvider(),
                                    blindsEnabled = blindsEnabledProvider(),
                                    sidePotEnabled = sidePotEnabledProvider(),
                                    selectedWinnerIds = selectedWinnerIdsProvider(),
                                    foldedPlayerIds = foldedPlayerIdsProvider(),
                                    gameStarted = gameStartedProvider(),
                                    currentRound = currentRoundProvider(),
                                    currentTurnPlayerId = currentTurnPlayerIdProvider(),
                                    roundContributions = roundContributionsProvider(),
                                    actedPlayerIds = actedPlayerIdsProvider()
                                )
                                writer.write(json.encodeToString(NetworkMessage.serializer(), sync))
                                writer.newLine()
                                writer.flush()

                                // 更新客户端连接
                                synchronized(clientsLock) {
                                    // 关闭旧连接（如有）
                                    clients.remove(oldId)?.let { old ->
                                        old.readerJob.cancel()
                                        runCatching { old.socket.close() }
                                    }
                                    clients[oldId] = ClientConnection(
                                        playerId = oldId,
                                        socket = socket,
                                        writer = writer,
                                        readerJob = this.coroutineContext[Job]
                                            ?: error("missing coroutine job")
                                    )
                                }

                                onEvent(Event.PlayerReconnected(oldId))
                            } else {
                                // 找不到该玩家，拒绝重连
                                val err = NetworkMessage.Error(reason = "重连失败: 找不到玩家记录")
                                writer.write(json.encodeToString(NetworkMessage.serializer(), err))
                                writer.newLine()
                                writer.flush()
                                socket.close()
                                return@launch
                            }
                        }

                        is NetworkMessage.JoinRequest -> {
                            // 先检查是否同一台设备重新进入（此时不限制游戏是否已开始）
                            val existingByDevice = if (msg.deviceId.isNotBlank()) {
                                hostPlayersProvider().firstOrNull {
                                    it.deviceId.isNotBlank() && it.deviceId == msg.deviceId
                                }
                            } else null

                            if (existingByDevice != null) {
                                // 同一设备重新加入：复用原有槽位和 ID
                                val oldId = existingByDevice.id
                                assignedId = oldId
                                synchronized(disconnectedLock) { disconnectedPlayers.remove(oldId) }

                                val accepted = NetworkMessage.JoinAccepted(assignedPlayerId = oldId)
                                writer.write(json.encodeToString(NetworkMessage.serializer(), accepted))
                                writer.newLine()
                                writer.flush()

                                val sync = NetworkMessage.StateSync(
                                    players = hostPlayersProvider(),
                                    handCounter = handCounterProvider(),
                                    transactions = txProvider().takeLast(50),
                                    contributions = contributionsProvider(),
                                    blindsState = blindsStateProvider(),
                                    blindsEnabled = blindsEnabledProvider(),
                                    sidePotEnabled = sidePotEnabledProvider(),
                                    selectedWinnerIds = selectedWinnerIdsProvider(),
                                    foldedPlayerIds = foldedPlayerIdsProvider(),
                                    gameStarted = gameStartedProvider(),
                                    currentRound = currentRoundProvider(),
                                    currentTurnPlayerId = currentTurnPlayerIdProvider(),
                                    roundContributions = roundContributionsProvider(),
                                    actedPlayerIds = actedPlayerIdsProvider()
                                )
                                writer.write(json.encodeToString(NetworkMessage.serializer(), sync))
                                writer.newLine()
                                writer.flush()

                                synchronized(clientsLock) {
                                    clients.remove(oldId)?.let { old ->
                                        old.readerJob.cancel()
                                        runCatching { old.socket.close() }
                                    }
                                    clients[oldId] = ClientConnection(
                                        playerId = oldId,
                                        socket = socket,
                                        writer = writer,
                                        readerJob = this.coroutineContext[Job]
                                            ?: error("missing coroutine job")
                                    )
                                }

                                onEvent(Event.PlayerRejoinedByDevice(oldId, msg.playerName, ""))
                            } else {
                                // 全新玩家加入：游戏进行中不允许
                                if (gameStartedProvider()) {
                                    val err = NetworkMessage.Error(reason = "游戏已开始，无法加入")
                                    writer.write(json.encodeToString(NetworkMessage.serializer(), err))
                                    writer.newLine()
                                    writer.flush()
                                    socket.close()
                                    return@launch
                                }
                                val playerId = UUID.randomUUID().toString()
                                assignedId = playerId
                                val seatOrder = hostPlayersProvider().size
                                val joined = PlayerState(
                                    id = playerId,
                                    name = msg.playerName,
                                    chips = msg.buyIn,
                                    seatOrder = seatOrder,
                                    deviceId = msg.deviceId
                                )
                                onPlayerJoined(joined)

                                val accepted = NetworkMessage.JoinAccepted(assignedPlayerId = playerId)
                                writer.write(json.encodeToString(NetworkMessage.serializer(), accepted))
                                writer.newLine()
                                writer.flush()

                                val sync = NetworkMessage.StateSync(
                                    players = hostPlayersProvider(),
                                    handCounter = handCounterProvider(),
                                    transactions = txProvider().takeLast(50),
                                    contributions = contributionsProvider(),
                                    blindsState = blindsStateProvider(),
                                    blindsEnabled = blindsEnabledProvider(),
                                    sidePotEnabled = sidePotEnabledProvider(),
                                    selectedWinnerIds = selectedWinnerIdsProvider(),
                                    foldedPlayerIds = foldedPlayerIdsProvider(),
                                    gameStarted = gameStartedProvider(),
                                    currentRound = currentRoundProvider(),
                                    currentTurnPlayerId = currentTurnPlayerIdProvider(),
                                    roundContributions = roundContributionsProvider(),
                                    actedPlayerIds = actedPlayerIdsProvider()
                                )
                                writer.write(json.encodeToString(NetworkMessage.serializer(), sync))
                                writer.newLine()
                                writer.flush()

                                synchronized(clientsLock) {
                                    clients[playerId] = ClientConnection(
                                        playerId = playerId,
                                        socket = socket,
                                        writer = writer,
                                        readerJob = this.coroutineContext[Job]
                                            ?: error("missing coroutine job")
                                    )
                                }

                                onEvent(Event.PlayerJoined(joined))
                            }
                        }

                        else -> Unit
                    }
                }
            } catch (ex: Exception) {
                onEvent(Event.Error("客户端处理失败: ${ex.message ?: "未知错误"}"))
            } finally {
                val id = assignedId
                if (id != null) {
                    synchronized(clientsLock) { clients.remove(id) }
                    // 掉线进入等待重连期，不立即移除玩家
                    synchronized(disconnectedLock) {
                        disconnectedPlayers[id] = System.currentTimeMillis()
                    }
                    onEvent(Event.PlayerDisconnected(id))
                }
                runCatching { socket.close() }
            }
        }

        readerJob.join()
    }
}

class LanTableClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private var listenJob: Job? = null
    private var heartbeatJob: Job? = null

    /** 保存连接信息，用于重连 */
    private var lastHostIp: String? = null
    private var lastPlayerName: String? = null
    private var lastBuyIn: Int = 0
    private var lastDeviceId: String = ""
    private var assignedPlayerId: String? = null
    private var reconnecting = false
    private var shouldReconnect = true

    sealed class Event {
        data class JoinAccepted(val playerId: String) : Event()
        data class StateSync(
            val players: List<PlayerState>,
            val handCounter: Int,
            val transactions: List<ChipTransaction>,
            val contributions: Map<String, Int> = emptyMap(),
            val blindsState: com.fushilaofang.texasholdemchipsim.blinds.BlindsState = com.fushilaofang.texasholdemchipsim.blinds.BlindsState(),
            val blindsEnabled: Boolean = true,
            val sidePotEnabled: Boolean = true,
            val selectedWinnerIds: Set<String> = emptySet(),
            val foldedPlayerIds: Set<String> = emptySet(),
            val gameStarted: Boolean = false,
            val currentRound: String = "PRE_FLOP",
            val currentTurnPlayerId: String = "",
            val roundContributions: Map<String, Int> = emptyMap(),
            val actedPlayerIds: Set<String> = emptySet(),
            val initialDealerIndex: Int = 0
        ) : Event()

        data class Disconnected(val willReconnect: Boolean) : Event()
        data class Reconnected(val playerId: String) : Event()
        data class ReconnectFailed(val reason: String) : Event()
        data class Error(val message: String) : Event()
    }

    @Volatile
    private var writer: BufferedWriter? = null

    fun connect(hostIp: String, playerName: String, buyIn: Int, deviceId: String = "", onEvent: (Event) -> Unit) {
        disconnect()
        lastHostIp = hostIp
        lastPlayerName = playerName
        lastBuyIn = buyIn
        lastDeviceId = deviceId
        assignedPlayerId = null
        reconnecting = false
        shouldReconnect = true

        doConnect(hostIp, playerName, buyIn, deviceId, isReconnect = false, onEvent = onEvent)
    }

    private fun doConnect(
        hostIp: String,
        playerName: String,
        buyIn: Int,
        deviceId: String = "",
        isReconnect: Boolean,
        onEvent: (Event) -> Unit
    ) {
        listenJob?.cancel()
        heartbeatJob?.cancel()

        listenJob = scope.launch {
            try {
                val newSocket = Socket(hostIp, DEFAULT_PORT)
                // 启用 TCP keepalive，让 OS 维护连接状态
                runCatching { newSocket.keepAlive = true }
                socket = newSocket
                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                val w = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))
                writer = w

                if (isReconnect && assignedPlayerId != null) {
                    // 发送重连请求
                    val reconMsg = NetworkMessage.Reconnect(
                        playerId = assignedPlayerId!!,
                        playerName = playerName,
                        deviceId = deviceId
                    )
                    w.write(json.encodeToString(NetworkMessage.serializer(), reconMsg))
                    w.newLine()
                    w.flush()
                } else {
                    // 发送首次加入请求
                    val join = NetworkMessage.JoinRequest(playerName = playerName, buyIn = buyIn, deviceId = deviceId)
                    w.write(json.encodeToString(NetworkMessage.serializer(), join))
                    w.newLine()
                    w.flush()
                }

                // 启动心跳发送
                heartbeatJob = scope.launch {
                    val pingText = json.encodeToString(NetworkMessage.serializer(), NetworkMessage.Ping)
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        try {
                            val cw = writer ?: break
                            cw.write(pingText)
                            cw.newLine()
                            cw.flush()
                        } catch (_: Exception) {
                            break
                        }
                    }
                }

                reconnecting = false

                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = json.decodeFromString(NetworkMessage.serializer(), line)
                    when (msg) {
                        is NetworkMessage.JoinAccepted -> {
                            assignedPlayerId = msg.assignedPlayerId
                            onEvent(Event.JoinAccepted(msg.assignedPlayerId))
                        }

                        is NetworkMessage.ReconnectAccepted -> {
                            onEvent(Event.Reconnected(msg.playerId))
                        }

                        is NetworkMessage.StateSync -> {
                            onEvent(
                                Event.StateSync(
                                    players = msg.players,
                                    handCounter = msg.handCounter,
                                    transactions = msg.transactions,
                                    contributions = msg.contributions,
                                    blindsState = msg.blindsState,
                                    blindsEnabled = msg.blindsEnabled,
                                    sidePotEnabled = msg.sidePotEnabled,
                                    selectedWinnerIds = msg.selectedWinnerIds,
                                    foldedPlayerIds = msg.foldedPlayerIds,
                                    gameStarted = msg.gameStarted,
                                    currentRound = msg.currentRound,
                                    currentTurnPlayerId = msg.currentTurnPlayerId,
                                    roundContributions = msg.roundContributions,
                                    actedPlayerIds = msg.actedPlayerIds,
                                    initialDealerIndex = msg.initialDealerIndex
                                )
                            )
                        }

                        is NetworkMessage.Ping -> {
                            // 回复 pong
                            try {
                                val cw = writer ?: continue
                                val pongText = json.encodeToString(NetworkMessage.serializer(), NetworkMessage.Pong)
                                cw.write(pongText)
                                cw.newLine()
                                cw.flush()
                            } catch (_: Exception) {
                                break
                            }
                        }

                        is NetworkMessage.Pong -> { /* 忽略，保活即可 */ }

                        is NetworkMessage.Error -> onEvent(Event.Error(msg.reason))
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                // 连接断开
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                writer = null
                runCatching { socket?.close() }
                socket = null
            }

            // 断线后尝试重连
            if (shouldReconnect && assignedPlayerId != null && !reconnecting) {
                reconnecting = true
                onEvent(Event.Disconnected(willReconnect = true))
                attemptReconnect(deviceId, onEvent)
            } else if (shouldReconnect && assignedPlayerId == null) {
                onEvent(Event.Disconnected(willReconnect = false))
            }
        }
    }

    private fun attemptReconnect(deviceId: String = lastDeviceId, onEvent: (Event) -> Unit) {
        val ip = lastHostIp ?: return
        val name = lastPlayerName ?: return

        scope.launch {
            var attempt = 0
            while (attempt < 9999 && shouldReconnect && isActive) {
                attempt++
                val delayMs = (3000L * attempt.coerceAtMost(3)).coerceAtMost(10_000L)
                delay(delayMs)
                if (!shouldReconnect) break

                // 快速 TCP 探测房主是否已重启服务
                val reachable = try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, DEFAULT_PORT), 3000)
                    }
                    true
                } catch (_: Exception) {
                    false
                }

                if (reachable) {
                    // 房主已上线，执行完整重连
                    reconnecting = false
                    doConnect(ip, name, lastBuyIn, deviceId, isReconnect = true, onEvent = onEvent)
                    return@launch
                }
            }
            if (shouldReconnect) {
                reconnecting = false
                onEvent(Event.ReconnectFailed("重连失败"))
            }
        }
    }

    /**
     * 从首页手动重连（已有 playerId，发送 Reconnect 消息）
     */
    fun reconnect(hostIp: String, playerId: String, playerName: String, buyIn: Int, deviceId: String = "", onEvent: (Event) -> Unit) {
        disconnect()
        lastHostIp = hostIp
        lastPlayerName = playerName
        lastBuyIn = buyIn
        lastDeviceId = deviceId
        assignedPlayerId = playerId
        reconnecting = false
        shouldReconnect = true
        doConnect(hostIp, playerName, buyIn, deviceId, isReconnect = true, onEvent = onEvent)
    }

    fun sendContribution(playerId: String, amount: Int) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.SubmitContribution(playerId = playerId, amount = amount)
                w.write(json.encodeToString(NetworkMessage.serializer(), msg))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    fun sendReady(playerId: String, isReady: Boolean) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.ReadyToggle(playerId = playerId, isReady = isReady)
                w.write(json.encodeToString(NetworkMessage.serializer(), msg))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    fun sendWinToggle(playerId: String, isWinner: Boolean) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.WinToggle(playerId = playerId, isWinner = isWinner)
                w.write(json.encodeToString(NetworkMessage.serializer(), msg))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    fun sendFold(playerId: String) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.Fold(playerId = playerId)
                w.write(json.encodeToString(NetworkMessage.serializer(), msg))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    fun sendUpdateProfile(playerId: String, newName: String, avatarBase64: String) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.UpdateProfile(playerId = playerId, newName = newName, avatarBase64 = avatarBase64)
                w.write(json.encodeToString(NetworkMessage.serializer(), msg))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnecting = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        listenJob?.cancel()
        listenJob = null
        writer = null
        runCatching { socket?.close() }
        socket = null
    }

    fun close() {
        disconnect()
        scope.cancel()
    }
}
