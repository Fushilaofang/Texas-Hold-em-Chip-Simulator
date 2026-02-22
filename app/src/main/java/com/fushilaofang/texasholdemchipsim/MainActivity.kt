package com.fushilaofang.texasholdemchipsim

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fushilaofang.texasholdemchipsim.blinds.BlindsConfig
import com.fushilaofang.texasholdemchipsim.model.PlayerState
import com.fushilaofang.texasholdemchipsim.model.TransactionType
import com.fushilaofang.texasholdemchipsim.network.DiscoveredRoom
import com.fushilaofang.texasholdemchipsim.ui.BettingRound
import com.fushilaofang.texasholdemchipsim.ui.MidGameJoinStatus
import com.fushilaofang.texasholdemchipsim.ui.PendingMidJoinInfo
import com.fushilaofang.texasholdemchipsim.ui.ScreenState
import com.fushilaofang.texasholdemchipsim.ui.TableMode
import com.fushilaofang.texasholdemchipsim.ui.TableUiState
import com.fushilaofang.texasholdemchipsim.ui.TableViewModel
import com.fushilaofang.texasholdemchipsim.ui.TableViewModelFactory
import com.fushilaofang.texasholdemchipsim.util.AvatarHelper
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
            val context = LocalContext.current

            // 图片选择器（申请权限 + 选取）
            // 选图后先进入裁切 UI，裁切确认后再写入 ViewModel
            var pendingAvatarBase64 by remember { mutableStateOf("") }
            // 裁切来源：uri 不为 null 时显示裁切对话框
            var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
            // 裁切完成后的目标：true=保存到 ViewModel，false=写入 pendingAvatarBase64（对话框预览）
            var cropTargetIsDialog by remember { mutableStateOf(false) }

            val imagePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    pendingCropUri = uri
                }
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) imagePicker.launch("image/*")
            }
            fun launchPickerRaw() {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_IMAGES
                else
                    Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                    imagePicker.launch("image/*")
                } else {
                    permissionLauncher.launch(permission)
                }
            }
            // 主界面选头像
            val launchAvatarPicker: () -> Unit = {
                cropTargetIsDialog = false
                launchPickerRaw()
            }
            // 资料对话框内选头像（裁切后写入 pendingAvatarBase64 预览）
            val launchAvatarPickerInDialog: () -> Unit = {
                cropTargetIsDialog = true
                launchPickerRaw()
            }

            // 裁切对话框
            if (pendingCropUri != null) {
                CropImageDialog(
                    uri = pendingCropUri!!,
                    onConfirm = { base64 ->
                        pendingCropUri = null
                        if (cropTargetIsDialog) {
                            pendingAvatarBase64 = base64
                        } else {
                            vm.saveAvatarBase64(base64)
                        }
                    },
                    onCancel = { pendingCropUri = null }
                )
            }

            Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colorScheme.background) {
                when (state.screen) {
                    ScreenState.HOME -> HomeScreen(
                        state = state,
                        onNavigateCreate = { vm.navigateTo(ScreenState.CREATE_ROOM) },
                        onNavigateJoin = { vm.navigateTo(ScreenState.JOIN_ROOM) },
                        onPlayerNameChange = vm::savePlayerName,
                        onBuyInChange = vm::saveBuyIn,
                        onRejoin = vm::rejoinSession,
                        onPickAvatar = launchAvatarPicker
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

                        onUpdateBlindsConfig = vm::updateBlindsConfig,
                        onMovePlayer = vm::movePlayer,
                        onSetInitialDealer = vm::setInitialDealer,
                        onToggleAllowMidGameJoin = vm::toggleAllowMidGameJoin,
                        onApproveMidGameJoin = vm::approveMidGameJoin,
                        onRejectMidGameJoin = vm::rejectMidGameJoin,
                        onCancelMidGameJoin = vm::cancelMidGameJoin
                    )
                    ScreenState.GAME -> GameScreen(
                        state = state,
                        pendingAvatarBase64 = pendingAvatarBase64,
                        onClearPendingAvatar = { pendingAvatarBase64 = "" },
                        onSubmitContribution = vm::submitMyContribution,
                        onToggleMyWinner = vm::toggleMyWinner,
                        onFold = vm::foldMyself,
                        onSettleAndAdvance = vm::settleAndAdvance,
                        onToggleBlinds = vm::toggleBlinds,

                        onUpdateBlindsConfig = vm::updateBlindsConfig,
                        onMovePlayer = vm::movePlayer,
                        onSetDealer = vm::setDealerInGame,
                        onUpdateMyProfile = vm::updateMyProfile,
                        onPickAvatar = launchAvatarPickerInDialog,
                        onLeave = vm::goHome,
                        onApproveMidGameJoin = vm::approveMidGameJoin,
                        onRejectMidGameJoin = vm::rejectMidGameJoin
                    )
                }

                // 等待房主重连弹窗
                if (state.waitingForHostReconnect) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { /* 不允许点击外部关闭 */ },
                        title = { Text("连接中断", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("请等待房主重连或检查局域网连接", fontSize = 16.sp)
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
    onRejoin: () -> Unit,
    onPickAvatar: () -> Unit
) {
    var playerName by remember(state.savedPlayerName) { mutableStateOf(state.savedPlayerName) }
    var buyIn by remember(state.savedBuyIn) { mutableIntStateOf(state.savedBuyIn) }
    var buyInText by remember(state.savedBuyIn) { mutableStateOf(state.savedBuyIn.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Texas Hold'em Chips",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))

        // 头像选择区域
        AvatarPicker(
            avatarBase64 = state.savedAvatarBase64,
            size = 80,
            onClick = onPickAvatar
        )
        Spacer(Modifier.height(4.dp))
        Text("点击更换头像", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = playerName,
            onValueChange = { playerName = it; onPlayerNameChange(it) },
            label = { Text("你的昵称") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = buyInText,
            onValueChange = {
                if (it.length <= 8) {
                    buyInText = it
                    val v = it.toIntOrNull()
                    if (v != null && v > 0) { buyIn = v; onBuyInChange(v) }
                }
            },
            label = { Text("初始筹码") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        val v = buyInText.toIntOrNull()
                        if (v == null || v <= 0) {
                            buyInText = "1"
                            buyIn = 1
                            onBuyInChange(1)
                        } else {
                            buyInText = v.toString()
                        }
                    }
                }
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

// ==================== 头像组件 ====================

/**
 * 可点击的圆形头像框：有头像时显示图片，无头像时显示首字母占位符
 */
@Composable
private fun AvatarPicker(
    avatarBase64: String,
    size: Int = 48,
    onClick: () -> Unit
) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64.isBlank()) null
        else {
            try {
                val bytes = android.util.Base64.decode(avatarBase64, android.util.Base64.NO_WRAP)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(0xFFBDBDBD))
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.bilibili_default_avatar),
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 圆形头像展示（不可点击），用于玩家卡片
 */
@Composable
private fun AvatarImage(
    avatarBase64: String,
    name: String,
    size: Int = 40
) {
    val bitmap = remember(avatarBase64) {
        if (avatarBase64.isBlank()) null
        else {
            try {
                val bytes = android.util.Base64.decode(avatarBase64, android.util.Base64.NO_WRAP)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
        }
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.bilibili_default_avatar),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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
                    val isFull = room.playerCount >= room.maxPlayers
                    val canJoin = !isFull && (!started || room.allowMidGameJoin)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (canJoin) {
                                    onJoinRoom(room, state.savedPlayerName, state.savedBuyIn)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                isFull -> Color(0xFFF5F5F5)
                                !started -> Color(0xFFE8F5E9)
                                room.allowMidGameJoin -> Color(0xFFE3F2FD)
                                else -> Color(0xFFF5F5F5)
                            }
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
                                    color = if (!canJoin) Color.Gray else Color.Unspecified)
                                Text(
                                    "房主: ${room.hostName} | ${room.playerCount}/${room.maxPlayers}人",
                                    fontSize = 12.sp, color = Color.Gray
                                )
                                when {
                                    isFull -> Text("房间已满", fontSize = 11.sp, color = Color(0xFFE53935))
                                    started && !room.allowMidGameJoin -> Text("游戏已开始，不可加入", fontSize = 11.sp, color = Color(0xFFE53935))
                                    started && room.allowMidGameJoin -> Text("游戏进行中 · 允许中途加入", fontSize = 11.sp, color = Color(0xFF1565C0))
                                }
                            }
                            when {
                                isFull -> Text("已满", color = Color.Gray, fontSize = 13.sp)
                                !started -> Text("加入 →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                room.allowMidGameJoin -> Text("中途加入 →", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)
                                else -> Text("🔒 已开始", color = Color.Gray, fontSize = 13.sp)
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

    onUpdateBlindsConfig: (Int, Int) -> Unit,
    onMovePlayer: (String, Int) -> Unit,
    onSetInitialDealer: (Int) -> Unit,
    onToggleAllowMidGameJoin: (Boolean) -> Unit = {},
    onApproveMidGameJoin: (String) -> Unit = {},
    onRejectMidGameJoin: (String, Boolean) -> Unit = { _, _ -> },
    onCancelMidGameJoin: () -> Unit = {}
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

        // 房主开关
        if (state.mode == TableMode.HOST) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("盲注自动轮转", fontSize = 13.sp)
                Switch(checked = state.blindsEnabled, onCheckedChange = onToggleBlinds)
                Spacer(Modifier.weight(1f))
                Text("中途加入", fontSize = 13.sp)
                Switch(checked = state.allowMidGameJoin, onCheckedChange = onToggleAllowMidGameJoin)
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
        }

        // HOST 中途加入审批弹窗
        if (state.mode == TableMode.HOST && state.pendingMidJoins.isNotEmpty()) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = {
                    Text("中途加入申请", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.pendingMidJoins.forEach { info ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (info.avatarBase64.isNotBlank()) {
                                    AvatarPicker(avatarBase64 = info.avatarBase64, size = 32, onClick = {})
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(info.playerName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("买入: ${info.buyIn}", fontSize = 11.sp, color = Color.Gray)
                                }
                                Button(
                                    onClick = { onApproveMidGameJoin(info.requestId) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                                ) { Text("同意", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { onRejectMidGameJoin(info.requestId, false) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("拒绝", fontSize = 12.sp) }
                                Button(
                                    onClick = { onRejectMidGameJoin(info.requestId, true) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                                ) { Text("屏蔽", fontSize = 12.sp) }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
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
                    isDealer -> Color(0xFFFFF8E1)
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
                                if (isDealer) {
                                    Text("[庄]", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    "${player.name}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (isOffline) {
                                    Text("[掉线]", fontSize = 11.sp, color = Color.Red)
                                }
                                if (state.midGameWaitingPlayerIds.contains(player.id)) {
                                    Text("[待加入]", fontSize = 11.sp, color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)
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
            val isSelfWaiting = state.selfId.isNotBlank() && state.midGameWaitingPlayerIds.contains(state.selfId)
            // 判断自己是否是中途加入者（游戏已开始且自己不在正式玩家行动列表中，或等待中）
            val isMidGameJoiner = state.gameStarted && !isSelfWaiting &&
                    state.selfId.isNotBlank() && sortedPlayers.none { it.id == state.selfId }
            when {
                isSelfWaiting -> {
                    // 已获批，等待下一手
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = {},
                        title = { Text("等待加入", fontWeight = FontWeight.Bold) },
                        text = { Text("您已获批加入，请等待当前手结束后下一手开始...") },
                        confirmButton = {},
                        dismissButton = {
                            OutlinedButton(onClick = onCancelMidGameJoin) { Text("取消加入") }
                        }
                    )
                    Button(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = false,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) { Text("等待下一手开始", fontSize = 18.sp) }
                }
                state.midGameJoinStatus == MidGameJoinStatus.PENDING -> {
                    Button(
                        onClick = onCancelMidGameJoin,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043))
                    ) { Text("取消申请", fontSize = 18.sp) }
                }
                state.midGameJoinStatus == MidGameJoinStatus.REJECTED -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("加入申请已被拒绝", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("返回主界面", fontSize = 18.sp) }
                    }
                }
                state.midGameJoinStatus == MidGameJoinStatus.BLOCKED -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("您已被屏蔽，无法再次申请加入该房间", color = Color(0xFFE53935), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("返回主界面", fontSize = 18.sp) }
                    }
                }
                isMidGameJoiner -> {
                    // 游戏进行中，自己尚未申请加入
                    Button(
                        onClick = onToggleReady,   // 复用 onToggleReady 触发加入申请（ViewModel 中识别 gameStarted 状态处理）
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                    ) { Text("中途加入", fontSize = 18.sp) }
                }
                else -> {
                    val selfReady = sortedPlayers.firstOrNull { it.id == state.selfId }?.isReady ?: false
                    Button(
                        onClick = onToggleReady,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
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

    onUpdateBlindsConfig: (Int, Int) -> Unit,
    onMovePlayer: (String, Int) -> Unit,
    onSetDealer: (Int) -> Unit,
    onLeave: () -> Unit,
    onUpdateMyProfile: (String, String) -> Unit = { _, _ -> },
    onPickAvatar: () -> Unit = {},
    pendingAvatarBase64: String = "",
    onClearPendingAvatar: () -> Unit = {},
    onApproveMidGameJoin: (String) -> Unit = {},
    onRejectMidGameJoin: (String, Boolean) -> Unit = { _, _ -> }
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
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showDealerPanel by remember { mutableStateOf(false) }
    val sortedPlayers = state.players
        .filter { !state.midGameWaitingPlayerIds.contains(it.id) }
        .sortedBy { it.seatOrder }
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

    // 修改本人资料对话框
    if (showEditProfileDialog) {
        val myPlayer = state.players.firstOrNull { it.id == state.selfId }
        // editName 仅在对话框首次展示时初始化一次
        var editName by remember { mutableStateOf(myPlayer?.name ?: state.savedPlayerName) }
        // 预览头像：优先用本轮选取的临时头像，否则用已保存的头像
        val previewAvatar = pendingAvatarBase64.ifBlank { state.savedAvatarBase64 }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showEditProfileDialog = false
                onClearPendingAvatar()
            },
            title = { Text("修改昵称和头像", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 头像选择器：点击后选图，对话框保持打开
                    AvatarPicker(
                        avatarBase64 = previewAvatar,
                        size = 72,
                        onClick = { onPickAvatar() }
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("点击头像可更换图片", fontSize = 11.sp, color = Color.Gray)
                        if (pendingAvatarBase64.isNotBlank()) {
                            Text("（已选新图）", fontSize = 11.sp, color = Color(0xFF43A047))
                        }
                    }
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalAvatar = pendingAvatarBase64.ifBlank { state.savedAvatarBase64 }
                    if (editName.isNotBlank()) {
                        onUpdateMyProfile(editName.trim(), finalAvatar)
                    }
                    showEditProfileDialog = false
                    onClearPendingAvatar()
                }) { Text("确定") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showEditProfileDialog = false
                    onClearPendingAvatar()
                }) { Text("取消") }
            }
        )
    }

    // 游戏中调整玩家顺序面板
    // 调整玩家顺序对话框（仅移动座位，不涉及选庄）
    if (showReorderPanel && state.mode == TableMode.HOST) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReorderPanel = false },
            title = { Text("调整玩家顺序", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("点击 ▲▼ 调整座位顺序", fontSize = 12.sp, color = Color.Gray)
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
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                OutlinedButton(
                                    onClick = { if (seatIdx > 0) onMovePlayer(player.id, seatIdx - 1) },
                                    enabled = seatIdx > 0,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) { Text("▲", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { if (seatIdx < reorderPlayers.size - 1) onMovePlayer(player.id, seatIdx + 1) },
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

    // 重新选庄对话框
    if (showDealerPanel && state.mode == TableMode.HOST) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDealerPanel = false },
            title = { Text("重新选庄", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("点击[设为庄]指定庄家", fontSize = 12.sp, color = Color.Gray)
                    val dealerPlayers = state.players.sortedBy { it.seatOrder }
                    dealerPlayers.forEachIndexed { seatIdx, player ->
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
                            if (!isDealer) {
                                Button(
                                    onClick = { onSetDealer(seatIdx); showDealerPanel = false },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                                ) { Text("设为庄", fontSize = 12.sp) }
                            } else {
                                Text("当前庄家", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showDealerPanel = false }) { Text("完成") }
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.tableName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("第${state.handCounter}手", fontSize = 14.sp, color = Color.Gray)
                    Text(
                        roundLabel,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isShowdown) Color(0xFFE65100) else Color(0xFF1976D2)
                    )
                }
                if (!isShowdown && turnPlayerName.isNotEmpty()) {
                    Text(
                        if (isMyTurn) "轮到你行动" else "等待 $turnPlayerName 行动",
                        fontSize = 13.sp,
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
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("重新选庄")
                                    if (!isBetweenHands) {
                                        Text("(手间可用)", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            },
                            enabled = isBetweenHands,
                            onClick = { showMenu = false; showDealerPanel = true }
                        )
                        HorizontalDivider()
                    }

                    DropdownMenuItem(
                        text = { Text("修改头像和昵称") },
                        onClick = { showMenu = false; showEditProfileDialog = true }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("返回主界面", color = Color(0xFFE53935)) },
                        onClick = { showMenu = false; showExitConfirm = true }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // ========== 游戏中中途加入审批弹窗 (HOST only) ==========
        if (state.mode == TableMode.HOST && state.pendingMidJoins.isNotEmpty()) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = {
                    Text("中途加入申请", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.pendingMidJoins.forEach { info ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (info.avatarBase64.isNotBlank()) {
                                    AvatarPicker(avatarBase64 = info.avatarBase64, size = 32, onClick = {})
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(info.playerName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("买入: ${info.buyIn}", fontSize = 11.sp, color = Color.Gray)
                                }
                                Button(
                                    onClick = { onApproveMidGameJoin(info.requestId) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                                ) { Text("同意", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { onRejectMidGameJoin(info.requestId, false) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("拒绝", fontSize = 12.sp) }
                                Button(
                                    onClick = { onRejectMidGameJoin(info.requestId, true) },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                                ) { Text("屏蔽", fontSize = 12.sp) }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }

        // ========== 牌桌区域（矢量绘制椭圆桌 + 玩家环绕） ==========
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // 牌桌背景
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                // 竖向圆槽形：限制最大高度，确保桌体完整显示在画布内
                val tableH = minOf(size.height * 0.78f, size.width * 1.55f)
                val tableW = minOf(size.width * 0.64f, tableH * 0.52f)
                val cornerR = tableW / 2f

                // 竖向胶囊形（Vertical Capsule）：高 > 宽，上下各一个半圆
                // r = w/2；顶部半圆逆时针（sweep=-180），底部半圆顺时针（sweep=+180）
                fun stadiumPath(left: Float, top: Float, w: Float, h: Float): Path {
                    val r = w / 2f
                    return Path().apply {
                        // 从顶部右侧出发
                        moveTo(left + w, top + r)
                        // 顶部半圆：起点右(0°)，逆时针 -180° 到左侧 → 上弧
                        arcTo(
                            rect = androidx.compose.ui.geometry.Rect(
                                Offset(left, top), Size(2 * r, 2 * r)
                            ),
                            startAngleDegrees = 0f, sweepAngleDegrees = -180f,
                            forceMoveTo = false
                        )
                        // 左侧竖直线向下
                        lineTo(left, top + h - r)
                        // 底部半圆：起点左(180°)，逆时针 -180° 到右侧 → 下弧（经过底部最低点）
                        arcTo(
                            rect = androidx.compose.ui.geometry.Rect(
                                Offset(left, top + h - 2 * r), Size(2 * r, 2 * r)
                            ),
                            startAngleDegrees = 180f, sweepAngleDegrees = -180f,
                            forceMoveTo = false
                        )
                        // 右侧竖直线回到起点
                        close()
                    }
                }

                val tableL = cx - tableW / 2f
                val tableT = cy - tableH / 2f

                // 阴影
                drawPath(
                    path = stadiumPath(tableL + 4f, tableT + 6f, tableW, tableH),
                    color = Color(0x28000000)
                )
                // 桌面主体
                val tablePath = stadiumPath(tableL, tableT, tableW, tableH)
                drawPath(path = tablePath, color = Color(0xFFF5F0E8))

                // -------- 装饰花纹（保持原色调）--------

                // 1. 内圈描边（内缩 14dp 的相似竖向圆槽形）
                val innerInset = 14f
                val innerPath = stadiumPath(
                    tableL + innerInset, tableT + innerInset,
                    tableW - innerInset * 2, tableH - innerInset * 2
                )
                drawPath(
                    path = innerPath,
                    color = Color(0xFFE0D8C8),
                    style = Stroke(width = 1.8f)
                )

                // 2. 再内圈细线
                val inner2Inset = 22f
                drawPath(
                    path = stadiumPath(
                        tableL + inner2Inset, tableT + inner2Inset,
                        tableW - inner2Inset * 2, tableH - inner2Inset * 2
                    ),
                    color = Color(0xFFD8CEBA),
                    style = Stroke(width = 1f)
                )

                // 3. 纵向中线（淡色分隔线）
                drawLine(
                    color = Color(0x30A09070),
                    start = Offset(cx, tableT + cornerR),
                    end = Offset(cx, tableT + tableH - cornerR),
                    strokeWidth = 1f
                )

                // 4. 网格装饰（仅在矩形中段内绘制，淡色）
                val gridColor = Color(0x18A09070)
                val gridStep = 28f
                val rectLeft = tableL + 8f
                val rectRight = tableL + tableW - 8f
                val rectTop = tableT + cornerR
                val rectBottom = tableT + tableH - cornerR
                var xi = rectLeft
                while (xi <= rectRight) {
                    drawLine(
                        color = gridColor,
                        start = Offset(xi, rectTop),
                        end = Offset(xi, rectBottom),
                        strokeWidth = 0.8f
                    )
                    xi += gridStep
                }
                var yi = rectTop
                while (yi <= rectBottom) {
                    drawLine(
                        color = gridColor,
                        start = Offset(rectLeft, yi),
                        end = Offset(rectRight, yi),
                        strokeWidth = 0.8f
                    )
                    yi += gridStep
                }

                // 5. 上下两端半圆扇形装饰（放射线）
                val fanColor = Color(0x15907050)
                val fanLineCount = 8
                // 上端半圆
                val topCircleCy = tableT + cornerR
                for (i in 0 until fanLineCount) {
                    val angle = (Math.PI * (i.toDouble() / (fanLineCount - 1))) + Math.PI
                    drawLine(
                        color = fanColor,
                        start = Offset(cx, topCircleCy),
                        end = Offset(
                            (cx + cornerR * 0.9f * kotlin.math.cos(angle)).toFloat(),
                            (topCircleCy + cornerR * 0.9f * kotlin.math.sin(angle)).toFloat()
                        ),
                        strokeWidth = 1f
                    )
                }
                // 下端半圆
                val bottomCircleCy = tableT + tableH - cornerR
                for (i in 0 until fanLineCount) {
                    val angle = (Math.PI * (i.toDouble() / (fanLineCount - 1)))
                    drawLine(
                        color = fanColor,
                        start = Offset(cx, bottomCircleCy),
                        end = Offset(
                            (cx + cornerR * 0.9f * kotlin.math.cos(angle)).toFloat(),
                            (bottomCircleCy + cornerR * 0.9f * kotlin.math.sin(angle)).toFloat()
                        ),
                        strokeWidth = 1f
                    )
                }

                // 6. 外框描边
                drawPath(
                    path = tablePath,
                    color = Color(0xFFCEC4B0),
                    style = Stroke(width = 2.5f)
                )
            }

            // 底池显示（桌中央）
            val totalPot = state.roundContributions.values.sum() +
                    state.contributionInputs.values.sumOf { it.toIntOrNull() ?: 0 }
            if (totalPot > 0) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.blindsEnabled) {
                        Text(
                            "${state.blindsState.config.smallBlind} / ${state.blindsState.config.bigBlind}",
                            fontSize = 11.sp,
                            color = Color(0xFF888070)
                        )
                    }
                    Text(
                        "$ $totalPot",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF5D4E37)
                    )
                }
            }

            // 玩家沿桌边排列
            val playerCount = sortedPlayers.size
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val areaW = constraints.maxWidth.toFloat()
                val areaH = constraints.maxHeight.toFloat()
                val cx = areaW / 2f
                val cy = areaH / 2f
                // 与牌桌绘制保持相同的尺寸参数
                val tableH = minOf(areaH * 0.78f, areaW * 1.55f)
                val tableW = minOf(areaW * 0.64f, tableH * 0.52f)
                val R = tableW / 2f
                val straight = (tableH - tableW).coerceAtLeast(0f)
                val tcy = cy - tableH / 2f + R   // 顶部半圆圆心 Y
                val bcy = cy + tableH / 2f - R   // 底部半圆圆心 Y

                // ── 单侧路径弧长：底部右1/4圆 + 右直边 + 顶部右1/4圆 ──────
                // 同理左侧对称，总长相同 = π*R/2 + straight + π*R/2 = π*R + straight
                val quarterArc = (Math.PI * R / 2).toFloat()
                val sideLen = 2f * quarterArc + straight   // 单侧总弧长

                // 右侧路径上距离 d（从底部中心出发，顺时针）处的坐标
                fun rightSidePos(d: Float): Pair<Float, Float> {
                    return when {
                        d <= quarterArc -> {
                            // 底部右 1/4 圆：角度从 90° → 0°
                            val a = (Math.PI / 2.0 * (1.0 - d / quarterArc)).toFloat()
                            Pair(cx + R * kotlin.math.cos(a), bcy + R * kotlin.math.sin(a))
                        }
                        d <= quarterArc + straight -> {
                            // 右侧直边：从 (cx+R, bcy) 向上到 (cx+R, tcy)
                            val frac = (d - quarterArc) / straight
                            Pair(cx + R, bcy - frac * straight)
                        }
                        else -> {
                            // 顶部右 1/4 圆：角度从 0° → -90°
                            val frac = (d - quarterArc - straight) / quarterArc
                            val a = -(Math.PI / 2.0 * frac).toFloat()
                            Pair(cx + R * kotlin.math.cos(a), tcy + R * kotlin.math.sin(a))
                        }
                    }
                }

                // 左侧路径上距离 d（从顶部中心出发，顺时针）处的坐标
                // 注意：为保证关于竖轴严格镜像对称，
                // 左侧席位 k 对应 leftSidePos(sideLen - k*step)，使其与右侧席位 k 关于 cx 对称
                fun leftSidePos(d: Float): Pair<Float, Float> {
                    // d 仍从顶部顺时针度量，但调用时传入 (sideLen - k*step) 使镜像正确
                    return when {
                        d <= quarterArc -> {
                            // 顶部左 1/4 圆：角度从 -90°（顶） → -180°（左）
                            val frac = d / quarterArc
                            val a = (-Math.PI / 2.0 - Math.PI / 2.0 * frac).toFloat()
                            Pair(cx + R * kotlin.math.cos(a), tcy + R * kotlin.math.sin(a))
                        }
                        d <= quarterArc + straight -> {
                            // 左侧直边：从 (cx-R, tcy) 向下到 (cx-R, bcy)
                            val frac = (d - quarterArc) / straight
                            Pair(cx - R, tcy + frac * straight)
                        }
                        else -> {
                            // 底部左 1/4 圆：角度从 180° → 90°（底）
                            val frac = (d - quarterArc - straight) / quarterArc
                            val a = (Math.PI - Math.PI / 2.0 * frac).toFloat()
                            Pair(cx + R * kotlin.math.cos(a), bcy + R * kotlin.math.sin(a))
                        }
                    }
                }

                // ── 10 个固定席位 ─────────────────────────────────────────────
                // 席位 0  : 正下方
                // 席位 1-4: 右侧路径五等分的 4 个中间点（从底→顶，k=1..4）
                // 席位 5  : 正上方
                // 席位 6-9: 左侧路径五等分的 4 个中间点，与右侧严格镜像对称
                //           左侧席位 k（k=1..4）对应右侧席位 k 的水平镜像：
                //           rightSidePos(k*step) 的 x 关于 cx 翻转即可，无需 leftSidePos
                val step = sideLen / 5f
                val allSeats: List<Pair<Float, Float>> = buildList {
                    add(Pair(cx, bcy + R))                          // 0 正下
                    for (k in 1..4) add(rightSidePos(k * step))     // 1-4 右侧（从底→顶）
                    add(Pair(cx, tcy - R))                          // 5 正上
                    for (k in 1..4) {                               // 6-9 左侧（右侧镜像，从顶→底，即 k=4..1）
                        val (rx, ry) = rightSidePos((5 - k) * step)
                        add(Pair(2f * cx - rx, ry))
                    }
                }

                // ── 按人数对称选座映射表 ──────────────────────────────────────
                val seatMap: Map<Int, List<Int>> = mapOf(
                    1  to listOf(0),
                    2  to listOf(0, 5),
                    3  to listOf(0, 4, 6),
                    4  to listOf(0, 3, 5, 7),
                    5  to listOf(0, 3, 4, 6, 7),
                    6  to listOf(0, 3, 4, 5, 6, 7),
                    7  to listOf(0, 2, 3, 4, 6, 7, 8),
                    8  to listOf(0, 2, 3, 4, 5, 6, 7, 8),
                    9  to listOf(0, 1, 2, 3, 4, 6, 7, 8, 9),
                    10 to listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
                )
                val selectedSeats = seatMap[playerCount.coerceIn(1, 10)]
                    ?: (0 until playerCount).map { it % 10 }

                // 计算每位玩家的固定席位坐标
                sortedPlayers.forEachIndexed { index, player ->
                    val seatIndex = selectedSeats.getOrElse(index) { index % 10 }
                    val (px, py) = allSeats[seatIndex]

                    // 密度转换
                    val density = LocalDensity.current
                    val cardWidthDp = 140.dp
                    // 标签行始终占固定高度 22dp（无标签时 Spacer 占位）
                    // 胶囊高度约 52dp（avatar 46dp + 上下 padding 各 2dp + Row 本身）
                    // offsetY = py - tagRowHeight - capsuleHeight/2
                    // 使胶囊中心恒等于 py，左右完全对称
                    val tagRowHeightDp = 22.dp
                    val capsuleHeightDp = 52.dp
                    val cardWidthPx = with(density) { cardWidthDp.toPx() }
                    val tagRowHeightPx = with(density) { tagRowHeightDp.toPx() }
                    val capsuleHeightPx = with(density) { capsuleHeightDp.toPx() }

                    val offsetX = with(density) { (px - cardWidthPx / 2f).toDp() }
                    val offsetY = with(density) { (py - tagRowHeightPx - capsuleHeightPx / 2f).toDp() }

                    val seatIdx = index
                    val isCurrentTurn = player.id == state.currentTurnPlayerId && state.currentRound != BettingRound.SHOWDOWN
                    val isFolded = state.foldedPlayerIds.contains(player.id)
                    val isWinner = state.selectedWinnerIds.contains(player.id)
                    val isOffline = state.disconnectedPlayerIds.contains(player.id)
                    val roundContrib = state.roundContributions[player.id]
                    // 标识列表（中文），每个标识独立 Text chip
                    val roleTags = buildList<String> {
                        if (state.blindsEnabled && state.players.size >= 2) {
                            if (seatIdx == state.blindsState.dealerIndex) add("庄")
                            if (seatIdx == state.blindsState.smallBlindIndex) add("小盲")
                            if (seatIdx == state.blindsState.bigBlindIndex) add("大盲")
                        }
                    }
                    val roleTag = roleTags.joinToString(" ")

                    Box(
                        modifier = Modifier
                            .offset(x = offsetX, y = offsetY)
                            .width(cardWidthDp)
                            .padding(2.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 上方：角色标签（庄/盲注/状态）——始终占 22dp 高度保证胶囊居中对齐
                            // 水平对齐：padding(start=25dp) 使最左标签对齐胶囊直边左侧端点
                            // 胶囊高50dp，端半圆半径=25dp，标签从25dp处开始
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(tagRowHeightDp)
                                    .padding(start = 25.dp, bottom = 2.dp),
                            ) {
                                if (roleTag.isNotEmpty() || isFolded || isWinner || isOffline) {
                                    roleTags.forEach { tag ->
                                        Text(
                                            tag,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(
                                                    color = Color(0xFF5C6BC0),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (isWinner) {
                                        Text(
                                            "Win",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(
                                                    color = Color(0xFF388E3C),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (isFolded) {
                                        Text(
                                            "弃牌",
                                            fontSize = 9.sp,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(
                                                    color = Color(0xFF9E9E9E),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (isOffline) {
                                        Text(
                                            "掉线",
                                            fontSize = 9.sp,
                                            color = Color.White,
                                            modifier = Modifier
                                                .background(
                                                    color = Color(0xFFE53935),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                } // end if 有标签
                            } // end Row 标签行

                            // 胶囊卡片主体
                            val capsuleColor = when {
                                isFolded -> Color(0xFFBDBDBD)
                                isCurrentTurn -> Color(0xFF7E57C2)
                                player.id == state.selfId -> Color(0xFF5C6BC0)
                                else -> Color(0xFF78909C)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = capsuleColor,
                                        shape = RoundedCornerShape(50)
                                    )
                                    .padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 头像：尺寸与胶囊端半圆直径一致（capsule高 = avatar + 2*3dp padding = avatar+6，端半圆直径 = avatar+6）
                                // 设 avatar = 46dp → 胶囊高 ≈ 52dp，端半圆半径 ≈ 26dp ≈ avatar半径23dp，视觉上填满端盖
                                Box {
                                    AvatarImage(
                                        avatarBase64 = player.avatarBase64,
                                        name = player.name,
                                        size = 46
                                    )
                                    if (player.id == state.selfId) {
                                        Box(
                                            modifier = Modifier
                                                .size(11.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF43A047))
                                                .align(Alignment.BottomEnd)
                                                .border(1.dp, Color.White, CircleShape)
                                        )
                                    }
                                }
                                // 昵称 + 筹码上下排列
                                Column {
                                    Text(
                                        player.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${player.chips}",
                                        fontSize = 13.sp,
                                        color = Color(0xFFE0E0E0)
                                    )
                                }
                            }

                            // 下方：本手下注 + 行动状态
                            if ((roundContrib != null && roundContrib > 0) || isCurrentTurn) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                                ) {
                                    if (roundContrib != null && roundContrib > 0) {
                                        Text(
                                            "$ $roundContrib",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF5D4E37)
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

        // ========== 底部：双页水平滑动操作栏 ==========
        val isFolded = state.foldedPlayerIds.contains(state.selfId)
        var showFoldConfirm by remember { mutableStateOf(false) }
        var showSettleConfirm by remember { mutableStateOf(false) }
        var showChipDialog by remember { mutableStateOf(false) }
        var showCallConfirm by remember { mutableStateOf(false) }
        var pendingCallAmount by remember { mutableIntStateOf(0) }
        var showCheckConfirm by remember { mutableStateOf(false) }
        var showAllInConfirm by remember { mutableStateOf(false) }
        var pendingAllInAmount by remember { mutableIntStateOf(0) }

        // 过牌确认弹窗
        if (showCheckConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showCheckConfirm = false },
                title = { Text("确认过牌", fontWeight = FontWeight.Bold) },
                text = { Text("确认过牌吗？") },
                confirmButton = {
                    Button(
                        onClick = { showCheckConfirm = false; onSubmitContribution(0) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                    ) { Text("确认过牌") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showCheckConfirm = false }) { Text("取消") }
                }
            )
        }
        // 跟注确认弹窗
        if (showCallConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showCallConfirm = false },
                title = { Text("确认跟注", fontWeight = FontWeight.Bold) },
                text = { Text("跟注需要投入 $pendingCallAmount 筹码") },
                confirmButton = {
                    Button(
                        onClick = { showCallConfirm = false; onSubmitContribution(pendingCallAmount) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                    ) { Text("确认跟注") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showCallConfirm = false }) { Text("取消") }
                }
            )
        }
        // All-In 确认弹窗
        if (showAllInConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAllInConfirm = false },
                title = { Text("确认 All-In!", fontWeight = FontWeight.Bold) },
                text = { Text("将全押所有剩余筹码 $pendingAllInAmount，此操作不可撤销！") },
                confirmButton = {
                    Button(
                        onClick = { showAllInConfirm = false; onSubmitContribution(pendingAllInAmount) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                    ) { Text("确认 All-In!", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showAllInConfirm = false }) { Text("取消") }
                }
            )
        }
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
            // chips 在每次 processContribution 时已实时扣除，直接取当前剩余筹码即可
            val maxAvailable = myPlayer?.chips ?: 0
            val myRoundContrib = state.roundContributions[state.selfId] ?: 0
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

                            // 过牌/跟注/All-In 按钮
                            val currentMaxBet = state.roundContributions.values.maxOrNull() ?: 0
                            val myRoundContrib = state.roundContributions[state.selfId] ?: 0
                            val callNeeded = currentMaxBet - myRoundContrib
                            val myChips = sortedPlayers.firstOrNull { it.id == state.selfId }?.chips ?: 0
                            val mustAllIn = callNeeded > 0 && myChips <= callNeeded
                            if (callNeeded <= 0) {
                                // 可以过牌
                                Button(
                                    onClick = { if (canAct) showCheckConfirm = true },
                                    enabled = canAct,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF43A047),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                    )
                                ) { Text("过牌", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            } else if (mustAllIn) {
                                // 筹码不足以完整跟注，只能 All-In
                                Button(
                                    onClick = { if (canAct) { pendingAllInAmount = myChips; showAllInConfirm = true } },
                                    enabled = canAct,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFB71C1C),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                    )
                                ) { Text("All-In!", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            } else {
                                // 需要跟注
                                Button(
                                    onClick = { if (canAct) { pendingCallAmount = callNeeded; showCallConfirm = true } },
                                    enabled = canAct,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF43A047),
                                        disabledContainerColor = Color(0xFFBDBDBD)
                                    )
                                ) { Text("跟注", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
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
                            Text(
                                "〉",
                                fontSize = 18.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(end = 4.dp)
                            )
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

// ==================== 头像裁切对话框 ====================

@Composable
private fun CropImageDialog(
    uri: Uri,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val originalBitmap = remember(uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) { null }
    }

    if (originalBitmap == null) {
        onCancel()
        return
    }

    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var userScale by remember { mutableFloatStateOf(1f) }
    var containerPx by remember { mutableFloatStateOf(0f) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("裁切头像", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("拖拽调整位置，双指缩放", fontSize = 12.sp, color = Color.Gray)

                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .onGloballyPositioned { coords ->
                            containerPx = coords.size.width.toFloat()
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (containerPx <= 0f) return@detectTransformGestures
                                val imgW = originalBitmap.width.toFloat()
                                val imgH = originalBitmap.height.toFloat()
                                val baseScale = maxOf(containerPx / imgW, containerPx / imgH)
                                val cropR = containerPx * 0.43f
                                // 最小缩放：图片最小边 >= 裁切圆直径（0.86 * containerPx）
                                // 推导：min(imgW,imgH)*baseScale = containerPx，
                                //       需 containerPx*minUserScale >= cropR*2，故 minUserScale = 0.86f
                                val minUserScale = (cropR * 2f) / (minOf(imgW, imgH) * baseScale)
                                val newUserScale = (userScale * zoom).coerceIn(minUserScale, 6f)
                                val totalScale = baseScale * newUserScale
                                val scaledW = imgW * totalScale
                                val scaledH = imgH * totalScale
                                val maxPanX = ((scaledW / 2f) - cropR).coerceAtLeast(0f)
                                val maxPanY = ((scaledH / 2f) - cropR).coerceAtLeast(0f)
                                panX = (panX + pan.x).coerceIn(-maxPanX, maxPanX)
                                panY = (panY + pan.y).coerceIn(-maxPanY, maxPanY)
                                userScale = newUserScale
                            }
                        }
                ) {
                    // 使用 EvenOdd 路径绘制环形遮罩
                    // 原理：全画布矩形 + 圆形叠加，EvenOdd 规则下圆内填充被抵消
                    // 圆内图片始终可见，无需 BlendMode
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (containerPx <= 0f) return@Canvas
                        val imgW = originalBitmap.width.toFloat()
                        val imgH = originalBitmap.height.toFloat()
                        val baseScale = maxOf(size.width / imgW, size.height / imgH)
                        val totalScale = baseScale * userScale
                        val scaledW = imgW * totalScale
                        val scaledH = imgH * totalScale
                        val left = (size.width - scaledW) / 2f + panX
                        val top = (size.height - scaledH) / 2f + panY
                        // 1. 绘制图片
                        with(drawContext.canvas.nativeCanvas) {
                            drawBitmap(
                                originalBitmap,
                                null,
                                android.graphics.RectF(left, top, left + scaledW, top + scaledH),
                                null
                            )
                        }
                        // 2. 用 EvenOdd 环形路径绘制圆外暗化区域（圆内不绘制，图片透出）
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val cropR = size.width * 0.43f
                        val overlayPath = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                            addOval(androidx.compose.ui.geometry.Rect(center = center, radius = cropR))
                        }
                        drawPath(overlayPath, Color.Black.copy(alpha = 0.55f))
                        // 3. 白色圆形边框
                        drawCircle(
                            color = Color.White,
                            radius = cropR,
                            center = center,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            val cPx = if (containerPx > 0f) containerPx else 840f
                            val imgW = originalBitmap.width.toFloat()
                            val imgH = originalBitmap.height.toFloat()
                            val baseScale = maxOf(cPx / imgW, cPx / imgH)
                            val totalScale = baseScale * userScale
                            val cropR = cPx * 0.43f
                            val cx = imgW / 2f - panX / totalScale
                            val cy = imgH / 2f - panY / totalScale
                            val rImg = cropR / totalScale
                            val left = (cx - rImg).toInt().coerceAtLeast(0)
                            val top = (cy - rImg).toInt().coerceAtLeast(0)
                            val side = (rImg * 2).toInt()
                                .coerceAtMost(originalBitmap.width - left)
                                .coerceAtMost(originalBitmap.height - top)
                                .coerceAtLeast(1)
                            val cropped = Bitmap.createBitmap(originalBitmap, left, top, side, side)
                            val scaled = Bitmap.createScaledBitmap(cropped, 96, 96, true)
                            val baos = java.io.ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                            val b64 = android.util.Base64.encodeToString(
                                baos.toByteArray(), android.util.Base64.NO_WRAP
                            )
                            onConfirm(b64)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("确认裁切")
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
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 最左侧：头像
                Box {
                    AvatarImage(
                        avatarBase64 = player.avatarBase64,
                        name = player.name,
                        size = 38
                    )
                    // 本人标识小圆点
                    if (isMe) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF43A047))
                                .align(Alignment.BottomEnd)
                                .border(1.dp, Color.White, CircleShape)
                        )
                    }
                }

                // 中间：身份信息
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
                                                if (customText.length < 9) {
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
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val currentAmount = if (customMode) (customText.toIntOrNull() ?: 0) else selectedAmount
                val isValid = currentAmount > callAmount && currentAmount <= maxChips
                val validationMsg = when {
                    currentAmount <= 0           -> "请选择或输入投入金额"
                    currentAmount <= callAmount  -> "加注需超过跟注额 $callAmount"
                    currentAmount > maxChips     -> "超出可用筹码 $maxChips"
                    else                         -> ""
                }
                if (validationMsg.isNotEmpty()) {
                    Text(
                        validationMsg,
                        fontSize = 11.sp,
                        color = Color(0xFFE53935),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Button(
                    onClick = { onConfirm(currentAmount) },
                    enabled = isValid,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1976D2),
                        disabledContainerColor = Color(0xFFBDBDBD)
                    )
                ) { Text("确认加注", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消") }
            }
        },
        dismissButton = null
    )
}

// ==================== 最近记录界面 ====================

@Composable
private fun TransactionDetailDialog(
    tx: com.fushilaofang.texasholdemchipsim.model.ChipTransaction,
    playerName: String,
    onDismiss: () -> Unit
) {
    val typeLabel = when (tx.type) {
        TransactionType.BLIND_DEDUCTION -> "盲注"
        TransactionType.BET             -> "下注"
        TransactionType.CALL            -> "跟注"
        TransactionType.RAISE           -> "加注"
        TransactionType.ALL_IN          -> "全压"
        TransactionType.CHECK           -> "过牌"
        TransactionType.FOLD            -> "弃牌"
        TransactionType.WIN_PAYOUT      -> "赢彩池"
        TransactionType.CONTRIBUTION    -> "投入"
    }
    val typeColor = when (tx.type) {
        TransactionType.BLIND_DEDUCTION -> Color(0xFFF57F17)
        TransactionType.BET             -> Color(0xFFE65100)
        TransactionType.CALL            -> Color(0xFF1565C0)
        TransactionType.RAISE           -> Color(0xFF6A1B9A)
        TransactionType.ALL_IN          -> Color(0xFFB71C1C)
        TransactionType.CHECK           -> Color(0xFF9E9E9E)
        TransactionType.FOLD            -> Color(0xFF757575)
        TransactionType.WIN_PAYOUT      -> Color(0xFF2E7D32)
        TransactionType.CONTRIBUTION    -> Color(0xFF78909C)
    }
    val absAmount = kotlin.math.abs(tx.amount)
    val balanceBefore = tx.balanceAfter - tx.amount
    val narrative = when (tx.type) {
        TransactionType.BLIND_DEDUCTION ->
            "$playerName 作为盲注支付了 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，操作后剩余 ${tx.balanceAfter} 筹码"
        TransactionType.BET ->
            "$playerName 主动下注 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，操作后剩余 ${tx.balanceAfter} 筹码"
        TransactionType.CALL ->
            "$playerName 选择跟注，跟入 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，操作后剩余 ${tx.balanceAfter} 筹码"
        TransactionType.RAISE ->
            "$playerName 选择加注，本次共投入 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，操作后剩余 ${tx.balanceAfter} 筹码"
        TransactionType.ALL_IN ->
            "$playerName 全押上阵，押上全部 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，操作后剩余 ${tx.balanceAfter} 筹码（已全押）"
        TransactionType.CHECK ->
            "$playerName 选择过牌，本次未投入任何筹码\n当前持有 ${tx.balanceAfter} 筹码"
        TransactionType.FOLD ->
            "$playerName 选择弃牌，退出本轮角逐\n当前持有 ${tx.balanceAfter} 筹码"
        TransactionType.WIN_PAYOUT ->
            "$playerName ${tx.note}，共获得 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，赢彩后持有 ${tx.balanceAfter} 筹码"
        TransactionType.CONTRIBUTION ->
            "$playerName 本轮共投入 $absAmount 筹码\n操作前持有 $balanceBefore 筹码，操作后剩余 ${tx.balanceAfter} 筹码"
    }
    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(tx.timestamp))
    val showAmount = tx.type != TransactionType.CHECK && tx.type != TransactionType.FOLD
    val amountText = when {
        !showAmount    -> "—"
        tx.amount >= 0 -> "+${tx.amount}"
        else           -> "${tx.amount}"
    }
    val amountColor = when {
        !showAmount    -> Color(0xFF9E9E9E)
        tx.amount >= 0 -> Color(0xFF2E7D32)
        else           -> Color(0xFFC62828)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(typeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .border(1.dp, typeColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(typeLabel, fontSize = 13.sp, color = typeColor, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        playerName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(amountText, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = amountColor)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFE0E0E0))

                // 基础信息表格
                @Composable
                fun InfoRow(label: String, value: String, valueColor: Color = Color(0xFF212121)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(label, fontSize = 12.sp, color = Color(0xFF757575))
                        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
                    }
                }

                InfoRow("手号", tx.handId, Color(0xFF5C6BC0))
                InfoRow("时间", timeStr)
                if (showAmount) {
                    InfoRow("操作前筹码", "$balanceBefore")
                    InfoRow("变化", amountText, amountColor)
                    InfoRow("操作后筹码", "${tx.balanceAfter}", Color(0xFF1565C0))
                } else {
                    InfoRow("当前筹码", "${tx.balanceAfter}", Color(0xFF1565C0))
                }
                if (tx.note.isNotBlank()) {
                    InfoRow("操作备注", tx.note)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFE0E0E0))

                // 语言描述
                Text("筹码变化过程", fontSize = 11.sp, color = Color(0xFF9E9E9E), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    narrative,
                    fontSize = 13.sp,
                    color = Color(0xFF424242),
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun LogsScreen(state: TableUiState, onBack: () -> Unit) {
    val sortedPlayers = state.players.sortedBy { it.seatOrder }
    var selectedTx by remember { mutableStateOf<com.fushilaofang.texasholdemchipsim.model.ChipTransaction?>(null) }

    // 详情弹窗
    selectedTx?.let { tx ->
        val pName = sortedPlayers.firstOrNull { it.id == tx.playerId }?.name
            ?: tx.playerName.ifBlank { tx.playerId.take(6) }
        TransactionDetailDialog(tx = tx, playerName = pName, onDismiss = { selectedTx = null })
    }

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

        // 图例
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "盲注" to Color(0xFFF57F17),
                "下注" to Color(0xFFE65100),
                "跟注" to Color(0xFF1565C0),
                "加注" to Color(0xFF6A1B9A),
                "全压" to Color(0xFFB71C1C),
                "弃牌" to Color(0xFF757575),
                "赢"   to Color(0xFF2E7D32)
            ).forEach { (label, color) ->
                Box(
                    modifier = Modifier
                        .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(label, fontSize = 9.sp, color = color, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (state.logs.isEmpty()) {
            Text(
                "暂无记录",
                fontSize = 14.sp, color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(state.logs.takeLast(200).reversed(), key = { it.id }) { tx ->
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tx.timestamp))
                    // 优先使用当前玩家列表中的最新昵称，玩家已离开时才回退到记录里存档的名字
                    val pName = sortedPlayers.firstOrNull { it.id == tx.playerId }?.name
                        ?: tx.playerName.ifBlank { tx.playerId.take(6) }

                    // 操作类型 → 显示文字 + 主题色
                    val (typeLabel, typeColor) = when (tx.type) {
                        TransactionType.BLIND_DEDUCTION -> "盲注" to Color(0xFFF57F17)
                        TransactionType.BET             -> "下注" to Color(0xFFE65100)
                        TransactionType.CALL            -> "跟注" to Color(0xFF1565C0)
                        TransactionType.RAISE           -> "加注" to Color(0xFF6A1B9A)
                        TransactionType.ALL_IN          -> "全压" to Color(0xFFB71C1C)
                        TransactionType.CHECK           -> "过牌" to Color(0xFF9E9E9E)
                        TransactionType.FOLD            -> "弃牌" to Color(0xFF757575)
                        TransactionType.WIN_PAYOUT      -> "赢"   to Color(0xFF2E7D32)
                        TransactionType.CONTRIBUTION    -> "投入" to Color(0xFF78909C)
                    }

                    val bgColor = when (tx.type) {
                        TransactionType.WIN_PAYOUT      -> Color(0xFFE8F5E9)
                        TransactionType.BLIND_DEDUCTION -> Color(0xFFFFF8E1)
                        TransactionType.ALL_IN          -> Color(0xFFFFEBEE)
                        TransactionType.RAISE           -> Color(0xFFF3E5F5)
                        TransactionType.FOLD            -> Color(0xFFF5F5F5)
                        TransactionType.CHECK           -> Color(0xFFFAFAFA)
                        else                            -> Color(0xFFF9F9F9)
                    }

                    val showAmount = tx.type != TransactionType.CHECK && tx.type != TransactionType.FOLD
                    val amountText = when {
                        !showAmount       -> ""
                        tx.amount >= 0    -> "+${tx.amount}"
                        else              -> "${tx.amount}"
                    }
                    val amountColor = if (tx.amount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTx = tx }
                            .background(bgColor, shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 时间
                        Text(time, fontSize = 10.sp, color = Color(0xFF9E9E9E),
                            modifier = Modifier.width(56.dp))
                        // 手号
                        Text(tx.handId, fontSize = 10.sp, color = Color(0xFF5C6BC0),
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
                        // 类型徽标
                        Box(
                            modifier = Modifier
                                .background(typeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, typeColor.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                .width(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(typeLabel, fontSize = 9.sp, color = typeColor, fontWeight = FontWeight.Bold,
                                maxLines = 1)
                        }
                        Spacer(Modifier.width(4.dp))
                        // 玩家名
                        Text(pName, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(56.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        // 操作说明（note 已含圈次信息，如 [翻牌] 加注至60）
                        Text(tx.note, fontSize = 10.sp, color = Color(0xFF616161),
                            modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        // 金额
                        if (showAmount) {
                            Text(amountText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = amountColor, modifier = Modifier.width(48.dp),
                                textAlign = TextAlign.End)
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                        // 结余
                        Text("→${tx.balanceAfter}", fontSize = 10.sp, color = Color(0xFF9E9E9E),
                            modifier = Modifier.padding(start = 4.dp).width(50.dp),
                            textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}
