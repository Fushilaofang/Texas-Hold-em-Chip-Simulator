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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

private const val DEFAULT_PORT = 45454
private const val HEARTBEAT_INTERVAL_MS = 2_000L   // 每 3 秒发一次 ping
private const val HEARTBEAT_TIMEOUT_MS = 6_000L    // 10 秒无 pong 才视为掉线

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

    /** 已进入大厅预览（StatePreviewRequest）且保持持久连接的观察者 */
    private val observerClients = mutableMapOf<Socket, BufferedWriter>()
    private val observerLock = Any()

    private data class PendingMidJoin(
        val requestId: String,
        val playerName: String,
        val buyIn: Int,
        val deviceId: String,
        val avatarBase64: String,
        val writer: BufferedWriter,
        val socket: Socket,
        val approval: CompletableDeferred<String?>  // null = 拒绝/超时
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
        /** 有新玩家申请中途加入游戏，需要房主审批 */
        data class MidGameJoinRequested(val requestId: String, val playerName: String, val buyIn: Int, val deviceId: String, val avatarBase64: String) : Event()
        /** 中途加入的玩家主动取消了申请或已批准的等待 */
        data class MidGameJoinCancelled(val requestId: String, val assignedPlayerId: String?, val reason: String = "取消了申请") : Event()
        /** 客户端主动离开房间 */
        data class PlayerLeft(val playerId: String) : Event()
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
                    // 同样地为服务端每个客户端连接设置 10 秒超时
                    runCatching { socket.soTimeout = 10000 }
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
        allowMidGameJoin: Boolean = false
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
            allowMidGameJoin = allowMidGameJoin
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

        // 同步推送给正在等待房主审批的中途加入玩家（尚未进入 clients，需单独通知）
        synchronized(pendingMidJoinsLock) {
            pendingMidJoins.values.forEach { pending ->
                try {
                    pending.writer.write(text)
                    pending.writer.newLine()
                    pending.writer.flush()
                } catch (_: Exception) {
                    // 连接已断开，等待各自协程清理
                }
            }
        }

        // 同步推送给持久观察者（已进入大厅预览的玩家）
        val staleObservers = mutableListOf<Socket>()
        synchronized(observerLock) {
            observerClients.forEach { (sock, w) ->
                try {
                    w.write(text)
                    w.newLine()
                    w.flush()
                } catch (_: Exception) {
                    staleObservers.add(sock)
                }
            }
            staleObservers.forEach { observerClients.remove(it) }
        }
        staleObservers.forEach { runCatching { it.close() } }
    }

    /** 房主批准某个中途加入申请，唤醒挂起的协程完成注册 */
    fun approveMidGameJoin(requestId: String, newPlayerId: String) {
        val pending = synchronized(pendingMidJoinsLock) { pendingMidJoins.remove(requestId) } ?: return
        pending.approval.complete(newPlayerId)
    }

    /** 房主拒绝/屏蔽某个中途加入申请 */
    fun rejectMidGameJoin(requestId: String, block: Boolean) {
        val pending = synchronized(pendingMidJoinsLock) { pendingMidJoins.remove(requestId) } ?: return
        if (block) {
            synchronized(blockedLock) { blockedDeviceIds.add(pending.deviceId) }
        }
        // 先同步发送拒绝消息给客户端，确保消息到达后再关闭连接
        scope.launch(Dispatchers.IO) {
            try {
                val reason = if (block) "您已被房主屏蔽" else "房主已拒绝您的申请"
                val msg = json.encodeToString(
                    NetworkMessage.serializer(),
                    NetworkMessage.MidGameJoinRejected(reason = reason, blocked = block)
                )
                pending.writer.write(msg)
                pending.writer.newLine()
                pending.writer.flush()
            } catch (_: Exception) {}
            // 消息发送完毕后才唤醒 approvalWatchJob（避免竞态导致 socket 提前关闭）
            pending.approval.complete(null)
            // 给客户端一点时间读取消息
            kotlinx.coroutines.delay(200)
            runCatching { pending.socket.close() }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        synchronized(disconnectedLock) { disconnectedPlayers.clear() }
        // 新建房间时重置屏蔽列表，被屏蔽的玩家可以加入新房间
        synchronized(blockedLock) { blockedDeviceIds.clear() }
        // 拒绝所有正在等待审批的中途加入申请
        val snapshot = synchronized(pendingMidJoinsLock) { pendingMidJoins.toMap().also { pendingMidJoins.clear() } }
        snapshot.values.forEach { p ->
            p.approval.complete(null)
            runCatching { p.socket.close() }
        }
        synchronized(clientsLock) {
            clients.values.forEach {
                runCatching { it.socket.close() }
                it.readerJob.cancel()
            }
            clients.clear()
        }
        // 关闭所有持久观察者连接
        synchronized(observerLock) {
            observerClients.forEach { (sock, _) -> runCatching { sock.close() } }
            observerClients.clear()
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /** 显式清空屏蔽列表（每次新建房间时自动调用，也可供外部手动调用） */
    fun clearBlockList() {
        synchronized(blockedLock) { blockedDeviceIds.clear() }
    }

    /**
     * 房主移除已连接的玩家。
     * 先向客户端发送 [NetworkMessage.KickedFromRoom] 通知，稍后再关闭连接。
     * @param playerId 要移除的玩家 ID
     * @param deviceId 玩家设备 ID（用于屏蔽时写入黑名单）
     * @param block 是否同时屏蔽该设备
     */
    fun kickPlayer(playerId: String, deviceId: String, block: Boolean) {
        if (block && deviceId.isNotBlank()) {
            synchronized(blockedLock) { blockedDeviceIds.add(deviceId) }
        }
        val conn = synchronized(clientsLock) { clients[playerId] } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val reason = if (block) "您已被房主移除并屏蔽" else "您已被房主移除"
                val msg = json.encodeToString(
                    NetworkMessage.serializer(),
                    NetworkMessage.KickedFromRoom(reason = reason, blocked = block)
                )
                conn.writer.write(msg)
                conn.writer.newLine()
                conn.writer.flush()
            } catch (_: Exception) {}
            // 给客户端足够时间读取消息后再断开
            kotlinx.coroutines.delay(400)
            synchronized(clientsLock) { clients.remove(playerId) }
            synchronized(disconnectedLock) { disconnectedPlayers.remove(playerId) }
            conn.readerJob.cancel()
            runCatching { conn.socket.close() }
        }
    }

    /** 彻底清理已主动离开的玩家的连接状态（不需经过Kicked流程） */
    fun removePlayer(playerId: String) {
        val conn = synchronized(clientsLock) { clients.remove(playerId) }
        synchronized(disconnectedLock) { disconnectedPlayers.remove(playerId) }
        if (conn != null) {
            conn.readerJob.cancel()
            runCatching { conn.socket.close() }
        }
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
        onPlayerJoined: (PlayerState) -> Unit,
        onEvent: (Event) -> Unit
    ) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        // 启用 TCP keepalive，让 OS 维护连接状态
        runCatching { socket.keepAlive = true }
        var assignedId: String? = null
        var pendingRequestId: String? = null     // 该 socket 对应的中途加入审批请求 ID
        var pendingApproval: CompletableDeferred<String?>? = null
        var approvalWatchJob: Job? = null

        val readerJob = scope.launch {
            val outerJob = this.coroutineContext[Job]!!
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

                        is NetworkMessage.StatePreviewRequest -> {
                            // 持久观察者：回复初始 StateSync，保持连接，注册到 observerClients
                            // 后续每次 broadcastState() 时会推送最新玩家列表，直到客户端主动断开
                            val previewSync = NetworkMessage.StateSync(
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
                                allowMidGameJoin = allowMidGameJoinProvider()
                            )
                            try {
                                writer.write(json.encodeToString(NetworkMessage.serializer(), previewSync))
                                writer.newLine()
                                writer.flush()
                            } catch (_: Exception) {}
                            // 注册为持久观察者，不关闭连接；readLine() 继续阻塞直到客户端断开
                            synchronized(observerLock) { observerClients[socket] = writer }
                        }

                        is NetworkMessage.RequestRoomState -> {
                            // 已连接（中途加入等待审批中）的玩家主动请求当前房间状态
                            val roomStateSync = NetworkMessage.StateSync(
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
                                allowMidGameJoin = allowMidGameJoinProvider()
                            )
                            try {
                                writer.write(json.encodeToString(NetworkMessage.serializer(), roomStateSync))
                                writer.newLine()
                                writer.flush()
                            } catch (_: Exception) {}
                        }

                        is NetworkMessage.MidGameJoinCancel -> {
                            // 客户端主动取消中途加入
                            // 1. 查找是否在 pending 审批队列中（通过 socket 匹配）
                            val cancelledEntry = synchronized(pendingMidJoinsLock) {
                                val entry = pendingMidJoins.entries.firstOrNull { it.value.socket === socket }
                                if (entry != null) {
                                    pendingMidJoins.remove(entry.key)
                                    entry
                                } else null
                            }
                            if (cancelledEntry != null) {
                                cancelledEntry.value.approval.complete(null)
                                onEvent(Event.MidGameJoinCancelled(cancelledEntry.key, null))
                            } else {
                                // 2. 已批准、正在等待下一手的玩家取消
                                val pid = assignedId
                                if (pid != null) {
                                    onEvent(Event.MidGameJoinCancelled("", pid))
                                    // 断开连接
                                    synchronized(clientsLock) { clients.remove(pid) }
                                    runCatching { socket.close() }
                                    return@launch
                                }
                            }
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

                        is NetworkMessage.PlayerLeft -> {
                            onEvent(Event.PlayerLeft(msg.playerId))
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
                                    allowMidGameJoin = allowMidGameJoinProvider()
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
                                    allowMidGameJoin = allowMidGameJoinProvider()
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
                                // 游戏进行中 — 中途加入流程
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
                                // 发送"等待房主审批"通知
                                val requestId = UUID.randomUUID().toString()
                                val approval = CompletableDeferred<String?>()
                                val pending = PendingMidJoin(
                                    requestId = requestId,
                                    playerName = msg.playerName,
                                    buyIn = msg.buyIn,
                                    deviceId = msg.deviceId,
                                    avatarBase64 = msg.avatarBase64,
                                    writer = writer,
                                    socket = socket,
                                    approval = approval
                                )
                                synchronized(pendingMidJoinsLock) { pendingMidJoins[requestId] = pending }
                                pendingRequestId = requestId
                                pendingApproval = approval
                                writer.write(json.encodeToString(NetworkMessage.serializer(), NetworkMessage.MidGameJoinPending))
                                writer.newLine(); writer.flush()
                                // 不再主动推送 StateSync，等待客户端发送 RequestRoomState 后再响应玩家列表
                                onEvent(Event.MidGameJoinRequested(requestId, msg.playerName, msg.buyIn, msg.deviceId, msg.avatarBase64))

                                // 启动独立协程监听审批结果，不阻塞 while 消息循环
                                approvalWatchJob = scope.launch {
                                    val approvedId = withTimeoutOrNull(300_000L) { approval.await() }
                                    synchronized(pendingMidJoinsLock) { pendingMidJoins.remove(requestId) }
                                    pendingRequestId = null
                                    pendingApproval = null

                                    if (approvedId == null) {
                                        // 区分超时和被拒绝/取消：approval 未完成说明是超时
                                        if (!approval.isCompleted) {
                                            // 真正超时（5分钟无操作）— 通知客户端并关闭
                                            try {
                                                val timeoutMsg = json.encodeToString(
                                                    NetworkMessage.serializer(),
                                                    NetworkMessage.MidGameJoinRejected(reason = "审批超时", blocked = false)
                                                )
                                                writer.write(timeoutMsg)
                                                writer.newLine(); writer.flush()
                                            } catch (_: Exception) {}
                                            runCatching { socket.close() }
                                            // 通知房主端移除该申请并提醒
                                            onEvent(Event.MidGameJoinCancelled(requestId, null, "审批超时"))
                                        }
                                        // 被拒绝/取消 — socket 由 rejectMidGameJoin 或 finally 块关闭
                                        return@launch
                                    }

                                    // 房主已同意
                                    assignedId = approvedId
                                    synchronized(clientsLock) {
                                        clients.remove(approvedId)?.let { old ->
                                            old.readerJob.cancel()
                                            runCatching { old.socket.close() }
                                        }
                                        clients[approvedId] = ClientConnection(
                                            playerId = approvedId,
                                            socket = socket,
                                            writer = writer,
                                            readerJob = outerJob
                                        )
                                    }
                                    // 发送 JoinAccepted
                                    try {
                                        val accepted = NetworkMessage.JoinAccepted(assignedPlayerId = approvedId)
                                        writer.write(json.encodeToString(NetworkMessage.serializer(), accepted))
                                        writer.newLine(); writer.flush()
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
                                            allowMidGameJoin = allowMidGameJoinProvider()
                                        )
                                        writer.write(json.encodeToString(NetworkMessage.serializer(), sync))
                                        writer.newLine(); writer.flush()
                                    } catch (_: Exception) {
                                        runCatching { socket.close() }
                                    }
                                }
                            } else {
                                // 全新玩家正常加入（游戏未开始）
                                // 检查是否已满人（10人上限）
                                val currentCount = hostPlayersProvider().size
                                if (currentCount >= 10) {
                                    val err = NetworkMessage.Error(reason = "房间已满，无法加入")
                                    writer.write(json.encodeToString(NetworkMessage.serializer(), err))
                                    writer.newLine(); writer.flush()
                                    socket.close(); return@launch
                                }
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
                                    allowMidGameJoin = allowMidGameJoinProvider()
                                )
                                writer.write(json.encodeToString(NetworkMessage.serializer(), sync))
                                writer.newLine()
                                writer.flush()

                                synchronized(clientsLock) {
                                    clients[playerId] = ClientConnection(
                                        playerId = playerId,
                                        socket = socket,
                                        writer = writer,
                                        readerJob = outerJob
                                    )
                                }

                                onEvent(Event.PlayerJoined(joined))
                            }
                        }

                        else -> Unit
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 忽略协程取消异常（如被踢出等关闭连接引发）
            } catch (ex: Exception) {
                val msg = ex.message ?: "未知错误"
                // 忽略网络断开引起的常规异常，不将其显示到大厅状态栏
                if (!msg.contains("Connection reset", ignoreCase = true) && 
                    !msg.contains("Socket closed", ignoreCase = true) &&
                    !msg.contains("Software caused connection abort", ignoreCase = true)) {
                    onEvent(Event.Error("客户端处理失败: $msg"))
                }
            } finally {
                approvalWatchJob?.cancel()
                // 观察者断开时从列表中移除
                synchronized(observerLock) { observerClients.remove(socket) }
                // 自动清理未决的中途加入申请（客户端断开连接时）
                val pReqId = pendingRequestId
                if (pReqId != null) {
                    val removedEntry = synchronized(pendingMidJoinsLock) { pendingMidJoins.remove(pReqId) }
                    removedEntry?.approval?.complete(null)
                    onEvent(Event.MidGameJoinCancelled(pReqId, null, "连接已断开"))
                }
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
            val initialDealerIndex: Int = 0,
            val midGameWaitingPlayerIds: Set<String> = emptySet(),
            val allowMidGameJoin: Boolean = false
        ) : Event()

        data class Disconnected(val willReconnect: Boolean) : Event()
        data class Reconnected(val playerId: String) : Event()
        data class ReconnectFailed(val reason: String) : Event()
        /** 申请中途加入已提交，等待房主审批 */
        data object MidGameJoinPending : Event()
        /** 房主拒绝或屏蔽了中途加入申请 */
        data class MidGameJoinRejected(val reason: String, val blocked: Boolean) : Event()
        /** 房主将本玩家移除（可能同时屏蔽） */
        data class Kicked(val reason: String, val blocked: Boolean) : Event()
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
                // 启用 TCP keepalive，设置读取超时让断网尽早抛出异常
                runCatching { 
                    newSocket.keepAlive = true
                    newSocket.soTimeout = 15000 
                }
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
                                    allowMidGameJoin = msg.allowMidGameJoin
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
                        is NetworkMessage.KickedFromRoom -> {
                            // 被移除：阻止重连，通知 ViewModel，等待服务端关闭连接
                            shouldReconnect = false
                            onEvent(Event.Kicked(msg.reason, msg.blocked))
                        }
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

    fun sendMidGameJoinCancel() {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.MidGameJoinCancel
                w.write(json.encodeToString(NetworkMessage.serializer(), msg))
                w.newLine()
                w.flush()
            } catch (_: Exception) { }
        }
    }

    /**
     * 已连接且处于中途加入等待状态的客户端主动向服务端请求当前房间玩家列表。
     * 服务端收到后响应一次 StateSync，客户端据此更新大厅中的玩家列表展示。
     */
    fun sendRequestRoomState() {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.RequestRoomState
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

    fun sendPlayerLeft(playerId: String) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val msg = NetworkMessage.PlayerLeft(playerId = playerId)
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

    /** 持久观察者连接（用于大厅预览，保持接收状态更新直到主动断开） */
    @Volatile private var observerSocket: Socket? = null
    private var observerJob: Job? = null

    /**
     * 以持久观察者模式连接：发送 StatePreviewRequest 后保持连接，
     * 持续接收服务端推送的 StateSync 更新，直到调用 [stopObserving]。
     * 不触发重连逻辑，不占用主连接 socket/writer。
     */
    fun startObserving(hostIp: String, onEvent: (Event) -> Unit) {
        stopObserving()
        observerJob = scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(hostIp, DEFAULT_PORT), 5000)
                observerSocket = sock
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val w = BufferedWriter(OutputStreamWriter(sock.getOutputStream()))
                val reqText = json.encodeToString(NetworkMessage.serializer(), NetworkMessage.StatePreviewRequest)
                w.write(reqText); w.newLine(); w.flush()
                // 持续读取 StateSync，直到连接断开或被取消
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = runCatching { json.decodeFromString(NetworkMessage.serializer(), line) }.getOrNull()
                    if (msg is NetworkMessage.StateSync) {
                        onEvent(Event.StateSync(
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
                            allowMidGameJoin = msg.allowMidGameJoin
                        ))
                    }
                }
            } catch (_: Exception) { /* 观察者连接断开，静默处理 */ } finally {
                runCatching { observerSocket?.close() }
                observerSocket = null
            }
        }
    }

    /** 停止持久观察（离开大厅预览时调用） */
    fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
        runCatching { observerSocket?.close() }
        observerSocket = null
    }

    fun close() {
        disconnect()
        scope.cancel()
    }
}
