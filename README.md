# PocketDock · beta1.0

将闲置 Android 手机改造成桌面控制坞：USB 键鼠接手机，手机通过蓝牙控制电脑，同时提供时钟、输入法、音量和常用快捷按钮。运行不需要 root、Deskflow、Wi-Fi 或另一台电脑中转。

本版本的实际使用方案：**Windows 键鼠直接插电脑；Mac 办公时将键鼠接到手机扩展坞，通过 PocketDock 控制 Mac。** Windows 仍可使用手机快捷按钮。

## 下载与安装

从 [beta1.0 发布页面](https://github.com/ChiahanX/pockeydock/releases/tag/beta1.0) 下载 `PocketDock-beta1.0.apk`。安装后允许附近设备权限，开启蓝牙及手机的 OTG 功能，连接支持 USB 数据的扩展坞和键鼠，并允许应用访问 USB 设备。在电脑蓝牙设置中配对手机，在应用中选择目标电脑。长按目标按钮可以修改配对目标。

手机当前一次连接一台目标电脑。按钮作用于当前目标。切换期间按住的键或鼠标按钮需要松开重新按下。拔插键鼠、切换和断线时释放输入状态，提供“释放全部按键”按钮。

## 功能

- USB 实体键盘、鼠标和纵横滚轮转发；蓝牙切换 Windows / Mac。
- 时间、日期和连接状态显示；应用运行时保持屏幕点亮。
- 中英文输入法切换、音量、静音、播放暂停、切换应用、复制、粘贴和锁屏。
- USB 重新扫描、连接诊断、测试文字、测试鼠标、服务重启和停止转发。

快捷按钮由手机生成指令，手机没有插入实体键鼠时也可使用。Windows 的实体键鼠直接插电脑即可。**本版本仍保留 Windows 键鼠转发能力，尚未增加单独关闭该转发的设置。** 切到 Mac 后，按钮也作用于 Mac，不能同时控制两台电脑。

## Windows 输入法助手

普通音量、媒体和快捷键通过蓝牙发送。Windows 默认的“手机专用切换（绕过 PowerToys）”需要运行发布页面中的 `PocketDock-ImeBridge.exe`。助手接收手机按钮信号，切换中英文，同时保留原有 PowerToys 键盘限制。

助手无需管理员权限，无网络连接。托盘右键可退出。源码及构建脚本在 `windows/`。将 exe 放到该目录后，执行以下命令可为当前用户设置登录启动：

```powershell
& .\windows\启动输入法桥接.ps1 -AutoStart
```

取消自动启动时，删除 Windows“启动”文件夹中的“PocketDock 输入法桥接”快捷方式。Mac 默认发送 Control + 空格，也可在“输入法设置”中选择 Caps Lock。电脑上的对应快捷键需要启用。

Windows 首次配对后若无法连接，可在已配对的电脑上执行一次以下修复，地址使用手机蓝牙地址去掉冒号后的 12 位字符：

```powershell
& .\windows\Enable-PocketDock.ps1 -BluetoothAddress '你的12位蓝牙地址'
```

## 实测范围与限制

已在 iQOO 9 Pro / Android 14、Drunkdeer G65 键盘及 LAMZU Maya X 8K 接收器上测试。用户确认实体键盘基本可用、Mac 操作适合办公。Windows 蓝牙鼠标仍有明显延迟及偶发卡顿，因此本版本采用 Windows 键鼠直连的使用方案。

蓝牙输出为六个普通按键加八个修饰键。接收器标注 8K 不代表电脑端以 8K 收到输入；本版本没有验证 8K 端到端性能，不以游戏低延迟为交付标准。Mac 办公手感通过用户实际使用确认，没有测得精确端到端延迟。长时间运行、OTG 同时充电、开机前界面及唤醒尚未完整验证。其他手机和外设需要单独适配验证。

USB 读取按端点包大小完成，避免等待多份 8 字节输入凑满大缓冲区。连续鼠标移动在约 8 毫秒窗口内合并，保留按键、点击和媒体键变化。4 毫秒窗口和蓝牙 QoS 实验曾使实际手感变差，本发布版本已恢复 8 毫秒窗口和默认蓝牙 QoS。

Windows 输入法绕过路径已在 PowerToys 0.100.2 验证，使用其内部输入标记，PowerToys 后续更新可能需要重新适配。详见 [验证记录](VALIDATION.md)。

## 从源码构建

构建脚本在 Windows PowerShell 下运行，使用 JDK 17、Android API 35 和 Build Tools 35.0.0。可传入自备的工具目录，不需要安装 Gradle。目录结构如下：

```text
toolchain/
  jdk/bin/                  # java、javac、jar、keytool
  platform/android.jar     # Android SDK platforms/android-35/android.jar
  build-tools/             # SDK build-tools/35.0.0 的完整文件
  platform-tools/adb.exe   # 仅安装和设备诊断需要
```

```powershell
& .\test.ps1 -ToolchainRoot 'D:\你的路径\toolchain'
& .\build.ps1 -ToolchainRoot 'D:\你的路径\toolchain'
& .\windows\build-ime-bridge.ps1
```

APK 输出为 `PocketDock-dev.apk`，Windows 助手输出为 `windows/PocketDock-ImeBridge.exe`。Android 构建会在工具目录的 `keys/` 中生成本地开发签名。私钥不在仓库中；自行构建的 APK 签名与发布包不同，不能直接覆盖安装发布包，需卸载后安装，会丢失应用设置。

测试覆盖真实外设描述、随机键鼠编解码、按键释放、切换抑制、多个输入源、鼠标移动合并和输入法快捷键，共 5,054 项检查。测试结果不代替真机延迟和长时间使用验证。

## 许可证

[MIT](LICENSE)
