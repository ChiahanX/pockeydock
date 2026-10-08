package com.pocketdock.core;

import java.util.Set;

public final class HidReports {
    private HidReports() {}
    public static final int KEYBOARD = 1, MOUSE = 2, CONSUMER = 3;
    public static final byte[] DESCRIPTOR = hex(
        "05 01 09 06 A1 01 85 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 " +
        "75 08 95 01 81 01 05 08 19 01 29 05 75 01 95 05 91 02 75 03 95 01 91 01 " +
        "05 07 19 00 29 FF 15 00 26 FF 00 75 08 95 06 81 00 C0 " +
        "05 01 09 02 A1 01 85 02 09 01 A1 00 05 09 19 01 29 05 15 00 25 01 " +
        "75 01 95 05 81 02 75 03 95 01 81 01 05 01 09 30 09 31 16 01 80 26 FF 7F " +
        "75 10 95 02 81 06 09 38 15 81 25 7F 75 08 95 01 81 06 " +
        "05 0C 0A 38 02 15 81 25 7F 75 08 95 01 81 06 C0 C0 " +
        "05 0C 09 01 A1 01 85 03 15 00 26 FF 03 19 00 2A FF 03 75 10 95 01 81 00 C0");

    public static byte[] keyboard(Set<Integer> keys) {
        byte[] out = new byte[8]; int count = 0;
        for (int usage : keys) {
            if (usage >= 0xE0 && usage <= 0xE7) out[0] |= (byte)(1 << (usage - 0xE0));
            else if (usage >= 4 && usage <= 0xDF) {
                if (count < 6) out[2 + count] = (byte)usage;
                count++;
            }
        }
        if (count > 6) for (int i = 2; i < 8; i++) out[i] = 1; // ErrorRollOver, never silently truncate.
        return out;
    }
    public static byte[] mouse(int buttons, int x, int y, int wheel, int pan) {
        return new byte[]{(byte)(buttons & 31), (byte)x, (byte)(x >> 8), (byte)y,
            (byte)(y >> 8), (byte)wheel, (byte)pan};
    }
    public static byte[] consumer(int usage) { return new byte[]{(byte)usage, (byte)(usage >> 8)}; }
    public static byte[] hex(String value) {
        String[] parts = value.trim().split("\\s+"); byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = (byte)Integer.parseInt(parts[i], 16);
        return out;
    }
}
