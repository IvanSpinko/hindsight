package org.example.visiontrack.meeting;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.example.visiontrack.GpuRequiredException;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NVIDIA Parakeet TDT 0.6B v3 in ONNX Runtime on CUDA: mel features, the encoder (one frame per 80 ms),
 * then greedy token-and-duration decoding.
 */
final class ParakeetModel implements AutoCloseable {

    static final String NAME = "parakeet-tdt-0.6b-v3";
    static final String PREPROCESSOR = "nemo128.onnx";
    static final String ENCODER = "encoder-model.onnx";
    static final String DECODER = "decoder_joint-model.onnx";
    static final String VOCABULARY = "vocab.txt";
    /** Every file the model needs; the encoder weights live next to it in a separate data file. */
    static final List<String> FILES = List.of(PREPROCESSOR, ENCODER, ENCODER + ".data", DECODER, VOCABULARY);

    /** 10 ms feature step times the encoder's subsampling factor of 8. */
    static final double FRAME_SECONDS = 0.08;
    /** NeMo's limit of tokens emitted on one frame before the decoder is forced to move on. */
    static final int MAX_TOKENS_PER_FRAME = 10;
    private static final String WORD_START = "\u2581";

    /** A token and the encoder frame it was emitted on. */
    record Emission(int token, int frame) {}

    /** What the decoder-joint network says for one frame: the likeliest token, the frames to skip, the new state. */
    record Prediction<S>(int token, int duration, S state) {}

    @FunctionalInterface
    interface Joint<S> {
        Prediction<S> predict(int frame, int previousToken, S state) throws OrtException;
    }

    private final OrtEnvironment env;
    private final OrtSession.SessionOptions options;
    private final OrtSession preprocessor;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final List<String> vocabulary;
    private final int blank;
    private final long[] stateShape1;
    private final long[] stateShape2;

    private ParakeetModel(OrtEnvironment env, OrtSession.SessionOptions options, OrtSession preprocessor,
                          OrtSession encoder, OrtSession decoder, List<String> vocabulary) throws OrtException {
        this.env = env;
        this.options = options;
        this.preprocessor = preprocessor;
        this.encoder = encoder;
        this.decoder = decoder;
        this.vocabulary = vocabulary;
        this.blank = vocabulary.indexOf("<blk>");
        if (blank < 0) throw new IllegalStateException("The vocabulary has no <blk> token");
        Map<String, NodeInfo> inputs = decoder.getInputInfo();
        this.stateShape1 = stateShape(inputs.get("input_states_1"));
        this.stateShape2 = stateShape(inputs.get("input_states_2"));
    }

    static boolean isInstalled(Path directory) {
        return FILES.stream().allMatch(f -> Files.isRegularFile(directory.resolve(f)));
    }

