using System;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

// PowerToys 0.100.2: KeyboardManagerConstants::KEYBOARDMANAGER_SHORTCUT_FLAG.
// The phone sends F24; existing keyboard and PowerToys mappings are never edited.
internal sealed class ImeBridge : ApplicationContext {
    [StructLayout(LayoutKind.Sequential)] struct KeyboardInput { public ushort vk,scan;public uint flags,time;public UIntPtr extra; }
    [StructLayout(LayoutKind.Explicit,Size=40)] struct Input { [FieldOffset(0)] public uint type;[FieldOffset(8)] public KeyboardInput keyboard; }
    [DllImport("user32.dll",SetLastError=true)] static extern uint SendInput(uint count,Input[] input,int size);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
    [DllImport("user32.dll",SetLastError=true)] static extern bool RegisterHotKey(IntPtr window,int id,uint modifiers,uint key);
    [DllImport("user32.dll")] static extern bool UnregisterHotKey(IntPtr window,int id);
    sealed class SignalWindow : NativeWindow {
        readonly ImeBridge owner;
        public SignalWindow(ImeBridge owner){this.owner=owner;CreateHandle(new CreateParams {Caption="PocketDock IME signal"});}
        protected override void WndProc(ref Message m){if(m.Msg==0x312&&m.WParam.ToInt32()==24)owner.Toggle();base.WndProc(ref m);}
    }
    readonly SignalWindow window;
    readonly NotifyIcon tray;
    readonly System.Windows.Forms.Timer timer;
    readonly bool test;
    int phase,count,waitTicks;bool busy,winHeld,spaceHeld;
    readonly string log=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"PocketDock","ime-bridge.log");
    ImeBridge(bool test){
        this.test=test;window=new SignalWindow(this);
        timer=new System.Windows.Forms.Timer();timer.Tick+=(s,e)=>Advance();
        tray=new NotifyIcon {Icon=SystemIcons.Application,Text="PocketDock 输入法桥接",Visible=!test};
        var menu=new ContextMenuStrip();menu.Items.Add("手机按钮切换输入法，键盘限制保持原样");menu.Items.Add("退出",null,(s,e)=>ExitThread());tray.ContextMenuStrip=menu;
        if(test){timer.Interval=500;timer.Tick+=StartTest;timer.Start();}
        else if(!RegisterHotKey(window.Handle,24,0x4000,0x87))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(),"F24 signal already registered");
        Write("启动，PowerToys 标记 0x101");
    }
    void StartTest(object sender,EventArgs e){timer.Tick-=StartTest;timer.Stop();Toggle();}
    void Write(string message){try{Directory.CreateDirectory(Path.GetDirectoryName(log));File.AppendAllText(log,DateTime.Now.ToString("s")+" "+message+Environment.NewLine);}catch{} }
    bool Key(ushort vk,bool up){
        var input=new Input {type=1,keyboard=new KeyboardInput {vk=vk,flags=(uint)((up?2:0)|(vk==0x5B?1:0)),extra=new UIntPtr(0x101)}};
        if(SendInput(1,new[]{input},40)==1)return true;
        Write("发送失败，错误 "+Marshal.GetLastWin32Error());return false;
    }
    void Toggle(){
        if(busy)return;
        foreach(int key in new[]{0x10,0x11,0x12,0x5B,0x5C,0x20})if((GetAsyncKeyState(key)&0x8000)!=0){Write("已有按键按住，本次跳过");if(test)ExitThread();return;}
        busy=true;phase=-1;waitTicks=0;
        // Finish the Bluetooth signal before injecting a shortcut from another keyboard.
        timer.Interval=200;timer.Start();
    }
    void Advance(){
        if(!busy)return;
        if(phase==-1){
            if((GetAsyncKeyState(0x87)&0x8000)!=0){if(++waitTicks>25){Write("手机信号未释放，本次跳过");Finish();if(test)ExitThread();}else timer.Interval=20;return;}
            foreach(int key in new[]{0x10,0x11,0x12,0x5B,0x5C,0x20})if((GetAsyncKeyState(key)&0x8000)!=0){Write("等待期间有实体按键，本次跳过");Finish();if(test)ExitThread();return;}
            winHeld=Key(0x5B,false);phase=0;timer.Interval=50;if(!winHeld)Finish();
        }
        else if(phase==0){spaceHeld=Key(0x20,false);phase=1;timer.Interval=80;if(!spaceHeld)Finish();}
        else if(phase==1){if(Key(0x20,true))spaceHeld=false;phase=2;timer.Interval=40;}
        else{Finish();count++;Write("已切换 "+count);tray.Text="PocketDock 输入法桥接，已切换 "+count;if(test)ExitThread();}
    }
    void Finish(){timer.Stop();if(spaceHeld){Key(0x20,true);spaceHeld=false;}if(winHeld){Key(0x5B,true);winHeld=false;}busy=false;}
    protected override void ExitThreadCore(){Finish();UnregisterHotKey(window.Handle,24);window.DestroyHandle();tray.Visible=false;tray.Dispose();timer.Dispose();base.ExitThreadCore();}
    [STAThread] static void Main(string[] args){
        bool test=Array.IndexOf(args,"--test")>=0,created;
        using(var mutex=new Mutex(true,"Local\\PocketDock.ImeBridge",out created)){
            if(!test&&!created)return;
            Application.EnableVisualStyles();
            try{Application.Run(new ImeBridge(test));}catch(Exception ex){MessageBox.Show(ex.Message,"PocketDock 输入法桥接");}
        }
    }
}
