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

    /** 已被房主屏蔽的设备ID */
    private val blockedDeviceIds = mutableSetOf<String>()
    private val blockedLock = Any()

    /** 正在等待房主审批的中途加入申请 */
    private val pendingMidJoins = mutableMapOf<String, PendingMidJoin>()
    private val pendingMidJoinsLock = Any()

    /** 已连接但尚未获批的观察者玩家 ID */
    private val midGameObserverPlayerIds = mutableSetOf<String>()
    private val observersLock = Any()

    private data class PendingMidJoin(
        val requestId: String,
        val playerId: String,      // 已分配的 ID（加入时即注册）
        val playerName: String,
        val deviceId: String
    )

    sealed class Event {
        data class PlayerJoined(val player: PlayerState) : Event()
        data class PlayerDisconnected(val playerId: String) : Event()
        /**
         * 玩家重连成功。
         * @param updatedName  鞞空则更新是后服务端玩家是同中昵称
         * @param updatedChips 大于 0 则更新筹码（仅大厅阶段），‑₼e表示不更改
         */
        data class PlayerReconnected(
            val playerId: String,
            val updatedName: String = "",
            val updatedChips: Int = -1
        ) : Event()
        data class ContributionReceived(val playerId: String, val amount: Int) : Event()
        data class ReadyToggleReceived(val playerId: String, val isReady: Boolean) : Event()
        data class WinToggleReceived(val playerId: String, val isWinner: Boolean) : Event()
        data class FoldReceived(val playerId: String) : Event()
        data class ProfileUpdateReceived(val playerId: String, val newName: String, val avatarBase64: String) : Event()
        /** 同一设备以新昵称/头像重新进入房间，应复用原有玩家槽位 */
        data class PlayerRejoinedByDevice(val playerId: String, val newName: String, val avatarBase64: String) : Event()
        /** 游戏进行中有观察者连入（尚未申请加入） */
        data class MidGameObserverJoined(val player: PlayerState) : Event()
        /** 观察者主动取消/离开（或超时断线） */
        data class MidGameObserverLeft(val playerId: String) : Event()
        /** 有新玩家申请中途加入游戏，需要房主审批 */
        data class MidGameJoinRequested(val requestId: String, val playerName: String, val buyIn: Int, val deviceId: String, val avatarBase64: String, val playerId: String = "") : Event()
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
        allowMidGameJoinProvider: () -> Boolean = { false },
        midGameWaitingPlayerIdsProvider: () -> Set<String> = { emptySet() },
        midGameObserverIdsProvider: () -> Set<String> = { emptySet() },
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
                            allowMidGameJoinProvider = allowMidGameJoinProvider,
                            midGameWaitingPlayerIdsProvider = midGameWaitingPlayerIdsProvider,
                            midGameObserverIdsProvider = midGameObserverIdsProvider,
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
        initialDealerIndex: Int = 0,
        midGameWaitingPlayerIds: Set<String> = emptySet(),
        allowMidGameJoin: Boolean = false,
        midGameObserverIds: Set<String> = emptySet()
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
            initialDealerIndex = initialDealerIndex,
            midGameWaitingPlayerIds = midGameWaitingPlayerIds,
            allowMidGameJoin = allowMidGameJoin,
            midGameObserverIds = midGameObserverIds
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

    /** 房主批准某个中途加入申请；玩家已注册在 clients，只需移除 pending 记录 */
    fun approveMidGameJoin(requestId: String) {
        synchronized(pendingMidJoinsLock) { pendingMidJoins.remove(requestId) }
        // 从观察者集合移除（ViewModel 会将其加入 midGameWaitingPlayerIds）
        // observer -> waiting 的状态迁移由 ViewModel 通过 syncToClients 广播完成
    }

    /** 房主拒绝/屏蔽某个中途加入申请 */
    fun rejectMidGameJoin(requestId: String, block: Boolean) {
        val pending = synchronized(pendingMidJoinsLock) { pendingMidJoins.remove(requestId) } ?: return
        if (block) {
            synchronized(blockedLock) { blockedDeviceIds.add(pending.deviceId) }
        }
        synchronized(observersLock) { midGameObserverPlayerIds.remove(pending.playerId) }
        // 向客户端发送拒绝消息并关闭连接
        scope.launch(Dispatchers.IO) {
            val conn = synchronized(clientsLock) { clients.remove(pending.playerId) }
            if (conn != null) {
                conn.readerJob.cancel()
                try {
                    val reason = if (block) "您已被房主屏蔽" else "房主已拒绝您的申请"
                    val msg = json.encodeToString(
                        NetworkMessage.serializer(),
                        NetworkMessage.MidGameJoinRejected(reason = reason, blocked = block)
                    )
                    conn.writer.write(msg)
                    conn.writer.newLine()
                    conn.writer.flush()
                    kotlinx.coroutines.delay(200)
                } catch (_: Exception) {}
                runCatching { conn.socket.close() }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        synchronized(disconnectedLock) { disconnectedPlayers.clear() }
        synchronized(pendingMidJoinsLock) { pendingMidJoins.clear() }
        synchronized(observersLock) { midGameObserverPlayerIds.clear() }
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
        allowMidGameJoinProvider: () -> Boolean,
        midGameWaitingPlayerIdsProvider: () -> Set<String>,
        midGameObserverIdsProvider: () -> Set<String>,
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
                                    actedPlayerIds = actedPlayerIdsProvider(),
                                    midGameWaitingPlayerIds = midGameWaitingPlayerIdsProvider(),
                                    allowMidGameJoin = allowMidGameJoinProvider(),
                                    midGameObserverIds = midGameObserverPlayerIds.toSet()
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

                                // 大厅阶段（游戏未开始）重连时，允许更新是同名和初始筹码
                                val updatedChips = if (!gameStartedProvider() && msg.newBuyIn > 0) msg.newBuyIn else -1
                                onEvent(Event.PlayerReconnected(
                                    playerId = oldId,
                                    updatedName = msg.playerName,
                                    updatedChips = updatedChips
                                ))
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
                                    actedPlayerIds = actedPlayerIdsProvider(),
                                    midGameWaitingPlayerIds = midGameWaitingPlayerIdsProvider(),
                                    allowMidGameJoin = allowMidGameJoinProvider(),
                                    midGameObserverIds = midGameObserverPlayerIds.toSet()
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
                            } else if (gameStartedProvider()) {
                                // 游戏进行中 — 立即接受为观察者，进入大厅
                                if (msg.deviceId.isNotBlank() && synchronized(blockedLock) { blockedDeviceIds.contains(msg.deviceId) }) {
                                    val err = NetworkMessage.Error(reason = "你已被房主屏蔽，无法加入该房间")
                                    writer.write(json.encodeToString(NetworkMessage.serializer(), err))
                                    writer.newLine(); writer.flush()
                                    socket.close(); return@launch
                                }
                                if (!allowMidGameJoinProvider()) {
                                    val err = NetworkMessage.Error(reason = "游戏已开始，房主未开放中途加入")
                                    writer.write(json.encodeToString(NetworkMessage.serializer(), err))
                                    writer.newLine(); writer.flush()
                                    socket.close(); return@launch
                                }
                                // 立即分配 ID，注册为观察者（可看大厅，但未申请加入）
                                val observerId = UUID.randomUUID().toString()
                                assignedId = observerId
                                synchronized(observersLock) { midGameObserverPlayerIds.add(observerId) }
                                synchronized(clientsLock) {
                                    clients[observerId] = ClientConnection(
                                        playerId = observerId,
                                        socket = socket,
                                        writer = writer,
                                        readerJob = this.coroutineContext[Job]
                                            ?: error("missing coroutine job")
                                    )
                                }
                                val accepted = NetworkMessage.JoinAccepted(assignedPlayerId = observerId)
                                writer.write(json.encodeToString(NetworkMessage.serializer(), accepted))
                                writer.newLine(); writer.flush()
                                // 发送当前完整状态，让观察者进入大厅看到玩家列表
                                val obsSync = NetworkMessage.StateSync(
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
                                    actedPlayerIds = actedPlayerIdsProvider(),
                                    midGameWaitingPlayerIds = midGameWaitingPlayerIdsProvider(),
                                    allowMidGameJoin = allowMidGameJoinProvider(),
                                    midGameObserverIds = midGameObserverPlayerIds.toSet()
                                )
                                writer.write(json.encodeToString(NetworkMessage.serializer(), obsSync))
                                writer.newLine(); writer.flush()
                                // 通知 ViewModel 添加观察者玩家
                                val observerPlayer = PlayerState(
                                    id = observerId,
                                    name = msg.playerName,
                                    chips = msg.buyIn,
                                    seatOrder = hostPlayersProvider().size,
                                    deviceId = msg.deviceId,
                                    avatarBase64 = msg.avatarBase64
                                )
                                onEvent(Event.MidGameObserverJoined(observerPlayer))
                                // 继续消息循环，等待 RequestMidGameJoin / CancelMidGameJoin
                            } else {
                                // 全新玩家正常加入（游戏未开始）
                                val playerId = UUID.randomUUID().toString()
                                assignedId = playerId
                                val seatOrder = hostPlayersProvider().size
                                val joined = PlayerState(
                                    id = playerId,
                                    name = msg.playerName,
                                    chips = msg.buyIn,
                                    seatOrder = seatOrder,
                                    deviceId = msg.deviceId,
                                    avatarBase64 = msg.avatarBase64
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
                                    actedPlayerIds = actedPlayerIdsProvider(),
                                    allowMidGameJoin = allowMidGameJoinProvider(),
                                    midGameWaitingPlayerIds = midGameWaitingPlayerIdsProvider(),
                                    midGameObserverIds = midGameObserverPlayerIds.toSet()
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

                        is NetworkMessage.RequestMidGameJoin -> {
                            // 观察者点击"申请加入"按钮
                            val id = assignedId ?: continue
                            val isObs = synchronized(observersLock) { midGameObserverPlayerIds.contains(id) }
                            if (!isObs) continue
                            val requestId = UUID.randomUUID().toString()
                            val player = hostPlayersProvider().firstOrNull { it.id == id }
                            val pending = PendingMidJoin(
                                requestId = requestId,
                                playerId = id,
                                playerName = player?.name ?: "?",
                                deviceId = player?.deviceId ?: ""
                            )
                            synchronized(pendingMidJoinsLock) { pendingMidJoins[requestId] = pending }
                            writer.write(json.encodeToString(NetworkMessage.serializer(), NetworkMessage.MidGameJoinPending))
                            writer.newLine(); writer.flush()
                            onEvent(Event.MidGameJoinRequested(
                                requestId = requestId,
                                playerName = player?.name ?: "?",
                                buyIn = player?.chips ?: 0,
                                deviceId = player?.deviceId ?: "",
                                avatarBase64 = player?.avatarBase64 ?: "",
                                playerId = id
                            ))
                        }

                        is NetworkMessage.CancelMidGameJoin -> {
                            // 观察者取消申请并离开
                            val id = assignedId ?: continue
                            synchronized(pendingMidJoinsLock) {
                                pendingMidJoins.entries.removeAll { it.value.playerId == id }
                            }
                            synchronized(observersLock) { midGameObserverPlayerIds.remove(id) }
                            onEvent(Event.MidGameObserverLeft(id))
                            break
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
                    val isObserver = synchronized(observersLock) { midGameObserverPlayerIds.remove(id) }
                    if (isObserver) {
                        // 观察者断线：通知 ViewModel 移除（不进入重连等待）
                        onEvent(Event.MidGameObserverLeft(id))
                    } else {
                        // 正常玩家掉线进入等待重连期
                        synchronized(disconnectedLock) {
                            disconnectedPlayers[id] = System.currentTimeMillis()
                        }
                        onEvent(Event.PlayerDisconnected(id))
                    }
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
            val initialDealerIndex: Int = 0,
            val midGameWaitingPlayerIds: Set<String> = emptySet(),
            val allowMidGameJoin: Boolean = false,
            val midGameObserverIds: Set<String> = emptySet()
        ) : Event()

        data class Disconnected(val willReconnect: Boolean) : Event()
        data class Reconnected(val playerId: String) : Event()
        data class ReconnectFailed(val reason: String) : Event()
        /** 申请中途加入已提交，等待房主审批 */
        data object MidGameJoinPending : Event()
        /** 房主拒绝或屏蔽了中途加入申请 */
        data class MidGameJoinRejected(val reason: String, val blocked: Boolean) : Event()
        data class Error(val message: String) : Event()
    }

    @Volatile
    private var writer: BufferedWriter? = null

    fun connect(hostIp: String, playerName: String, buyIn: Int, deviceId: String = "", avatarBase64: String = "", onEvent: (Event) -> Unit) {
        disconnect()
        lastHostIp = hostIp
        lastPlayerName = playerName
        lastBuyIn = buyIn
        lastDeviceId = deviceId
        assignedPlayerId = null
        reconnecting = false
        shouldReconnect = true

        doConnect(hostIp, playerName, buyIn, deviceId, avatarBase64, isReconnect = false, onEvent = onEvent)
    }

    private fun doConnect(
        hostIp: String,
        playerName: String,
        buyIn: Int,
        deviceId: String = "",
        avatarBase64: String = "",
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
                    // 发送重连请求，携带新昵称及希望更新的初始筹码
                    val reconMsg = NetworkMessage.Reconnect(
                        playerId = assignedPlayerId!!,
                        playerName = playerName,
                        deviceId = deviceId,
                        newBuyIn = buyIn
                    )
                    w.write(json.encodeToString(NetworkMessage.serializer(), reconMsg))
                    w.newLine()
                    w.flush()
                } else {
                    // 发送首次加入请求
                    val join = NetworkMessage.JoinRequest(playerName = playerName, buyIn = buyIn, deviceId = deviceId, avatarBase64 = avatarBase64)
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
                                    initialDealerIndex = msg.initialDealerIndex,
                                    midGameWaitingPlayerIds = msg.midGameWaitingPlayerIds,
                                    allowMidGameJoin = msg.allowMidGameJoin,
                                    midGameObserverIds = msg.midGameObserverIds
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
                        is NetworkMessage.MidGameJoinPending -> onEvent(Event.MidGameJoinPending)
                        is NetworkMessage.MidGameJoinRejected -> onEvent(Event.MidGameJoinRejected(msg.reason, msg.blocked))
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
                    doConnect(ip, name, lastBuyIn, deviceId, "", isReconnect = true, onEvent = onEvent)
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

    /** 观察者点击"申请加入"按钮，向服务端发送申请 */
    fun requestMidGameJoin() {
        scope.launch {
            try {
                val w = writer ?: return@launch
                w.write(json.encodeToString(NetworkMessage.serializer(), NetworkMessage.RequestMidGameJoin))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    /** 观察者取消申请并断开连接 */
    fun cancelMidGameJoin() {
        scope.launch {
            try {
                val w = writer
                if (w != null) {
                    w.write(json.encodeToString(NetworkMessage.serializer(), NetworkMessage.CancelMidGameJoin))
                    w.newLine()
                    w.flush()
                }
            } catch (_: Exception) { }
            disconnect()
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
