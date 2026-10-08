package com.pocketdock;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.pocketdock.core.LanguageShortcut;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private DockService service;
    private boolean bound,resumed;
    private TextView clock,date,status,usb;
    private Button windows,mac,inputLanguage;
    private Handler ui=new Handler(Looper.getMainLooper());
    private final int background=Color.rgb(15,20,29),card=Color.rgb(31,41,56),accent=Color.rgb(81,211,189);
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){service=((DockService.LocalBinder)binder).service();service.setListener(()->refresh());refresh();}
        public void onServiceDisconnected(ComponentName name){service=null;refresh();}
    };
    private final Runnable tick=new Runnable(){public void run(){
        Date now=new Date();clock.setText(new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(now));
        date.setText(new SimpleDateFormat("MM月dd日  EEEE",Locale.CHINA).format(now));
        if(resumed)ui.postDelayed(this,1000);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams windowParams=getWindow().getAttributes();windowParams.screenBrightness=0.25f;getWindow().setAttributes(windowParams);
        getWindow().setStatusBarColor(background);getWindow().setNavigationBarColor(background);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.HORIZONTAL);root.setPadding(dp(18),dp(14),dp(18),dp(12));root.setBackgroundColor(background);
        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);left.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label("POCKETDOCK",16,accent);left.addView(title);
        clock=label("00:00:00",38,Color.WHITE);clock.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);left.addView(clock);
        date=label("",15,Color.LTGRAY);left.addView(date);
        status=label("正在准备连接",14,accent);status.setPadding(0,dp(18),dp(8),dp(5));left.addView(status);
        usb=label("等待 USB 键鼠",12,Color.LTGRAY);left.addView(usb);
        TextView note=label("键鼠接手机\n电脑可独立开关机",12,Color.GRAY);note.setPadding(0,dp(10),0,0);left.addView(note);
        root.addView(left,new LinearLayout.LayoutParams(0,-1,1.05f));
        ScrollView scroll=new ScrollView(this);LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);scroll.addView(controls);
        LinearLayout hosts=row();windows=button("控制 Windows",()->choose("Windows",false));mac=button("控制 Mac",()->choose("Mac",false));add(hosts,windows);add(hosts,mac);controls.addView(hosts);
        windows.setOnLongClickListener(view->{choose("Windows",true);return true;});
        mac.setOnLongClickListener(view->{choose("Mac",true);return true;});
        LinearLayout language=row();inputLanguage=button("中 / 英输入法",()->switchInputLanguage());inputLanguage.setEnabled(false);inputLanguage.setAlpha(0.45f);add(language,inputLanguage);add(language,button("输入法设置",()->languageSettings()));controls.addView(language);
        LinearLayout actions=row();add(actions,button("切换应用",()->shortcut(0x04,0x08,0x2B)));add(actions,button("复制",()->shortcut(0x01,0x08,0x06)));add(actions,button("粘贴",()->shortcut(0x01,0x08,0x19)));controls.addView(actions);
        LinearLayout media=row();add(media,button("音量 −",()->media(0xEA)));add(media,button("播放 / 暂停",()->media(0xCD)));add(media,button("音量 +",()->media(0xE9)));controls.addView(media);
        LinearLayout extra=row();add(extra,button("静音",()->media(0xE2)));add(extra,button("锁屏",()->{if(service!=null)service.shortcut(isMac()?0x09:0x08,isMac()?0x14:0x0F);}));add(extra,button("释放全部按键",()->{if(service!=null)service.emergencyRelease();}));controls.addView(extra);
        LinearLayout tools=row();add(tools,button("重新扫描 USB",()->{if(service!=null)service.scanUsb();}));add(tools,button("蓝牙配对",()->discoverable()));add(tools,button("设置 / 诊断",()->settings()));controls.addView(tools);
        TextView help=label("在电脑上配对这台手机。长按电脑按钮可修改目标。",12,Color.LTGRAY);help.setPadding(dp(5),dp(6),0,0);controls.addView(help);
        root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1.7f));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(dp(18)+insets.getSystemWindowInsetLeft(),dp(14)+insets.getSystemWindowInsetTop(),dp(18)+insets.getSystemWindowInsetRight(),dp(12)+insets.getSystemWindowInsetBottom());return insets;
        });
        setContentView(root);
        permissions();
    }
    private void permissions(){
        List<String> needed=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){needed.add(Manifest.permission.BLUETOOTH_CONNECT);needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);}
        if(Build.VERSION.SDK_INT>=33)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing=new ArrayList<>();for(String permission:needed)if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)missing.add(permission);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),10);else start();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==10){
            if(Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)start();
            else status.setText("需要允许附近设备权限");
        }
    }
    private void start(){
        Intent intent=new Intent(this,DockService.class);startForegroundService(intent);
        if(!bound){bound=bindService(intent,connection,BIND_AUTO_CREATE);}
    }
    private void stop(){
        if(service!=null)service.setListener(null);
        if(bound){unbindService(connection);bound=false;}
        service=null;stopService(new Intent(this,DockService.class));status.setText("转发已停止");usb.setText("USB 接口将归还系统");
    }
    private void restart(){stop();ui.postDelayed(()->permissions(),700);}
    private void refresh(){
        if(service==null)return;status.setText(service.bluetoothStatus());usb.setText(service.usbStatus());
        String current=service.currentAddress();
        windows.setText("控制 Windows"+(current.equals(host("Windows"))&&!current.isEmpty()?" · 已连接":""));
        mac.setText("控制 Mac"+(current.equals(host("Mac"))&&!current.isEmpty()?" · 已连接":""));
        inputLanguage.setEnabled(!current.isEmpty());inputLanguage.setAlpha(current.isEmpty()?0.45f:1f);
    }
    private String host(String role){return getSharedPreferences("dock",0).getString("host."+role,"");}
    private void choose(String role,boolean configure){
        if(service==null){permissions();return;}
        String saved=host(role);
        if(!configure&&!saved.isEmpty()){service.selectHost(saved,role);return;}
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter adapter=manager.getAdapter();
        if(adapter==null||!adapter.isEnabled()){startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}
        List<BluetoothDevice> devices=new ArrayList<>(adapter.getBondedDevices());
        if(devices.isEmpty()){new AlertDialog.Builder(this).setMessage("还没有已配对电脑。点击蓝牙配对，在电脑上添加 PocketDock。").setPositiveButton("确定",null).show();return;}
        String[] names=new String[devices.size()];for(int i=0;i<devices.size();i++)names[i]=devices.get(i).getName()+"\n"+devices.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("选择 "+role+" 电脑").setItems(names,(dialog,index)->{
            String address=devices.get(index).getAddress();getSharedPreferences("dock",0).edit().putString("host."+role,address).apply();service.selectHost(address,role);
        }).setNegativeButton("取消",null).show();
    }
    private void discoverable(){
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)!=PackageManager.PERMISSION_GRANTED){permissions();return;}
        Intent intent=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,120);startActivity(intent);
    }
    private void diagnostics(){
        String text="PocketDock beta1.0\n"+Build.MANUFACTURER+" "+Build.MODEL+" / Android "+Build.VERSION.RELEASE+"\n"+(service==null?"服务未启动":service.diagnostics());
        TextView view=label(text,12,Color.WHITE);view.setTextIsSelectable(true);view.setPadding(dp(12),dp(12),dp(12),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("运行诊断").setView(scroll).setPositiveButton("关闭",null).setNeutralButton("复制",(dialog,which)->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PocketDock",text));
        }).show();
    }
    private void settings(){
        new AlertDialog.Builder(this).setTitle("桌面坞设置").setItems(new String[]{"运行诊断","发送测试文字 pocketdock","测试鼠标移动","重启服务","停止转发"},(dialog,index)->{
            if(index==0)diagnostics();else if(index==1){if(service!=null)service.testTyping();}
            else if(index==2){if(service!=null)service.testMouse();}else if(index==3)restart();else stop();
        }).setNegativeButton("取消",null).show();
    }
    private boolean isMac(){return service!=null&&"Mac".equals(service.role());}
    private void switchInputLanguage(){
        if(service==null||service.currentAddress().isEmpty()){Toast.makeText(this,"连接电脑后才能切换输入法",Toast.LENGTH_SHORT).show();return;}
        service.switchInputLanguage();
    }
    private void languageSettings(){
        new AlertDialog.Builder(this).setTitle("选择要配置的电脑").setItems(new String[]{"Windows","Mac"},(dialog,index)->configureLanguage(index==0?"Windows":"Mac")).setNegativeButton("取消",null).show();
    }
    private void configureLanguage(String role){
        final LanguageShortcut[] options="Mac".equals(role)?new LanguageShortcut[]{LanguageShortcut.CTRL_SPACE,LanguageShortcut.CAPS_LOCK}:new LanguageShortcut[]{LanguageShortcut.WINDOWS_BRIDGE,LanguageShortcut.WIN_SPACE,LanguageShortcut.SHIFT,LanguageShortcut.CTRL_SPACE,LanguageShortcut.ALT_SHIFT};
        LanguageShortcut current=LanguageShortcut.resolve(role,getSharedPreferences("dock",0).getString("ime."+role,null));
        String[] names=new String[options.length];int selected=0;
        for(int i=0;i<options.length;i++){names[i]=options[i].label;if(options[i]==current)selected=i;}
        new AlertDialog.Builder(this).setTitle(role+" 输入法快捷键").setSingleChoiceItems(names,selected,(dialog,index)->{
            getSharedPreferences("dock",0).edit().putString("ime."+role,options[index].name()).apply();dialog.dismiss();
            Toast.makeText(this,"已设置 "+role+"："+options[index].label,Toast.LENGTH_SHORT).show();
        }).setNeutralButton("使用说明",(dialog,which)->new AlertDialog.Builder(this).setTitle("输入法切换说明").setMessage("Windows：默认使用手机专用切换，需要电脑运行 PocketDock-ImeBridge。保留 PowerToys 的键盘限制，通过手机按钮切换输入法。\n\nMac：默认 Control + 空格。\n\n其他快捷键用于电脑自行配置。手机无法读取电脑当前输入语言。").setPositiveButton("关闭",null).show()).setNegativeButton("取消",null).show();
    }
    private void shortcut(int windowsModifier,int macModifier,int key){if(service!=null)service.shortcut(isMac()?macModifier:windowsModifier,key);}
    private void media(int usage){if(service!=null)service.media(usage);}
    private TextView label(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    private LinearLayout row(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.HORIZONTAL);return layout;}
    private Button button(String text,Runnable action){Button v=new Button(this);v.setText(text);v.setTextSize(13);v.setTextColor(Color.WHITE);v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(card));v.setAllCaps(false);v.setMinHeight(dp(46));v.setPadding(dp(5),0,dp(5),0);v.setOnClickListener(view->action.run());return v;}
    private void add(LinearLayout row,View view){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(44),1);params.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(view,params);}
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    @Override protected void onResume(){super.onResume();resumed=true;ui.removeCallbacks(tick);tick.run();}
    @Override protected void onPause(){resumed=false;ui.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){if(service!=null)service.setListener(null);if(bound){unbindService(connection);bound=false;}super.onDestroy();}
}
