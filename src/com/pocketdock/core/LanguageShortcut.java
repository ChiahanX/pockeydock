package com.pocketdock.core;

import java.util.Set;
import java.util.TreeSet;

/** Host shortcuts with a separate primary-key release before modifier release. */
public enum LanguageShortcut {
    WINDOWS_BRIDGE("手机专用切换（绕过 PowerToys）",0,0x73,80),
    WIN_SPACE("Win + 空格",0x08,0x2C,80),
    CTRL_SPACE("Control + 空格",0x01,0x2C,80),
    SHIFT("Shift",0x02,0,80),
    ALT_SHIFT("Alt + Shift",0x06,0,80),
    CAPS_LOCK("Caps Lock",0,0x39,180);

    public final String label;
    public final int modifiers,usage,holdMillis;
    LanguageShortcut(String label,int modifiers,int usage,int holdMillis){
        this.label=label;this.modifiers=modifiers;this.usage=usage;this.holdMillis=holdMillis;
    }
    public Set<Integer> keys(boolean primary){
        Set<Integer> keys=new TreeSet<>();
        for(int bit=0;bit<8;bit++)if((modifiers&(1<<bit))!=0)keys.add(0xE0+bit);
        if(primary&&usage!=0)keys.add(usage);
        return keys;
    }
    public static LanguageShortcut resolve(String role,String saved){
        LanguageShortcut fallback="Mac".equals(role)?CTRL_SPACE:WINDOWS_BRIDGE;
        if(saved!=null)try {
            LanguageShortcut chosen=valueOf(saved);
            if("Mac".equals(role))return chosen==CTRL_SPACE||chosen==CAPS_LOCK?chosen:fallback;
            return chosen==CAPS_LOCK?fallback:chosen;
        }catch(IllegalArgumentException ignored){}
        return fallback;
    }
}
