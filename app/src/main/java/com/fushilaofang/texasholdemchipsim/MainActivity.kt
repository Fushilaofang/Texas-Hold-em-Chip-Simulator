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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fushilaofang.texasholdemchipsim.blinds.BlindsConfig
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import com.fushilaofang.texasholdemchipsim.network.DiscoveredRoom
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

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                        onRemovePlayer = vm::removePlayer
                    )
                    ScreenState.GAME -> GameScreen(
                        state = state,
                        onSubmitContribution = vm::submitMyContribution,
                        onToggleWinner = vm::toggleWinner,
                        onSettleAndAdvance = vm::settleAndAdvance,
                        onReset = vm::resetTable,
                        onToggleBlinds = vm::toggleBlinds,
                        getMinContribution = vm::getMinContribution,
                        onRemovePlayer = vm::removePlayer
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJoinRoom(room, state.savedPlayerName, state.savedBuyIn) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(room.roomName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(
                                    "房主: ${room.hostName} | ${room.playerCount}人在线",
                                    fontSize = 12.sp, color = Color.Gray
                                )
                            }
                            Text("加入 →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
    onRemovePlayer: (String) -> Unit
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
            }
        }

        // 房主盲注开关
        if (state.mode == TableMode.HOST) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("盲注自动轮转", fontSize = 13.sp)
                Switch(checked = state.blindsEnabled, onCheckedChange = onToggleBlinds)
            }
        }

        HorizontalDivider()
        Text("玩家列表", fontWeight = FontWeight.Bold)

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortedPlayers, key = { it.id }) { player ->
                val isMe = player.id == state.selfId
                val isHost = state.mode == TableMode.HOST
                val isOffline = state.disconnectedPlayerIds.contains(player.id)
                var showMenu by remember { mutableStateOf(false) }

                val cardColor = when {
                    isOffline -> Color(0xFFEEEEEE)
                    player.isReady -> Color(0xFFE8F5E9)
                    else -> MaterialTheme.colorScheme.surface
                }
                Box {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isHost && !isMe) {
                                    Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { showMenu = true }
                                    )
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
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${player.name}${if (isMe) " (我)" else ""}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (isOffline) {
                                        Text("[掉线]", fontSize = 11.sp, color = Color.Red)
                                    }
                                }
                                Text("筹码: ${player.chips}", fontSize = 13.sp, color = Color.Gray)
                            }
                            Text(
                                if (player.isReady) "✔ 已准备" else "未准备",
                                color = if (player.isReady) Color(0xFF388E3C) else Color.Gray,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("移除 ${player.name}", color = Color.Red) },
                            onClick = {
                                showMenu = false
                                onRemovePlayer(player.id)
                            }
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
    onToggleWinner: (String) -> Unit,
    onSettleAndAdvance: () -> Unit,
    onReset: () -> Unit,
    onToggleBlinds: (Boolean) -> Unit,
    getMinContribution: (String) -> Int,
    onRemovePlayer: (String) -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) {
        LogsScreen(state = state, onBack = { showLogs = false })
        return
    }

    var myContribInput by remember { mutableStateOf("") }
    val sortedPlayers = state.players.sortedBy { it.seatOrder }
    val dealerName = sortedPlayers.getOrNull(state.blindsState.dealerIndex)?.name ?: "-"
    val sbName = sortedPlayers.getOrNull(state.blindsState.smallBlindIndex)?.name ?: "-"
    val bbName = sortedPlayers.getOrNull(state.blindsState.bigBlindIndex)?.name ?: "-"

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
                }
                if (state.blindsEnabled && sortedPlayers.size >= 2) {
                    Text(
                        "庄:$dealerName  小盲:$sbName  大盲:$bbName  (${state.blindsState.config.smallBlind}/${state.blindsState.config.bigBlind})",
                        fontSize = 11.sp, color = Color.Gray, maxLines = 1
                    )
                }
            }
            OutlinedButton(
                onClick = { showLogs = true },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("记录") }
        }

        // 房主盲注开关
        if (state.mode == TableMode.HOST) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 2.dp)
            ) {
                Text("盲注轮转", fontSize = 12.sp)
                Switch(
                    checked = state.blindsEnabled,
                    onCheckedChange = onToggleBlinds,
                    modifier = Modifier.height(28.dp)
                )
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

        // ========== 玩家网格（自适应填满，一次显示全部） ==========
        val playerRows = sortedPlayers.chunked(2)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            playerRows.forEach { rowPlayers ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowPlayers.forEach { player ->
                        CompactPlayerCard(
                            player = player,
                            state = state,
                            sortedPlayers = sortedPlayers,
                            onToggleWinner = onToggleWinner,
                            getMinContribution = getMinContribution,
                            onRemovePlayer = onRemovePlayer,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                    if (rowPlayers.size < 2) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ========== 底部：我的投入操作 ==========
        val myPlayer = sortedPlayers.firstOrNull { it.id == state.selfId }
        if (myPlayer != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    if (state.blindsEnabled && sortedPlayers.size >= 2) {
                        val minC = getMinContribution(myPlayer.id)
                        if (minC > 0) {
                            Text(
                                "最低投入: $minC | 最大: ${myPlayer.chips}",
                                fontSize = 11.sp, color = Color(0xFFE65100)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = myContribInput,
                            onValueChange = { myContribInput = it },
                            label = { Text("我的本手投入") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(onClick = {
                            val amount = myContribInput.toIntOrNull() ?: 0
                            onSubmitContribution(amount)
                        }) { Text("提交") }
                    }
                }
            }
        }

        // ========== 底部：房主操作按钮 ==========
        if (state.mode == TableMode.HOST) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSettleAndAdvance,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) { Text("结算本手") }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) { Text("清空输入") }
            }
        }
    }
}

