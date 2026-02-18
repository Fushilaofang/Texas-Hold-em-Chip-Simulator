package com.fushilaofang.texasholdemchipsim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fushilaofang.texasholdemchipsim.blinds.BlindsConfig
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import com.fushilaofang.texasholdemchipsim.network.DiscoveredRoom
import com.fushilaofang.texasholdemchipsim.ui.BettingRound
import com.fushilaofang.texasholdemchipsim.ui.ScreenState
import com.fushilaofang.texasholdemchipsim.ui.TableMode
import com.fushilaofang.texasholdemchipsim.ui.TableUiState
import com.fushilaofang.texasholdemchipsim.ui.TableViewModel
import com.fushilaofang.texasholdemchipsim.ui.TableViewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: TableViewModel = viewModel(factory = TableViewModelFactory(applicationContext))
            val state by vm.uiState.collectAsStateWithLifecycle()

            Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colorScheme.background) {
                when (state.screen) {
                    ScreenState.HOME -> HomeScreen(
                        state = state,
                        onNavigateCreate = { vm.navigateTo(ScreenState.CREATE_ROOM) },
                        onNavigateJoin = { vm.navigateTo(ScreenState.JOIN_ROOM) },
                        onPlayerNameChange = vm::savePlayerName,
                        onBuyInChange = vm::saveBuyIn,
                        onRejoin = vm::rejoinSession
                    )
                    ScreenState.CREATE_ROOM -> CreateRoomScreen(
                        state = state,
                        onBack = { vm.navigateTo(ScreenState.HOME) },
                        onHost = vm::hostTable,
                        onRoomNameChange = vm::saveRoomName,
                        onSmallBlindChange = vm::saveSmallBlind,
                        onBigBlindChange = vm::saveBigBlind
                    )
                    ScreenState.JOIN_ROOM -> JoinRoomScreen(
                        state = state,
                        onBack = { vm.stopRoomScan(); vm.navigateTo(ScreenState.HOME) },
                        onStartScan = vm::startRoomScan,
                        onStopScan = vm::stopRoomScan,
                        onJoinRoom = vm::joinRoom
                    )
                    ScreenState.LOBBY -> LobbyScreen(
                        state = state,
                        onToggleReady = vm::toggleReady,
                        onStartGame = vm::startGame,
                        onLeave = vm::goHome,
                        onToggleBlinds = vm::toggleBlinds,
                        onToggleSidePot = vm::toggleSidePot,
                        onUpdateBlindsConfig = vm::updateBlindsConfig,
                        onMovePlayer = vm::movePlayer,
                        onSetInitialDealer = vm::setInitialDealer
                    )
                    ScreenState.GAME -> GameScreen(
                        state = state,
                        onSubmitContribution = vm::submitMyContribution,
                        onToggleMyWinner = vm::toggleMyWinner,
                        onFold = vm::foldMyself,
                        onSettleAndAdvance = vm::settleAndAdvance,
                        onToggleBlinds = vm::toggleBlinds,
                        onToggleSidePot = vm::toggleSidePot,
                        onUpdateBlindsConfig = vm::updateBlindsConfig,
                        onMovePlayer = vm::movePlayer,
                        onSetDealer = vm::setDealerInGame,
                        getMinContribution = vm::getMinContribution,
                        onLeave = vm::goHome
                    )
                }

                // 等待房主重连弹窗
                if (state.waitingForHostReconnect) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { /* 不允许点击外部关闭 */ },
                        title = { Text("连接中断", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("正在等待房主重连……", fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                Text("系统会自动重新连接，请耐心等待", fontSize = 13.sp, color = Color.Gray)
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { vm.goHome() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                            ) {
                                Text("退出房间")
                            }
                        }
                    )
                }
            }
        }
    }
}

// ==================== 首页 ====================

