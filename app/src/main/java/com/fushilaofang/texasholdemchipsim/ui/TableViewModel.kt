package com.fushilaofang.texasholdemchipsim.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
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
import com.fushilaofang.texasholdemchipsim.settlement.SidePotCalculator
import com.fushilaofang.texasholdemchipsim.util.DeviceIdManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

enum class BettingRound {
    PRE_FLOP, FLOP, TURN, RIVER, SHOWDOWN
}

/** 中途加入申请信息（仅房主展示） */
data class PendingMidJoinInfo(
    val requestId: String,
    val playerName: String,
    val buyIn: Int,
    val avatarBase64: String = ""
)

/** 客户端中途加入的审批状态 */
enum class MidGameJoinStatus { NONE, PENDING, REJECTED, BLOCKED }

data class TableUiState(
    val mode: TableMode = TableMode.IDLE,
    val screen: ScreenState = ScreenState.HOME,
    val tableName: String = "家庭牌局",
    val selfId: String = "",
    val selfName: String = "",
    val hostIp: String = "",
    val players: List<PlayerState> = emptyList(),
    val selectedWinnerIds: Set<String> = emptySet(),
    val foldedPlayerIds: Set<String> = emptySet(),
    val contributionInputs: Map<String, String> = emptyMap(),
    val handCounter: Int = 1,
    val logs: List<ChipTransaction> = emptyList(),
    val info: String = "准备开始",
    val gameStarted: Boolean = false,
    // --- 轮次/回合 ---
    val currentRound: BettingRound = BettingRound.PRE_FLOP,
    val currentTurnPlayerId: String = "",
    val roundContributions: Map<String, Int> = emptyMap(),
    val actedPlayerIds: Set<String> = emptySet(),
    val initialDealerIndex: Int = 0,
    // --- 盲注 ---
    val blindsState: BlindsState = BlindsState(),
    val blindsEnabled: Boolean = true,
    val sidePotEnabled: Boolean = true,
    val blindContributions: Map<String, Int> = emptyMap(),
    // --- 边池 ---
    val lastSidePots: List<SidePot> = emptyList(),
    // --- 掉线玩家 ---
    val disconnectedPlayerIds: Set<String> = emptySet(),
    // --- 等待房主重连 ---
    val waitingForHostReconnect: Boolean = false,
    // --- 重新加入 ---
    val canRejoin: Boolean = false,
    val lastSessionTableName: String = "",
    val lastSessionMode: TableMode = TableMode.IDLE,
    // --- 房间发现 ---
    val isScanning: Boolean = false,
    val discoveredRooms: List<DiscoveredRoom> = emptyList(),
    // --- 用户设置（持久化） ---
    val savedPlayerName: String = "",
    val savedRoomName: String = "家庭牌局",
    val savedBuyIn: Int = 1000,
    val savedSmallBlind: Int = 10,
    val savedBigBlind: Int = 20,
    val savedAvatarBase64: String = "",  // 本机玩家头像
    // --- 中途加入 ---
    val allowMidGameJoin: Boolean = false,
    val pendingMidJoins: List<PendingMidJoinInfo> = emptyList(),
    val midGameJoinStatus: MidGameJoinStatus = MidGameJoinStatus.NONE,
    val midGameWaitingPlayerIds: Set<String> = emptySet()
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
    private val sessionJson = Json { ignoreUnknownKeys = true }
    
    /** 设备唯一ID */
    private val deviceId: String = DeviceIdManager.getDeviceId(context.applicationContext)

    /** 持有 CPU 唤醒锁，防止后台挂起导致心跳断线 */
    private val wakeLock: PowerManager.WakeLock? =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TexasHoldem:NetworkLock")
            ?.apply { setReferenceCounted(false) }

    private fun acquireWakeLock() {
        try { if (wakeLock?.isHeld == false) wakeLock.acquire(4 * 60 * 60 * 1000L) } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
    }

    private val _uiState = MutableStateFlow(
        TableUiState(
            savedPlayerName = prefs.getString("player_name", "") ?: "",
            savedRoomName = prefs.getString("room_name", "家庭牌局") ?: "家庭牌局",
            savedBuyIn = prefs.getInt("buy_in", 1000),
            savedSmallBlind = prefs.getInt("small_blind", 10),
            savedBigBlind = prefs.getInt("big_blind", 20),
            savedAvatarBase64 = prefs.getString("avatar_base64", "") ?: ""
        )
    )
    val uiState: StateFlow<TableUiState> = _uiState.asStateFlow()

    init {
        loadSessionInfo()
    }

    // ==================== 用户设置持久化 ====================

    fun savePlayerName(name: String) {
        _uiState.update { it.copy(savedPlayerName = name) }
        prefs.edit().putString("player_name", name).apply()
    }

    fun saveAvatarBase64(base64: String) {
        _uiState.update { it.copy(savedAvatarBase64 = base64) }
        prefs.edit().putString("avatar_base64", base64).apply()
        // 同时更新已在房间内的玩家头像
        val selfId = _uiState.value.selfId
        if (selfId.isNotBlank()) {
            _uiState.update { state ->
                state.copy(
                    players = state.players.map {
                        if (it.id == selfId) it.copy(avatarBase64 = base64) else it
                    }
                )
            }
            if (_uiState.value.mode == TableMode.HOST) {
                syncToClients()
            } else if (_uiState.value.mode == TableMode.CLIENT) {
                val name = _uiState.value.players.firstOrNull { it.id == selfId }?.name
                    ?: _uiState.value.savedPlayerName
                client.sendUpdateProfile(selfId, name, base64)
            }
        }
    }

    /**
     * 玩家更新自己的昵称和头像（房主或客户端均可调用）
     */
    fun updateMyProfile(newName: String, avatarBase64: String) {
        val state = _uiState.value
        val selfId = state.selfId
        if (selfId.isBlank()) return
        _uiState.update { s ->
            s.copy(
                savedPlayerName = newName,
                savedAvatarBase64 = avatarBase64,
                players = s.players.map {
                    if (it.id == selfId) it.copy(name = newName, avatarBase64 = avatarBase64) else it
                }
            )
        }
        prefs.edit().putString("player_name", newName).putString("avatar_base64", avatarBase64).apply()
        if (state.mode == TableMode.HOST) {
            syncToClients()
        } else if (state.mode == TableMode.CLIENT) {
            client.sendUpdateProfile(selfId, newName, avatarBase64)
        }
    }

    /**
     * 房主强制更新任意玩家的头像（仅头像，不能改名）
     */
    fun updatePlayerAvatar(playerId: String, avatarBase64: String) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        _uiState.update { s ->
            s.copy(
                players = s.players.map {
                    if (it.id == playerId) it.copy(avatarBase64 = avatarBase64) else it
                }
            )
        }
        syncToClients()
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
        // 保存会话信息以便后续重连
        val prevMode = _uiState.value.mode
        val prevTableName = _uiState.value.tableName
        saveSession()
        releaseWakeLock()
        // 断开连接，回到首页（使用 stop/disconnect 而非 close，保留 scope 以便重连）
        roomAdvertiser.stopBroadcast()
        roomScanner.stopScan()
        server.stop()
        client.disconnect()
        val canRejoin = prevMode != TableMode.IDLE
        _uiState.update {
            it.copy(
                mode = TableMode.IDLE,
                screen = ScreenState.HOME,
                players = emptyList(),
                gameStarted = false,
                selfId = "",
                isScanning = false,
                discoveredRooms = emptyList(),
                disconnectedPlayerIds = emptySet(),
                waitingForHostReconnect = false,
                canRejoin = canRejoin,
                lastSessionTableName = if (canRejoin) prevTableName else "",
                lastSessionMode = if (canRejoin) prevMode else TableMode.IDLE,
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
        clearSession()
        val hostId = UUID.randomUUID().toString()
        val hostPlayer = PlayerState(
            id = hostId,
            name = hostName.ifBlank { "庄家" },
            chips = buyIn,
            seatOrder = 0,
            isReady = true, // 房主默认准备
            avatarBase64 = _uiState.value.savedAvatarBase64,
            deviceId = deviceId
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
                logs = emptyList(),
                isScanning = false,
                discoveredRooms = emptyList(),
                canRejoin = false,
                info = "已创建房间「${roomName.ifBlank { "家庭牌局" }}」| 等待玩家加入并准备"
            )
        }

        startServer()
        startAdvertiser()
    }

    /**
     * 通过选择已发现的房间加入
     */
    fun joinRoom(room: DiscoveredRoom, playerName: String, buyIn: Int) {
        clearSession()
        stopRoomScan()
        _uiState.update {
            it.copy(
                mode = TableMode.CLIENT,
                screen = ScreenState.LOBBY,
                tableName = room.roomName,
                hostIp = room.hostIp,
                selfName = playerName,
                gameStarted = false,
                logs = emptyList(),
                isScanning = false,
                discoveredRooms = emptyList(),
                canRejoin = false,
                info = "正在加入「${room.roomName}」..."
            )
        }

        client.connect(room.hostIp, playerName.ifBlank { "玩家" }, buyIn, deviceId, _uiState.value.savedAvatarBase64, ::handleClientEvent)
        acquireWakeLock()
    }

    // ==================== 盲注 ====================

    fun updateBlindsConfig(smallBlind: Int, bigBlind: Int) {
        _uiState.update { state ->
            val newConfig = BlindsConfig(
                smallBlind = smallBlind.coerceAtLeast(1),
                bigBlind = bigBlind.coerceAtLeast(2)
            )
            state.copy(
                blindsState = state.blindsState.copy(config = newConfig),
                savedSmallBlind = newConfig.smallBlind,
                savedBigBlind = newConfig.bigBlind
            )
        }
        // 持久化 + 同步
        prefs.edit()
            .putInt("small_blind", _uiState.value.savedSmallBlind)
            .putInt("big_blind", _uiState.value.savedBigBlind)
            .apply()
        if (_uiState.value.mode == TableMode.HOST) {
            syncToClients()
        }
    }

    fun toggleBlinds(enabled: Boolean) {
        _uiState.update { it.copy(blindsEnabled = enabled) }
        if (_uiState.value.mode == TableMode.HOST) {
            syncToClients()
        }
    }

    fun toggleSidePot(enabled: Boolean) {
        _uiState.update { it.copy(sidePotEnabled = enabled) }
        if (_uiState.value.mode == TableMode.HOST) {
            syncToClients()
        }
    }

    // ==================== 中途加入管理（房主） ====================

    fun toggleAllowMidGameJoin(enabled: Boolean) {
        if (_uiState.value.mode != TableMode.HOST) return
        _uiState.update { it.copy(allowMidGameJoin = enabled) }
        syncToClients()
    }

    fun approveMidGameJoin(requestId: String) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        val pending = state.pendingMidJoins.firstOrNull { it.requestId == requestId } ?: return
        val newPlayerId = UUID.randomUUID().toString()
        val seatOrder = state.players.size
        val player = PlayerState(
            id = newPlayerId,
            name = pending.playerName,
            chips = pending.buyIn,
            seatOrder = seatOrder,
            avatarBase64 = pending.avatarBase64
        )
        _uiState.update { s ->
            s.copy(
                players = s.players + player,
                midGameWaitingPlayerIds = s.midGameWaitingPlayerIds + newPlayerId,
                pendingMidJoins = s.pendingMidJoins.filter { it.requestId != requestId },
                info = "${pending.playerName} 的加入请求已批准，等待下一手..."
            )
        }
        server.approveMidGameJoin(requestId, newPlayerId)
        syncToClients()
        saveSession()
    }

    fun rejectMidGameJoin(requestId: String, block: Boolean) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        val pending = state.pendingMidJoins.firstOrNull { it.requestId == requestId } ?: return
        _uiState.update { s ->
            s.copy(
                pendingMidJoins = s.pendingMidJoins.filter { it.requestId != requestId },
                info = "${pending.playerName} 的加入请求已${if (block) "屏蔽" else "拒绝"}"
            )
        }
        server.rejectMidGameJoin(requestId, block)
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

    // ==================== 轮次 / 回合管理 ====================

    /** 获取按座位排序的活跃玩家（未弃牌、未掉线） */
    private fun getActivePlayers(state: TableUiState): List<PlayerState> {
        val sorted = state.players.sortedBy { it.seatOrder }
        return sorted.filter { p ->
            !state.foldedPlayerIds.contains(p.id) &&
            !state.disconnectedPlayerIds.contains(p.id) &&
            !state.midGameWaitingPlayerIds.contains(p.id)
        }
    }

    /** 判断玩家是否已 all-in（筹码耗尽）*/
    private fun isPlayerAllIn(state: TableUiState, player: PlayerState): Boolean {
        return player.chips <= 0
    }

    /**
     * 计算下一个行动玩家的 ID。
     * 跳过弃牌、掉线、已 all-in 的玩家。
     */
    private fun getNextTurnPlayerId(state: TableUiState, afterPlayerId: String): String {
        val sorted = state.players.sortedBy { it.seatOrder }
        val actionable = sorted.filter { p ->
            !state.foldedPlayerIds.contains(p.id) &&
            !state.disconnectedPlayerIds.contains(p.id) &&
            !isPlayerAllIn(state, p)
        }
        if (actionable.isEmpty()) return ""

        // 兜底：唯一可行动玩家且已行动 → 直接推进轮次
        if (actionable.size == 1 && state.actedPlayerIds.contains(actionable[0].id)) return ""

        val currentIdx = actionable.indexOfFirst { it.id == afterPlayerId }
        if (currentIdx != -1) {
            val nextIdx = (currentIdx + 1) % actionable.size
            // 再次回到自己且已行动 → 轮次完成
            if (nextIdx == currentIdx) return ""
            return actionable[nextIdx].id
        }

        // afterPlayerId 不在 actionable 中（如刚弃牌/all-in），按座位找下一个
        val afterSeat = sorted.firstOrNull { it.id == afterPlayerId }?.seatOrder ?: return actionable.first().id
        val nextActive = actionable.firstOrNull { it.seatOrder > afterSeat } ?: actionable.first()
        return nextActive.id
    }

    /** 翻牌前首个行动玩家 = BB 之后的下一位可行动玩家 */
    private fun getPreFlopFirstPlayerId(state: TableUiState, blinds: BlindsState): String {
        val sorted = state.players.sortedBy { it.seatOrder }
        val actionable = sorted.filter { p ->
            !state.foldedPlayerIds.contains(p.id) &&
            !state.disconnectedPlayerIds.contains(p.id) &&
            !isPlayerAllIn(state, p)
        }
        if (actionable.isEmpty()) return ""
        val bbPlayer = sorted.getOrNull(blinds.bigBlindIndex) ?: return actionable.first().id
        val bbIdxInActionable = actionable.indexOfFirst { it.id == bbPlayer.id }
        if (bbIdxInActionable == -1) return actionable.first().id
        return actionable[(bbIdxInActionable + 1) % actionable.size].id
    }

    /** 翻牌后首个行动玩家 = 庄家之后的第一位可行动玩家 */
    private fun getPostFlopFirstPlayerId(state: TableUiState, blinds: BlindsState): String {
        val sorted = state.players.sortedBy { it.seatOrder }
        val actionable = sorted.filter { p ->
            !state.foldedPlayerIds.contains(p.id) &&
            !state.disconnectedPlayerIds.contains(p.id) &&
            !isPlayerAllIn(state, p)
        }
        if (actionable.isEmpty()) return ""
        val dealerPlayer = sorted.getOrNull(blinds.dealerIndex) ?: return actionable.first().id
        val dealerIdxInActionable = actionable.indexOfFirst { it.id == dealerPlayer.id }
        if (dealerIdxInActionable == -1) return actionable.first().id
        return actionable[(dealerIdxInActionable + 1) % actionable.size].id
    }

    /**
     * 检查当前轮是否完成：
     * - 所有活跃玩家都已行动（actedPlayerIds）
     * - 所有已行动的玩家投入匹配最高投入（或已 all-in）
     *
     * 关键：All-In 玩家无法再行动，视为永久"已行动"，
     *       不要求出现在 actedPlayerIds 中，避免加注清空 acted 集合后死循环。
     */
    private fun isRoundComplete(state: TableUiState): Boolean {
        val activePlayers = getActivePlayers(state)
        if (activePlayers.size <= 1) return true

        // 若除 all-in 玩家外只剩 0 位可行动玩家，直接完成
        val actionablePlayers = activePlayers.filter { !isPlayerAllIn(state, it) }
        if (actionablePlayers.isEmpty()) return true

        val maxBet = state.roundContributions.values.maxOrNull() ?: 0
        return activePlayers.all { p ->
            val rc = state.roundContributions[p.id] ?: 0
            val isAllIn = isPlayerAllIn(state, p)
            // All-In 玩家：只要投入不超过 maxBet（普通情况），直接视为完成
            if (isAllIn) return@all true
            // 普通玩家：必须已行动且投入匹配最高注
            state.actedPlayerIds.contains(p.id) && rc == maxBet
        }
    }

    /** 推进到下一个轮次（FLOP → TURN → RIVER → SHOWDOWN） */
    private fun advanceRound() {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return

        val nextRound = when (state.currentRound) {
            BettingRound.PRE_FLOP -> BettingRound.FLOP
            BettingRound.FLOP -> BettingRound.TURN
            BettingRound.TURN -> BettingRound.RIVER
            BettingRound.RIVER -> BettingRound.SHOWDOWN
            BettingRound.SHOWDOWN -> return
        }

        // 将本轮投入累加到总投入
        val newContribs = state.contributionInputs.toMutableMap()
        state.roundContributions.forEach { (pid, rc) ->
            val prev = newContribs[pid]?.toIntOrNull() ?: 0
            newContribs[pid] = (prev + rc).toString()
        }

        val activePlayers = getActivePlayers(state)

        // 可行动的玩家（非 all-in）
        val updatedState = state.copy(contributionInputs = newContribs, roundContributions = emptyMap())
        val actionable = activePlayers.filter { p -> !isPlayerAllIn(updatedState, p) }

        if (nextRound == BettingRound.SHOWDOWN || activePlayers.size <= 1 || actionable.size <= 1) {
            // 进入摊牌 或 仅剩一人/只剩 all-in 玩家 → 自动进入 SHOWDOWN
            // 实时计算本手边池结构，供摊牌阶段展示
            val showdownContribs = newContribs.mapValues { (_, v) -> v.toIntOrNull() ?: 0 }
            val showdownSidePots = if (state.sidePotEnabled) SidePotCalculator.buildPots(showdownContribs) else emptyList()
            _uiState.update {
                it.copy(
                    currentRound = BettingRound.SHOWDOWN,
                    currentTurnPlayerId = "",
                    roundContributions = emptyMap(),
                    actedPlayerIds = emptySet(),
                    contributionInputs = newContribs,
                    lastSidePots = showdownSidePots,
                    info = "摊牌阶段 - 请标记赢家并结算"
                )
            }
        } else {
            val firstPlayer = getPostFlopFirstPlayerId(updatedState, state.blindsState)
            val roundName = when (nextRound) {
                BettingRound.FLOP -> "翻牌圈"
                BettingRound.TURN -> "转牌圈"
                BettingRound.RIVER -> "河牌圈"
                else -> ""
            }
            val turnName = state.players.firstOrNull { it.id == firstPlayer }?.name ?: "?"
            _uiState.update {
                it.copy(
                    currentRound = nextRound,
                    currentTurnPlayerId = firstPlayer,
                    roundContributions = emptyMap(),
                    actedPlayerIds = emptySet(),
                    contributionInputs = newContribs,
                    info = "$roundName 开始 - $turnName 行动"
                )
            }
        }
        syncToClients()
        saveSession()
    }

    /** 当前轮行动后，推进到下一位玩家或下一轮 */
    private fun advanceTurnOrRound() {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return

        val activePlayers = getActivePlayers(state)
        if (activePlayers.size <= 1) {
            advanceRound()
            return
        }

        if (isRoundComplete(state)) {
            advanceRound()
        } else {
            val nextPlayer = getNextTurnPlayerId(state, state.currentTurnPlayerId)
            if (nextPlayer.isBlank()) {
                advanceRound()
                return
            }
            val pName = state.players.firstOrNull { it.id == nextPlayer }?.name ?: "?"
            _uiState.update {
                it.copy(
                    currentTurnPlayerId = nextPlayer,
                    info = "$pName 行动"
                )
            }
            syncToClients()
            saveSession()
        }
    }

    // ==================== 投入/弃牌处理（HOST 逻辑，服务端和本地共用） ====================

    /** 将 BettingRound 转为中文显示名称 */
    private fun BettingRound.label() = when (this) {
        BettingRound.PRE_FLOP -> "翻前"
        BettingRound.FLOP     -> "翻牌"
        BettingRound.TURN     -> "转牌"
        BettingRound.RIVER    -> "河牌"
        BettingRound.SHOWDOWN -> "摊牌"
    }

    /**
     * 处理投入请求（仅 HOST 调用）
     * @param playerId 玩家 ID
     * @param amount 增量金额
     */
    private fun processContribution(playerId: String, amount: Int) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        if (state.currentRound == BettingRound.SHOWDOWN) return

        val player = state.players.firstOrNull { it.id == playerId } ?: return
        val currentRoundContrib = state.roundContributions[playerId] ?: 0
        val newRoundContrib = currentRoundContrib + amount
        val currentMaxBet = state.roundContributions.values.maxOrNull() ?: 0
        val isRaise = newRoundContrib > currentMaxBet
        val isAllIn = amount > 0 && player.chips <= amount  // 投入后筹码归零
        val rl = state.currentRound.label()

        val (actionType, actionNote) = when {
            amount == 0           -> Pair(TransactionType.CHECK, "[$rl] 过牌")
            isAllIn               -> Pair(TransactionType.ALL_IN, "[$rl] 全压 $amount")
            isRaise && currentMaxBet == 0 -> Pair(TransactionType.BET,   "[$rl] 下注 $amount")
            isRaise               -> Pair(TransactionType.RAISE,  "[$rl] 加注至 $newRoundContrib")
            else                  -> Pair(TransactionType.CALL,   "[$rl] 跟注 $amount")
        }

        val actionLog = ChipTransaction(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            handId = "第${state.handCounter}手",
            playerId = playerId,
            playerName = player.name,
            amount = if (amount > 0) -amount else 0,
            type = actionType,
            note = actionNote,
            balanceAfter = player.chips - amount
        )

        val playerName = player.name
        _uiState.update {
            val newRoundContribs = it.roundContributions + (playerId to newRoundContrib)
            val newActed = if (isRaise) setOf(playerId) else it.actedPlayerIds + playerId
            val updatedPlayers = it.players.map { p ->
                if (p.id == playerId && amount > 0) p.copy(chips = p.chips - amount) else p
            }
            it.copy(
                players = updatedPlayers,
                roundContributions = newRoundContribs,
                actedPlayerIds = newActed,
                logs = (it.logs + actionLog).takeLast(500),
                info = "$playerName 投入本轮: $newRoundContrib${if (isRaise) " (加注)" else ""}"
            )
        }
        advanceTurnOrRound()
    }

    /**
     * 处理弃牌请求（仅 HOST 调用）
     */
    private fun processFold(playerId: String) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return

        val player = state.players.firstOrNull { it.id == playerId } ?: return
        val rl = state.currentRound.label()
        val foldLog = ChipTransaction(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            handId = "第${state.handCounter}手",
            playerId = playerId,
            playerName = player.name,
            amount = 0,
            type = TransactionType.FOLD,
            note = "[$rl] 弃牌",
            balanceAfter = player.chips
        )
        _uiState.update {
            it.copy(
                foldedPlayerIds = it.foldedPlayerIds + playerId,
                logs = (it.logs + foldLog).takeLast(500),
                info = "${player.name} 已弃牌"
            )
        }
        advanceTurnOrRound()
    }

    // ==================== 大厅：排序 / 设庄 ====================

    /** 房主交换两个座位的顺序 */
    fun swapSeatOrder(fromPlayerId: String, toPlayerId: String) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        val fromP = state.players.firstOrNull { it.id == fromPlayerId } ?: return
        val toP = state.players.firstOrNull { it.id == toPlayerId } ?: return
        val newPlayers = state.players.map {
            when (it.id) {
                fromPlayerId -> it.copy(seatOrder = toP.seatOrder)
                toPlayerId -> it.copy(seatOrder = fromP.seatOrder)
                else -> it
            }
        }
        _uiState.update { it.copy(players = newPlayers) }
        syncToClients()
    }

    /** 房主移动玩家到指定位置 */
    fun movePlayer(playerId: String, newIndex: Int) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        val sorted = state.players.sortedBy { it.seatOrder }.toMutableList()
        val player = sorted.firstOrNull { it.id == playerId } ?: return
        sorted.remove(player)
        val safeIndex = newIndex.coerceIn(0, sorted.size)
        sorted.add(safeIndex, player)
        val reindexed = sorted.mapIndexed { idx, p -> p.copy(seatOrder = idx) }
        _uiState.update { it.copy(players = reindexed) }
        syncToClients()
    }

    /** 房主设置首手庄家索引 */
    fun setInitialDealer(playerIndex: Int) {
        if (_uiState.value.mode != TableMode.HOST) return
        _uiState.update { it.copy(initialDealerIndex = playerIndex) }
        syncToClients()
    }

    /**
     * 游戏中修改庄家（手间空档期可调用）
     * 重新计算 SB/BB 位并同步客户端
     */
    fun setDealerInGame(playerIndex: Int) {
        val state = _uiState.value
        if (state.mode != TableMode.HOST) return
        val newBlinds = if (state.blindsEnabled) {
            blindsManager.computeFromDealerSkippingBroke(playerIndex, state.players, state.blindsState.config)
        } else {
            blindsManager.computeFromDealer(playerIndex, state.players.size, state.blindsState.config)
        }
        // 重新计算盲注预填（盲注已在手开始时扣除，此处只更新显示位置和 roundContributions）
        val blindPrefills = if (state.blindsEnabled) {
            blindsManager.calculateBlindPrefills(state.players, newBlinds)
        } else emptyMap()
        _uiState.update {
            it.copy(
                blindsState = newBlinds,
                blindContributions = blindPrefills,
                roundContributions = if (state.blindsEnabled) blindPrefills else emptyMap()
            )
        }
        syncToClients()
    }

    // ==================== 开始游戏 / 结算 ====================

    /**
     * 为本手盲注扣除生成日志条目
     * @param handId      手 ID，如 "第1手"
     * @param sortedPlayers 按 seatOrder 排序后的玩家列表（扣除前）
     * @param blindPrefills 盲注预扣 Map<playerId, amount>
     * @param playersAfterBlinds 扣除盲注后的玩家列表（用于获取 balanceAfter）
     * @param blindsState 盲注状态（判断谁是 SB / BB）
     * @param now         时间戳
     */
    private fun buildBlindLogs(
        handId: String,
        sortedPlayers: List<PlayerState>,
        blindPrefills: Map<String, Int>,
        playersAfterBlinds: List<PlayerState>,
        blindsState: BlindsState,
        now: Long
    ): List<ChipTransaction> = buildList {
        blindPrefills.entries
            .sortedBy { (pid, _) -> sortedPlayers.indexOfFirst { it.id == pid } }
            .forEach { (pid, amount) ->
                if (amount <= 0) return@forEach
                val player = sortedPlayers.firstOrNull { it.id == pid } ?: return@forEach
                val playerAfter = playersAfterBlinds.firstOrNull { it.id == pid } ?: return@forEach
                val seatIdx = sortedPlayers.indexOfFirst { it.id == pid }
                val role = when (seatIdx) {
                    blindsState.smallBlindIndex -> "小盲"
                    blindsState.bigBlindIndex  -> "大盲"
                    else                        -> "盲注"
                }
                add(
                    ChipTransaction(
                        id = UUID.randomUUID().toString(),
                        timestamp = now,
                        handId = handId,
                        playerId = pid,
                        playerName = player.name,
                        amount = -amount,
                        type = TransactionType.BLIND_DEDUCTION,
                        note = "扣除${role}",
                        balanceAfter = playerAfter.chips
                    )
                )
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

        // 初始化盲注位（使用大厅设置的庄家索引，跳过筹码为 0 的玩家）
        val safeDealer = state.initialDealerIndex.coerceIn(0, state.players.size - 1)
        val blinds = if (state.blindsEnabled) {
            blindsManager.computeFromDealerSkippingBroke(safeDealer, state.players, state.blindsState.config)
        } else {
            blindsManager.computeFromDealer(safeDealer, state.players.size, state.blindsState.config)
        }
        val blindPrefills = if (state.blindsEnabled) {
            blindsManager.calculateBlindPrefills(state.players, blinds)
        } else emptyMap()

        // 盲注立即从筹码扣除
        val playersAfterBlinds = if (state.blindsEnabled) {
            blindsManager.deductBlinds(state.players, blindPrefills)
        } else state.players

        // 翻牌前：盲注计入 roundContributions，首位行动 = UTG
        val roundContribs = if (state.blindsEnabled) blindPrefills else emptyMap()
        // 盲注全压玩家（扣除盲注后 chips=0）预先加入 actedPlayerIds，
        // 防止 isRoundComplete 因「未行动」导致回合坷死
        val blindAllInActed = if (state.blindsEnabled) {
            playersAfterBlinds.filter { it.chips <= 0 && (blindPrefills[it.id] ?: 0) > 0 }
                .map { it.id }.toSet()
        } else emptySet()
        val tmpState = state.copy(
            players = playersAfterBlinds,
            blindsState = blinds,
            foldedPlayerIds = emptySet()
        )
        val firstTurn = if (state.blindsEnabled) {
            getPreFlopFirstPlayerId(tmpState, blinds)
        } else {
            getPostFlopFirstPlayerId(tmpState, blinds)
        }
        val firstName = playersAfterBlinds.sortedBy { it.seatOrder }.firstOrNull { it.id == firstTurn }?.name ?: "?"

        // 记录盲注扣除日志
        val blindLogs = if (state.blindsEnabled) {
            buildBlindLogs(
                handId = "第${state.handCounter}手",
                sortedPlayers = state.players.sortedBy { it.seatOrder },
                blindPrefills = blindPrefills,
                playersAfterBlinds = playersAfterBlinds,
                blindsState = blinds,
                now = System.currentTimeMillis()
            )
        } else emptyList()

        _uiState.update {
            it.copy(
                screen = ScreenState.GAME,
                gameStarted = true,
                players = playersAfterBlinds,
                blindsState = blinds,
                blindContributions = blindPrefills,
                contributionInputs = emptyMap(),
                currentRound = BettingRound.PRE_FLOP,
                currentTurnPlayerId = firstTurn,
                roundContributions = roundContribs,
                actedPlayerIds = blindAllInActed,
                foldedPlayerIds = emptySet(),
                selectedWinnerIds = emptySet(),
                lastSidePots = emptyList(),
                logs = (state.logs + blindLogs).takeLast(500),
                info = "翻牌前 - $firstName 行动 | Hand #${state.handCounter}"
            )
        }
        syncToClients()
        saveSession()
    }

    /**
     * 仅在摊牌阶段可操作。
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

        // 仅在摊牌阶段允许结算（首手无投入时跳过此检查）
        val hasAnyContrib = state.contributionInputs.values.any { (it.toIntOrNull() ?: 0) > 0 } ||
                state.roundContributions.values.any { it > 0 }
        if (hasAnyContrib && state.currentRound != BettingRound.SHOWDOWN) {
            _uiState.update { it.copy(info = "请完成所有投注轮次后再结算") }
            return
        }

        // 先将未累加的 roundContributions 合并到 contributionInputs
        val mergedContribs = state.contributionInputs.toMutableMap()
        state.roundContributions.forEach { (pid, rc) ->
            val prev = mergedContribs[pid]?.toIntOrNull() ?: 0
            mergedContribs[pid] = (prev + rc).toString()
        }

        // ---------- 1) 结算当前手（如果有投入）----------
        val hasContributions = mergedContribs.values.any { (it.toIntOrNull() ?: 0) > 0 }
        var playersAfterSettle = state.players
        var logsAfterSettle = state.logs
        var settleInfo = ""
        var sidePots: List<SidePot> = emptyList()

        if (hasContributions) {
            if (state.selectedWinnerIds.isEmpty()) {
                _uiState.update { it.copy(info = "请选择赢家后再结算") }
                return
            }

            val contributions = state.players.associate { player ->
                val raw = mergedContribs[player.id].orEmpty()
                player.id to (raw.toIntOrNull() ?: 0)
            }

            val handId = "第${state.handCounter}手"
            val now = System.currentTimeMillis()

            // 还原所有手牌过程中实际扣除的筹码，以便结算引擎重新分配
            val playersForSettlement = state.players.map { player ->
                val actualContrib = contributions[player.id] ?: 0
                player.copy(chips = player.chips + actualContrib)
            }

            val result = settlementEngine.settleHandSimple(
                handId = handId,
                players = playersForSettlement,
                contributions = contributions,
                winnerIds = state.selectedWinnerIds.toList(),
                timestamp = now
            )

            // 投注过程已由 processContribution/processFold 实时记录
            // 结算时只记录赢家获得筹码，避免重复
            val winLogs = result.transactions.filter { it.type == TransactionType.WIN_PAYOUT }

            playersAfterSettle = result.updatedPlayers
            logsAfterSettle = (state.logs + winLogs).takeLast(500)
            sidePots = if (state.sidePotEnabled) result.sidePots else result.sidePots.take(1)
            settleInfo = if (state.sidePotEnabled && result.sidePots.size > 1) {
                result.sidePots.joinToString(" | ") { "${it.label}:${it.amount}" }
            } else {
                "底池 ${result.totalPot}"
            }
        }

        // ---------- 2) 轮转庄位 + 设置下一手翻牌前 ----------
        val nextHandCounter = if (hasContributions) state.handCounter + 1 else state.handCounter

        if (state.blindsEnabled) {
            // 使用跳过破产玩家的轮转
            val newBlinds = blindsManager.rotateSkippingBroke(state.blindsState, playersAfterSettle)
            val blindPrefills = blindsManager.calculateBlindPrefills(playersAfterSettle, newBlinds)
            // 盲注立即扣除
            val playersAfterBlinds = blindsManager.deductBlinds(playersAfterSettle, blindPrefills)

            // 记录下一手盲注扣除日志
            val blindLogs = buildBlindLogs(
                handId = "第${nextHandCounter}手",
                sortedPlayers = playersAfterSettle.sortedBy { it.seatOrder },
                blindPrefills = blindPrefills,
                playersAfterBlinds = playersAfterBlinds,
                blindsState = newBlinds,
                now = System.currentTimeMillis()
            )
            logsAfterSettle = (logsAfterSettle + blindLogs).takeLast(500)

            // 设置下一手的翻牌前
            val tmpState = state.copy(
                players = playersAfterBlinds,
                blindsState = newBlinds,
                foldedPlayerIds = emptySet(),
                contributionInputs = emptyMap(),
                roundContributions = blindPrefills,
                midGameWaitingPlayerIds = emptySet()  // 中途等待玩家此时正式加入
            )
            val firstTurn = getPreFlopFirstPlayerId(tmpState, newBlinds)
            val firstName = playersAfterBlinds.sortedBy { it.seatOrder }
                .firstOrNull { it.id == firstTurn }?.name ?: "?"
            val blindAllInActed = playersAfterBlinds
                .filter { it.chips <= 0 && (blindPrefills[it.id] ?: 0) > 0 }
                .map { it.id }.toSet()

            val infoText = buildString {
                if (settleInfo.isNotEmpty()) append("结算完成: $settleInfo | ")
                append("Hand #$nextHandCounter | 翻牌前 - $firstName 行动")
            }

            _uiState.update {
                it.copy(
                    players = playersAfterBlinds,
                    handCounter = nextHandCounter,
                    blindsState = newBlinds,
                    blindContributions = blindPrefills,
                    contributionInputs = emptyMap(),
                    selectedWinnerIds = emptySet(),
                    foldedPlayerIds = emptySet(),
                    lastSidePots = emptyList(),
                    logs = logsAfterSettle,
                    currentRound = BettingRound.PRE_FLOP,
                    currentTurnPlayerId = firstTurn,
                    roundContributions = blindPrefills,
                    actedPlayerIds = blindAllInActed,
                    midGameWaitingPlayerIds = emptySet(),
                    info = infoText
                )
            }
        } else {
            val newBlinds = blindsManager.rotate(state.blindsState, playersAfterSettle.size)
            val tmpState = state.copy(
                players = playersAfterSettle,
                blindsState = newBlinds,
                foldedPlayerIds = emptySet(),
                contributionInputs = emptyMap(),
                roundContributions = emptyMap(),
                midGameWaitingPlayerIds = emptySet()  // 中途等待玩家此时正式加入
            )
            val firstTurn = getPostFlopFirstPlayerId(tmpState, newBlinds)
            val firstName = playersAfterSettle.sortedBy { it.seatOrder }
                .firstOrNull { it.id == firstTurn }?.name ?: "?"

            val infoText = buildString {
                if (settleInfo.isNotEmpty()) append("结算完成: $settleInfo | ")
                append("Hand #$nextHandCounter | 翻牌前 - $firstName 行动")
            }

            _uiState.update {
                it.copy(
                    players = playersAfterSettle,
                    handCounter = nextHandCounter,
                    blindsState = newBlinds,
                    blindContributions = emptyMap(),
                    contributionInputs = emptyMap(),
                    selectedWinnerIds = emptySet(),
                    foldedPlayerIds = emptySet(),
                    lastSidePots = emptyList(),
                    logs = logsAfterSettle,
                    currentRound = BettingRound.PRE_FLOP,
                    currentTurnPlayerId = firstTurn,
                    roundContributions = emptyMap(),
                    actedPlayerIds = emptySet(),
                    midGameWaitingPlayerIds = emptySet(),
                    info = infoText
                )
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.save(_uiState.value.logs)
        }

        syncToClients()
        saveSession()
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
     * 玩家提交自己的本轮投入（轮次制：仅在轮到自己时可操作）
     */
    fun submitMyContribution(amount: Int) {
        val state = _uiState.value
        val selfId = state.selfId
        if (selfId.isBlank()) return

        // 轮次制校验
        if (state.gameStarted && state.currentRound != BettingRound.SHOWDOWN) {
            if (state.currentTurnPlayerId != selfId) {
                _uiState.update { it.copy(info = "还没轮到你") }
                return
            }
        }

        val player = state.players.firstOrNull { it.id == selfId } ?: return

        // 实时扣除后 player.chips 即为真实可用筹码
        val maxAvailable = player.chips

        if (amount > maxAvailable) {
            _uiState.update { it.copy(info = "投入超出可用筹码 (剩余: $maxAvailable)") }
            return
        }

        // 跟注校验
        val currentMaxBet = state.roundContributions.values.maxOrNull() ?: 0
        val currentRoundContrib = state.roundContributions[selfId] ?: 0
        val callAmount = currentMaxBet - currentRoundContrib
        val newRoundContrib = currentRoundContrib + amount
        if (newRoundContrib < currentMaxBet && amount < player.chips) {
            _uiState.update { it.copy(info = "至少需要跟注 $callAmount") }
            return
        }

        if (state.mode == TableMode.HOST) {
            processContribution(selfId, amount)
        } else if (state.mode == TableMode.CLIENT) {
            client.sendContribution(selfId, amount)
            // 乐观更新：同步扣除筹码，等服务端同步后覆盖
            _uiState.update {
                it.copy(
                    players = it.players.map { p ->
                        if (p.id == selfId && amount > 0) p.copy(chips = p.chips - amount) else p
                    },
                    roundContributions = it.roundContributions + (selfId to newRoundContrib),
                    info = "已投入本轮: $newRoundContrib"
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
        if (_uiState.value.mode == TableMode.HOST) {
            syncToClients()
        }
    }

    /**
     * 玩家自行切换赢家状态：房主直接处理，客户端发送到服务端
     */
    fun toggleMyWinner() {
        val state = _uiState.value
        val selfId = state.selfId
        if (selfId.isBlank()) return
        val isCurrentlyWinner = state.selectedWinnerIds.contains(selfId)
        val newIsWinner = !isCurrentlyWinner
        if (state.mode == TableMode.HOST) {
            toggleWinner(selfId)
        } else {
            client.sendWinToggle(selfId, newIsWinner)
            // 客户端先乐观更新本地状态，等待服务端同步确认
            _uiState.update { s ->
                val newSet = s.selectedWinnerIds.toMutableSet()
                if (newIsWinner) newSet.add(selfId) else newSet.remove(selfId)
                s.copy(selectedWinnerIds = newSet)
            }
        }
    }

    /** 弃牌（不可撤销，本手内持续生效） */
    fun foldMyself() {
        val state = _uiState.value
        val selfId = state.selfId
        if (selfId.isBlank()) return
        if (state.foldedPlayerIds.contains(selfId)) return // 已弃牌
        if (state.currentRound == BettingRound.SHOWDOWN) return // 摊牌阶段不可弃牌

        // 仅在轮到自己时可弃牌
        if (state.gameStarted && state.currentTurnPlayerId != selfId) {
            _uiState.update { it.copy(info = "还没轮到你") }
            return
        }

        if (state.mode == TableMode.HOST) {
            processFold(selfId)
        } else {
            client.sendFold(selfId)
            _uiState.update { s ->
                s.copy(
                    foldedPlayerIds = s.foldedPlayerIds + selfId,
                    info = "你已弃牌"
                )
            }
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
                    sidePotEnabled = state.sidePotEnabled,
                    selectedWinnerIds = state.selectedWinnerIds,
                    foldedPlayerIds = state.foldedPlayerIds,
                    gameStarted = state.gameStarted,
                    currentRound = state.currentRound.name,
                    currentTurnPlayerId = state.currentTurnPlayerId,
                    roundContributions = state.roundContributions,
                    actedPlayerIds = state.actedPlayerIds,
                    initialDealerIndex = state.initialDealerIndex,
                    midGameWaitingPlayerIds = state.midGameWaitingPlayerIds,
                    allowMidGameJoin = state.allowMidGameJoin
                )
            }
        }
    }

    // ==================== 提取的服务端/客户端事件处理 ====================

    /** 启动 TCP 服务端 */
    private fun startServer() {
        acquireWakeLock()
        server.start(
            hostPlayersProvider = { _uiState.value.players },
            handCounterProvider = { _uiState.value.handCounter },
            txProvider = { _uiState.value.logs },
            contributionsProvider = {
                _uiState.value.contributionInputs.mapValues { (_, v) -> v.toIntOrNull() ?: 0 }
            },
            blindsStateProvider = { _uiState.value.blindsState },
            blindsEnabledProvider = { _uiState.value.blindsEnabled },
            sidePotEnabledProvider = { _uiState.value.sidePotEnabled },
            selectedWinnerIdsProvider = { _uiState.value.selectedWinnerIds },
            foldedPlayerIdsProvider = { _uiState.value.foldedPlayerIds },
            gameStartedProvider = { _uiState.value.gameStarted },
            currentRoundProvider = { _uiState.value.currentRound.name },
            currentTurnPlayerIdProvider = { _uiState.value.currentTurnPlayerId },
            roundContributionsProvider = { _uiState.value.roundContributions },
            actedPlayerIdsProvider = { _uiState.value.actedPlayerIds },
            allowMidGameJoinProvider = { _uiState.value.allowMidGameJoin },
            midGameWaitingPlayerIdsProvider = { _uiState.value.midGameWaitingPlayerIds },
            onPlayerJoined = { player ->
                _uiState.update { state ->
                    state.copy(players = state.players + player, info = "${player.name} 已加入房间")
                }
                syncToClients()
                saveSession()
            },
            onEvent = ::handleServerEvent
        )
    }

    /** 启动 UDP 广播 */
    private fun startAdvertiser() {
        roomAdvertiser.startBroadcast(
            roomName = _uiState.value.tableName,
            tcpPort = 45454,
            hostName = _uiState.value.selfName,
            playerCountProvider = { _uiState.value.players.size },
            gameStartedProvider = { _uiState.value.gameStarted }
        )
    }

    private fun handleServerEvent(event: LanTableServer.Event) {
        when (event) {
            is LanTableServer.Event.Error ->
                _uiState.update { it.copy(info = event.message) }
            is LanTableServer.Event.PlayerDisconnected -> {
                val pName = _uiState.value.players.firstOrNull { it.id == event.playerId }?.name ?: "?"
                _uiState.update { state ->
                    state.copy(
                        disconnectedPlayerIds = state.disconnectedPlayerIds + event.playerId,
                        info = "$pName 掉线，等待重连..."
                    )
                }
            }
            is LanTableServer.Event.PlayerReconnected -> {
                _uiState.update { state ->
                    val newName = event.updatedName.ifBlank {
                        state.players.firstOrNull { it.id == event.playerId }?.name ?: "?"
                    }
                    state.copy(
                        players = state.players.map {
                            if (it.id == event.playerId) {
                                var p = it.copy(name = newName)
                                if (event.updatedChips > 0) p = p.copy(chips = event.updatedChips)
                                p
                            } else it
                        },
                        disconnectedPlayerIds = state.disconnectedPlayerIds - event.playerId,
                        info = "$newName 已重连"
                    )
                }
                syncToClients()
            }
            is LanTableServer.Event.ContributionReceived -> {
                processContribution(event.playerId, event.amount)
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
            is LanTableServer.Event.WinToggleReceived -> {
                _uiState.update { state ->
                    val newSet = state.selectedWinnerIds.toMutableSet()
                    if (event.isWinner) newSet.add(event.playerId) else newSet.remove(event.playerId)
                    val playerName = state.players.firstOrNull { it.id == event.playerId }?.name ?: "?"
                    state.copy(
                        selectedWinnerIds = newSet,
                        info = "$playerName ${if (event.isWinner) "标记为赢家" else "取消赢家"}"
                    )
                }
                syncToClients()
            }
            is LanTableServer.Event.FoldReceived -> {
                processFold(event.playerId)
            }
            is LanTableServer.Event.ProfileUpdateReceived -> {
                _uiState.update { state ->
                    state.copy(
                        players = state.players.map {
                            if (it.id == event.playerId)
                                it.copy(name = event.newName.ifBlank { it.name }, avatarBase64 = event.avatarBase64)
                            else it
                        }
                    )
                }
                syncToClients()
            }
            is LanTableServer.Event.PlayerRejoinedByDevice -> {
                // 同一台设备换了昵称/头像后重新进入：复用原有槽位，更新资料
                _uiState.update { state ->
                    state.copy(
                        players = state.players.map {
                            if (it.id == event.playerId)
                                it.copy(
                                    name = event.newName.ifBlank { it.name },
                                    avatarBase64 = event.avatarBase64.ifBlank { it.avatarBase64 }
                                )
                            else it
                        },
                        disconnectedPlayerIds = state.disconnectedPlayerIds - event.playerId,
                        info = "${event.newName.ifBlank { "玩家" }} 重新加入房间"
                    )
                }
                syncToClients()
                saveSession()
            }
            is LanTableServer.Event.MidGameJoinRequested -> {
                // 有玩家申请中途加入，展示给房主审批
                val newPending = PendingMidJoinInfo(
                    requestId = event.requestId,
                    playerName = event.playerName,
                    buyIn = event.buyIn,
                    avatarBase64 = event.avatarBase64
                )
                _uiState.update { state ->
                    state.copy(
                        pendingMidJoins = state.pendingMidJoins + newPending,
                        info = "${event.playerName} 申请中途加入"
                    )
                }
            }
            else -> Unit
        }
    }

    private fun handleClientEvent(event: LanTableClient.Event) {
        when (event) {
            is LanTableClient.Event.JoinAccepted -> {
                _uiState.update { it.copy(selfId = event.playerId, info = "已加入「${_uiState.value.tableName}」") }
                saveSession()
                // 加入后立即上传本机头像
                val avatar = _uiState.value.savedAvatarBase64
                val name = _uiState.value.savedPlayerName
                if (avatar.isNotBlank()) {
                    client.sendUpdateProfile(event.playerId, name, avatar)
                }
            }
            is LanTableClient.Event.StateSync -> {
                _uiState.update {
                    val selfId = it.selfId
                    val isMidGameWaiting = selfId.isNotBlank() && event.midGameWaitingPlayerIds.contains(selfId)
                    // 中途等待玩家留在 LOBBY，其其余 gameStarted=true 则进 GAME
                    val newScreen = when {
                        event.gameStarted && !isMidGameWaiting -> ScreenState.GAME
                        else -> it.screen
                    }
                    val round = try { BettingRound.valueOf(event.currentRound) } catch (_: Exception) { BettingRound.PRE_FLOP }
                    it.copy(
                        screen = newScreen,
                        players = event.players,
                        handCounter = event.handCounter,
                        logs = event.transactions.takeLast(200),
                        contributionInputs = event.contributions.mapValues { (_, v) -> v.toString() },
                        blindsState = event.blindsState,
                        blindsEnabled = event.blindsEnabled,
                        sidePotEnabled = event.sidePotEnabled,
                        selectedWinnerIds = event.selectedWinnerIds,
                        foldedPlayerIds = event.foldedPlayerIds,
                        gameStarted = event.gameStarted,
                        currentRound = round,
                        currentTurnPlayerId = event.currentTurnPlayerId,
                        roundContributions = event.roundContributions,
                        actedPlayerIds = event.actedPlayerIds,
                        initialDealerIndex = event.initialDealerIndex,
                        midGameWaitingPlayerIds = event.midGameWaitingPlayerIds,
                        allowMidGameJoin = event.allowMidGameJoin,
                        info = if (event.gameStarted && !isMidGameWaiting) "牌局状态已同步" else if (isMidGameWaiting) "已批准加入，等待下一手开始" else "等待房主开始游戏"
                    )
                }
                saveSession()
            }
            is LanTableClient.Event.Error ->
                _uiState.update { it.copy(info = event.message) }
            is LanTableClient.Event.Disconnected -> {
                if (event.willReconnect) {
                    _uiState.update { it.copy(waitingForHostReconnect = true, info = "连接断开，正在尝试重连...") }
                } else {
                    _uiState.update { it.copy(info = "连接断开") }
                }
            }
            is LanTableClient.Event.Reconnected -> {
                _uiState.update { it.copy(waitingForHostReconnect = false, info = "重连成功") }
            }
            is LanTableClient.Event.ReconnectFailed -> {
                _uiState.update { it.copy(waitingForHostReconnect = false, info = event.reason) }
            }
            is LanTableClient.Event.MidGameJoinPending -> {
                _uiState.update { it.copy(
                    screen = ScreenState.LOBBY,
                    midGameJoinStatus = MidGameJoinStatus.PENDING,
                    info = "申请已提交，等待房主审批..."
                ) }
            }
            is LanTableClient.Event.MidGameJoinRejected -> {
                _uiState.update { it.copy(
                    midGameJoinStatus = if (event.blocked) MidGameJoinStatus.BLOCKED else MidGameJoinStatus.REJECTED,
                    info = event.reason
                ) }
            }
        }
    }

    // ==================== 会话持久化 ====================

    private fun saveSession() {
        val state = _uiState.value
        if (state.mode == TableMode.IDLE) return
        prefs.edit().apply {
            putString("session_mode", state.mode.name)
            putString("session_self_id", state.selfId)
            putString("session_self_name", state.selfName)
            putString("session_table_name", state.tableName)
            putString("session_host_ip", state.hostIp)
            putBoolean("session_game_started", state.gameStarted)
            putInt("session_hand_counter", state.handCounter)
            putBoolean("session_blinds_enabled", state.blindsEnabled)
            putBoolean("session_side_pot_enabled", state.sidePotEnabled)
            putString("session_players", sessionJson.encodeToString(state.players))
            putString("session_blinds_state", sessionJson.encodeToString(state.blindsState))
            putString("session_contributions", sessionJson.encodeToString(state.contributionInputs))
            putString("session_blind_contribs", sessionJson.encodeToString(state.blindContributions))
            // 游戏进行中的轮次状态
            putString("session_current_round", state.currentRound.name)
            putString("session_current_turn", state.currentTurnPlayerId)
            putString("session_round_contribs", sessionJson.encodeToString(state.roundContributions))
            putString("session_acted_ids", sessionJson.encodeToString(state.actedPlayerIds.toList()))
            putString("session_folded_ids", sessionJson.encodeToString(state.foldedPlayerIds.toList()))
            // 当前房间内产生的日志（不含历史房间记录）
            putString("session_logs", sessionJson.encodeToString(state.logs))
            apply()
        }
    }

    private fun clearSession() {
        val keys = listOf(
            "session_mode", "session_self_id", "session_self_name", "session_table_name",
            "session_host_ip", "session_game_started", "session_hand_counter",
            "session_blinds_enabled", "session_side_pot_enabled", "session_players", "session_blinds_state",
            "session_contributions", "session_blind_contribs",
            "session_current_round", "session_current_turn",
            "session_round_contribs", "session_acted_ids", "session_folded_ids",
            "session_logs"
        )
        prefs.edit().apply {
            keys.forEach { remove(it) }
            apply()
        }
        _uiState.update { it.copy(canRejoin = false, lastSessionTableName = "", lastSessionMode = TableMode.IDLE) }
    }

    private fun loadSessionInfo() {
        val modeStr = prefs.getString("session_mode", null) ?: return
        val mode = try { TableMode.valueOf(modeStr) } catch (_: Exception) { return }
        if (mode == TableMode.IDLE) return
        val tableName = prefs.getString("session_table_name", "") ?: ""
        _uiState.update { it.copy(canRejoin = true, lastSessionTableName = tableName, lastSessionMode = mode) }
    }

    // ==================== 重新加入会话 ====================

    fun rejoinSession() {
        val modeStr = prefs.getString("session_mode", null) ?: return
        val mode = try { TableMode.valueOf(modeStr) } catch (_: Exception) { return }

        val selfId = prefs.getString("session_self_id", "") ?: ""
        val selfName = prefs.getString("session_self_name", "") ?: ""
        val tableName = prefs.getString("session_table_name", "") ?: ""
        val hostIp = prefs.getString("session_host_ip", "") ?: ""
        val gameStarted = prefs.getBoolean("session_game_started", false)
        val handCounter = prefs.getInt("session_hand_counter", 1)
        val blindsEnabled = prefs.getBoolean("session_blinds_enabled", true)
        val sidePotEnabled = prefs.getBoolean("session_side_pot_enabled", true)

        val players: List<PlayerState> = try {
            val s = prefs.getString("session_players", "[]") ?: "[]"
            sessionJson.decodeFromString(s)
        } catch (_: Exception) { emptyList() }

        val blindsState: BlindsState = try {
            val s = prefs.getString("session_blinds_state", null)
            s?.let { sessionJson.decodeFromString(it) } ?: BlindsState()
        } catch (_: Exception) { BlindsState() }

        val contributions: Map<String, String> = try {
            val s = prefs.getString("session_contributions", "{}") ?: "{}"
            sessionJson.decodeFromString(s)
        } catch (_: Exception) { emptyMap() }

        val blindContributions: Map<String, Int> = try {
            val s = prefs.getString("session_blind_contribs", "{}") ?: "{}"
            sessionJson.decodeFromString(s)
        } catch (_: Exception) { emptyMap() }

        // 恢复游戏进行中的轮次状态
        val currentRound = try {
            val s = prefs.getString("session_current_round", null)
            s?.let { BettingRound.valueOf(it) } ?: BettingRound.PRE_FLOP
        } catch (_: Exception) { BettingRound.PRE_FLOP }
        val currentTurnPlayerId = prefs.getString("session_current_turn", "") ?: ""
        val roundContributions: Map<String, Int> = try {
            val s = prefs.getString("session_round_contribs", "{}") ?: "{}"
            sessionJson.decodeFromString(s)
        } catch (_: Exception) { emptyMap() }
        val actedPlayerIds: Set<String> = try {
            val s = prefs.getString("session_acted_ids", "[]") ?: "[]"
            sessionJson.decodeFromString<List<String>>(s).toSet()
        } catch (_: Exception) { emptySet() }
        val foldedPlayerIds: Set<String> = try {
            val s = prefs.getString("session_folded_ids", "[]") ?: "[]"
            sessionJson.decodeFromString<List<String>>(s).toSet()
        } catch (_: Exception) { emptySet() }

        if (mode == TableMode.HOST) {
            // 使用当前已保存的昵称（玩家可能在退出后修改过昵称）
            val currentName = _uiState.value.savedPlayerName.ifBlank { selfName }
            val currentBuyIn = _uiState.value.savedBuyIn
            // 在大厅阶段允许更新自身昵称和筹码
            val restoredPlayers = players.map { p ->
                if (p.id == selfId) {
                    val nameUpdated = p.copy(name = currentName)
                    if (!gameStarted) nameUpdated.copy(chips = currentBuyIn) else nameUpdated
                } else p
            }
            // 房主重新加入：恢复状态 + 重启服务端 + 重启广播
            _uiState.update {
                it.copy(
                    mode = TableMode.HOST,
                    screen = if (gameStarted) ScreenState.GAME else ScreenState.LOBBY,
                    tableName = tableName,
                    selfId = selfId,
                    selfName = currentName,
                    players = restoredPlayers,
                    handCounter = handCounter,
                    gameStarted = gameStarted,
                    blindsState = blindsState,
                    blindsEnabled = blindsEnabled,
                    sidePotEnabled = sidePotEnabled,
                    contributionInputs = contributions,
                    blindContributions = blindContributions,
                    currentRound = currentRound,
                    currentTurnPlayerId = currentTurnPlayerId,
                    roundContributions = roundContributions,
                    actedPlayerIds = actedPlayerIds,
                    foldedPlayerIds = foldedPlayerIds,
                    selectedWinnerIds = emptySet(),
                    lastSidePots = emptyList(),
                    logs = try {
                        val s = prefs.getString("session_logs", "[]") ?: "[]"
                        sessionJson.decodeFromString<List<ChipTransaction>>(s)
                    } catch (_: Exception) { emptyList() },
                    canRejoin = false,
                    // 标记所有非房主玩家为掉线
                    disconnectedPlayerIds = players.filter { p -> p.id != selfId }.map { p -> p.id }.toSet(),
                    info = "正在恢复牌局..."
                )
            }

            startServer()
            startAdvertiser()

            _uiState.update { it.copy(info = "牌局已恢复，等待玩家重连...") }
        } else {
            // 客户端重新加入：恢复基本状态 + 发送重连请求
            if (selfId.isBlank() || hostIp.isBlank()) {
                clearSession()
                _uiState.update { it.copy(info = "会话信息不完整，无法重连") }
                return
            }

            // 使用当前已保存的昵称，并更新本地玩家列表中的自身昵称展示
            val currentName = _uiState.value.savedPlayerName.ifBlank { selfName }
            val currentBuyIn = _uiState.value.savedBuyIn

            _uiState.update {
                it.copy(
                    mode = TableMode.CLIENT,
                    screen = if (gameStarted) ScreenState.GAME else ScreenState.LOBBY,
                    tableName = tableName,
                    selfId = selfId,
                    selfName = currentName,
                    hostIp = hostIp,
                    players = players.map { p ->
                        if (p.id == selfId) p.copy(name = currentName) else p
                    },
                    handCounter = handCounter,
                    gameStarted = gameStarted,
                    blindsState = blindsState,
                    blindsEnabled = blindsEnabled,
                    sidePotEnabled = sidePotEnabled,
                    contributionInputs = contributions,
                    canRejoin = false,
                    info = "正在重连到「$tableName」..."
                )
            }

            client.reconnect(hostIp, selfId, currentName, currentBuyIn, deviceId, ::handleClientEvent)
            acquireWakeLock()
        }
    }

    override fun onCleared() {
        super.onCleared()
        releaseWakeLock()
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
