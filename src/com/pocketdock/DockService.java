package com.pocketdock;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import com.pocketdock.core.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DockService extends Service {
    public interface Listener { void changed(); }
    public final class LocalBinder extends Binder { public DockService service(){return DockService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private HandlerThread thread;
    private Handler worker,main;
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice current,desired;
    private UsbInput usb;
    private InputRouter router;
    private final InputBatch inputBatch=new InputBatch();
    private final java.util.concurrent.atomic.AtomicLong usbFrames=new java.util.concurrent.atomic.AtomicLong();
    private long mouseReports,keyReports,maxSendMicros,statsAt;
    private final Runnable inputStats=new Runnable(){public void run(){
        long now=SystemClock.uptimeMillis();
        log("输入性能 windowMs="+(now-statsAt)+" usb="+usbFrames.getAndSet(0)+" mouseSent="+mouseReports+" keySent="+keyReports+" maxSendUs="+maxSendMicros);
        statsAt=now;mouseReports=0;keyReports=0;maxSendMicros=0;
        if(!destroyed)worker.postDelayed(this,5000);
    }};
    private final Runnable drainInput=()->{
        for(InputBatch.Entry entry:inputBatch.drain()){
            if(!entry.alive.get()||DockService.this.destroyed)continue;
            HidParser.Frame frame=entry.frame;String source=entry.source;
            if(frame.keys!=null)router.keyboard(source,frame.keys);
            if(frame.consumer!=null)router.consumer(source,frame.consumer);
            if(frame.buttons!=null||frame.x!=0||frame.y!=0||frame.wheel!=0||frame.pan!=0)
                router.mouse(source,frame.buttons,frame.x,frame.y,frame.wheel,frame.pan);
        }
    };
    private volatile Listener listener;
    private volatile String bluetoothStatus="正在检查蓝牙", usbStatus="等待 USB 键鼠", role="Windows";
    private volatile String currentAddress="";
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    private boolean registered,destroyed,switching;
    private long generation;
    private long traceUntil;
    private long languageBusyUntil;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();main=new Handler(Looper.getMainLooper());
        thread=new HandlerThread("PocketDock-Router",android.os.Process.THREAD_PRIORITY_DISPLAY);thread.start();worker=new Handler(thread.getLooper());
        statsAt=SystemClock.uptimeMillis();worker.postDelayed(inputStats,5000);
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("dock","桌面坞连接",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1,new Notification.Builder(this,"dock").setContentTitle("PocketDock 桌面坞运行中")
            .setContentText("点击查看连接与键鼠状态").setSmallIcon(android.R.drawable.ic_menu_manage).setContentIntent(open).setOngoing(true).build());
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"PocketDock:Input");
        wakeLock.acquire();
        router=new InputRouter((id,data)->send(id,data));
        usb=new UsbInput(this,new UsbInput.Listener(){
            public void frame(String source,HidParser.Frame frame,AtomicBoolean alive){
                usbFrames.incrementAndGet();
                if(inputBatch.offer(source,frame,alive))worker.postDelayed(drainInput,8);
            }
            public void detached(String prefix){worker.post(()->router.removePrefix(prefix));}
            public void status(String text){usbStatus=text;log(text);changed();}
        });
        usb.start();
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){status("手机未提供蓝牙适配器");return;}
        if(!adapter.isEnabled()){status("请打开手机蓝牙，再启动服务");return;}
        try {
            boolean requested=adapter.getProfileProxy(this,new BluetoothProfile.ServiceListener(){
                public void onServiceConnected(int profile,BluetoothProfile proxy){worker.post(()->{
                    if(destroyed){adapter.closeProfileProxy(profile,proxy);return;}
                    hid=(BluetoothHidDevice)proxy;
                    BluetoothHidDeviceAppSdpSettings settings=new BluetoothHidDeviceAppSdpSettings(
                        "PocketDock","Wired keyboard and mouse bridge","PocketDock",BluetoothHidDevice.SUBCLASS1_COMBO,HidReports.DESCRIPTOR);
                    if(!hid.registerApp(settings,null,null,command->worker.post(command),callbacks))status("蓝牙键鼠注册请求被拒绝");
                });}
                public void onServiceDisconnected(int profile){worker.post(()->{
                    router.deactivate();hid=null;current=null;currentAddress="";registered=false;status("蓝牙键鼠服务已断开，请重新启动服务");
                });}
            },BluetoothProfile.HID_DEVICE);
            if(!requested)status("此系统未开放蓝牙键鼠功能");
            else worker.postDelayed(()->{if(hid==null&&!destroyed)status("未获得蓝牙键鼠服务，等待真机诊断");},5000);
        }catch(Exception ex){status("蓝牙初始化失败："+ex.getMessage());}
    }
    private final BluetoothHidDevice.Callback callbacks=new BluetoothHidDevice.Callback(){
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice,boolean enabled){
            registered=enabled;
            if(!enabled){router.deactivate();current=null;currentAddress="";status("蓝牙键鼠注册失效，请重新启动服务");return;}
            status("蓝牙键鼠可用，选择一台已配对电脑");
            String address=getSharedPreferences("dock",0).getString("lastHost","");
            if(desired==null&&!address.isEmpty()) {
                try{desired=adapter.getRemoteDevice(address);role=getSharedPreferences("dock",0).getString("lastRole","Windows");}catch(Exception ignored){}
            }
            if(pluggedDevice!=null && desired!=null && pluggedDevice.equals(desired)) {
                current=pluggedDevice;currentAddress=current.getAddress();router.activate();status("已连接 "+role);
            }else if(pluggedDevice!=null){hid.disconnect(pluggedDevice);}
            else connectDesired(generation);
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device,int state){
            log("蓝牙连接回调 "+device.getAddress()+" 状态 "+state);
            if(state==BluetoothProfile.STATE_CONNECTED){
                if(desired==null||!device.equals(desired)){hid.disconnect(device);return;}
                if(current!=null&&!current.equals(device))router.deactivate();
                current=device;currentAddress=device.getAddress();switching=false;router.activate();status("已连接 "+role);
            }else if(state==BluetoothProfile.STATE_DISCONNECTED){
                if(current!=null&&current.equals(device)){router.deactivate();current=null;currentAddress="";}
                if(switching)connectDesired(generation);
                else if(desired!=null&&device.equals(desired))status(role+" 已断开，点击目标按钮重连");
            }else if(state==BluetoothProfile.STATE_CONNECTING && desired!=null&&device.equals(desired))status("正在连接 "+role);
        }
        @Override public void onSetProtocol(BluetoothDevice device,byte protocol){
            log("电脑请求协议 "+protocol);
            if(protocol==BluetoothHidDevice.PROTOCOL_BOOT_MODE){router.deactivate();status("电脑要求启动协议，当前版本暂停转发");}
            else if(current!=null&&current.equals(device)){router.activate();status("已连接 "+role);}
        }
        @Override public void onGetReport(BluetoothDevice device,byte type,byte id,int bufferSize){
            log("电脑读取报告 类型 "+type+" 编号 "+id+" 长度 "+bufferSize);
            if(hid==null)return;
            byte[] report=current!=null&&current.equals(device)?router.snapshot(id):null;
            if(type==BluetoothHidDevice.REPORT_TYPE_INPUT && report!=null)hid.replyReport(device,type,id,report);
            else if(type==BluetoothHidDevice.REPORT_TYPE_OUTPUT && id==HidReports.KEYBOARD)hid.replyReport(device,type,id,new byte[1]);
            else hid.reportError(device,BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
        }
    };
    public void selectHost(String address,String selectedRole){worker.post(()->{
        if(hid==null||!registered){status("蓝牙键鼠尚未准备好");return;}
        try {
            BluetoothDevice target=adapter.getRemoteDevice(address);
            if(current!=null&&current.equals(target)&&router.isReady()){status("已连接 "+selectedRole);return;}
            router.removePrefix("macro/");router.deactivate();
            desired=target;role=selectedRole;generation++;switching=true;long ticket=generation;
            getSharedPreferences("dock",0).edit().putString("lastHost",address).putString("lastRole",role).apply();
            status("正在切换到 "+role);
            // Give the old host a short window to receive the neutral reports before disconnecting.
            worker.postDelayed(()->{
                if(ticket!=generation||hid==null||destroyed)return;
                List<BluetoothDevice> active=hid.getDevicesMatchingConnectionStates(new int[]{BluetoothProfile.STATE_CONNECTED,BluetoothProfile.STATE_CONNECTING,BluetoothProfile.STATE_DISCONNECTING});
                boolean wait=false;
                for(BluetoothDevice device:active) {
                    if(!device.equals(target)){hid.disconnect(device);wait=true;}
                    else if(hid.getConnectionState(device)==BluetoothProfile.STATE_DISCONNECTING)wait=true;
                }
                if(current!=null&&!current.equals(target)&&hid.getConnectionState(current)!=BluetoothProfile.STATE_DISCONNECTED)wait=true;
                if(!wait)connectDesired(ticket);
            },120);
            worker.postDelayed(()->{
                if(ticket==generation && switching && hid!=null && !destroyed){
                    router.deactivate();switching=false;status("连接超时，目标可能已关机。点击按钮重试");
                    for(BluetoothDevice device:hid.getDevicesMatchingConnectionStates(new int[]{BluetoothProfile.STATE_CONNECTING}))hid.disconnect(device);
                }
            },15000);
        }catch(Exception ex){switching=false;status("切换失败："+ex.getMessage());}
    });}
    private void connectDesired(long ticket){
        if(destroyed||ticket!=generation||desired==null||hid==null||!registered)return;
        if(current!=null) {
            int actual=hid.getConnectionState(current);
            if(actual==BluetoothProfile.STATE_CONNECTED&&!current.equals(desired))return;
            if(actual==BluetoothProfile.STATE_CONNECTED&&current.equals(desired)){router.activate();switching=false;status("已连接 "+role);return;}
            if(actual==BluetoothProfile.STATE_DISCONNECTING)return;
            current=null;currentAddress="";
        }
        try {
            int state=hid.getConnectionState(desired);
            if(state==BluetoothProfile.STATE_CONNECTED){current=desired;currentAddress=desired.getAddress();router.activate();switching=false;status("已连接 "+role);}
            else if(state==BluetoothProfile.STATE_DISCONNECTED && !hid.connect(desired)){switching=false;status("电脑未接受连接，请从电脑蓝牙设置连接 PocketDock");}
        }catch(Exception ex){switching=false;status("连接请求失败："+ex.getMessage());}
    }
    private void send(int id,byte[] data){
        if(current==null||hid==null)return;
        try {
            long started=System.nanoTime();
            boolean accepted=hid.sendReport(current,id,data);
            maxSendMicros=Math.max(maxSendMicros,(System.nanoTime()-started)/1000);
            if(id==HidReports.MOUSE)mouseReports++;else if(id==HidReports.KEYBOARD)keyReports++;
            if(SystemClock.uptimeMillis()<traceUntil) {
                StringBuilder packet=new StringBuilder();
                for(byte value:data)packet.append(String.format(Locale.ROOT,"%02x ",value&255));
                log("测试报告 "+id+" 接受 "+accepted+" 数据 "+packet);
            }
            if(!accepted)log("蓝牙发送未被接受，报告 "+id);
        }
        catch(Exception ex){log("蓝牙发送失败："+ex.getMessage());}
    }
    public void scanUsb(){main.post(()->usb.scan());}
    public void emergencyRelease(){worker.post(()->{
        router.removePrefix("macro/");router.deactivate();
        if(current!=null)router.activate();
        status(current!=null?"已释放按键，仍连接 "+role:"已释放本地输入状态");
    });}
    public void shortcut(int modifiers,int usage){worker.post(()->{
        if(!router.isReady())return;
        String source="macro/"+UUID.randomUUID();Set<Integer> keys=new TreeSet<>();
        for(int bit=0;bit<8;bit++)if((modifiers&(1<<bit))!=0)keys.add(0xE0+bit);keys.add(usage);
        long ticket=generation;router.keyboard(source,keys);
        worker.postDelayed(()->{router.remove(source);if(ticket!=generation)log("旧快捷键已清理");},80);
    });}
    public void switchInputLanguage(){worker.post(()->{
        if(!router.isReady()){log("输入法切换未发送：电脑未连接");return;}
        long now=SystemClock.uptimeMillis();
        if(now<languageBusyUntil)return;
        LanguageShortcut chord=LanguageShortcut.resolve(role,getSharedPreferences("dock",0).getString("ime."+role,null));
        int lead=chord.modifiers!=0&&chord.usage!=0?50:0;
        languageBusyUntil=now+lead+chord.holdMillis+150;
        String source="macro/language/"+UUID.randomUUID();long ticket=generation;
        traceUntil=now+1000;router.keyboard(source,chord.keys(lead==0));
        if(lead!=0)worker.postDelayed(()->{
            if(ticket==generation&&router.isReady())router.keyboard(source,chord.keys(true));
            else router.remove(source);
        },lead);
        worker.postDelayed(()->{
            if(ticket!=generation||!router.isReady()){router.remove(source);return;}
            router.keyboard(source,chord.keys(false));
        },lead+chord.holdMillis);
        worker.postDelayed(()->router.remove(source),lead+chord.holdMillis+40);
        log("已发送输入法切换快捷键 "+chord.label+"，目标 "+role);
    });}
    public void media(int usage){worker.post(()->{
        if(!router.isReady())return;
        String source="macro/"+UUID.randomUUID();router.consumer(source,Collections.singleton(usage));
        worker.postDelayed(()->router.remove(source),80);
    });}
    public void testTyping(){worker.post(()->{
        if(!router.isReady()){status("连接电脑后才能发送测试文字");return;}
        traceUntil=SystemClock.uptimeMillis()+3000;
        final String text="pocketdock";final long ticket=generation;
        final HidParser parser=new HidParser(HidReports.DESCRIPTOR);
        for(int index=0;index<text.length();index++) {
            final int usage=4+text.charAt(index)-'a';long delay=index*150L;
            worker.postDelayed(()->{
                if(ticket!=generation||!router.isReady())return;
                HidParser.Frame frame=parser.decode(new byte[]{1,0,0,(byte)usage,0,0,0,0,0});
                router.keyboard("macro/test",frame.keys);
            },delay);
            worker.postDelayed(()->{if(ticket==generation)router.remove("macro/test");},delay+75);
        }
        log("已安排发送测试文字 pocketdock，目标 "+role);
    });}
    public void testMouse(){worker.post(()->{
        if(!router.isReady()){status("连接电脑后才能测试鼠标");return;}
        long ticket=generation;int[][] moves={{80,0},{0,80},{-80,0},{0,-80}};
        for(int i=0;i<moves.length;i++) {
            final int[] move=moves[i];worker.postDelayed(()->{
                if(ticket==generation&&router.isReady())router.mouse("macro/testMouse",0,move[0],move[1],0,0);
            },i*250L);
        }
        worker.postDelayed(()->router.remove("macro/testMouse"),1100);
        log("已安排方形鼠标移动测试，目标 "+role);
    });}
    public String bluetoothStatus(){return bluetoothStatus;}
    public String usbStatus(){return usbStatus;}
    public String role(){return role;}
    public String currentAddress(){return currentAddress;}
    public String diagnostics(){synchronized(logs){StringBuilder out=new StringBuilder();for(String line:logs)out.append(line).append('\n');return out.toString();}}
    public void setListener(Listener value){listener=value;}
    private void status(String text){bluetoothStatus=text;log(text);changed();}
    private void changed(){main.post(()->{Listener value=listener;if(value!=null)value.changed();});}
    private void log(String text){android.util.Log.i("PocketDock",text);synchronized(logs){if(logs.size()>=100)logs.removeFirst();logs.addLast(new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" "+text);}}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){
        destroyed=true;listener=null;
        if(usb!=null)usb.stop();
        if(worker!=null)worker.post(()->{
            router.deactivate();
            if(hid!=null){try{hid.unregisterApp();adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,hid);}catch(Exception ignored){}}
            if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();thread.quitSafely();
        });
        super.onDestroy();
    }
}
