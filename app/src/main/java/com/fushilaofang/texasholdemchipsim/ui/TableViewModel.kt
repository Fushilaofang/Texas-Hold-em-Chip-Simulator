package com.fushilaofang.texasholdemchipsim.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fushilaofang.texasholdemchipsim.blinds.BlindsConfig
import com.fushilaofang.texasholdemchipsim.blinds.BlindsManager
import com.fushilaofang.texasholdemchipsim.blinds.BlindsState
import com.fushilaofang.texasholdemchipsim.data.TransactionRepository
import com.fushilaofang.texasholdemchipsim.model.ChipTransaction
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import com.fushilaofang.texasholdemchipsim.model.TransactionType
import com.fushilaofang.texasholdemchipsim.network.DiscoveredRoom
import com.fushilaofang.texasholdemchipsim.network.LanTableClient
import com.fushilaofang.texasholdemchipsim.network.LanTableServer
import com.fushilaofang.texasholdemchipsim.network.RoomAdvertiser
import com.fushilaofang.texasholdemchipsim.network.RoomScanner
import com.fushilaofang.texasholdemchipsim.settlement.SettlementEngine
import com.fushilaofang.texasholdemchipsim.settlement.SidePot
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TableMode {
    IDLE,
    HOST,
    CLIENT
}

data class TableUiState(
    val mode: TableMode = TableMode.IDLE,
    val tableName: String = "家庭牌局",
    val selfId: String = "",
    val selfName: String = "",
    val hostIp: String = "",
    val players: List<PlayerState> = emptyList(),
    val selectedWinnerIds: Set<String> = emptySet(),
    val contributionInputs: Map<String, String> = emptyMap(),
    val handCounter: Int = 1,
    val logs: List<ChipTransaction> = emptyList(),
    val info: String = "准备开始",
    // --- 盲注 ---
    val blindsState: BlindsState = BlindsState(),
    val blindsEnabled: Boolean = true,
    val blindContributions: Map<String, Int> = emptyMap(),
    // --- 边池 ---
    val lastSidePots: List<SidePot> = emptyList(),
    // --- 房间发现 ---
    val isScanning: Boolean = false,
    val discoveredRooms: List<DiscoveredRoom> = emptyList()
)

