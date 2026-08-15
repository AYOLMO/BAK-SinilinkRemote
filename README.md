# 欣易连功放遥控（Sinilink Remote）

一个用于控制欣易连（Sinilink）/ DAJUNGUO 等品牌蓝牙功放的第三方 Android 应用：通过蓝牙（BLE）直接切换功放输入音源（内部音频 / 蓝牙模式），完全不需要官方 app。

## 功能

- 打开应用自动扫描并连接功放（识别 `DJG-APP` / `Sinilink-APP` 等名称）
- 同时发现多个功放时，弹出列表让用户选择
- 控制页两个按钮一键切换：**内部音频**、**蓝牙模式**
- 简洁黑色界面、天空蓝按钮、蓝牙图标
- 自动重试扫描；蓝牙未开启时提示开启；连接失败自动重连

## 支持的设备

本项目基于社区逆向的欣易连 BLE 协议开发，已在以下设备实测通过：

| 设备 | BLE 名称 | 固件 | 说明 |
| --- | --- | --- | --- |
| DAJUNGUO S220HS 功放 | `DJG-APP` | V122 | 实测可正常切换音源 |

协议与 Gadgetbridge 已支持的欣易连功放（如 XY-AP50L）同源，其他欣易连/DAJUNGUO 型号大概率可直接使用。

## BLE 协议摘要

```
Service:    0000ae00-0000-1000-8000-00805f9b34fb
TX(写入):   0000ae10-0000-1000-8000-00805f9b34fb
RX(通知):   0000ae04-0000-1000-8000-00805f9b34fb
            0000ae02 / 0000ae05 (部分固件)
```

帧格式：

```
0x7E | 总长度 | 载荷 | 校验和高字节 | 校验和低字节
校验和 = 校验前所有字节求和，对 65536 取模（大端序）
```

主要命令：

| 功能 | 命令码 |
| --- | --- |
| 内部音频 / AUX | `0x16` |
| 蓝牙模式 | `0x14` |
| TF 卡 / USB / 声卡 | `0x03` / `0x04` / `0x15` |
| 播放/暂停、上一曲、下一曲 | `0x01` / `0x07` / `0x08` |
| 音量（0-30） | `0x1D` |
| 查询版本 / 状态 / 名称 | `0x1E` / `0x1F` / `0x20` |

完整的命令与解析实现见 [Gadgetbridge 的 Sinilink 支持](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sinilink/SinilinkSupport.kt)。

## 工程结构

```
SinilinkRemote/         Android 应用工程（Kotlin）
sinilink_scan.py        电脑端 BLE 扫描诊断工具（需 bleak）
sinilink_ctrl.py        电脑端协议探测 / 控制工具（需 bleak）
```

## 构建

环境要求：Android SDK 36、JDK 17+、Gradle 9.1（AGP 9.0.1）。

```bash
cd SinilinkRemote
gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`，可直接安装到 Android 设备。

## 已知限制

- 部分安卓电视盒子的蓝牙栈收不到功放的状态通知，但**写入命令正常**，应用不依赖通知即可完成控制。
- 功放 BLE 控制连接同一时间通常只允许一个客户端，请确保手机/电脑未占用连接。
- 协议无加密，蓝牙范围内的其他设备理论上也能发送控制指令。

## 致谢

协议逆向参考了开源项目 [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) 对欣易连功放的支持（[issue #6040](https://codeberg.org/Freeyourgadget/Gadgetbridge/issues/6040)），感谢维护者与社区的工作。