    /** Loads the three networks on CUDA; without a usable GPU this is a {@link GpuRequiredException}. */
    static ParakeetModel load(Path directory) throws IOException {
        List<String> vocabulary = readVocabulary(directory.resolve(VOCABULARY));
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        List<OrtSession> opened = new ArrayList<>();
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.addCUDA(0);
            for (String file : List.of(PREPROCESSOR, ENCODER, DECODER)) {
                opened.add(env.createSession(directory.resolve(file).toString(), options));
            }
            return new ParakeetModel(env, options, opened.get(0), opened.get(1), opened.get(2), vocabulary);
        } catch (OrtException | RuntimeException e) {
            for (OrtSession session : opened) {
                try {
                    session.close();
                } catch (OrtException ignored) {
                    // already failing
                }
            }
            options.close();
            throw new GpuRequiredException("Speech recognition", e);
        }
    }

    /** "id token" per line, as written by the export; the line number is not trusted, the id is. */
    static List<String> readVocabulary(Path file) throws IOException {
        Map<Integer, String> byId = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int space = line.lastIndexOf(' ');
            if (space <= 0) continue;
            byId.put(Integer.parseInt(line.substring(space + 1).strip()), line.substring(0, space));
        }
        List<String> vocabulary = new ArrayList<>(byId.size());
        for (int id = 0; id < byId.size(); id++) {
            String token = byId.get(id);
            if (token == null) throw new IOException("The vocabulary has no token " + id + ": " + file);
            vocabulary.add(token);
        }
        return vocabulary;
    }

    /** Recognises one piece of 16 kHz mono audio (samples in -1..1); tokens come with their frame. */
    List<Emission> recognize(float[] samples) throws OrtException {
        if (samples.length == 0) return List.of();
        float[] features;
        long[] featuresShape;
        long featuresLength;
        try (OnnxTensor waveforms = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), new long[]{1, samples.length});
             OnnxTensor lengths = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{samples.length}), new long[]{1});
             OrtSession.Result result = preprocessor.run(Map.of("waveforms", waveforms, "waveforms_lens", lengths))) {
            OnnxTensor out = (OnnxTensor) result.get("features").orElseThrow();
            features = floats(out);
            featuresShape = out.getInfo().getShape();
            featuresLength = ((OnnxTensor) result.get("features_lens").orElseThrow()).getLongBuffer().get(0);
        }

        float[] encoded;
        int dimension;
        int frames;
        try (OnnxTensor signal = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), featuresShape);
             OnnxTensor length = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{featuresLength}), new long[]{1});
             OrtSession.Result result = encoder.run(Map.of("audio_signal", signal, "length", length))) {
            OnnxTensor out = (OnnxTensor) result.get("outputs").orElseThrow();
            long[] shape = out.getInfo().getShape();     // 1 x dimension x frames
            encoded = floats(out);
            dimension = (int) shape[1];
            int available = (int) shape[2];
            long valid = ((OnnxTensor) result.get("encoded_lengths").orElseThrow()).getLongBuffer().get(0);
            frames = (int) Math.min(available, valid);
            return greedy(frames, blank, new float[][]{new float[size(stateShape1)], new float[size(stateShape2)]},
                    (frame, previous, state) -> predict(column(encoded, dimension, available, frame), previous, state));
        }
    }

    /** Greedy TDT decoding: each step picks a token and how many frames to skip. */
    static <S> List<Emission> greedy(int frames, int blank, S initial, Joint<S> joint) throws OrtException {
        List<Emission> emitted = new ArrayList<>();
        S state = initial;
        int previous = blank;
        int frame = 0;
        int onFrame = 0;
        while (frame < frames) {
            Prediction<S> p = joint.predict(frame, previous, state);
            if (p.token() != blank) {
                state = p.state();
                previous = p.token();
                emitted.add(new Emission(p.token(), frame));
                onFrame++;
            }
            if (p.duration() > 0) {
                frame += p.duration();
                onFrame = 0;
            } else if (p.token() == blank || onFrame == MAX_TOKENS_PER_FRAME) {
                frame++;
                onFrame = 0;
            }
        }
        return emitted;
    }

    private Prediction<float[][]> predict(float[] encoderFrame, int previous, float[][] state) throws OrtException {
        try (OnnxTensor frame = OnnxTensor.createTensor(env, FloatBuffer.wrap(encoderFrame), new long[]{1, encoderFrame.length, 1});
             OnnxTensor targets = OnnxTensor.createTensor(env, IntBuffer.wrap(new int[]{previous}), new long[]{1, 1});
             OnnxTensor targetLength = OnnxTensor.createTensor(env, IntBuffer.wrap(new int[]{1}), new long[]{1});
             OnnxTensor states1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(state[0]), stateShape1);
             OnnxTensor states2 = OnnxTensor.createTensor(env, FloatBuffer.wrap(state[1]), stateShape2);
             OrtSession.Result result = decoder.run(Map.of("encoder_outputs", frame, "targets", targets,
                     "target_length", targetLength, "input_states_1", states1, "input_states_2", states2))) {
            float[] logits = floats((OnnxTensor) result.get("outputs").orElseThrow());
            int vocabularySize = vocabulary.size();
            int token = argmax(logits, 0, vocabularySize);
            int duration = argmax(logits, vocabularySize, logits.length) - vocabularySize;
            float[][] next = {floats((OnnxTensor) result.get("output_states_1").orElseThrow()),
                    floats((OnnxTensor) result.get("output_states_2").orElseThrow())};
            return new Prediction<>(token, duration, next);
        }
    }

    /** The token text with SentencePiece's word marker turned into a space. */
    String text(int token) {
        return vocabulary.get(token).replace(WORD_START, " ");
    }

    private static float[] column(float[] encoded, int dimension, int frames, int frame) {
        float[] column = new float[dimension];
        for (int d = 0; d < dimension; d++) column[d] = encoded[d * frames + frame];
        return column;
    }

    static int argmax(float[] values, int from, int to) {
        int best = from;
        for (int i = from + 1; i < to; i++) {
            if (values[i] > values[best]) best = i;
        }
        return best;
    }

    private static float[] floats(OnnxTensor tensor) {
        FloatBuffer buffer = tensor.getFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    /** The decoder's LSTM state, layers x batch x size, with the batch fixed to one. */
    private static long[] stateShape(NodeInfo info) {
        long[] shape = ((TensorInfo) info.getInfo()).getShape().clone();
        shape[1] = 1;
        return shape;
    }

    private static int size(long[] shape) {
        long size = 1;
        for (long s : shape) size *= s;
        return (int) size;
    }

    @Override
    public void close() {
        for (OrtSession session : List.of(decoder, encoder, preprocessor)) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // nothing left to free
            }
        }
        options.close();
    }
}
