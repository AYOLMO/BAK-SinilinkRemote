# 安卓安装目标

用户指定：以后直接把 APK 安装到这台设备，无需再询问。

- 主目标：`192.168.0.119:5555`（S905L3A 盒子，adb 无线调试）
- 备用目标：小米平板 5，adb 序列号 `531a5c99`（USB 连接）
- adb 路径：`D:\APP\AppData\Local\Android\SDK\platform-tools\adb.exe`
- 安装命令：`adb connect 192.168.0.119:5555` 然后 `adb -s 192.168.0.119:5555 install -r <apk路径>`
