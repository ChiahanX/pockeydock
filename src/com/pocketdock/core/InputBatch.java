package com.pocketdock.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One scheduled drain for USB bursts. Only adjacent motion with identical buttons is merged. */
public final class InputBatch {
    public static final class Entry {
        public final String source;
        public final HidParser.Frame frame;
        public final AtomicBoolean alive;
        Entry(String source,HidParser.Frame frame,AtomicBoolean alive){this.source=source;this.frame=frame;this.alive=alive;}
    }
    private final ArrayList<Entry> pending=new ArrayList<>();
    private boolean scheduled;
    public synchronized boolean offer(String source,HidParser.Frame frame,AtomicBoolean alive){
        Entry last=pending.isEmpty()?null:pending.get(pending.size()-1);
        if(last!=null&&last.source.equals(source)&&last.alive==alive&&motion(last.frame)&&motion(frame)&&Objects.equals(last.frame.buttons,frame.buttons)&&Objects.equals(last.frame.keys,frame.keys)&&Objects.equals(last.frame.consumer,frame.consumer)){
            last.frame.x=add(last.frame.x,frame.x);last.frame.y=add(last.frame.y,frame.y);
            last.frame.wheel=add(last.frame.wheel,frame.wheel);last.frame.pan=add(last.frame.pan,frame.pan);
        }else pending.add(new Entry(source,frame,alive));
        if(scheduled)return false;scheduled=true;return true;
    }
    public synchronized List<Entry> drain(){List<Entry> result=new ArrayList<>(pending);pending.clear();scheduled=false;return result;}
    private static boolean motion(HidParser.Frame frame){return frame.buttons!=null||frame.x!=0||frame.y!=0||frame.wheel!=0||frame.pan!=0;}
    private static int add(int a,int b){return (int)Math.max(-1048544L,Math.min(1048544L,(long)a+b));}
}
