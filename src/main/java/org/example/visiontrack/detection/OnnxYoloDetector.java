package org.example.visiontrack.detection;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.example.visiontrack.GpuRequiredException;
import org.example.visiontrack.config.VisionTrackProperties;
import org.example.visiontrack.detection.ImagePreprocessor.Letterbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** YOLO on ONNX Runtime with CUDA; there is no CPU fallback, see {@link GpuRequiredException}. */
public final class OnnxYoloDetector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxYoloDetector.class);
    private static final int MAX_DETECTIONS_PER_FRAME = 300;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final OrtSession.SessionOptions options;
    private final String modelName;
    private final String inputName;
    private final int inputWidth;
    private final int inputHeight;
    private final YoloOutputDecoder.Layout layout;
    private final List<String> labels;
    private final float confidenceThreshold;
    private final float nmsThreshold;

    private final ThreadLocal<float[]> inputBuffers;

    private OnnxYoloDetector(OrtEnvironment env, OrtSession session, OrtSession.SessionOptions options,
                             String modelName,
                             Path labelsFile, float confidenceThreshold, float nmsThreshold) throws OrtException {
        this.env = env;
        this.session = session;
        this.options = options;
        this.modelName = modelName;
        this.confidenceThreshold = confidenceThreshold;
        this.nmsThreshold = nmsThreshold;

        Map.Entry<String, NodeInfo> input = session.getInputInfo().entrySet().iterator().next();
        this.inputName = input.getKey();
        long[] inputShape = ((TensorInfo) input.getValue().getInfo()).getShape();
        if (inputShape.length != 4 || inputShape[2] <= 0 || inputShape[3] <= 0) {
            throw new IllegalStateException("Unsupported model input shape " + Arrays.toString(inputShape)
                    + "; export the model with a fixed image size (dynamic=False)");
        }
        this.inputHeight = (int) inputShape[2];
        this.inputWidth = (int) inputShape[3];

        long[] outputShape = ((TensorInfo) session.getOutputInfo().values().iterator().next().getInfo()).getShape();
        this.layout = YoloOutputDecoder.Layout.fromShape(outputShape);
        this.labels = YoloOutputDecoder.resolveLabels(labelsFile, layout.numClasses());

        int bufferSize = 3 * inputWidth * inputHeight;
        this.inputBuffers = ThreadLocal.withInitial(() -> new float[bufferSize]);
    }

    public static OnnxYoloDetector load(VisionTrackProperties.Model config) throws OrtException {
        Path modelPath = config.path();
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalStateException("Model file not found: " + modelPath.toAbsolutePath()
                    + " (set visiontrack.model.path)");
        }
        preloadNativeLibraries(config.nativeDir(), config.nativePreload());

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        OrtSession session;
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.addCUDA(0);
            session = env.createSession(modelPath.toString(), options);
        } catch (OrtException | RuntimeException e) {
            options.close();
            throw new GpuRequiredException("Object detection", e);
        }
        OnnxYoloDetector detector = new OnnxYoloDetector(env, session, options, modelPath.getFileName().toString(),
                config.classesPath(), config.confidenceThreshold(), config.nmsThreshold());
        log.info("Loaded {}", detector.description());
        return detector;
    }

    private static void preloadNativeLibraries(Path nativeDir, List<Path> libraries) {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) return;

        List<Path> pending = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (nativeDir != null && Files.isDirectory(nativeDir)) {
            try (Stream<Path> files = Files.list(nativeDir)) {
                files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".dll")).sorted().forEach(pending::add);
            } catch (IOException e) {
                log.warn("Cannot list native libraries in {}: {}", nativeDir, e.getMessage());
            }
        }
        pending.forEach(f -> names.add(f.getFileName().toString().toLowerCase()));
        for (Path library : libraries) {
            if (Files.exists(library) && names.add(library.getFileName().toString().toLowerCase())) pending.add(library);
        }

        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Iterator<Path> it = pending.iterator(); it.hasNext(); ) {
                Path library = it.next();
                try {
                    System.load(library.toAbsolutePath().toString());
                    log.debug("Pre-loaded {}", library);
                    it.remove();
                    progress = true;
                } catch (UnsatisfiedLinkError e) {
                    log.debug("Retrying {} later: {}", library, e.getMessage());
                }
            }
        }
        pending.forEach(library -> log.warn("Could not pre-load {}", library));
    }

    public List<Detection> detect(RgbImage image) {
        Letterbox letterbox = Letterbox.fit(image.width(), image.height(), inputWidth, inputHeight);
        float[] input = inputBuffers.get();
        ImagePreprocessor.toChwTensor(image, letterbox, input);

        long[] shape = {1, 3, inputHeight, inputWidth};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {

            FloatBuffer output = ((OnnxTensor) result.get(0)).getFloatBuffer();
            List<Detection> candidates = YoloOutputDecoder.decode(output, layout, letterbox,
                    image.width(), image.height(), confidenceThreshold, labels);
            return YoloOutputDecoder.nonMaxSuppression(candidates, nmsThreshold, MAX_DETECTIONS_PER_FRAME);
        } catch (OrtException e) {
            throw new IllegalStateException("Inference failed", e);
        }
    }

    public String description() {
        return "%s (%dx%d, %d classes) on CUDA".formatted(modelName, inputWidth, inputHeight, labels.size());
    }

    @Override
    public void close() throws OrtException {
        session.close();
        options.close();
    }
}