@Composable
private fun HomeScreen(
    state: TableUiState,
    onNavigateCreate: () -> Unit,
    onNavigateJoin: () -> Unit,
    onPlayerNameChange: (String) -> Unit,
    onBuyInChange: (Int) -> Unit,
    onRejoin: () -> Unit
) {
    var playerName by remember(state.savedPlayerName) { mutableStateOf(state.savedPlayerName) }
    var buyIn by remember(state.savedBuyIn) { mutableIntStateOf(state.savedBuyIn) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "德州扑克筹码统计",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it; onPlayerNameChange(it) },
            label = { Text("你的昵称") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = buyIn.toString(),
            onValueChange = { val v = it.toIntOrNull() ?: buyIn; buyIn = v; onBuyInChange(v) },
            label = { Text("初始筹码") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onNavigateCreate,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("创建房间", fontSize = 18.sp)
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onNavigateJoin,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("加入房间", fontSize = 18.sp)
        }

        if (state.canRejoin) {
            Spacer(Modifier.height(16.dp))
            val modeLabel = if (state.lastSessionMode == TableMode.HOST) "房主" else "玩家"
            Button(
                onClick = onRejoin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Text("重新加入「${state.lastSessionTableName}」($modeLabel)", fontSize = 16.sp)
            }
        }
    }
}

// ==================== 创建房间 ====================

