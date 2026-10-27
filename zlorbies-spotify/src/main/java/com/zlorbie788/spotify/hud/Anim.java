package com.zlorbie788.spotify.hud;

import com.zlorbie788.spotify.config.ConfigIo;
import com.zlorbie788.spotify.util.Clock;
import com.zlorbie788.spotify.util.Ease;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Smooth values for the interface: a button that fades to its hover colour, a switch knob that slides, a tab line that
 * glides. Every value belongs to an owner (the widget) and a name; it chases its target at a given speed and does not
 * depend on the frame rate. With "Buttons and menus animate" off every value jumps straight to its target.
 */
public final class Anim {
    private static final Map<Object, Map<String, float[]>> VALUES = new WeakHashMap<>();
    private static long lastNs;
    private static float dt = 0.016f;

    private Anim() {}

    /** Call once per frame (the player's screens do). Works out how long the last frame took. */
    public static void frame() {
        long now = Clock.nanos();
        if (lastNs != 0L) {
            float d = (now - lastNs) / 1_000_000_000f;
            if (d > 0f) dt = Math.min(0.1f, Math.max(0.001f, d));
        }
        lastNs = now;
    }

    public static float dt() {
        return dt;
    }

    public static boolean enabled() {
        try {
            return ConfigIo.get().uiAnimations && ConfigIo.get().motion();
        } catch (Throwable t) {
            return true;
        }
    }

    private static float[] slot(Object owner, String name, float initial) {
        Map<String, float[]> map = VALUES.computeIfAbsent(owner, k -> new HashMap<>());
        return map.computeIfAbsent(name, k -> new float[]{initial, 0f});
    }

    /** Moves the value of (owner, name) towards {@code target}; {@code rate} is how quickly (about 1/seconds). */
    public static float smooth(Object owner, String name, float target, float rate) {
        float[] v = slot(owner, name, target);
        if (!enabled()) {
            v[0] = target;
            return target;
        }
        v[0] = Ease.approach(v[0], target, rate, dt);
        if (Math.abs(v[0] - target) < 0.0015f) v[0] = target;
        return v[0];
    }

    /** Like {@link #smooth} but the value starts at {@code initial} the first time (so it can animate in). */
    public static float smooth(Object owner, String name, float initial, float target, float rate) {
        slot(owner, name, initial);
        return smooth(owner, name, target, rate);
    }

    /** A spring: overshoots a little and settles. {@code stiffness} ~ 180, {@code damping} ~ 14 feels lively. */
    public static float spring(Object owner, String name, float target, float stiffness, float damping) {
        float[] v = slot(owner, name, target);
        if (!enabled()) {
            v[0] = target;
            v[1] = 0f;
            return target;
        }
        int steps = Math.max(1, (int) Math.ceil(dt / 0.008f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float force = (target - v[0]) * stiffness - v[1] * damping;
            v[1] += force * h;
            v[0] += v[1] * h;
        }
        if (Math.abs(v[0] - target) < 0.0008f && Math.abs(v[1]) < 0.01f) {
            v[0] = target;
            v[1] = 0f;
        }
        return v[0];
    }

    /** Forgets a value (so it starts from its target next time). */
    public static void reset(Object owner) {
        VALUES.remove(owner);
    }

    /** Current value without moving it. */
    public static float peek(Object owner, String name, float fallback) {
        Map<String, float[]> map = VALUES.get(owner);
        if (map == null) return fallback;
        float[] v = map.get(name);
        return v == null ? fallback : v[0];
    }
}
