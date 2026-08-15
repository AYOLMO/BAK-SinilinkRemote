#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
欣易连 / Sinilink 功放 BLE 控制工具

用法:
    python sinilink_ctrl.py <MAC> info                  # 读取版本/状态/名称(先做这个)
    python sinilink_ctrl.py <MAC> probe                 # 自动探测有效写入通道(无回复时用)
    python sinilink_ctrl.py <MAC> version
    python sinilink_ctrl.py <MAC> status
    python sinilink_ctrl.py <MAC> name
    python sinilink_ctrl.py <MAC> vol 15                # 音量 0-30
    python sinilink_ctrl.py <MAC> play                  # 播放/暂停(切换)
    python sinilink_ctrl.py <MAC> prev | next
    python sinilink_ctrl.py <MAC> eq rock               # normal/rock/pop/classic/jazz/country
    python sinilink_ctrl.py <MAC> source bt             # tf/usb/bt/audiocard/aux
    python sinilink_ctrl.py <MAC> mode random           # list_cycle/single/single_cycle/random/order
    python sinilink_ctrl.py <MAC> tone                  # 切换提示音开关
    python sinilink_ctrl.py <MAC> listen                # 监听设备主动上报的事件(15秒)
    python sinilink_ctrl.py <MAC> name2 ABCDEFGHIJ      # 改设备名(最多10字符, 会改蓝牙名)
    python sinilink_ctrl.py <MAC> passwd 1234           # 设4位数字密码(下次连接需输密码!)