@Composable
private fun CreateRoomScreen(
    state: TableUiState,
    onBack: () -> Unit,
    onHost: (String, String, Int, BlindsConfig) -> Unit,
    onRoomNameChange: (String) -> Unit,
    onSmallBlindChange: (Int) -> Unit,
    onBigBlindChange: (Int) -> Unit
) {
    var roomName by remember(state.savedRoomName) { mutableStateOf(state.savedRoomName) }
    var smallBlind by remember(state.savedSmallBlind) { mutableIntStateOf(state.savedSmallBlind) }
    var bigBlind by remember(state.savedBigBlind) { mutableIntStateOf(state.savedBigBlind) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("创建房间", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 占位保持居中
            Spacer(Modifier.size(72.dp))
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = roomName,
            onValueChange = { roomName = it; onRoomNameChange(it) },
            label = { Text("房间名") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = smallBlind.toString(),
                onValueChange = { val v = it.toIntOrNull() ?: smallBlind; smallBlind = v; onSmallBlindChange(v) },
                label = { Text("小盲") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = bigBlind.toString(),
                onValueChange = { val v = it.toIntOrNull() ?: bigBlind; bigBlind = v; onBigBlindChange(v) },
                label = { Text("大盲") },
                modifier = Modifier.weight(1f)
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("房间信息", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("昵称: ${state.savedPlayerName.ifBlank { "庄家" }}", fontSize = 13.sp)
                Text("初始筹码: ${state.savedBuyIn}", fontSize = 13.sp)
                Text("小盲/大盲: $smallBlind / $bigBlind", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = {
                onHost(roomName, state.savedPlayerName, state.savedBuyIn, BlindsConfig(smallBlind, bigBlind))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("创建并等待玩家", fontSize = 18.sp)
        }
    }
}

// ==================== 加入房间 ====================

@Composable
private fun JoinRoomScreen(
    state: TableUiState,
    onBack: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onJoinRoom: (DiscoveredRoom, String, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.weight(1f))
            Text("加入房间", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(72.dp))
        }

        Text("状态：${state.info}", fontSize = 13.sp, color = Color.Gray)

        if (!state.isScanning) {
            Button(
                onClick = onStartScan,
                modifier = Modifier.fillMaxWidth()
            ) { Text("搜索局域网房间") }
        } else {
            OutlinedButton(
                onClick = onStopScan,
                modifier = Modifier.fillMaxWidth()
            ) { Text("停止搜索") }
        }

        if (state.isScanning) {
            DisposableEffect(Unit) {
                onDispose { onStopScan() }
            }
        }

        HorizontalDivider()

        if (state.discoveredRooms.isNotEmpty()) {
            Text("发现的房间:", fontWeight = FontWeight.Bold)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.discoveredRooms) { room ->
                    val started = room.gameStarted
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!started) {
                                    onJoinRoom(room, state.savedPlayerName, state.savedBuyIn)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (started) Color(0xFFF5F5F5) else Color(0xFFE8F5E9)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(room.roomName, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    color = if (started) Color.Gray else Color.Unspecified)
                                Text(
                                    "房主: ${room.hostName} | ${room.playerCount}人在线",
                                    fontSize = 12.sp, color = Color.Gray
                                )
                                if (started) {
                                    Text("游戏已开始，不可加入", fontSize = 11.sp, color = Color(0xFFE53935))
                                }
                            }
                            if (started) {
                                Text("🔒 已开始", color = Color.Gray, fontSize = 13.sp)
                            } else {
                                Text("加入 →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else if (state.isScanning) {
            Text("搜索中...", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Text("点击上方按钮开始搜索", fontSize = 14.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ==================== 大厅等待 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LobbyScreen(
    state: TableUiState,
    onToggleReady: () -> Unit,
    onStartGame: () -> Unit,
    onLeave: () -> Unit,
    onToggleBlinds: (Boolean) -> Unit,
    onToggleSidePot: (Boolean) -> Unit,
    onUpdateBlindsConfig: (Int, Int) -> Unit,
    onMovePlayer: (String, Int) -> Unit,
    onSetInitialDealer: (Int) -> Unit
) {
    val sortedPlayers = state.players.sortedBy { it.seatOrder }
    val allReady = sortedPlayers.isNotEmpty() && sortedPlayers.all { it.isReady }
    val readyCount = sortedPlayers.count { it.isReady }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onLeave) { Text("← 离开") }
            Spacer(Modifier.weight(1f))
            Text("房间大厅", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(72.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("房间: ${state.tableName}", fontWeight = FontWeight.Bold)
                Text("状态: ${state.info}", fontSize = 13.sp, color = Color.Gray)
                Text("玩家: ${sortedPlayers.size} | 已准备: $readyCount / ${sortedPlayers.size}", fontSize = 13.sp)
                if (state.blindsEnabled) {
                    Text("小盲/大盲: ${state.blindsState.config.smallBlind} / ${state.blindsState.config.bigBlind}", fontSize = 13.sp)
                }
                Text("边池规则: ${if (state.sidePotEnabled) "开启" else "关闭"}", fontSize = 13.sp)
            }
        }

        // 房主开关
        if (state.mode == TableMode.HOST) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("盲注自动轮转", fontSize = 13.sp)
                Switch(checked = state.blindsEnabled, onCheckedChange = onToggleBlinds)
            }
            // 盲注金额编辑
            if (state.blindsEnabled) {
                var sbText by remember(state.blindsState.config.smallBlind) {
                    mutableStateOf(state.blindsState.config.smallBlind.toString())
                }
                var bbText by remember(state.blindsState.config.bigBlind) {
                    mutableStateOf(state.blindsState.config.bigBlind.toString())
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sbText,
                        onValueChange = { sbText = it.filter { c -> c.isDigit() } },
                        label = { Text("小盲") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = bbText,
                        onValueChange = { bbText = it.filter { c -> c.isDigit() } },
                        label = { Text("大盲") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val sb = sbText.toIntOrNull() ?: state.blindsState.config.smallBlind
                            val bb = bbText.toIntOrNull() ?: state.blindsState.config.bigBlind
                            onUpdateBlindsConfig(sb, bb)
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("应用") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("边池规则", fontSize = 13.sp)
                Switch(checked = state.sidePotEnabled, onCheckedChange = onToggleSidePot)
            }
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("玩家列表", fontWeight = FontWeight.Bold)
            if (state.mode == TableMode.HOST) {
                Spacer(Modifier.weight(1f))
                Text("点击玩家设为庄家 / ▲▼调整顺序", fontSize = 11.sp, color = Color.Gray)
            }
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortedPlayers, key = { it.id }) { player ->
                val isMe = player.id == state.selfId
                val isOffline = state.disconnectedPlayerIds.contains(player.id)
                val seatIdx = sortedPlayers.indexOf(player)
                val isDealer = seatIdx == state.initialDealerIndex

                val cardColor = when {
                    isOffline -> Color(0xFFEEEEEE)
                    isDealer && state.mode == TableMode.HOST -> Color(0xFFFFF8E1)
                    player.isReady -> Color(0xFFE8F5E9)
                    else -> MaterialTheme.colorScheme.surface
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (state.mode == TableMode.HOST) {
                                Modifier.clickable { onSetInitialDealer(seatIdx) }
                            } else Modifier
                        ),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isDealer && state.mode == TableMode.HOST) {
                                    Text("[庄]", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "${player.name}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (isOffline) {
                                    Text("[掉线]", fontSize = 11.sp, color = Color.Red)
                                }
                            }
                            Text("筹码: ${player.chips}", fontSize = 13.sp, color = Color.Gray)
                        }

                        // 房主：▲▼ 调整顺序按钮
                        if (state.mode == TableMode.HOST && sortedPlayers.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                OutlinedButton(
                                    onClick = { if (seatIdx > 0) onMovePlayer(player.id, seatIdx - 1) },
                                    enabled = seatIdx > 0,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text("▲", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { if (seatIdx < sortedPlayers.size - 1) onMovePlayer(player.id, seatIdx + 1) },
                                    enabled = seatIdx < sortedPlayers.size - 1,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text("▼", fontSize = 12.sp) }
                            }
                        }

                        Text(
                            if (player.isReady) "✔ 已准备" else "未准备",
                            color = if (player.isReady) Color(0xFF388E3C) else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        // 底部操作按钮
        if (state.mode == TableMode.HOST) {
            Button(
                onClick = onStartGame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = allReady && sortedPlayers.size >= 2
            ) {
                Text(
                    if (!allReady) "等待所有玩家准备..." else "开始游戏",
                    fontSize = 18.sp
                )
            }
        } else {
            val selfReady = sortedPlayers.firstOrNull { it.id == state.selfId }?.isReady ?: false
            Button(
                onClick = onToggleReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selfReady) Color(0xFFFF7043) else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (selfReady) "取消准备" else "准备",
                    fontSize = 18.sp
                )
            }
        }
    }
}

// ==================== 游戏界面 ====================

@Composable
private fun GameScreen(
    state: TableUiState,
    onSubmitContribution: (Int) -> Unit,
    onToggleMyWinner: () -> Unit,
    onFold: () -> Unit,
    onSettleAndAdvance: () -> Unit,
    onToggleBlinds: (Boolean) -> Unit,
    onToggleSidePot: (Boolean) -> Unit,
    onUpdateBlindsConfig: (Int, Int) -> Unit,
    onMovePlayer: (String, Int) -> Unit,
    onSetDealer: (Int) -> Unit,
    getMinContribution: (String) -> Int,
    onLeave: () -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) {
        LogsScreen(state = state, onBack = { showLogs = false })
        return
    }

    var showMenu by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showBlindEditDialog by remember { mutableStateOf(false) }
    var showReorderPanel by remember { mutableStateOf(false) }
    val sortedPlayers = state.players.sortedBy { it.seatOrder }
    // 手间空档：翻牌前且没有任何行动（可调整顺序）
    val isBetweenHands = state.currentRound == BettingRound.PRE_FLOP &&
            state.actedPlayerIds.isEmpty() &&
            state.contributionInputs.isEmpty()
    if (showExitConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("返回主界面", fontWeight = FontWeight.Bold) },
            text = { Text("确定要返回主界面吗？当前牌局状态会保存，可以重新加入。") },
            confirmButton = {
                Button(
                    onClick = { showExitConfirm = false; onLeave() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) { Text("确定返回") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitConfirm = false }) { Text("取消") }
            }
        )
    }

    // 游戏中调整玩家顺序面板
    if (showReorderPanel && state.mode == TableMode.HOST) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReorderPanel = false },
            title = { Text("调整顺序 / 选庄", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("▲▼ 调整座位顺序・点击[设为庄]指定庄家", fontSize = 12.sp, color = Color.Gray)
                    val reorderPlayers = state.players.sortedBy { it.seatOrder }
                    reorderPlayers.forEachIndexed { seatIdx, player ->
                        val isDealer = seatIdx == state.blindsState.dealerIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isDealer) Color(0xFFFFF8E1) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 庄家标记
                            if (isDealer) {
                                Text(
                                    "[庄]",
                                    fontSize = 11.sp,
                                    color = Color(0xFFE65100),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.widthIn(min = 28.dp)
                                )
                            } else {
                                Text(
                                    "${seatIdx + 1}.",
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.widthIn(min = 28.dp)
                                )
                            }
                            Text(
                                player.name,
                                modifier = Modifier.weight(1f),
                                fontWeight = if (isDealer) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            // 设为庄按钮
                            if (!isDealer) {
                                OutlinedButton(
                                    onClick = { onSetDealer(seatIdx) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("设庄", fontSize = 11.sp) }
                            }
                            // 上下移动按钮
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        if (seatIdx > 0) onMovePlayer(player.id, seatIdx - 1)
                                    },
                                    enabled = seatIdx > 0,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("▲", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = {
                                        if (seatIdx < reorderPlayers.size - 1) onMovePlayer(player.id, seatIdx + 1)
                                    },
                                    enabled = seatIdx < reorderPlayers.size - 1,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("▼", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showReorderPanel = false }) { Text("完成") }
            }
        )
    }

    // 盲注修改弹窗
    if (showBlindEditDialog) {
        var sbText by remember { mutableStateOf(state.blindsState.config.smallBlind.toString()) }
        var bbText by remember { mutableStateOf(state.blindsState.config.bigBlind.toString()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBlindEditDialog = false },
            title = { Text("修改盲注金额", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("修改后将在下一手生效", fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = sbText,
                        onValueChange = { sbText = it.filter { c -> c.isDigit() } },
                        label = { Text("小盲") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bbText,
                        onValueChange = { bbText = it.filter { c -> c.isDigit() } },
                        label = { Text("大盲") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val sb = sbText.toIntOrNull() ?: state.blindsState.config.smallBlind
                    val bb = bbText.toIntOrNull() ?: state.blindsState.config.bigBlind
                    onUpdateBlindsConfig(sb, bb)
                    showBlindEditDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBlindEditDialog = false }) { Text("取消") }
            }
        )
    }

    val dealerName = sortedPlayers.getOrNull(state.blindsState.dealerIndex)?.name ?: "-"
    val sbName = sortedPlayers.getOrNull(state.blindsState.smallBlindIndex)?.name ?: "-"
    val bbName = sortedPlayers.getOrNull(state.blindsState.bigBlindIndex)?.name ?: "-"
    val roundLabel = when (state.currentRound) {
        BettingRound.PRE_FLOP -> "翻牌前"
        BettingRound.FLOP -> "翻牌圈"
        BettingRound.TURN -> "转牌圈"
        BettingRound.RIVER -> "河牌圈"
        BettingRound.SHOWDOWN -> "摊牌"
    }
    val turnPlayerName = sortedPlayers.firstOrNull { it.id == state.currentTurnPlayerId }?.name ?: ""
    val isMyTurn = state.currentTurnPlayerId == state.selfId
    val isShowdown = state.currentRound == BettingRound.SHOWDOWN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // ========== 顶部信息栏 + 记录按钮 ==========
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.tableName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("第${state.handCounter}手", fontSize = 13.sp, color = Color.Gray)
                    Text(
                        roundLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isShowdown) Color(0xFFE65100) else Color(0xFF1976D2)
                    )
                }
                if (state.blindsEnabled && sortedPlayers.size >= 2) {
                    Text(
                        "庄:$dealerName  小盲:$sbName  大盲:$bbName  (${state.blindsState.config.smallBlind}/${state.blindsState.config.bigBlind})",
                        fontSize = 11.sp, color = Color.Gray, maxLines = 1
                    )
                }
                if (!isShowdown && turnPlayerName.isNotEmpty()) {
                    Text(
                        if (isMyTurn) "轮到你行动" else "等待 $turnPlayerName 行动",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMyTurn) Color(0xFFE65100) else Color.Gray
                    )
                }
            }
            OutlinedButton(
                onClick = { showLogs = true },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("记录") }

            // 三条杠菜单按钒
            Box {
                OutlinedButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.padding(start = 4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("≡", fontSize = 18.sp) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    // 打开关系直接放在菜单和进入菜单项之间
                    if (state.mode == TableMode.HOST) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("盲注轮转", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = state.blindsEnabled,
                                        onCheckedChange = {
                                            onToggleBlinds(it)
                                            showMenu = false
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            },
                            onClick = {}
                        )
                        if (state.blindsEnabled) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "修改盲注 (${state.blindsState.config.smallBlind}/${state.blindsState.config.bigBlind})",
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = { showMenu = false; showBlindEditDialog = true }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("调整玩家顺序")
                                    if (!isBetweenHands) {
                                        Text(
                                            "(手间可用)",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            },
                            enabled = isBetweenHands,
                            onClick = { showMenu = false; showReorderPanel = true }
                        )
                        HorizontalDivider()
                    }
                    if (state.mode == TableMode.HOST) {
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("边池规则", modifier = Modifier.weight(1f))
                                    Switch(
                                        checked = state.sidePotEnabled,
                                        onCheckedChange = {
                                            onToggleSidePot(it)
                                            showMenu = false
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            },
                            onClick = {}
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("返回主界面", color = Color(0xFFE53935)) },
                        onClick = { showMenu = false; showExitConfirm = true }
                    )
                }
            }
        }

        // 边池信息
        if (state.lastSidePots.size > 1) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Text("边池详情", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    state.lastSidePots.forEach { pot ->
                        val names = sortedPlayers
                            .filter { pot.eligiblePlayerIds.contains(it.id) }
                            .joinToString(", ") { it.name }
                        Text("${pot.label}: ${pot.amount} | $names", fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ========== 玩家列表（纵向，均匀填满，无需滚动） ==========
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            sortedPlayers.forEach { player ->
                CompactPlayerCard(
                    player = player,
                    state = state,
                    sortedPlayers = sortedPlayers,
                    getMinContribution = getMinContribution,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ========== 底部：双页水平滑动操作栏 ==========
        val isFolded = state.foldedPlayerIds.contains(state.selfId)
        var showFoldConfirm by remember { mutableStateOf(false) }
        var showSettleConfirm by remember { mutableStateOf(false) }
        var showChipDialog by remember { mutableStateOf(false) }

        // 弃牌确认弹窗
        if (showFoldConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showFoldConfirm = false },
                title = { Text("确认弃牌", fontWeight = FontWeight.Bold) },
                text = { Text("弃牌后本手无法再操作，确定弃牌吗？") },
                confirmButton = {
                    Button(
                        onClick = { showFoldConfirm = false; onFold() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) { Text("确定弃牌") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showFoldConfirm = false }) { Text("取消") }
                }
            )
        }
        // 结算确认弹窗
        if (showSettleConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSettleConfirm = false },
                title = { Text("确认结算本手", fontWeight = FontWeight.Bold) },
                text = { Text("确认结束并结算本手吗？结算后将自动进入下一手。") },
                confirmButton = {
                    Button(
                        onClick = { showSettleConfirm = false; onSettleAndAdvance() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) { Text("确定结算") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSettleConfirm = false }) { Text("取消") }
                }
            )
        }
        // 筹码投入弹窗
        if (showChipDialog) {
            val myPlayer = sortedPlayers.firstOrNull { it.id == state.selfId }
            val totalPrev = state.contributionInputs[state.selfId]?.toIntOrNull() ?: 0
            val myRoundContrib = state.roundContributions[state.selfId] ?: 0
            val maxAvailable = (myPlayer?.chips ?: 0) - totalPrev - myRoundContrib
            val currentMaxBet = state.roundContributions.values.maxOrNull() ?: 0
            val callAmount = (currentMaxBet - myRoundContrib).coerceAtLeast(0)
            ChipInputDialog(
                maxChips = maxAvailable,
                callAmount = callAmount,
                onDismiss = { showChipDialog = false },
                onConfirm = { amount ->
                    showChipDialog = false
                    onSubmitContribution(amount)
                }
            )
        }

        // 操作可用性
        val canAct = isMyTurn && !isFolded && !isShowdown
        val canFold = canAct
        val canBet = canAct
        val canWin = isShowdown && !isFolded
        val canSettle = isShowdown && state.mode == TableMode.HOST

        val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { 2 })
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (page == 0) {
                            // 第一页：弃牌 + 过牌/投入
                            Button(
                                onClick = { if (canFold) showFoldConfirm = true },
                                enabled = canFold,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE53935),
                                    disabledContainerColor = Color(0xFFBDBDBD)
                                )
                            ) { Text("弃牌", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }

                            // 过牌/跟注按钮
                            val currentMaxBet = state.roundContributions.values.maxOrNull() ?: 0
                            val myRoundContrib = state.roundContributions[state.selfId] ?: 0
                            val callNeeded = currentMaxBet - myRoundContrib
                            if (callNeeded <= 0) {
                                // 可以过牌
                                Button(
                                    onClick = { if (canAct) onSubmitContribution(0) },
                                    enabled = canAct,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF43A047),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                    )
                                ) { Text("过牌", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            } else {
                                // 需要跟注
                                Button(
                                    onClick = { if (canAct) onSubmitContribution(callNeeded) },
                                    enabled = canAct,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF43A047),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                    )
                                ) { Text("跟注 $callNeeded", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                            }

                            Button(
                                onClick = { if (canBet) showChipDialog = true },
                                enabled = canBet,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1976D2),
                                    disabledContainerColor = Color(0xFFBDBDBD)
                                )
                            ) { Text("加注", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }

                            Text(
                                "〈",
                                fontSize = 18.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        } else {
                            // 第二页：Win + 结算本手（摊牌阶段可用）
                            val isWinner = state.selectedWinnerIds.contains(state.selfId)
                            Button(
                                onClick = { if (canWin) onToggleMyWinner() },
                                enabled = canWin,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isWinner) Color(0xFF388E3C) else Color(0xFF9E9E9E),
                                    disabledContainerColor = Color(0xFFBDBDBD)
                                )
                            ) { Text(if (isWinner) "Win ✓" else "Win", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }

                            if (state.mode == TableMode.HOST) {
                                Button(
                                    onClick = { if (canSettle) showSettleConfirm = true },
                                    enabled = canSettle,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                    )
                                ) { Text("结算本手", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 紧凑玩家卡片 ====================

@Composable
private fun CompactPlayerCard(
    player: PlayerState,
    state: TableUiState,
    sortedPlayers: List<PlayerState>,
    getMinContribution: (String) -> Int,
    modifier: Modifier = Modifier
) {
    val seatIdx = sortedPlayers.indexOf(player)
    val isMe = player.id == state.selfId
    val isOffline = state.disconnectedPlayerIds.contains(player.id)
    val isFolded = state.foldedPlayerIds.contains(player.id)
    val isCurrentTurn = player.id == state.currentTurnPlayerId && state.currentRound != BettingRound.SHOWDOWN

    val roleTag = buildString {
        if (state.blindsEnabled && state.players.size >= 2) {
            if (seatIdx == state.blindsState.dealerIndex) append("[庄]")
            if (seatIdx == state.blindsState.smallBlindIndex) append("[小盲]")
            if (seatIdx == state.blindsState.bigBlindIndex) append("[大盲]")
        }
    }
    val cardColor = when {
        isFolded -> Color(0xFFE0E0E0)
        isOffline -> Color(0xFFEEEEEE)
        isCurrentTurn -> Color(0xFFFFE0B2) // 橙色高亮：当前行动者
        isMe -> Color(0xFFFFF8E1)
        state.blindsEnabled && seatIdx == state.blindsState.dealerIndex -> Color(0xFFE3F2FD)
        else -> MaterialTheme.colorScheme.surface
    }
    val submittedAmount = state.contributionInputs[player.id]
    val roundContrib = state.roundContributions[player.id]

    Box(modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 左侧：身份信息
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (roleTag.isNotEmpty()) {
                            Text(
                                roleTag,
                                fontSize = 10.sp,
                                color = Color(0xFF1565C0),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            player.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (state.selectedWinnerIds.contains(player.id)) {
                            Text("[Win]", fontSize = 10.sp, color = Color(0xFF388E3C), fontWeight = FontWeight.Bold)
                        }
                        if (isFolded) {
                            Text("[弃牌]", fontSize = 10.sp, color = Color(0xFF9E9E9E), fontWeight = FontWeight.Bold)
                        }
                        if (isOffline) {
                            Text("[掉线]", fontSize = 10.sp, color = Color.Red)
                        }
                    }
                    if (state.blindsEnabled && state.players.size >= 2) {
                        val minContrib = getMinContribution(player.id)
                        if (minContrib > 0) {
                            Text("最低投入: $minContrib", fontSize = 10.sp, color = Color(0xFFE65100))
                        }
                    }
                }

                // 右侧：筹码 + 投入 + 本轮投入
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "筹码 ${player.chips}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (!submittedAmount.isNullOrBlank() && submittedAmount != "0") {
                        Text(
                            "总投入 $submittedAmount",
                            fontSize = 12.sp,
                            color = Color(0xFF388E3C),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (roundContrib != null && roundContrib > 0) {
                        Text(
                            "本轮 $roundContrib",
                            fontSize = 11.sp,
                            color = Color(0xFF1976D2),
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (isCurrentTurn) {
                        Text(
                            "⬤ 行动中",
                            fontSize = 10.sp,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==================== 筹码投入弹窗 ====================

@Composable
private fun ChipInputDialog(
    maxChips: Int,
    callAmount: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedAmount by remember { mutableIntStateOf(0) }
    var customMode by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    // 筹码矩阵值
    val chipValues = listOf(1, 5, 10, 20, 50, 100, 200)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加注筹码", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 提示信息
                if (callAmount > 0) {
                    Text(
                        "跟注需要 $callAmount，加注请选择更多",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Text(
                    "可用筹码: $maxChips",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                // 已选金额显示
                Text(
                    "投入: ${if (customMode) (customText.toIntOrNull() ?: 0) else selectedAmount}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1976D2),
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // 3×3 筹码按键矩阵
                val rows = listOf(
                    listOf(0, 1, 2),    // 1, 5, 10
                    listOf(3, 4, 5),    // 20, 50, 100
                    listOf(6, 7, 8)     // 200, All-In, 自定义
                )
                rows.forEach { rowIndices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowIndices.forEach { idx ->
                            when {
                                idx < 7 -> {
                                    // 数值按键
                                    val value = chipValues[idx]
                                    val isSelected = !customMode && selectedAmount == value
                                    OutlinedButton(
                                        onClick = {
                                            customMode = false
                                            selectedAmount = value
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isSelected) Color(0xFF1976D2) else Color.Transparent
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text(
                                            "$value",
                                            color = if (isSelected) Color.White else Color.Black,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                idx == 7 -> {
                                    // All-In
                                    val isSelected = !customMode && selectedAmount == maxChips
                                    OutlinedButton(
                                        onClick = {
                                            customMode = false
                                            selectedAmount = maxChips
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isSelected) Color(0xFFE53935) else Color.Transparent
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text(
                                            "All-In!",
                                            color = if (isSelected) Color.White else Color(0xFFE53935),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                else -> {
                                    // 自定义：点击后变成输入区
                                    if (customMode) {
                                        OutlinedButton(
                                            onClick = {},
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = Color(0xFFFFF8E1)
                                            ),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                        ) {
                                            Text(
                                                customText.ifEmpty { "0" },
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1976D2)
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                customMode = true
                                                customText = ""
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(44.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                        ) {
                                            Text(
                                                "自定义",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 虚拟数字键盘（仅在自定义模式显示）
                if (customMode) {
                    Spacer(Modifier.height(4.dp))
                    val numRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("00", "0", "⌫")
                    )
                    numRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { key ->
                                Button(
                                    onClick = {
                                        when (key) {
                                            "⌫" -> {
                                                if (customText.isNotEmpty()) {
                                                    customText = customText.dropLast(1)
                                                }
                                            }
                                            else -> {
                                                if (customText.length < 8) {
                                                    customText += key
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (key == "⌫") Color(0xFFEF9A9A) else Color(0xFFE0E0E0)
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                ) {
                                    Text(
                                        key,
                                        color = Color.Black,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = if (customMode) (customText.toIntOrNull() ?: 0) else selectedAmount
                    if (amount > 0) onConfirm(amount) else onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) { Text("确认加注", fontWeight = FontWeight.Bold) }
        },
        dismissButton = null
    )
}

// ==================== 最近记录界面 ====================

@Composable
private fun LogsScreen(state: TableUiState, onBack: () -> Unit) {
    val sortedPlayers = state.players.sortedBy { it.seatOrder }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 返回游戏") }
            Spacer(Modifier.weight(1f))
            Text("最近记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(72.dp))
        }

        Spacer(Modifier.height(8.dp))

        if (state.logs.isEmpty()) {
            Text(
                "暂无记录",
                fontSize = 14.sp, color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.logs.takeLast(50).reversed(), key = { it.id }) { tx ->
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tx.timestamp))
                    val pName = tx.playerName.ifBlank {
                        sortedPlayers.firstOrNull { it.id == tx.playerId }?.name
                            ?: tx.playerId.take(6)
                    }
                    val bg = when (tx.type) {
                        com.fushilaofang.texasholdemchipsim.model.TransactionType.WIN_PAYOUT -> Color(0xFFE8F5E9)
                        com.fushilaofang.texasholdemchipsim.model.TransactionType.BLIND_DEDUCTION -> Color(0xFFFFF9C4)
                        else -> Color.Transparent
                    }
                    Text(
                        "[$time] ${tx.handId} $pName ${tx.note} ${tx.amount} 余额:${tx.balanceAfter}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(4.dp),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
