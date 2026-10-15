package org.example.visiontrack.meeting;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A 16-bit PCM mono WAV file held in memory (an hour at 16 kHz is about 115 MB), from which pieces are
 * cut as standalone WAV files for Whisper without running ffmpeg for each one.
 */
final class PcmWav {

    private final int sampleRate;
    private final byte[] samples;

    private PcmWav(int sampleRate, byte[] samples) {
        this.sampleRate = sampleRate;
        this.samples = samples;
    }

    static PcmWav read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    static PcmWav parse(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {   // "RIFF", "WAVE"
            throw new IOException("Not a WAV file");
        }
        int sampleRate = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos);
            long size = Integer.toUnsignedLong(b.getInt(pos + 4));
            int body = pos + 8;
            if (id == 0x20746d66) {                                   // "fmt "
                int format = b.getShort(body) & 0xFFFF, channels = b.getShort(body + 2), bits = b.getShort(body + 14);
                if (format != 1 || channels != 1 || bits != 16) {
                    throw new IOException("Expected 16-bit PCM mono, got format " + format + ", " + channels + " channels, " + bits + " bits");
                }
                sampleRate = b.getInt(body + 4);
            } else if (id == 0x61746164) {                            // "data"
                if (sampleRate == 0) throw new IOException("WAV data before format");
                // A size of 0 or past the end means the writer was interrupted: take what is there
                int end = size == 0 || body + size > bytes.length ? bytes.length : (int) (body + size);
                byte[] samples = new byte[(end - body) & ~1];
                System.arraycopy(bytes, body, samples, 0, samples.length);
                return new PcmWav(sampleRate, samples);
            }
            pos = (int) (body + size + (size & 1));
        }
        throw new IOException("WAV file has no audio data");
    }

    int sampleRate() {
        return sampleRate;
    }

    double durationSeconds() {
        return samples.length / 2.0 / sampleRate;
    }

    /** The audio between {@code start} and {@code end} seconds as a complete WAV file. */
    byte[] slice(double start, double end) {
        int from = Math.max(0, (int) (start * sampleRate)) * 2;
        int to = Math.min(samples.length, (int) Math.ceil(end * sampleRate) * 2);
        int length = Math.max(0, to - from);
        ByteBuffer out = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46464952).putInt(36 + length).putInt(0x45564157);
        out.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        out.putInt(0x61746164).putInt(length).put(samples, from, length);
        return out.array();
    }
}