// ==================== 紧凑玩家卡片 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactPlayerCard(
    player: PlayerState,
    state: TableUiState,
    sortedPlayers: List<PlayerState>,
    onToggleWinner: (String) -> Unit,
    getMinContribution: (String) -> Int,
    onRemovePlayer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val seatIdx = sortedPlayers.indexOf(player)
    val isMe = player.id == state.selfId
    val isHost = state.mode == TableMode.HOST
    val isOffline = state.disconnectedPlayerIds.contains(player.id)
    var showMenu by remember { mutableStateOf(false) }

    val roleTag = buildString {
        if (state.blindsEnabled && state.players.size >= 2) {
            if (seatIdx == state.blindsState.dealerIndex) append("[庄]")
            if (seatIdx == state.blindsState.smallBlindIndex) append("[小盲]")
            if (seatIdx == state.blindsState.bigBlindIndex) append("[大盲]")
        }
    }
    val cardColor = when {
        isOffline -> Color(0xFFEEEEEE)
        isMe -> Color(0xFFFFF8E1)
        state.blindsEnabled && seatIdx == state.blindsState.dealerIndex -> Color(0xFFE3F2FD)
        else -> MaterialTheme.colorScheme.surface
    }
    val submittedAmount = state.contributionInputs[player.id]

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isHost && !isMe) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                    } else Modifier
                ),
            colors = CardDefaults.cardColors(containerColor = cardColor)
        ) {
            Column(
                modifier = Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$roleTag${player.name}${if (isMe) "(我)" else ""}",
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOffline) {
                        Text("[掉线]", fontSize = 10.sp, color = Color.Red)
                    }
                }
                Text("筹码: ${player.chips}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (!submittedAmount.isNullOrBlank()) {
                    Text("投入: $submittedAmount", fontSize = 11.sp, color = Color(0xFF388E3C))
                } else {
                    Text("未提交", fontSize = 11.sp, color = Color.Gray)
                }
                if (state.blindsEnabled && state.players.size >= 2) {
                    val minContrib = getMinContribution(player.id)
                    if (minContrib > 0) {
                        Text("最低: $minContrib", fontSize = 10.sp, color = Color(0xFFE65100))
                    }
                }
                if (isHost) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.selectedWinnerIds.contains(player.id),
                            onCheckedChange = { onToggleWinner(player.id) },
                            modifier = Modifier.size(20.dp)
                        )
                        Text("赢家", fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("移除 ${player.name}", color = Color.Red) },
                onClick = {
                    showMenu = false
                    onRemovePlayer(player.id)
                }
            )
        }
    }
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
