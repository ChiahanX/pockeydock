package com.pocketdock.core;

import java.util.*;

/** HID short-item descriptor parser. Keyboard arrays/NKRO, buttons and relative axes only. */
public final class HidParser {
    public static final class Frame {
        public int reportId, x, y, wheel, pan;
        public Set<Integer> keys, consumer;
        public Integer buttons;
    }
    private static final class Global {
        int page, min, max, size, count, id;
        Global copy() { Global g = new Global(); g.page=page; g.min=min; g.max=max; g.size=size; g.count=count; g.id=id; return g; }
    }
    private static final class Field {
        int offset, size, page, usage, flags, min, logicalMin;
        int[] usages;
    }
    private static final class Layout {
        int bits; boolean keyboard, mouse, consumer;
        List<Field> fields = new ArrayList<>();
    }
    private final Map<Integer, Layout> layouts = new HashMap<>();
    private boolean ids;

    public HidParser(byte[] descriptor) {
        Global g = new Global(); Deque<Global> stack = new ArrayDeque<>();
        List<Integer> usages = new ArrayList<>(); int usageMin = 0, usageMax = -1;
        for (int pos=0; pos<descriptor.length;) {
            int prefix = descriptor[pos++] & 255;
            if (prefix == 0xFE) {
                if (pos+2 > descriptor.length) throw new IllegalArgumentException("Truncated long HID item");
                int length = descriptor[pos] & 255; pos += 2;
                if (pos+length > descriptor.length) throw new IllegalArgumentException("Truncated long HID data");
                pos += length; continue;
            }
            int size = prefix & 3; if (size==3) size=4;
            if (pos+size > descriptor.length) throw new IllegalArgumentException("Truncated HID item");
            int value=0; for (int i=0;i<size;i++) value |= (descriptor[pos++] & 255) << (8*i);
            int signed = size==0 ? 0 : size==4 ? value : (value << (32-size*8)) >> (32-size*8);
            int type=(prefix>>2)&3, tag=prefix>>4;
            if (type==1) {
                switch(tag) {
                    case 0:g.page=value;break; case 1:g.min=signed;break;
                    case 2:g.max=g.min<0?signed:value;break; case 7:g.size=value;break;
                    case 8:if(value<1||value>255) throw new IllegalArgumentException("Invalid HID report ID"); g.id=value;ids=true;break;
                    case 9:g.count=value;break; case 10:stack.push(g.copy());break;
                    case 11:if(stack.isEmpty()) throw new IllegalArgumentException("HID global stack underflow");g=stack.pop();break;
                    default:break;
                }
            } else if (type==2) {
                if(tag==0) usages.add(size==4?value:((g.page<<16)|(value&65535)));
                else if(tag==1) usageMin=size==4?value:((g.page<<16)|(value&65535));
                else if(tag==2) usageMax=size==4?value:((g.page<<16)|(value&65535));
            } else if (type==0) {
                if(tag==8) {
                    if(g.size<0||g.size>32||g.count<0||g.count>4096) throw new IllegalArgumentException("Unsupported HID field size");
                    Layout l=layouts.computeIfAbsent(g.id, k->new Layout());
                    if ((long)l.bits+(long)g.size*g.count > 65536) throw new IllegalArgumentException("Oversized HID input report");
                    for(int i=0;i<g.count;i++) {
                        Field f=new Field(); f.offset=l.bits+i*g.size; f.size=g.size; f.flags=value;
                        f.logicalMin=g.min; f.min=g.min;
                        int fullUsage=usages.isEmpty() ? usageMin + ((usageMax>=usageMin)?Math.min(i,usageMax-usageMin):0)
                            : usages.get(Math.min(i,usages.size()-1));
                        f.page=fullUsage>>>16; f.usage=fullUsage&65535;
                        if((value&2)==0) {
                            f.usage=usageMin&65535;
                            f.usages=new int[usages.size()]; for(int u=0;u<usages.size();u++) f.usages[u]=usages.get(u)&65535;
                        }
                        if((value&1)==0 && f.size>0) {
                            l.fields.add(f);
                            l.keyboard|=f.page==7; l.consumer|=f.page==12 && f.usage!=0x238;
                            l.mouse|=f.page==9 || (f.page==1&&(f.usage==0x30||f.usage==0x31||f.usage==0x38)) || (f.page==12&&f.usage==0x238);
                        }
                    }
                    l.bits+=g.size*g.count;
                }
                usages.clear();usageMin=0;usageMax=-1;
            }
        }
    }
    public boolean supported() {
        for(Layout l:layouts.values()) if(l.keyboard||l.mouse||l.consumer) return true;
        return false;
    }
    public Frame decode(byte[] report) {
        if(report.length==0) return null;
        int id=ids?(report[0]&255):0, offset=ids?8:0;
        Layout l=layouts.get(id); if(l==null || report.length*8 < l.bits+offset) return null;
        Frame out=new Frame();out.reportId=id;
        if(l.keyboard) out.keys=new TreeSet<>();
        if(l.consumer) out.consumer=new TreeSet<>();
        boolean rollover=false;
        for(Field f:l.fields) {
            int v=bits(report,f.offset+offset,f.size);
            if(f.min<0 && f.size>0 && f.size<32) v=(v << (32-f.size)) >> (32-f.size);
            boolean variable=(f.flags&2)!=0;
            int usage=f.usage;
            if(!variable) {
                int index=v-f.logicalMin;
                usage=f.usages.length>0 ? (index>=0&&index<f.usages.length?f.usages[index]:0) : f.usage+index;
            }
            if(f.page==7) {
                if(!variable && usage>=1 && usage<=3) rollover=true;
                if((!variable||v!=0)&&usage>=4&&usage<=255) out.keys.add(usage);
            } else if(f.page==9 && f.usage>=1&&f.usage<=5) {
                if(out.buttons==null)out.buttons=0;
                if(v!=0)out.buttons|=1<<(f.usage-1);
            } else if((f.flags&4)!=0 && f.page==1) {
                if(f.usage==0x30)out.x=v; else if(f.usage==0x31)out.y=v;else if(f.usage==0x38)out.wheel=v;
            } else if(f.page==12) {
                if(f.usage==0x238 && (f.flags&4)!=0)out.pan=v;
                else if((!variable||v!=0)&&usage>0&&usage<=0x3FF)out.consumer.add(usage);
            }
        }
        if(rollover)out.keys=null; // Keep the last valid key state until a valid report arrives.
        return out;
    }
    private static int bits(byte[] data,int offset,int size) {
        if((offset&7)==0&&(size==8||size==16||size==32)){
            int value=0,index=offset/8;
            for(int i=0;i<size/8;i++)value|=(data[index+i]&255)<<(8*i);
            return value;
        }
        int value=0;
        for(int bit=0;bit<size;bit++) if(((data[(offset+bit)/8]>>((offset+bit)%8))&1)!=0)value|=1<<bit;
        return value;
    }
}
