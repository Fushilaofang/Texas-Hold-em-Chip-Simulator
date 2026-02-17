package com.fushilaofang.texasholdemchipsim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fushilaofang.texasholdemchipsim.blinds.BlindsConfig
import com.fushilaofang.texasholdemchipsim.network.DiscoveredRoom
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
                TableScreen(
                    state = state,
                    onHost = vm::hostTable,
                    onJoinRoom = vm::joinRoom,
                    onStartScan = vm::startRoomScan,
                    onStopScan = vm::stopRoomScan,
                    onContributionChange = vm::updateContribution,
                    onSubmitContribution = vm::submitMyContribution,
                    onToggleWinner = vm::toggleWinner,
                    onSettleAndAdvance = vm::settleAndAdvance,
                    onReset = vm::resetTable,
                    onToggleBlinds = vm::toggleBlinds,
                    onUpdateBlinds = vm::updateBlindsConfig,
                    onPlayerNameChange = vm::savePlayerName,
                    onRoomNameChange = vm::saveRoomName,
                    onBuyInChange = vm::saveBuyIn,
                    onSmallBlindChange = vm::saveSmallBlind,
                    onBigBlindChange = vm::saveBigBlind
                )
            }
        }
    }
}

@Composable
private fun TableScreen(
    state: TableUiState,
    onHost: (String, String, Int, BlindsConfig) -> Unit,
    onJoinRoom: (DiscoveredRoom, String, Int) -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onContributionChange: (String, String) -> Unit,
    onSubmitContribution: (Int) -> Unit,
    onToggleWinner: (String) -> Unit,
    onSettleAndAdvance: () -> Unit,
    onReset: () -> Unit,
    onToggleBlinds: (Boolean) -> Unit,
    onUpdateBlinds: (Int, Int) -> Unit,
    onPlayerNameChange: (String) -> Unit,
    onRoomNameChange: (String) -> Unit,
    onBuyInChange: (Int) -> Unit,
    onSmallBlindChange: (Int) -> Unit,
    onBigBlindChange: (Int) -> Unit
) {
    // 从持久化状态初始化，用户编辑时同步回写
    var roomName by remember(state.savedRoomName) { mutableStateOf(state.savedRoomName) }
    var playerName by remember(state.savedPlayerName) { mutableStateOf(state.savedPlayerName) }
    var buyIn by remember(state.savedBuyIn) { mutableIntStateOf(state.savedBuyIn) }
    var smallBlind by remember(state.savedSmallBlind) { mutableIntStateOf(state.savedSmallBlind) }
    var bigBlind by remember(state.savedBigBlind) { mutableIntStateOf(state.savedBigBlind) }

    // 本地投入输入框（只有自己的）
    var myContribInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("德州扑克牌桌筹码统计", style = MaterialTheme.typography.headlineSmall)
        Text("状态：${state.info}", style = MaterialTheme.typography.bodyMedium)

        // ==================== 开桌 / 搜索房间面板 ====================
        if (state.mode == TableMode.IDLE) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = playerName, onValueChange = { playerName = it; onPlayerNameChange(it) },
                        label = { Text("你的昵称") }, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = buyIn.toString(), onValueChange = { val v = it.toIntOrNull() ?: buyIn; buyIn = v; onBuyInChange(v) },
                        label = { Text("初始筹码") }, modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider()
                    Text("创建房间", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    OutlinedTextField(
                        value = roomName, onValueChange = { roomName = it; onRoomNameChange(it) },
                        label = { Text("房间名") }, modifier = Modifier.fillMaxWidth()
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
                    Button(
                        onClick = { onHost(roomName, playerName, buyIn, BlindsConfig(smallBlind, bigBlind)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("创建房间") }

                    HorizontalDivider()
                    Text("搜索并加入房间", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    if (!state.isScanning) {
                        Button(
                            onClick = onStartScan,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
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

                    // 发现的房间列表
                    if (state.discoveredRooms.isNotEmpty()) {
                        state.discoveredRooms.forEach { room ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onJoinRoom(room, playerName, buyIn) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
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
                    } else if (state.isScanning) {
                        Text("搜索中...", fontSize = 13.sp, color = Color.Gray)
                    }
                }
            }
        }

        // ==================== 牌桌信息栏 ====================
        val sortedPlayers = state.players.sortedBy { it.seatOrder }
        val dealerName = sortedPlayers.getOrNull(state.blindsState.dealerIndex)?.name ?: "-"
        val sbName = sortedPlayers.getOrNull(state.blindsState.smallBlindIndex)?.name ?: "-"
        val bbName = sortedPlayers.getOrNull(state.blindsState.bigBlindIndex)?.name ?: "-"

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("房间：${state.tableName}", fontWeight = FontWeight.Bold)
            Text("手数：${state.handCounter}")
        }
        if (state.mode == TableMode.HOST && state.players.size >= 2) {
            Text(
                "D: $dealerName | SB: $sbName | BB: $bbName | SB=${state.blindsState.config.smallBlind} BB=${state.blindsState.config.bigBlind}",
                fontSize = 13.sp, color = Color.Gray
            )
        }

        // ==================== 盲注开关（主持人） ====================
        if (state.mode == TableMode.HOST) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("盲注自动轮转", fontSize = 13.sp)
                Switch(checked = state.blindsEnabled, onCheckedChange = onToggleBlinds)
            }
        }

        HorizontalDivider()

        // ==================== 边池信息 ====================
        if (state.lastSidePots.size > 1) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("边池详情", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    state.lastSidePots.forEach { pot ->
                        val names = sortedPlayers
                            .filter { pot.eligiblePlayerIds.contains(it.id) }
                            .joinToString(", ") { it.name }
                        Text("${pot.label}: ${pot.amount} 筹码 | 参与: $names", fontSize = 12.sp)
                    }
                }
            }
        }

        // ==================== 玩家列表 ====================
        Text("玩家与下注", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortedPlayers, key = { it.id }) { player ->
                val seatIdx = sortedPlayers.indexOf(player)
                val roleTag = buildString {
                    if (seatIdx == state.blindsState.dealerIndex) append("[D] ")
                    if (seatIdx == state.blindsState.smallBlindIndex) append("[SB] ")
                    if (seatIdx == state.blindsState.bigBlindIndex) append("[BB] ")
                }
                val cardColor = when {
                    seatIdx == state.blindsState.dealerIndex -> Color(0xFFE3F2FD)
                    else -> MaterialTheme.colorScheme.surface
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("$roleTag${player.name}", fontWeight = FontWeight.SemiBold)
                            Text("筹码: ${player.chips}", fontWeight = FontWeight.Bold)
                        }

                        // 显示该玩家已提交的投入
                        val submittedAmount = state.contributionInputs[player.id]
                        if (!submittedAmount.isNullOrBlank()) {
                            Text("已提交投入: $submittedAmount", fontSize = 12.sp, color = Color(0xFF388E3C))
                        } else {
                            Text("未提交投入", fontSize = 12.sp, color = Color.Gray)
                        }

                        // 只有自己能编辑自己的投入
                        val isMe = player.id == state.selfId
                        if (isMe) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = myContribInput,
                                    onValueChange = { myContribInput = it },
                                    label = { Text("我的本手投入") },
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = {
                                    val amount = myContribInput.toIntOrNull() ?: 0
                                    onSubmitContribution(amount)
                                }) { Text("提交") }
                            }
                        }

                        // 房主可以勾选赢家
                        if (state.mode == TableMode.HOST) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.selectedWinnerIds.contains(player.id),
                                    onCheckedChange = { onToggleWinner(player.id) }
                                )
                                Text("赢家")
                            }
                        }
                    }
                }
            }
        }

        // ==================== 操作按钮 ====================
        if (state.mode == TableMode.HOST) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSettleAndAdvance,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) { Text("结算本手") }
                OutlinedButton(onClick = onReset) { Text("清空输入") }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ==================== 记录 ====================
        Text("最近记录", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.weight(0.7f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.logs.takeLast(30).reversed(), key = { it.id }) { tx ->
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tx.timestamp))
                val pName = state.players.firstOrNull { it.id == tx.playerId }?.name ?: tx.playerId.take(6)
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
                        .padding(2.dp),
                    fontSize = 12.sp
                )
            }
        }
    }
}
