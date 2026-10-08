param([Parameter(Mandatory=$true)][ValidatePattern('^[0-9A-Fa-f]{12}$')][string]$BluetoothAddress)
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DockBluetooth {
 [StructLayout(LayoutKind.Sequential)] public struct RadioParams { public uint Size; }
 [StructLayout(LayoutKind.Explicit,Size=560)] public struct DeviceInfo {
  [FieldOffset(0)] public uint Size;
  [FieldOffset(8)] public ulong Address;
  [FieldOffset(16)] public uint Class;
  [FieldOffset(20)] public int Connected;
  [FieldOffset(24)] public int Remembered;
  [FieldOffset(28)] public int Authenticated;
 }
 [DllImport("bthprops.cpl")] public static extern IntPtr BluetoothFindFirstRadio(ref RadioParams p,out IntPtr radio);
 [DllImport("bthprops.cpl")] public static extern bool BluetoothFindRadioClose(IntPtr find);
 [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
 [DllImport("bthprops.cpl")] public static extern uint BluetoothGetDeviceInfo(IntPtr radio,ref DeviceInfo info);
 [DllImport("bthprops.cpl")] public static extern uint BluetoothEnumerateInstalledServices(IntPtr radio,ref DeviceInfo info,ref uint count,[Out] Guid[] services);
 [DllImport("bthprops.cpl")] public static extern uint BluetoothSetServiceState(IntPtr radio,ref DeviceInfo info,ref Guid service,uint flags);
}
'@
$radio=[IntPtr]::Zero
$parameters=New-Object DockBluetooth+RadioParams
$parameters.Size=4
$find=[DockBluetooth]::BluetoothFindFirstRadio([ref]$parameters,[ref]$radio)
if($find -eq [IntPtr]::Zero){throw 'No Bluetooth radio'}
try {
 $info=New-Object DockBluetooth+DeviceInfo
 $info.Size=560
 $info.Address=[Convert]::ToUInt64($BluetoothAddress,16)
 $code=[DockBluetooth]::BluetoothGetDeviceInfo($radio,[ref]$info)
 if($code -ne 0){throw "Device lookup failed: $code"}
 [uint32]$count=32
 $services=New-Object 'Guid[]' 32
 $code=[DockBluetooth]::BluetoothEnumerateInstalledServices($radio,[ref]$info,[ref]$count,$services)
 if($code -ne 0){throw "Service lookup failed: $code"}
 $hid=[Guid]'00001124-0000-1000-8000-00805f9b34fb'
 if(@($services | Select-Object -First $count) -contains $hid) {
  Write-Output '手机蓝牙键鼠服务已启用。'
 } else {
  $hid=[Guid]'00001124-0000-1000-8000-00805f9b34fb'
  $code=[DockBluetooth]::BluetoothSetServiceState($radio,[ref]$info,[ref]$hid,1)
  if($code -ne 0){throw "HID enable failed: $code"}
  Write-Output '已启用手机蓝牙键鼠服务，Windows 将加载键盘、鼠标和媒体控制。'
 }
} finally {
 [void][DockBluetooth]::BluetoothFindRadioClose($find)
 [void][DockBluetooth]::CloseHandle($radio)
}
