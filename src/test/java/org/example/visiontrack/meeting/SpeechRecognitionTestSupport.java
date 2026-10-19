package org.example.visiontrack.meeting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Builds 16-bit mono WAV files for speech recognition tests. */
final class SpeechRecognitionTestSupport {

    private SpeechRecognitionTestSupport() {}

    static byte[] wav(int sampleRate, short[] samples) {
        int length = samples.length * 2;
        ByteBuffer out = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46464952).putInt(36 + length).putInt(0x45564157);
        out.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        out.putInt(0x61746164).putInt(length);
        for (short s : samples) out.putShort(s);
        return out.array();
    }
}