class TableViewModel(
    context: Context
) : ViewModel() {
    private val settlementEngine = SettlementEngine()
    private val blindsManager = BlindsManager()
    private val repository = TransactionRepository(context.applicationContext)
    private val server = LanTableServer()
    private val client = LanTableClient()
    private val roomAdvertiser = RoomAdvertiser()
    private val roomScanner = RoomScanner()

    private val _uiState = MutableStateFlow(
        TableUiState(logs = repository.load().takeLast(200))
    )
    val uiState: StateFlow<TableUiState> = _uiState.asStateFlow()

    // ==================== 房间发现 ====================

    fun startRoomScan() {
        _uiState.update { it.copy(isScanning = true, discoveredRooms = emptyList(), info = "正在搜索局域网房间...") }
        roomScanner.startScan { rooms ->
            _uiState.update { state ->
                state.copy(
                    discoveredRooms = rooms,
                    info = if (rooms.isEmpty()) "搜索中，暂未发现房间..." else "发现 ${rooms.size} 个房间"
                )
            }
        }
    }

    fun stopRoomScan() {
        roomScanner.stopScan()
        _uiState.update { it.copy(isScanning = false) }
    }

    // ==================== 开桌 / 加入 ====================

    fun hostTable(roomName: String, hostName: String, buyIn: Int, blindsConfig: BlindsConfig = BlindsConfig()) {
        val hostId = UUID.randomUUID().toString()
        val hostPlayer = PlayerState(
            id = hostId,
            name = hostName.ifBlank { "庄家" },
            chips = buyIn,
            seatOrder = 0
        )
        val initialBlinds = blindsManager.initialize(1, blindsConfig)

        _uiState.update {
            it.copy(
                mode = TableMode.HOST,
                tableName = roomName.ifBlank { "家庭牌局" },
                selfId = hostId,
                selfName = hostPlayer.name,
                players = listOf(hostPlayer),
                selectedWinnerIds = emptySet(),
                contributionInputs = emptyMap(),
                handCounter = 1,
                blindsState = initialBlinds,
                blindsEnabled = true,
                blindContributions = emptyMap(),
                lastSidePots = emptyList(),
                isScanning = false,
                discoveredRooms = emptyList(),
                info = "已创建房间「${roomName.ifBlank { "家庭牌局" }}」| 等待玩家加入"
            )
        }

        // 启动 TCP 服务
        server.start(
            hostPlayersProvider = { _uiState.value.players },
            handCounterProvider = { _uiState.value.handCounter },
            txProvider = { _uiState.value.logs },
            onPlayerJoined = { player ->
                _uiState.update { state ->
                    state.copy(players = state.players + player, info = "${player.name} 已加入房间")
                }
                syncToClients()
            },
            onEvent = { event ->
                when (event) {
                    is LanTableServer.Event.Error ->
                        _uiState.update { it.copy(info = event.message) }
                    is LanTableServer.Event.PlayerDisconnected ->
                        _uiState.update { it.copy(info = "玩家离线: ${event.playerId.take(6)}") }
                    else -> Unit
                }
            }
        )

        // 启动 UDP 广播让其他玩家发现
        roomAdvertiser.startBroadcast(
            roomName = _uiState.value.tableName,
            tcpPort = 45454,
            hostName = hostPlayer.name,
            playerCountProvider = { _uiState.value.players.size }
        )
    }

    /**
     * 通过选择已发现的房间加入
     */
    fun joinRoom(room: DiscoveredRoom, playerName: String, buyIn: Int) {
        stopRoomScan()
        _uiState.update {
            it.copy(
                mode = TableMode.CLIENT,
                tableName = room.roomName,
                hostIp = room.hostIp,
                selfName = playerName,
                isScanning = false,
                discoveredRooms = emptyList(),
                info = "正在加入「${room.roomName}」..."
            )
        }

        client.connect(room.hostIp, playerName.ifBlank { "玩家" }, buyIn) { event ->
            when (event) {
                is LanTableClient.Event.JoinAccepted ->
                    _uiState.update { it.copy(selfId = event.playerId, info = "已加入「${room.roomName}」") }
                is LanTableClient.Event.StateSync ->
                    _uiState.update {
                        it.copy(
                            players = event.players,
                            handCounter = event.handCounter,
                            logs = event.transactions.takeLast(200),
                            info = "牌局状态已同步"
                        )
                    }
                is LanTableClient.Event.Error ->
                    _uiState.update { it.copy(info = event.message) }
            }
        }
    }

    // ==================== 盲注 ====================

    fun updateBlindsConfig(smallBlind: Int, bigBlind: Int) {
        _uiState.update { state ->
            val newConfig = BlindsConfig(
                smallBlind = smallBlind.coerceAtLeast(1),
                bigBlind = bigBlind.coerceAtLeast(2)
            )
            state.copy(blindsState = state.blindsState.copy(config = newConfig))
        }
    }

    fun toggleBlinds(enabled: Boolean) {
        _uiState.update { it.copy(blindsEnabled = enabled) }
    }

    /**
     * 开始新一手：自动轮转庄位、扣除盲注、生成盲注记录
     */
    fun startNewHand() {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        if (state.players.size < 2) {
            _uiState.update { it.copy(info = "至少需要2名玩家") }
            return
        }

        val newBlinds = blindsManager.rotate(state.blindsState, state.players.size)

        if (state.blindsEnabled) {
            val sortedPlayers = state.players.sortedBy { it.seatOrder }
            val (updatedPlayers, blindContribs) = blindsManager.deductBlinds(sortedPlayers, newBlinds)

            val handId = "HAND-${state.handCounter.toString().padStart(4, '0')}"
            val now = System.currentTimeMillis()
            val updatedMap = updatedPlayers.associateBy { it.id }
            val blindTxs = blindContribs.map { (pid, amount) ->
                val label = when {
                    sortedPlayers.indexOfFirst { it.id == pid } == newBlinds.smallBlindIndex -> "小盲"
                    else -> "大盲"
                }
                ChipTransaction(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    handId = handId,
                    playerId = pid,
                    amount = -amount,
                    type = TransactionType.BLIND_DEDUCTION,
                    note = "${label}扣除",
                    balanceAfter = updatedMap[pid]?.chips ?: 0
                )
            }

            val prefilled = blindContribs.mapValues { (_, v) -> v.toString() }

            _uiState.update {
                it.copy(
                    players = updatedPlayers,
                    blindsState = newBlinds,
                    blindContributions = blindContribs,
                    contributionInputs = prefilled,
                    selectedWinnerIds = emptySet(),
                    lastSidePots = emptyList(),
                    logs = (it.logs + blindTxs).takeLast(500),
                    info = "Hand #${state.handCounter} | 庄:座位${newBlinds.dealerIndex} SB:座位${newBlinds.smallBlindIndex} BB:座位${newBlinds.bigBlindIndex}"
                )
            }

            viewModelScope.launch(Dispatchers.IO) {
                repository.save(_uiState.value.logs)
            }
        } else {
            _uiState.update {
                it.copy(
                    blindsState = newBlinds,
                    blindContributions = emptyMap(),
                    contributionInputs = emptyMap(),
                    selectedWinnerIds = emptySet(),
                    lastSidePots = emptyList(),
                    info = "Hand #${state.handCounter} | 庄:座位${newBlinds.dealerIndex} (无盲注)"
                )
            }
        }

        syncToClients()
    }

    // ==================== 投入 / 赢家 ====================

    fun updateContribution(playerId: String, value: String) {
        _uiState.update {
            it.copy(contributionInputs = it.contributionInputs + (playerId to value))
        }
    }

    fun toggleWinner(playerId: String) {
        _uiState.update { state ->
            val newSet = state.selectedWinnerIds.toMutableSet()
            if (!newSet.add(playerId)) newSet.remove(playerId)
            state.copy(selectedWinnerIds = newSet)
        }
    }

    // ==================== 结算（支持边池） ====================

    fun settleCurrentHand() {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) {
            _uiState.update { it.copy(info = "仅主持人可结算") }
            return
        }
        if (state.selectedWinnerIds.isEmpty()) {
            _uiState.update { it.copy(info = "请选择赢家") }
            return
        }

        val contributions = state.players.associate { player ->
            val raw = state.contributionInputs[player.id].orEmpty()
            player.id to (raw.toIntOrNull() ?: 0)
        }

        val handId = "HAND-${state.handCounter.toString().padStart(4, '0')}"
        val now = System.currentTimeMillis()

        val result = settlementEngine.settleHandSimple(
            handId = handId,
            players = state.players,
            contributions = contributions,
            winnerIds = state.selectedWinnerIds.toList(),
            timestamp = now
        )

        val potDescription = if (result.sidePots.size > 1) {
            result.sidePots.joinToString(" | ") { "${it.label}:${it.amount}" }
        } else {
            "底池 ${result.totalPot}"
        }

        val mergedLogs = (state.logs + result.transactions).takeLast(500)
        _uiState.update {
            it.copy(
                players = result.updatedPlayers,
                handCounter = it.handCounter + 1,
                selectedWinnerIds = emptySet(),
                contributionInputs = emptyMap(),
                blindContributions = emptyMap(),
                lastSidePots = result.sidePots,
                logs = mergedLogs,
                info = "$handId 结算完成 | $potDescription"
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.save(_uiState.value.logs)
        }

        syncToClients()
    }

    // ==================== 其他 ====================

    fun resetTable() {
        _uiState.update {
            it.copy(
                selectedWinnerIds = emptySet(),
                contributionInputs = emptyMap(),
                blindContributions = emptyMap(),
                lastSidePots = emptyList(),
                info = "已清空本手输入"
            )
        }
    }

    private fun syncToClients() {
        val state = _uiState.value
        if (state.mode == TableMode.HOST) {
            server.broadcastState(state.players, state.handCounter, state.logs)
        }
    }

    override fun onCleared() {
        super.onCleared()
        roomAdvertiser.close()
        roomScanner.close()
        server.close()
        client.close()
    }
}

class TableViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TableViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TableViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
