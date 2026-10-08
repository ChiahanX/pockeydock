package com.pocketdock;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import com.pocketdock.core.HidParser;
import com.pocketdock.core.HidReports;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit USB permission, per-interface ownership, report-mode capture. No root. */
final class UsbInput {
    interface Listener {
        void frame(String source, HidParser.Frame frame, AtomicBoolean sessionAlive);
        void detached(String prefix);
        void status(String text);
    }
    private final Context context;
    private final UsbManager manager;
    private final Listener listener;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private boolean started;
    private static final String PERMISSION = "com.pocketdock.USB_PERMISSION";
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if(device==null)return;
            if(PERMISSION.equals(intent.getAction())) {
                pending.remove(device.getDeviceName());
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) open(device);
                else listener.status("USB 访问未获授权，可点击重新扫描");
            } else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) request(device);
            else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) closeDevice(device);
        }
    };
    UsbInput(Context context, Listener listener) {
        this.context=context;this.listener=listener;manager=(UsbManager)context.getSystemService(Context.USB_SERVICE);
    }
    void start() {
        if(started)return;started=true;
        IntentFilter filter=new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver,filter);
        scan();
    }
    void scan() {
        if(manager==null)return;
        int count=0;
        for(UsbDevice device:manager.getDeviceList().values()) {
            if(isHid(device)){count++;request(device);}
        }
        if(count==0)listener.status("未检测到 USB 键鼠，等待 OTG 接入");
    }
    private boolean isHid(UsbDevice device) {
        for(int i=0;i<device.getInterfaceCount();i++)if(device.getInterface(i).getInterfaceClass()==3)return true;
        return false;
    }
    private void request(UsbDevice device) {
        if(!isHid(device))return;
        if(manager.hasPermission(device)){open(device);return;}
        if(!pending.add(device.getDeviceName()))return;
        int flags=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
        PendingIntent permission=PendingIntent.getBroadcast(context,device.getDeviceId(),new Intent(PERMISSION).setPackage(context.getPackageName()),flags);
        manager.requestPermission(device,permission);
    }
    private void open(UsbDevice device) {
        android.util.Log.i("PocketDock","USB device "+device.getDeviceName()+" vid="+device.getVendorId()+" pid="+device.getProductId()+" name="+device.getProductName());
        for(int i=0;i<device.getInterfaceCount();i++) {
            UsbInterface intf=device.getInterface(i);
            if(intf.getInterfaceClass()!=3)continue;
            String source=device.getDeviceName()+"/if"+intf.getId()+"/";
            if(sessions.containsKey(source)) {
                if(sessions.get(source).alive.get())continue;
                sessions.remove(source);
            }
            UsbEndpoint input=null;
            for(int e=0;e<intf.getEndpointCount();e++) {
                UsbEndpoint endpoint=intf.getEndpoint(e);
                if(endpoint.getDirection()==UsbConstants.USB_DIR_IN && endpoint.getType()==UsbConstants.USB_ENDPOINT_XFER_INT){input=endpoint;break;}
            }
            if(input==null)continue;
            android.util.Log.i("PocketDock","USB interface "+source+" subclass="+intf.getInterfaceSubclass()+" protocol="+intf.getInterfaceProtocol()+" packet="+input.getMaxPacketSize()+" interval="+input.getInterval());
            UsbDeviceConnection connection=manager.openDevice(device);
            if(connection==null){listener.status("无法打开 USB 接口");continue;}
            if(!connection.claimInterface(intf,true)){connection.close();listener.status("USB 接口占用失败，等待真机诊断");continue;}
            try {
                byte[] raw=new byte[4096];
                int length=connection.controlTransfer(0x81,6,0x2200,intf.getId(),raw,raw.length,1000);
                android.util.Log.i("PocketDock","USB descriptor "+source+" length="+length+" data="+hex(raw,Math.max(0,length)));
                HidParser parser;
                if(length>0) {
                    parser=new HidParser(Arrays.copyOf(raw,length));
                    // Boot devices also support report mode; request report mode explicitly.
                    if(intf.getInterfaceSubclass()==1 && connection.controlTransfer(0x21,11,1,intf.getId(),null,0,1000)<0)
                        throw new IllegalStateException("USB report protocol request failed");
                } else if(intf.getInterfaceSubclass()==1 && (intf.getInterfaceProtocol()==1||intf.getInterfaceProtocol()==2)) {
                    if(connection.controlTransfer(0x21,11,0,intf.getId(),null,0,1000)<0)
                        throw new IllegalStateException("USB boot protocol request failed");
                    parser=new HidParser(intf.getInterfaceProtocol()==1 ? HidReports.hex(
                        "05 01 09 06 A1 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 75 08 95 01 81 01 19 00 29 FF 26 FF 00 75 08 95 06 81 00 C0") : HidReports.hex(
                        "05 01 09 02 A1 01 09 01 A1 00 05 09 19 01 29 03 15 00 25 01 75 01 95 03 81 02 75 05 95 01 81 01 05 01 09 30 09 31 15 81 25 7F 75 08 95 02 81 06 C0 C0"));
                } else throw new IllegalStateException("No readable HID report descriptor");
                if(!parser.supported()){
                    android.util.Log.i("PocketDock","USB skipped configuration-only interface "+source);
                    connection.releaseInterface(intf);connection.close();continue;
                }
                Session session=new Session(source,connection,intf,input,parser);
                sessions.put(source,session);session.start();
                listener.status("USB 已捕获 "+sessions.size()+" 个输入接口");
            } catch(Exception ex) {
                connection.releaseInterface(intf);connection.close();
                listener.status("USB 接口未启用："+ex.getMessage());
            }
        }
    }
    private static String hex(byte[] data,int length){StringBuilder out=new StringBuilder();for(int i=0;i<length;i++)out.append(String.format(Locale.ROOT,"%02x",data[i]&255));return out.toString();}
    private void closeDevice(UsbDevice device) {
        String prefix=device.getDeviceName()+"/";
        List<String> names=new ArrayList<>(sessions.keySet());
        for(String name:names)if(name.startsWith(prefix)){sessions.remove(name).close();}
        pending.remove(device.getDeviceName());listener.detached(prefix);
        listener.status("USB 设备已拔出，剩余接口 "+sessions.size());
    }
    void stop() {
        if(started){context.unregisterReceiver(receiver);started=false;}
        for(Session session:sessions.values())session.close();sessions.clear();pending.clear();
        listener.detached("/");
    }
    private final class Session extends Thread {
        final String source;final UsbDeviceConnection connection;final UsbInterface intf;
        final UsbEndpoint endpoint;final HidParser parser;
        final AtomicBoolean alive=new AtomicBoolean(true);
        Session(String source,UsbDeviceConnection connection,UsbInterface intf,UsbEndpoint endpoint,HidParser parser) {
            super("PocketDock-USB");this.source=source;this.connection=connection;this.intf=intf;this.endpoint=endpoint;this.parser=parser;
            setDaemon(true);
        }
        @Override public void run() {
            Map<UsbRequest,ByteBuffer> requests=new IdentityHashMap<>();
            try {
                // A full 8-byte interrupt packet must finish immediately. A 64-byte
                // buffer on an 8-byte endpoint waits for eight keyboard/mouse packets.
                // Keep one complete report ready for the next read.
                for(int i=0;i<1;i++){
                    UsbRequest request=new UsbRequest();
                    if(!request.initialize(connection,endpoint)){request.close();throw new IllegalStateException("USB request initialization failed");}
                    ByteBuffer buffer=ByteBuffer.allocateDirect(endpoint.getMaxPacketSize());requests.put(request,buffer);
                    if(!request.queue(buffer))throw new IllegalStateException("USB request queue failed");
                }
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
                long received=0,decoded=0,lastStats=android.os.SystemClock.uptimeMillis();
                while(alive.get()) {
                    UsbRequest completed=null;
                    while(alive.get()&&completed==null) {
                        try{completed=connection.requestWait(1000);}catch(TimeoutException timeout){continue;}
                        if(completed==null)throw new IllegalStateException("USB transfer stopped");
                    }
                    if(!alive.get())break;
                    ByteBuffer buffer=requests.get(completed);
                    if(buffer==null)throw new IllegalStateException("Unexpected USB request");
                    int count=buffer.position();buffer.flip();byte[] data=new byte[count];buffer.get(data);
                    buffer.clear();
                    if(!completed.queue(buffer))throw new IllegalStateException("USB request requeue failed");
                    HidParser.Frame frame=parser.decode(data);
                    received++;if(frame!=null)decoded++;
                    if(received<=3)android.util.Log.i("PocketDock","USB sample "+source+" bytes="+hex(data,data.length)+" decoded="+(frame!=null));
                    long now=android.os.SystemClock.uptimeMillis();
                    if(now-lastStats>=5000){android.util.Log.i("PocketDock","USB stats "+source+" packets="+received+" decoded="+decoded+" windowMs="+(now-lastStats));received=0;decoded=0;lastStats=now;}
                    if(frame!=null)listener.frame(source+"r"+frame.reportId,frame,alive);
                }
            } catch(Exception ex) {
                if(alive.get())listener.status("USB 读取停止："+ex.getMessage());
            } finally {
                alive.set(false);for(UsbRequest request:requests.keySet()){request.cancel();request.close();}
                connection.releaseInterface(intf);connection.close();listener.detached(source);
            }
        }
        void close(){alive.set(false);connection.close();interrupt();}
    }
}