依赖:  pip install bleak
"""

import argparse
import asyncio
import sys

from bleak import BleakClient

CHAR_RX = "0000ae04-0000-1000-8000-00805f9b34fb"  # 通知(设备 -> 手机)
CHAR_TX = "0000ae10-0000-1000-8000-00805f9b34fb"  # 写入(手机 -> 设备)

# 命令码(与 Gadgetbridge 逆向结果一致)
CMD_PLAY_PAUSE = 0x01
CMD_PREVIOUS = 0x07
CMD_NEXT = 0x08
CMD_EQ = {"normal": 0x09, "rock": 0x0A, "pop": 0x0B,
          "classic": 0x0C, "jazz": 0x0D, "country": 0x0E}
CMD_MODE = {"list_cycle": 0x0F, "single": 0x10, "single_cycle": 0x11,
            "random": 0x12, "order": 0x13}
CMD_SOURCE = {"tf": 0x03, "usb": 0x04, "bt": 0x14,
              "audiocard": 0x15, "aux": 0x16}
CMD_PROMPT_TOGGLE = 0x18
CMD_PASSWORD_TOGGLE = 0x19
CMD_NAME_SET = 0x1A
CMD_PASSWORD_SET = 0x1B
CMD_SET_VOLUME = 0x1D
CMD_VERSION = 0x1E
CMD_STATUS_GET = 0x1F
CMD_NAME_GET = 0x20
CMD_EVENT_1 = 0xA8
CMD_EVENT_2 = 0xC3
CMD_INIT_HANDSHAKE = 0xAA  # 部分固件连接后需要先握手 [0xAA, 0x01, 0xBB]


def encode_frame(payload: bytes) -> bytes:
    """帧格式: [0x7E][总长度][载荷][校验和高][校验和低], 校验和=前面所有字节求和 mod 65536"""
    total_len = 2 + len(payload) + 2
    frame = bytearray(total_len)
    frame[0] = 0x7E
    frame[1] = total_len
    frame[2:2 + len(payload)] = payload
    checksum = sum(frame[:total_len - 2]) % 65536
    frame[total_len - 2] = (checksum >> 8) & 0xFF
    frame[total_len - 1] = checksum & 0xFF
    return bytes(frame)


def decode_ascii(data: bytes) -> str:
    return data.decode("ascii", errors="replace")


def handle_frame(frame: bytes) -> bool:
    """解析并打印一帧设备返回的数据"""
    if len(frame) < 5:
        print(f"  [帧太短] {frame.hex()}")
        return False
    if frame[0] != 0x7E:
        return False
    if frame[1] != len(frame):
        print(f"  [长度不符] 声明{frame[1]} 实际{len(frame)}")
        return False
    calc = sum(frame[:-2]) % 65536
    recv = (frame[-2] << 8) | frame[-1]
    if calc != recv:
        print(f"  [校验和错误] 计算0x{calc:04X} 收到0x{recv:04X}")
        return False

    payload = frame[2:-2]
    cmd = payload[0]
    if cmd == CMD_STATUS_GET and len(payload) == 12:
        volume = payload[3]
        tone = bool(payload[1])
        pw_enabled = bool(payload[2])
        pw = decode_ascii(payload[4:8])
        pw_txt = f"(内容:{pw})" if pw_enabled else ""
        print(f"  [状态] 音量={volume}/30  提示音={'开' if tone else '关'}"
              f"  密码={'启用' + pw_txt if pw_enabled else '关闭'}")
    elif cmd == CMD_VERSION and len(payload) == 5:
        print(f"  [版本] {decode_ascii(payload[1:5])}")
    elif cmd == CMD_NAME_GET and len(payload) >= 11:
        name = decode_ascii(payload[1:11]).rstrip("\x00")
        print(f"  [名称] {name}")
    elif cmd in (CMD_EVENT_1, CMD_EVENT_2) and len(payload) == 11:
        src_map = {0x03: "TF", 0x04: "USB", 0x14: "蓝牙", 0x15: "声卡", 0x16: "AUX", 0x17: "声卡"}
        state_map = {0x01: "停止", 0x02: "播放中"}
        mode_map = {0x0F: "列表循环", 0x10: "单曲", 0x11: "单曲循环",
                    0x12: "随机", 0x13: "顺序"}
        eq_map = {0x09: "普通", 0x0A: "摇滚", 0x0B: "流行",
                  0x0C: "古典", 0x0D: "爵士", 0x0E: "乡村"}
        src = src_map.get(payload[2], f"0x{payload[2]:02X}")
        state = state_map.get(payload[3], f"0x{payload[3]:02X}")
        mode = mode_map.get(payload[5], f"0x{payload[5]:02X}")
        eq = eq_map.get(payload[6], f"0x{payload[6]:02X}")
        ver = decode_ascii(payload[7:11])
        print(f"  [事件] 音源={src}  播放={state}  模式={mode}  音效={eq}  版本={ver}")
    else:
        print(f"  [未知/其他命令 0x{cmd:02X}] 载荷={payload.hex()}")
    return True


class SinilinkController:
    def __init__(self, mac: str):
        self.mac = mac
        self.client = None

    async def connect(self) -> None:
        self.client = BleakClient(self.mac, timeout=15)
        await self.client.connect()
        if not self.client.is_connected:
            raise RuntimeError("连接失败")
        print(f"已连接 {self.mac}")
        # 订阅所有通知/指示特征, 以防设备把回复发在 ae02/ae05 上
        subscribed = []
        for uuid in (CHAR_RX, "0000ae02-0000-1000-8000-00805f9b34fb",
                     "0000ae05-0000-1000-8000-00805f9b34fb"):
            try:
                await self.client.start_notify(uuid, self._on_data)
                subscribed.append(uuid[-8:-4].upper())
            except Exception as exc:
                print(f"  通知订阅失败 {uuid[-8:-4].upper()}: {exc}")
        if subscribed:
            print(f"已订阅通知: {', '.join(subscribed)}")
        else:
            print("警告: 所有通知订阅都失败了, 设备可能要求先在 Windows 蓝牙设置中配对")

    def _on_data(self, _char, data: bytearray) -> None:
        if not handle_frame(bytes(data)):
            print(f"  收到原始数据: {bytes(data).hex()}")

    async def handshake(self) -> None:
        payload = bytes([CMD_INIT_HANDSHAKE, 0x01, 0xBB])
        print(f"握手: {encode_frame(payload).hex()}")
        try:
            await self.client.write_gatt_char(CHAR_TX, encode_frame(payload))
        except Exception as exc:
            print(f"  握手写入失败: {exc}")
        await asyncio.sleep(1.0)

    async def send(self, payload: bytes, wait: float = 2.0) -> None:
        frame = encode_frame(payload)
        print(f"发送: {frame.hex()}  (命令 0x{payload[0]:02X})")
        await self.client.write_gatt_char(CHAR_TX, frame)
        await asyncio.sleep(wait)

    async def close(self) -> None:
        if self.client:
            await self.client.disconnect()


async def run(args: argparse.Namespace) -> int:
    ctrl = SinilinkController(args.mac)
    try:
        await ctrl.connect()
        verb = args.verb

        if verb == "info":
            await ctrl.handshake()
            await ctrl.send(bytes([CMD_VERSION]))
            await ctrl.send(bytes([CMD_STATUS_GET]))
            await ctrl.send(bytes([CMD_NAME_GET]), wait=1.5)
            try:
                data = await ctrl.client.read_gatt_char(CHAR_TX)
                if data:
                    handle_frame(bytes(data))
                else:
                    print("读取TX: 空")
            except Exception as exc:
                print(f"读取TX失败: {exc}")
        elif verb == "probe":
            await ctrl.handshake()
            targets = [
                ("ae01", "0000ae01-0000-1000-8000-00805f9b34fb", False),
                ("ae03", "0000ae03-0000-1000-8000-00805f9b34fb", False),
                ("ae10", CHAR_TX, True),
            ]
            for label, uuid, with_response in targets:
                for payload in (bytes([CMD_VERSION]), bytes([CMD_STATUS_GET])):
                    frame = encode_frame(payload)
                    print(f"[探测] 写 {label} (命令0x{payload[0]:02X}): {frame.hex()}")
                    try:
                        await ctrl.client.write_gatt_char(uuid, frame, response=with_response)
                    except Exception as exc:
                        print(f"  写入失败: {exc}")
                    await asyncio.sleep(1.2)
            print("探测结束, 上面任何一条'收到原始数据'或解析结果都说明该通道有效")
        elif verb == "version":
            await ctrl.send(bytes([CMD_VERSION]))
        elif verb == "status":
            await ctrl.send(bytes([CMD_STATUS_GET]))
        elif verb == "name":
            await ctrl.send(bytes([CMD_NAME_GET]))
        elif verb == "vol":
            v = args.value
            if not 0 <= v <= 30:
                print("音量范围 0-30")
                return 1
            payload = bytearray(11)
            payload[0] = CMD_SET_VOLUME
            payload[1] = v
            await ctrl.send(bytes(payload))
        elif verb in ("play", "prev", "next"):
            code = {"play": CMD_PLAY_PAUSE, "prev": CMD_PREVIOUS,
                    "next": CMD_NEXT}[verb]
            await ctrl.send(bytes([code]))
        elif verb == "eq":
            await ctrl.send(bytes([CMD_EQ[args.value]]))
        elif verb == "source":
            await ctrl.send(bytes([CMD_SOURCE[args.value]]))
        elif verb == "mode":
            await ctrl.send(bytes([CMD_MODE[args.value]]))
        elif verb == "tone":
            await ctrl.send(bytes([CMD_PROMPT_TOGGLE]))
        elif verb == "listen":
            print("监听中, 15 秒后退出...")
            await asyncio.sleep(15)
        elif verb == "name2":
            name = args.value
            if len(name) > 10 or any(ord(c) < 0x20 or ord(c) > 0x7E for c in name):
                print("设备名必须是 1-10 个可打印 ASCII 字符")
                return 1
            payload = bytearray(11)
            payload[0] = CMD_NAME_SET
            payload[1:1 + len(name)] = name.encode("ascii")
            await ctrl.send(bytes(payload), wait=2.5)
            print("改名命令已发送, 设备蓝牙名可能已变化")
        elif verb == "passwd":
            pw = args.value
            if len(pw) != 4 or not pw.isdigit():
                print("密码必须是 4 位数字")
                return 1
            payload = bytearray(11)
            payload[0] = CMD_PASSWORD_SET
            payload[1:5] = pw.encode("ascii")
            await ctrl.send(bytes(payload), wait=2.5)
            print("密码已设置(尚未启用), 如需启用请再发: tone 旁边的密码开关暂未提供")
        else:
            print(f"未知命令: {verb}")
            return 1
        return 0
    finally:
        await ctrl.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="欣易连/Sinilink 功放 BLE 控制工具")
    parser.add_argument("mac", help="设备 MAC 地址(如 C8:16:BB:98:4E:61)")
    parser.add_argument("verb", help="命令: info/version/status/name/vol/play/prev/next/eq/source/mode/tone/listen/name2/passwd")
    parser.add_argument("value", nargs="?", help="参数(音量值/音效名/音源名/模式名/名称/密码)")
    args = parser.parse_args()

    if args.verb in ("vol", "eq", "source", "mode", "name2", "passwd") and args.value is None:
        print(f"命令 {args.verb} 需要一个参数")
        return 1
    if args.verb == "vol":
        try:
            args.value = int(args.value)
        except ValueError:
            print("音量必须是数字")
            return 1

    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0
    except ImportError:
        print("缺少 bleak 库, 请先运行:  pip install bleak")
        return 1
    except Exception as exc:
        print(f"出错了: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
