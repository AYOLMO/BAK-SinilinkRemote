#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
欣易连 (Sinilink / Xinyi) 功放 BLE 诊断工具

用法:
    python sinilink_scan.py               # 扫描附近所有 BLE 设备(名称+服务UUID)
    python sinilink_scan.py AA:BB:CC:..   # 连接指定设备, 列出全部 GATT 服务/特征

需要先安装依赖:  pip install bleak
Windows 需系统蓝牙适配器可用(蓝牙 4.0+), 运行时会弹蓝牙权限提示。
"""

import asyncio
import sys

from bleak import BleakClient, BleakScanner

# Gadgetbridge 已逆向的欣易连控制服务/特征
SINILINK_SERVICE = "0000ae00-0000-1000-8000-00805f9b34fb"


async def scan():
    print("正在扫描附近的 BLE 设备(15 秒)...")
    devices = await BleakScanner.discover(timeout=15, return_adv=True)

    rows = []
    for addr, (device, adv) in devices.items():
        uuids = list(adv.service_uuids or [])
        name = adv.local_name or device.name or "(无名称)"
        rows.append((name, device.address or addr, adv.rssi, uuids))

    rows.sort(key=lambda r: -r[2])
    print(f"共发现 {len(rows)} 个设备:\n")
    for name, addr, rssi, uuids in rows:
        by_service = any(u.lower() == SINILINK_SERVICE for u in uuids)
        by_name = name and any(k in name.lower() for k in ("sinilink", "xinyi", "xy-"))
        if by_service:
            hint = "   <== 疑似欣易连(服务 UUID 匹配!)"
        elif by_name:
            hint = "   <== 疑似欣易连(名称匹配)"
        else:
            hint = ""
        print(f"  {rssi:>5} dBm  {name:<28} {addr}{hint}")
        if uuids:
            print(f"         广播的服务: {', '.join(uuids)}")

    print("\n提示: 欣易连功放通常会同时广播两个蓝牙条目:")
    print("  1) 音频连接(如 XinYi / XY-S220H / XINYI Sini Audio), 用于放歌")
    print("  2) BLE 控制连接, 若含 0000ae00-... 服务则与已知协议同族")
    print("把上面两行信息(名称+服务UUID)发给我, 即可判断协议是否一致。")


async def inspect(mac: str):
    print(f"正在连接 {mac} ...")
    async with BleakClient(mac, timeout=15) as client:
        if not client.is_connected:
            print("连接失败(设备可能已断开、太远或被占用)")
            return
        print(f"已连接: {client.is_connected}")
        services = client.services
        print(f"共 {len(services.services)} 个服务:\n")
        for service in services:
            print(f"服务 {service.uuid}  ({service.description})")
            for char in service.characteristics:
                props = ",".join(char.properties)
                print(f"    - 特征 {char.uuid}  [{props}]")
        has = any(s.uuid.lower() == SINILINK_SERVICE for s in services)
        print(f"\n>>> 是否包含欣易连控制服务 0xAE00: {'是' if has else '否'}")


async def main():
    if len(sys.argv) > 1:
        await inspect(sys.argv[1])
    else:
        await scan()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    except ImportError:
        print("缺少 bleak 库, 请先运行:  pip install bleak")
    except Exception as exc:
        print(f"出错了: {exc}")
