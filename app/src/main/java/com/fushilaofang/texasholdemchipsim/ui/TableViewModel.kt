package com.fushilaofang.texasholdemchipsim.ui

import android.content.Context
import android.content.SharedPreferences
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

/** UI 所在的屏幕 */
enum class ScreenState {
    HOME,           // 首页：创建 / 加入
    CREATE_ROOM,    // 创建房间设置
    JOIN_ROOM,      // 搜索并加入房间
    LOBBY,          // 等待准备
    GAME            // 游戏中
}

data class TableUiState(
    val mode: TableMode = TableMode.IDLE,
    val screen: ScreenState = ScreenState.HOME,
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
    val gameStarted: Boolean = false,
    // --- 盲注 ---
    val blindsState: BlindsState = BlindsState(),
    val blindsEnabled: Boolean = true,
    val blindContributions: Map<String, Int> = emptyMap(),
    // --- 边池 ---
    val lastSidePots: List<SidePot> = emptyList(),
    // --- 房间发现 ---
    val isScanning: Boolean = false,
    val discoveredRooms: List<DiscoveredRoom> = emptyList(),
    // --- 用户设置（持久化） ---
    val savedPlayerName: String = "",
    val savedRoomName: String = "家庭牌局",
    val savedBuyIn: Int = 1000,
    val savedSmallBlind: Int = 10,
    val savedBigBlind: Int = 20
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
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("user_settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        TableUiState(
            logs = repository.load().takeLast(200),
            savedPlayerName = prefs.getString("player_name", "") ?: "",
            savedRoomName = prefs.getString("room_name", "家庭牌局") ?: "家庭牌局",
            savedBuyIn = prefs.getInt("buy_in", 1000),
            savedSmallBlind = prefs.getInt("small_blind", 10),
            savedBigBlind = prefs.getInt("big_blind", 20)
        )
    )
    val uiState: StateFlow<TableUiState> = _uiState.asStateFlow()

    // ==================== 用户设置持久化 ====================

    fun savePlayerName(name: String) {
        _uiState.update { it.copy(savedPlayerName = name) }
        prefs.edit().putString("player_name", name).apply()
    }

    fun saveRoomName(name: String) {
        _uiState.update { it.copy(savedRoomName = name) }
        prefs.edit().putString("room_name", name).apply()
    }

    // ==================== 屏幕导航 ====================

    fun navigateTo(screen: ScreenState) {
        _uiState.update { it.copy(screen = screen) }
    }

    fun goHome() {
        // 断开连接，回到首页
        roomAdvertiser.close()
        roomScanner.close()
        server.close()
        client.close()
        _uiState.update {
            it.copy(
                mode = TableMode.IDLE,
                screen = ScreenState.HOME,
                players = emptyList(),
                gameStarted = false,
                selfId = "",
                isScanning = false,
                discoveredRooms = emptyList(),
                info = "准备开始"
            )
        }
    }

    fun saveBuyIn(value: Int) {
        _uiState.update { it.copy(savedBuyIn = value) }
        prefs.edit().putInt("buy_in", value).apply()
    }

    fun saveSmallBlind(value: Int) {
        _uiState.update { it.copy(savedSmallBlind = value) }
        prefs.edit().putInt("small_blind", value).apply()
    }

    fun saveBigBlind(value: Int) {
        _uiState.update { it.copy(savedBigBlind = value) }
        prefs.edit().putInt("big_blind", value).apply()
    }

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
            seatOrder = 0,
            isReady = true // 房主默认准备
        )
        val initialBlinds = blindsManager.initialize(1, blindsConfig)

        _uiState.update {
            it.copy(
                mode = TableMode.HOST,
                screen = ScreenState.LOBBY,
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
                gameStarted = false,
                isScanning = false,
                discoveredRooms = emptyList(),
                info = "已创建房间「${roomName.ifBlank { "家庭牌局" }}」| 等待玩家加入并准备"
            )
        }

        // 启动 TCP 服务
        server.start(
            hostPlayersProvider = { _uiState.value.players },
            handCounterProvider = { _uiState.value.handCounter },
            txProvider = { _uiState.value.logs },
            contributionsProvider = {
                _uiState.value.contributionInputs.mapValues { (_, v) -> v.toIntOrNull() ?: 0 }
            },
            blindsStateProvider = { _uiState.value.blindsState },
            blindsEnabledProvider = { _uiState.value.blindsEnabled },
            gameStartedProvider = { _uiState.value.gameStarted },
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
                    is LanTableServer.Event.PlayerDisconnected -> {
                        val pName = _uiState.value.players.firstOrNull { it.id == event.playerId }?.name ?: "?"
                        _uiState.update { state ->
                            state.copy(info = "$pName 掉线，等待重连...")
                        }
                        // 不移除玩家，等待重连或超时
                    }
                    is LanTableServer.Event.PlayerReconnected -> {
                        val pName = _uiState.value.players.firstOrNull { it.id == event.playerId }?.name ?: "?"
                        _uiState.update { it.copy(info = "$pName 已重连") }
                        syncToClients()
                    }
                    is LanTableServer.Event.ContributionReceived -> {
                        _uiState.update { state ->
                            val newInputs = state.contributionInputs + (event.playerId to event.amount.toString())
                            val playerName = state.players.firstOrNull { it.id == event.playerId }?.name ?: event.playerId.take(6)
                            state.copy(
                                contributionInputs = newInputs,
                                info = "$playerName 提交投入: ${event.amount}"
                            )
                        }
                        syncToClients()
                    }
                    is LanTableServer.Event.ReadyToggleReceived -> {
                        _uiState.update { state ->
                            val updatedPlayers = state.players.map {
                                if (it.id == event.playerId) it.copy(isReady = event.isReady) else it
                            }
                            val playerName = state.players.firstOrNull { it.id == event.playerId }?.name ?: "?"
                            state.copy(
                                players = updatedPlayers,
                                info = "$playerName ${if (event.isReady) "已准备" else "取消准备"}"
                            )
                        }
                        syncToClients()
                    }
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
                screen = ScreenState.LOBBY,
                tableName = room.roomName,
                hostIp = room.hostIp,
                selfName = playerName,
                gameStarted = false,
                isScanning = false,
                discoveredRooms = emptyList(),
                info = "正在加入「${room.roomName}」..."
            )
        }

        client.connect(room.hostIp, playerName.ifBlank { "玩家" }, buyIn) { event ->
            when (event) {
                is LanTableClient.Event.JoinAccepted ->
                    _uiState.update { it.copy(selfId = event.playerId, info = "已加入「${room.roomName}」") }
                is LanTableClient.Event.StateSync -> {
                    _uiState.update {
                        val newScreen = if (event.gameStarted) ScreenState.GAME else it.screen
                        it.copy(
                            screen = newScreen,
                            players = event.players,
                            handCounter = event.handCounter,
                            logs = event.transactions.takeLast(200),
                            contributionInputs = event.contributions.mapValues { (_, v) -> v.toString() },
                            blindsState = event.blindsState,
                            blindsEnabled = event.blindsEnabled,
                            gameStarted = event.gameStarted,
                            info = if (event.gameStarted) "牌局状态已同步" else "等待房主开始游戏"
                        )
                    }
                }
                is LanTableClient.Event.Error ->
                    _uiState.update { it.copy(info = event.message) }
                is LanTableClient.Event.Disconnected -> {
                    if (event.willReconnect) {
                        _uiState.update { it.copy(info = "连接断开，正在尝试重连...") }
                    } else {
                        _uiState.update { it.copy(info = "连接断开") }
                    }
                }
                is LanTableClient.Event.Reconnected -> {
                    _uiState.update { it.copy(info = "重连成功") }
                }
                is LanTableClient.Event.ReconnectFailed -> {
                    _uiState.update { it.copy(info = event.reason) }
                }
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
        if (_uiState.value.mode == TableMode.HOST) {
            syncToClients()
        }
    }

    /** 房主移除指定玩家 */
    fun removePlayer(playerId: String) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        if (playerId == state.selfId) return // 不能移除自己

        val pName = state.players.firstOrNull { it.id == playerId }?.name ?: "?"
        server.kickPlayer(playerId)
        _uiState.update { s ->
            s.copy(
                players = s.players.filter { it.id != playerId },
                info = "已移除 $pName"
            )
        }
        syncToClients()
    }

    // ==================== 准备 / 开始游戏 ====================

    /** 非房主玩家切换准备状态 */
    fun toggleReady() {
        val state = _uiState.value
        val selfId = state.selfId
        if (selfId.isBlank()) return

        val currentReady = state.players.firstOrNull { it.id == selfId }?.isReady ?: false
        val newReady = !currentReady

        _uiState.update { s ->
            s.copy(
                players = s.players.map { if (it.id == selfId) it.copy(isReady = newReady) else it },
                info = if (newReady) "已准备" else "已取消准备"
            )
        }

        if (state.mode == TableMode.CLIENT) {
            client.sendReady(selfId, newReady)
        }
        if (state.mode == TableMode.HOST) {
            syncToClients()
        }
    }

    /** 房主点击"开始游戏" */
    fun startGame() {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        if (state.players.size < 2) {
            _uiState.update { it.copy(info = "至少需要 2 名玩家") }
            return
        }
        val notReady = state.players.filter { !it.isReady }
        if (notReady.isNotEmpty()) {
            val names = notReady.joinToString(", ") { it.name }
            _uiState.update { it.copy(info = "以下玩家未准备: $names") }
            return
        }

        // 初始化盲注位
        val blinds = blindsManager.initialize(state.players.size, state.blindsState.config)
        val blindPrefills = if (state.blindsEnabled) {
            blindsManager.calculateBlindPrefills(state.players, blinds)
        } else emptyMap()

        _uiState.update {
            it.copy(
                screen = ScreenState.GAME,
                gameStarted = true,
                blindsState = blinds,
                blindContributions = blindPrefills,
                contributionInputs = blindPrefills.mapValues { (_, v) -> v.toString() },
                info = "游戏开始！Hand #${state.handCounter}"
            )
        }
        syncToClients()
    }

    /**
     * 结算本手并自动开始下一手：结算→轮转庄位→扣除盲注
     * 如果是第一手（尚无人提交投入），则跳过结算直接开始。
     */
    fun settleAndAdvance() {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) {
            _uiState.update { it.copy(info = "仅房主可结算") }
            return
        }
        if (state.players.size < 2) {
            _uiState.update { it.copy(info = "至少需要2名玩家") }
            return
        }

        // ---------- 1) 结算当前手（如果有投入）----------
        val hasContributions = state.contributionInputs.values.any { (it.toIntOrNull() ?: 0) > 0 }
        var playersAfterSettle = state.players
        var logsAfterSettle = state.logs
        var settleInfo = ""
        var sidePots: List<SidePot> = emptyList()

        if (hasContributions) {
            if (state.selectedWinnerIds.isEmpty()) {
                _uiState.update { it.copy(info = "请选择赢家后再结算") }
                return
            }

            // 盲注规则校验
            if (state.blindsEnabled && state.players.size >= 2) {
                val blindsViolations = validateBlinds(state)
                if (blindsViolations.isNotEmpty()) {
                    _uiState.update { it.copy(info = "盲注校验失败: ${blindsViolations.joinToString("; ")}") }
                    return
                }
            }

            val contributions = state.players.associate { player ->
                val raw = state.contributionInputs[player.id].orEmpty()
                player.id to (raw.toIntOrNull() ?: 0)
            }

            val handId = "第${state.handCounter}手"
            val now = System.currentTimeMillis()

            val result = settlementEngine.settleHandSimple(
                handId = handId,
                players = state.players,
                contributions = contributions,
                winnerIds = state.selectedWinnerIds.toList(),
                timestamp = now
            )

            playersAfterSettle = result.updatedPlayers
            logsAfterSettle = (state.logs + result.transactions).takeLast(500)
            sidePots = result.sidePots
            settleInfo = if (result.sidePots.size > 1) {
                result.sidePots.joinToString(" | ") { "${it.label}:${it.amount}" }
            } else {
                "底池 ${result.totalPot}"
            }
        }

        // ---------- 2) 轮转庄位 + 扣盲注（下一手准备）----------
        val nextHandCounter = if (hasContributions) state.handCounter + 1 else state.handCounter
        val newBlinds = blindsManager.rotate(state.blindsState, playersAfterSettle.size)

        if (state.blindsEnabled) {
            // 只预填盲注金额到 contributionInputs，不从筹码中扣除
            // 结算引擎会在下一手结算时统一处理扣款
            val blindPrefills = blindsManager.calculateBlindPrefills(playersAfterSettle, newBlinds)
            val prefilled = blindPrefills.mapValues { (_, v) -> v.toString() }

            val infoText = buildString {
                if (settleInfo.isNotEmpty()) append("结算完成: $settleInfo | ")
                append("Hand #$nextHandCounter | 庄:座位${newBlinds.dealerIndex} 小盲:座位${newBlinds.smallBlindIndex} 大盲:座位${newBlinds.bigBlindIndex}")
            }

            _uiState.update {
                it.copy(
                    players = playersAfterSettle,
                    handCounter = nextHandCounter,
                    blindsState = newBlinds,
                    blindContributions = blindPrefills,
                    contributionInputs = prefilled,
                    selectedWinnerIds = emptySet(),
                    lastSidePots = sidePots,
                    logs = logsAfterSettle,
                    info = infoText
                )
            }
        } else {
            val infoText = buildString {
                if (settleInfo.isNotEmpty()) append("结算完成: $settleInfo | ")
                append("Hand #$nextHandCounter | 庄:座位${newBlinds.dealerIndex} (无盲注)")
            }

            _uiState.update {
                it.copy(
                    players = playersAfterSettle,
                    handCounter = nextHandCounter,
                    blindsState = newBlinds,
                    blindContributions = emptyMap(),
                    contributionInputs = emptyMap(),
                    selectedWinnerIds = emptySet(),
                    lastSidePots = sidePots,
                    logs = logsAfterSettle,
                    info = infoText
                )
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.save(_uiState.value.logs)
        }

        syncToClients()
    }

    // ==================== 投入 / 赢家 ====================

    /**
     * 本地更新投入输入框（仅用于房主直接编辑自己的投入）
     */
    fun updateContribution(playerId: String, value: String) {
        _uiState.update {
            it.copy(contributionInputs = it.contributionInputs + (playerId to value))
        }
    }

    /**
     * 玩家提交自己的本手投入（房主直接更新本地，客户端发送到服务端）
     * 投入代表本手总投入（包含盲注），结算时由引擎统一从筹码扣除。
     */
    fun submitMyContribution(amount: Int) {
        val state = _uiState.value
        val selfId = state.selfId
        if (selfId.isBlank()) return

        val player = state.players.firstOrNull { it.id == selfId } ?: return

        // 投入不能超过筹码
        if (amount > player.chips) {
            _uiState.update { it.copy(info = "投入不能超过筹码 (${player.chips})") }
            return
        }

        // 盲注规则校验
        if (state.blindsEnabled && state.players.size >= 2 && amount > 0) {
            val minRequired = getMinContribution(selfId)
            if (amount < minRequired) {
                _uiState.update { it.copy(info = "投入不足: 最低需要 $minRequired") }
                return
            }
        }

        if (state.mode == TableMode.HOST) {
            _uiState.update {
                it.copy(
                    contributionInputs = it.contributionInputs + (selfId to amount.toString()),
                    info = "已提交投入: $amount"
                )
            }
            syncToClients()
        } else if (state.mode == TableMode.CLIENT) {
            client.sendContribution(selfId, amount)
            _uiState.update {
                it.copy(
                    contributionInputs = it.contributionInputs + (selfId to amount.toString()),
                    info = "已提交投入: $amount"
                )
            }
        }
    }

    /**
     * 盲注规则校验，委托给 BlindsManager
     */
    private fun validateBlinds(state: TableUiState): List<String> {
        val contributions = state.players.associate { player ->
            player.id to (state.contributionInputs[player.id]?.toIntOrNull() ?: 0)
        }
        return blindsManager.validateContributions(state.players, state.blindsState, contributions)
    }

    /**
     * 获取玩家的最低投入要求（盲注开启时使用）
     */
    fun getMinContribution(playerId: String): Int {
        val state = _uiState.value
        if (!state.blindsEnabled || state.players.size < 2) return 0
        val sortedPlayers = state.players.sortedBy { it.seatOrder }
        val blinds = state.blindsState
        val index = sortedPlayers.indexOfFirst { it.id == playerId }
        if (index < 0) return 0
        val player = sortedPlayers[index]
        return blindsManager.getMinContribution(index, player.chips, blinds)
    }

    fun toggleWinner(playerId: String) {
        _uiState.update { state ->
            val newSet = state.selectedWinnerIds.toMutableSet()
            if (!newSet.add(playerId)) newSet.remove(playerId)
            state.copy(selectedWinnerIds = newSet)
        }
    }

    // ==================== 结算已整合到 settleAndAdvance ====================


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
            val contribs = state.contributionInputs.mapValues { (_, v) -> v.toIntOrNull() ?: 0 }
            viewModelScope.launch(Dispatchers.IO) {
                server.broadcastState(
                    players = state.players,
                    handCounter = state.handCounter,
                    transactions = state.logs,
                    contributions = contribs,
                    blindsState = state.blindsState,
                    blindsEnabled = state.blindsEnabled,
                    gameStarted = state.gameStarted
                )
            }
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
