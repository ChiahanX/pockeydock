import com.pocketdock.core.*;
import java.util.*;

public final class CoreTests {
    private static int checks;
    private static final class Packet {int id;byte[] data;Packet(int id,byte[] data){this.id=id;this.data=data;}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Set<Integer> keys(Integer... usages){return new TreeSet<>(Arrays.asList(usages));}
    private static Packet last(List<Packet> packets,int id){for(int i=packets.size()-1;i>=0;i--)if(packets.get(i).id==id)return packets.get(i);throw new AssertionError("missing packet");}
    private static boolean zero(byte[] data){for(byte b:data)if(b!=0)return false;return true;}
    private static int signed16(byte[] data,int offset){return (short)((data[offset]&255)|((data[offset+1]&255)<<8));}
    public static void main(String[] args){
        List<Packet> packets=new ArrayList<>();InputRouter router=new InputRouter((id,data)->packets.add(new Packet(id,data)));
        router.keyboard("keyboard",keys(0xE0,4));router.mouse("mouse",1,12,0,0,0);
        check(packets.isEmpty(),"offline input must be dropped");router.activate();
        check(packets.size()==3&&zero(last(packets,1).data),"new host starts neutral");
        router.keyboard("keyboard",keys(0xE0,4,5));
        check(last(packets,1).data[0]==0&&last(packets,1).data[2]==5,"held keys are suppressed after connection");
        router.mouse("mouse",1,5,-9,-1,2);
        check(last(packets,2).data[0]==0&&signed16(last(packets,2).data,1)==5,"held button blocked but new motion passed");
        router.keyboard("keyboard",keys());router.mouse("mouse",0,0,0,0,0);
        router.keyboard("keyboard",keys(0xE0,4));router.mouse("mouse",1,0,0,0,0);
        check(last(packets,1).data[0]==1&&last(packets,1).data[2]==4,"released and pressed keys become active");
        check(Arrays.equals(router.snapshot(1),last(packets,1).data),"host GET_REPORT sees current held keyboard state");
        check(last(packets,2).data[0]==1,"released and pressed mouse button becomes active");
        int old=packets.size();router.deactivate();
        check(packets.size()==old+3&&zero(last(packets,1).data)&&zero(last(packets,2).data),"switch releases old host");
        old=packets.size();router.mouse("mouse",1,100,100,1,1);router.keyboard("keyboard",keys(0xE0,4));
        check(packets.size()==old,"no input during switch");router.activate();
        check(zero(last(packets,1).data)&&zero(last(packets,2).data),"new host does not inherit drag or modifier");
        router.keyboard("keyboard",keys());router.keyboard("other",keys(6));router.keyboard("keyboard",keys(7));
        check(Arrays.equals(last(packets,1).data,new byte[]{0,0,6,7,0,0,0,0}),"aggregate multiple keyboards");
        old=packets.size();router.remove("other");check(last(packets,1).data[2]==7,"device removal releases only its own keys");
        check(packets.size()==old+1&&packets.get(old).id==1,"keyboard removal must not flood mouse and consumer reports");
        old=packets.size();router.remove("nonexistent");check(packets.size()==old,"unknown source removal sends nothing");
        router.remove("keyboard");check(zero(last(packets,1).data),"detach releases stuck key");
        old=packets.size();router.mouse("mouse",0,70000,-70000,260,-260);
        int x=0,y=0,wheel=0,pan=0;for(int i=old;i<packets.size();i++){Packet p=packets.get(i);x+=signed16(p.data,1);y+=signed16(p.data,3);wheel+=p.data[5];pan+=p.data[6];}
        check(x==70000&&y==-70000&&wheel==260&&pan==-260,"movement chunking preserves signed totals");
        byte[] overflow=HidReports.keyboard(keys(4,5,6,7,8,9,10));
        check(overflow[2]==1&&overflow[7]==1,"rollover is explicit");
        router.consumer("media",keys(0xE9));check(last(packets,3).data[0]==(byte)0xE9,"consumer key forwarded");
        router.deactivate();router.activate();router.consumer("media",keys(0xE9));check(zero(last(packets,3).data),"held media key suppressed");
        router.consumer("media",keys());router.consumer("media",keys(0xE9));check(!zero(last(packets,3).data),"media resumes after physical release");

        HidParser own=new HidParser(HidReports.DESCRIPTOR);check(own.supported(),"outgoing descriptor parseable");
        HidParser.Frame keyboard=own.decode(new byte[]{1,3,0,4,5,0,0,0,0});
        check(keyboard.keys.equals(keys(0xE0,0xE1,4,5)),"report ID and keyboard modifiers decoded");
        byte[] rawMouse=HidReports.mouse(19,-300,32767,-4,3),mouse=new byte[8];mouse[0]=2;System.arraycopy(rawMouse,0,mouse,1,7);
        HidParser.Frame f=own.decode(mouse);
        check(f.buttons==19&&f.x==-300&&f.y==32767&&f.wheel==-4&&f.pan==3,"16bit axes and horizontal wheel decoded");
        HidParser.Frame media=own.decode(new byte[]{3,(byte)0xE9,0});check(media.consumer.equals(keys(0xE9)),"consumer usage array decoded");
        check(own.decode(new byte[]{2,0})==null,"truncated report rejected");check(own.decode(new byte[]{99,0})==null,"unknown report ID ignored");
        HidParser.Frame rollover=own.decode(new byte[]{1,0,0,1,1,1,1,1,1});check(rollover.keys==null,"USB rollover preserves previous state");
        HidParser nkro=new HidParser(HidReports.hex("05 07 19 04 29 0B 15 00 25 01 75 01 95 08 81 02"));
        check(nkro.decode(new byte[]{(byte)0xA1}).keys.equals(keys(4,9,11)),"NKRO bitmap decoded");
        HidParser unsigned=new HidParser(HidReports.hex("05 01 09 30 15 00 26 FF 00 75 08 95 01 81 06"));
        check(unsigned.decode(new byte[]{(byte)200}).x==200,"unsigned logical minimum respected");
        boolean rejected=false;try{new HidParser(new byte[]{0x75});}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"truncated descriptor rejected");
        check(LanguageShortcut.resolve("Windows",null)==LanguageShortcut.WINDOWS_BRIDGE,"Windows default uses companion signal without changing input shortcuts");
        check(LanguageShortcut.resolve("Mac",null)==LanguageShortcut.CTRL_SPACE,"Mac default uses Control Space");
        check(LanguageShortcut.resolve("Mac","WIN_SPACE")==LanguageShortcut.CTRL_SPACE,"incompatible saved shortcut falls back safely");
        check(LanguageShortcut.resolve("Windows","invalid")==LanguageShortcut.WINDOWS_BRIDGE,"invalid preference falls back safely");
        List<Packet> languagePackets=new ArrayList<>();InputRouter languageRouter=new InputRouter((id,data)->languagePackets.add(new Packet(id,data)));
        languageRouter.activate();
        languageRouter.keyboard("macro/language",LanguageShortcut.SHIFT.keys(true));
        check(Arrays.equals(last(languagePackets,1).data,new byte[]{2,0,0,0,0,0,0,0}),"Shift-only toggle contains no phantom HID key");
        languageRouter.remove("macro/language");
        check(zero(last(languagePackets,1).data),"modifier-only toggle releases Shift");
        languageRouter.keyboard("physical",keys(0xE1));
        languageRouter.keyboard("macro/language",LanguageShortcut.CTRL_SPACE.keys(true));
        check(Arrays.equals(last(languagePackets,1).data,new byte[]{3,0,0x2C,0,0,0,0,0}),"input-source chord preserves physical modifier");
        languageRouter.keyboard("macro/language",LanguageShortcut.CTRL_SPACE.keys(false));
        check(Arrays.equals(last(languagePackets,1).data,new byte[]{3,0,0,0,0,0,0,0}),"Space releases before Control for Mac switching");
        languageRouter.remove("macro/language");
        check(last(languagePackets,1).data[0]==2,"macro cleanup leaves held physical Shift intact");
        languageRouter.keyboard("physical",keys());
        languageRouter.keyboard("macro/language",LanguageShortcut.WIN_SPACE.keys(true));
        check(Arrays.equals(last(languagePackets,1).data,new byte[]{8,0,0x2C,0,0,0,0,0}),"Windows source toggle uses GUI Space");
        languageRouter.removePrefix("macro/");languageRouter.deactivate();languageRouter.activate();
        int clean=languagePackets.size();languageRouter.remove("macro/language");
        check(languagePackets.size()==clean&&zero(last(languagePackets,1).data),"delayed old-language cleanup cannot trigger new host");
        languageRouter.keyboard("macro/language",LanguageShortcut.CAPS_LOCK.keys(true));
        check(last(languagePackets,1).data[2]==0x39&&last(languagePackets,1).data[0]==0,"Caps Lock option is a plain keypress");
        languageRouter.keyboard("macro/language",LanguageShortcut.CAPS_LOCK.keys(false));
        check(zero(last(languagePackets,1).data),"Caps Lock toggle fully releases its key");
        Random random=new Random(20261008L);
        InputBatch batch=new InputBatch();java.util.concurrent.atomic.AtomicBoolean alive=new java.util.concurrent.atomic.AtomicBoolean(true);
        for(int i=0;i<1000;i++){
            HidParser.Frame move=new HidParser.Frame();move.buttons=0;move.x=1;move.y=-1;move.consumer=keys();
            check(batch.offer("mouse",move,alive)==(i==0),"USB burst schedules one drain");
        }
        List<InputBatch.Entry> burst=batch.drain();
        check(burst.size()==1&&burst.get(0).frame.x==1000&&burst.get(0).frame.y==-1000,"1000 mouse polls merge with total motion preserved");
        HidParser.Frame down=new HidParser.Frame();down.buttons=1;down.x=2;batch.offer("mouse",down,alive);
        HidParser.Frame up=new HidParser.Frame();up.buttons=0;up.x=3;batch.offer("mouse",up,alive);
        HidParser.Frame press=new HidParser.Frame();press.keys=keys(4);batch.offer("keyboard",press,alive);
        HidParser.Frame release=new HidParser.Frame();release.keys=keys();batch.offer("keyboard",release,alive);
        burst=batch.drain();check(burst.size()==4&&burst.get(0).frame.buttons==1&&burst.get(1).frame.buttons==0&&burst.get(2).frame.keys.contains(4)&&burst.get(3).frame.keys.isEmpty(),"batch preserves click and keyboard transitions in order");
        List<Packet> quiet=new ArrayList<>();InputRouter quietRouter=new InputRouter((id,data)->quiet.add(new Packet(id,data)));quietRouter.activate();
        quietRouter.keyboard("k",keys(4));int quietCount=quiet.size();
        for(int i=0;i<1000;i++)quietRouter.keyboard("k",keys(4));
        check(quiet.size()==quietCount,"identical keyboard reports do not flood Bluetooth");
        quietRouter.keyboard("k",keys());check(zero(last(quiet,1).data),"suppression still forwards keyboard release");
        quietCount=quiet.size();for(int i=0;i<1000;i++)quietRouter.mouse("m",0,0,0,0,0);
        check(quiet.size()==quietCount,"idle mouse polling produces no Bluetooth traffic");
        quietRouter.mouse("m",1,0,0,0,0);quietRouter.mouse("m",0,0,0,0,0);
        check(quiet.size()==quietCount+2&&last(quiet,2).data[0]==0,"stationary click forwards down and up");
        HidParser realKeyboard=new HidParser(HidReports.hex("05010906a101050719e029e715002501750195088102950175088101950575010508190129059102950175039101950675081500269400050719002a94008100c0".replaceAll("(..)","$1 ")));
        check(realKeyboard.decode(new byte[]{0,0,4,0,0,0,0,0}).keys.equals(keys(4)),"actual Drunkdeer G65 eight-byte keypress decoded");
        check(realKeyboard.decode(new byte[8]).keys.isEmpty(),"actual Drunkdeer G65 eight-byte release decoded");
        HidParser realMouse=new HidParser(HidReports.hex("05010902a1010901a10005091901290515002501950575018102950175038101050109381581257f75089501810605010930093116018026ff7f751095028106050c0a38021581257f750895018106050c090915002501750895018106c0c0".replaceAll("(..)","$1 ")));
        HidParser.Frame realMove=realMouse.decode(new byte[]{1,0,10,0,(byte)0xF6,(byte)0xFF,0,0});
        check(realMove.buttons==1&&realMove.x==10&&realMove.y==-10,"actual LAMZU eight-byte report decoded without waiting for extra packets");
        InputBatch realBatch=new InputBatch();
        for(int i=0;i<1000;i++)realBatch.offer("lamzu",realMouse.decode(new byte[]{0,0,1,0,0,0,0,0}),alive);
        List<InputBatch.Entry> realBurst=realBatch.drain();
        check(realBurst.size()==1&&realBurst.get(0).frame.x==1000,"actual LAMZU descriptor empty consumer field must not defeat movement coalescing");
        InputBatch edgeBatch=new InputBatch();
        HidParser.Frame mediaDown=realMouse.decode(new byte[]{0,0,1,0,0,0,0,1});
        edgeBatch.offer("lamzu",mediaDown,alive);edgeBatch.offer("lamzu",realMouse.decode(new byte[]{0,0,1,0,0,0,0,0}),alive);
        check(edgeBatch.drain().size()==2,"consumer transitions embedded in mouse packets are preserved");
        for(int sample=0;sample<2000;sample++) {
            Set<Integer> input=new TreeSet<>();int amount=random.nextInt(7);
            while(input.size()<amount)input.add(4+random.nextInt(100));
            for(int bit=0;bit<8;bit++)if(random.nextBoolean())input.add(0xE0+bit);
            byte[] encoded=HidReports.keyboard(input),raw=new byte[9];raw[0]=1;System.arraycopy(encoded,0,raw,1,8);
            check(own.decode(raw).keys.equals(input),"random keyboard descriptor roundtrip");
            int mx=random.nextInt(65535)-32767,my=random.nextInt(65535)-32767,mw=random.nextInt(255)-127,mp=random.nextInt(255)-127,mb=random.nextInt(32);
            encoded=HidReports.mouse(mb,mx,my,mw,mp);raw=new byte[8];raw[0]=2;System.arraycopy(encoded,0,raw,1,7);
            HidParser.Frame decoded=own.decode(raw);
            check(decoded.x==mx&&decoded.y==my&&decoded.wheel==mw&&decoded.pan==mp&&decoded.buttons==mb,"random mouse descriptor roundtrip");
        }
        System.out.println("PASS: "+checks+" meaningful checks");
    }
}
