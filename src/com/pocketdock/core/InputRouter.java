package com.pocketdock.core;

import java.util.*;

/** Called on a single serialized worker. A newly connected host never inherits held inputs. */
public final class InputRouter {
    public interface Sink { void send(int reportId, byte[] data); }
    private final Sink sink;
    private final Map<String, Set<Integer>> keyboards = new HashMap<>();
    private final Map<String, Integer> mice = new HashMap<>();
    private final Map<String, Set<Integer>> consumers = new HashMap<>();
    private final Set<Integer> blockedKeys = new HashSet<>(), blockedConsumer = new HashSet<>();
    private int blockedButtons;
    private boolean ready;
    public InputRouter(Sink sink) { this.sink = sink; }

    public void keyboard(String source, Set<Integer> keys) {
        Set<Integer> previous=keyboards.put(source, new TreeSet<>(keys));
        blockedKeys.retainAll(allKeys());
        if (ready && !keys.equals(previous)) sink.send(HidReports.KEYBOARD, HidReports.keyboard(visibleKeys()));
    }
    public void consumer(String source, Set<Integer> values) {
        Set<Integer> previous=consumers.put(source, new TreeSet<>(values));
        blockedConsumer.retainAll(allConsumers());
        if (ready && !values.equals(previous)) sendConsumer();
    }
    public void mouse(String source, Integer buttons, int x, int y, int wheel, int pan) {
        int previousButtons=allButtons() & ~blockedButtons;
        if (buttons != null) mice.put(source, buttons & 31);
        blockedButtons &= allButtons();
        if (!ready) return; // No offline queues, stale movement must never be replayed.
        int outputButtons = allButtons() & ~blockedButtons;
        if(outputButtons==previousButtons&&x==0&&y==0&&wheel==0&&pan==0)return;
        // Bound input from unusual descriptors to avoid unbounded packet bursts.
        x = clamp(x, -1048544, 1048544); y = clamp(y, -1048544, 1048544);
        wheel = clamp(wheel, -4064, 4064); pan = clamp(pan, -4064, 4064);
        do {
            int dx = clamp(x, -32767, 32767), dy = clamp(y, -32767, 32767);
            int dw = clamp(wheel, -127, 127), dp = clamp(pan, -127, 127);
            sink.send(HidReports.MOUSE, HidReports.mouse(outputButtons, dx, dy, dw, dp));
            x -= dx; y -= dy; wheel -= dw; pan -= dp;
        } while (x != 0 || y != 0 || wheel != 0 || pan != 0);
    }
    public void remove(String source) {
        boolean keyboardChanged = keyboards.remove(source) != null;
        boolean mouseChanged = mice.remove(source) != null;
        boolean consumerChanged = consumers.remove(source) != null;
        blockedKeys.retainAll(allKeys()); blockedConsumer.retainAll(allConsumers());
        blockedButtons &= allButtons();
        if (ready) {
            if (keyboardChanged) sink.send(HidReports.KEYBOARD, HidReports.keyboard(visibleKeys()));
            if (mouseChanged) sink.send(HidReports.MOUSE, HidReports.mouse(allButtons() & ~blockedButtons, 0, 0, 0, 0));
            if (consumerChanged) sendConsumer();
        }
    }
    public void removePrefix(String prefix) {
        Set<String> names = new HashSet<>(keyboards.keySet());
        names.addAll(mice.keySet()); names.addAll(consumers.keySet());
        for (String name : names) if (name.startsWith(prefix)) remove(name);
    }
    public void deactivate() {
        if (ready) neutral(); // The caller still points the sink at the OLD host here.
        ready = false;
        blockHeld();
    }
    public void activate() {
        blockHeld(); ready = true; neutral();
    }
    public boolean isReady() { return ready; }
    public byte[] snapshot(int reportId) {
        if(reportId==HidReports.KEYBOARD)return ready?HidReports.keyboard(visibleKeys()):new byte[8];
        if(reportId==HidReports.MOUSE)return HidReports.mouse(ready?(allButtons()&~blockedButtons):0,0,0,0,0);
        if(reportId==HidReports.CONSUMER) {
            Set<Integer> values=allConsumers();values.removeAll(blockedConsumer);
            return HidReports.consumer(ready&&!values.isEmpty()?values.iterator().next():0);
        }
        return null;
    }
    private void blockHeld() {
        blockedKeys.clear(); blockedKeys.addAll(allKeys());
        blockedConsumer.clear(); blockedConsumer.addAll(allConsumers());
        blockedButtons = allButtons();
    }
    private void neutral() {
        sink.send(HidReports.KEYBOARD, new byte[8]);
        sink.send(HidReports.MOUSE, new byte[7]);
        sink.send(HidReports.CONSUMER, new byte[2]);
    }
    private Set<Integer> allKeys() { return union(keyboards); }
    private Set<Integer> allConsumers() { return union(consumers); }
    private Set<Integer> visibleKeys() { Set<Integer> values = allKeys(); values.removeAll(blockedKeys); return values; }
    private static Set<Integer> union(Map<String, Set<Integer>> sources) {
        Set<Integer> values = new TreeSet<>(); for (Set<Integer> source : sources.values()) values.addAll(source); return values;
    }
    private int allButtons() { int value = 0; for (int buttons : mice.values()) value |= buttons; return value; }
    private void sendConsumer() {
        Set<Integer> values = allConsumers(); values.removeAll(blockedConsumer);
        sink.send(HidReports.CONSUMER, HidReports.consumer(values.isEmpty() ? 0 : values.iterator().next()));
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
