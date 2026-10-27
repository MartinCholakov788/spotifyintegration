package com.zlorbie788.spotify.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/** The real sound card, through Java Sound. A device name picks a specific output (headphones...); empty means the system default. */
public final class JavaSoundSink implements AudioSink {
    private final String device;
    private SourceDataLine line;
    private int bufferBytes;

    public JavaSoundSink(String device) {
        this.device = device == null ? "" : device.trim();
    }

    /** Names of the outputs that can play CD-quality sound (for the settings list). */
    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        try {
            AudioFormat fmt = new AudioFormat(Engine.RATE, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info) && !names.contains(mi.getName())) names.add(mi.getName());
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return names;
    }

    @Override
    public void open(int rate, int bufferFrames) throws Exception {
        AudioFormat fmt = new AudioFormat(rate, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt, bufferFrames * 4);
        SourceDataLine l = null;
        if (!device.isEmpty()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (!mi.getName().equals(device)) continue;
                try {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (m.isLineSupported(info)) {
                        l = (SourceDataLine) m.getLine(info);
                        break;
                    }
                } catch (Throwable ignored) { }
            }
        }
        if (l == null) l = (SourceDataLine) AudioSystem.getLine(info);
        l.open(fmt, bufferFrames * 4);
        bufferBytes = l.getBufferSize();
        line = l;
    }

    @Override
    public int write(byte[] data, int off, int len) {
        return line.write(data, off, len);
    }

    @Override
    public int queuedFrames() {
        SourceDataLine l = line;
        return l == null ? 0 : Math.max(0, (bufferBytes - l.available()) / 4);
    }

    @Override public void start() { if (line != null) line.start(); }
    @Override public void stop() { if (line != null) line.stop(); }
    @Override public void flush() { if (line != null) line.flush(); }

    @Override
    public void close() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Throwable ignored) { }
        }
    }
}
