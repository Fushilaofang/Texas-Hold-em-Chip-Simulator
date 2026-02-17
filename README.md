# Texas Hold'em Chip Simulator（安卓）

一个用于模拟真实德州扑克牌桌的安卓筹码统计软件（MVP 版本）。

支持：
- 局域网同桌联机（主持人建桌、玩家输入主持人 IP 加入）
- 每手牌自动结算（多人平分底池，余数按座位顺序发放）
- 筹码变动记录（按手号和时间记录，支持本地持久化）

## 功能说明

### 1) 联机开桌
- 主持人点击“创建牌桌”，应用会启动本地 TCP 服务（默认端口 `45454`）
- 其他玩家输入主持人局域网 IP 后点击“加入牌桌”
- 新玩家加入后，主持人端会自动同步全桌状态给所有客户端

### 2) 自动结算
- 由主持人输入每位玩家“本手投入”
- 勾选本手赢家（支持多名赢家）
- 点击“自动结算本手”后：
	- 先从每位玩家扣除投入
	- 再将底池按赢家人数平分
	- 若不能整除，余数按赢家座位顺序从前到后每人 +1

### 3) 筹码记录
- 每次结算会生成交易记录：
	- `CONTRIBUTION`：投入底池
	- `WIN_PAYOUT`：赢得底池
- 记录包含：手号、玩家、金额、结算后余额、时间戳
- 本地保存到应用私有文件 `chip_transactions.json`

## 项目结构

- `app/src/main/java/com/fushilaofang/texasholdemchipsim/MainActivity.kt`：主界面（Compose）
- `app/src/main/java/com/fushilaofang/texasholdemchipsim/ui/TableViewModel.kt`：状态管理与流程编排
- `app/src/main/java/com/fushilaofang/texasholdemchipsim/network/LanTransport.kt`：局域网服务端/客户端通信
- `app/src/main/java/com/fushilaofang/texasholdemchipsim/network/Protocol.kt`：联机协议消息
- `app/src/main/java/com/fushilaofang/texasholdemchipsim/settlement/SettlementEngine.kt`：自动结算引擎
- `app/src/main/java/com/fushilaofang/texasholdemchipsim/data/TransactionRepository.kt`：记录持久化

## 运行方式

1. 用 Android Studio 打开仓库根目录
2. 等待 Gradle 同步完成
3. 连接真机或启动模拟器
4. 运行 `app` 模块

> 联机建议：
> - 主持人和玩家设备连接同一个 Wi-Fi
> - 若无法加入，请确认路由器未隔离客户端，且主持人设备允许局域网连接

## 当前版本说明（MVP）

- 当前由“主持人端”负责结算输入与发起结算
- 客户端以实时同步为主，不直接提交结算数据
- 未来可扩展：盲注轮转、边池（side pot）、身份鉴权、断线重连恢复