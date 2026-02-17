package com.fushilaofang.texasholdemchipsim.network

import com.fushilaofang.texasholdemchipsim.model.ChipTransaction
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val DEFAULT_PORT = 45454

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

    sealed class Event {
        data class PlayerJoined(val player: PlayerState) : Event()
        data class PlayerDisconnected(val playerId: String) : Event()
        data class ContributionReceived(val playerId: String, val amount: Int) : Event()
        data class ReadyToggleReceived(val playerId: String, val isReady: Boolean) : Event()
        data class Error(val message: String) : Event()
    }

    data class ClientConnection(
        val playerId: String,
        val socket: Socket,
        val writer: BufferedWriter,
        val readerJob: Job
    )

    fun start(
        hostPlayersProvider: () -> List<PlayerState>,
        handCounterProvider: () -> Int,
        txProvider: () -> List<ChipTransaction>,
        contributionsProvider: () -> Map<String, Int> = { emptyMap() },
        blindsStateProvider: () -> com.fushilaofang.texasholdemchipsim.blinds.BlindsState = { com.fushilaofang.texasholdemchipsim.blinds.BlindsState() },
        blindsEnabledProvider: () -> Boolean = { true },
        gameStartedProvider: () -> Boolean = { false },
        onPlayerJoined: (PlayerState) -> Unit,
        onEvent: (Event) -> Unit
    ) {
        stop()

        scope.launch {
            try {
                serverSocket = ServerSocket(DEFAULT_PORT)
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
                            gameStartedProvider = gameStartedProvider,
                            onPlayerJoined = onPlayerJoined,
                            onEvent = onEvent
                        )
                    }
                }
            } catch (ex: Exception) {
                onEvent(Event.Error("服务端异常: ${ex.message ?: "未知错误"}"))
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
        gameStarted: Boolean = false
    ) {
        val message = NetworkMessage.StateSync(
            players = players,
            handCounter = handCounter,
            transactions = transactions.take(50),
            contributions = contributions,
            blindsState = blindsState,
            blindsEnabled = blindsEnabled,
            gameStarted = gameStarted
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
                clients.remove(id)?.socket?.close()
            }
        }
    }

    fun stop() {
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
        gameStartedProvider: () -> Boolean,
        onPlayerJoined: (PlayerState) -> Unit,
        onEvent: (Event) -> Unit
    ) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
        var assignedId: String? = null

        val readerJob = scope.launch {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = json.decodeFromString(NetworkMessage.serializer(), line)
                    when (msg) {
                        is NetworkMessage.SubmitContribution -> {
                            onEvent(Event.ContributionReceived(msg.playerId, msg.amount))
                        }

                        is NetworkMessage.ReadyToggle -> {
                            onEvent(Event.ReadyToggleReceived(msg.playerId, msg.isReady))
                        }

                        is NetworkMessage.JoinRequest -> {
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
                                seatOrder = seatOrder
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
                                gameStarted = gameStartedProvider()
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

                        else -> Unit
                    }
                }
            } catch (ex: Exception) {
                onEvent(Event.Error("客户端处理失败: ${ex.message ?: "未知错误"}"))
            } finally {
                val id = assignedId
                if (id != null) {
                    synchronized(clientsLock) { clients.remove(id) }
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

    sealed class Event {
        data class JoinAccepted(val playerId: String) : Event()
        data class StateSync(
            val players: List<PlayerState>,
            val handCounter: Int,
            val transactions: List<ChipTransaction>,
            val contributions: Map<String, Int> = emptyMap(),
            val blindsState: com.fushilaofang.texasholdemchipsim.blinds.BlindsState = com.fushilaofang.texasholdemchipsim.blinds.BlindsState(),
            val blindsEnabled: Boolean = true,
            val gameStarted: Boolean = false
        ) : Event()

        data class Error(val message: String) : Event()
    }

    private var writer: BufferedWriter? = null

    fun connect(hostIp: String, playerName: String, buyIn: Int, onEvent: (Event) -> Unit) {
        disconnect()

        listenJob = scope.launch {
            try {
                val newSocket = Socket(hostIp, DEFAULT_PORT)
                socket = newSocket
                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                val w = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream()))
                writer = w

                val join = NetworkMessage.JoinRequest(playerName = playerName, buyIn = buyIn)
                w.write(json.encodeToString(NetworkMessage.serializer(), join))
                w.newLine()
                w.flush()

                while (isActive) {
                    val line = reader.readLine() ?: break
                    val msg = json.decodeFromString(NetworkMessage.serializer(), line)
                    when (msg) {
                        is NetworkMessage.JoinAccepted -> onEvent(Event.JoinAccepted(msg.assignedPlayerId))
                        is NetworkMessage.StateSync -> {
                            onEvent(
                                Event.StateSync(
                                    players = msg.players,
                                    handCounter = msg.handCounter,
                                    transactions = msg.transactions,
                                    contributions = msg.contributions,
                                    blindsState = msg.blindsState,
                                    blindsEnabled = msg.blindsEnabled,
                                    gameStarted = msg.gameStarted
                                )
                            )
                        }

                        is NetworkMessage.Error -> onEvent(Event.Error(msg.reason))
                        else -> Unit
                    }
                }
            } catch (ex: Exception) {
                onEvent(Event.Error("连接失败: ${ex.message ?: "未知错误"}"))
            }
        }
    }

    /**
     * 客户端提交本手投入到服务端
     */
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

    /**
     * 客户端发送准备状态到服务端
     */
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

    fun disconnect() {
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
