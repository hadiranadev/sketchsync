package client.ui;

import javax.sound.sampled.*;

/**
 * Generates and plays simple beep sounds programmatically.
 *
 * No external audio files are used — tones are synthesized as raw PCM samples.
 * Playback runs on a daemon thread so UI responsiveness is never affected.
 */
public final class SoundPlayer {

    private SoundPlayer() {}

    /** Short tick beep used during countdown warnings. */
    public static void playTick() {
        playTone(880, 80, 0.25f);  // 880 Hz, 80ms, low volume
    }

    /** Sharper, louder beep for the final urgent seconds. */
    public static void playUrgent() {
        playTone(1200, 120, 0.5f);
    }

    /** Pleasant confirmation tone for correct guesses. */
    public static void playCorrect() {
        playTone(1047, 200, 0.6f); // C6
    }

    /** Lower tone indicating round end or timeout. */
    public static void playRoundEnd() {
        playTone(440, 300, 0.5f);  // A4
    }

    /**
     * Synthesizes and plays a sine-wave tone on a background daemon thread.
     *
     * @param frequencyHz pitch of the tone
     * @param durationMs  duration in milliseconds
     * @param amplitude   volume (0.0–1.0)
     *
     * Implementation notes:
     *  - Generates 16‑bit PCM samples at 44.1 kHz.
     *  - Applies a simple linear fade‑out to avoid audible clicks.
     *  - Uses a daemon thread so sound playback never blocks the EDT.
     */
    private static void playTone(int frequencyHz, int durationMs, float amplitude) {
        Thread t = new Thread(() -> {
            try {
                float sampleRate = 44100f;
                int numSamples   = (int) (sampleRate * durationMs / 1000.0);
                byte[] data      = new byte[numSamples * 2]; // 16-bit mono

                for (int i = 0; i < numSamples; i++) {
                    double angle  = 2.0 * Math.PI * i * frequencyHz / sampleRate;
                    double fade   = 1.0 - (double) i / numSamples; // prevents click at end
                    short sample  = (short) (amplitude * fade * Short.MAX_VALUE * Math.sin(angle));

                    data[2 * i]     = (byte) (sample & 0xFF);
                    data[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
                }

                AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                    line.open(format);
                    line.start();
                    line.write(data, 0, data.length);
                    line.drain();
                }
            } catch (Exception e) {
                // Sound is non-critical; failures are silently ignored.
            }
        });

        t.setDaemon(true); // ensures thread won't block JVM shutdown
        t.start();
    }
}