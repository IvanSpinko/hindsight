package org.example.visiontrack.meeting;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/** Processes of the CUDA tools (whisper.cpp, llama.cpp), which find the CUDA libraries in {@code cudaDir} when they have no copy of their own. */
final class CudaTools {

    private CudaTools() {}

    /** The desktop app ships CUDA once (app\cuda) instead of next to every tool; null leaves the PATH as it is. */
    static ProcessBuilder process(List<String> command, Path cudaDir) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cudaDir != null) {
            builder.environment().merge("PATH", cudaDir.toString(), (path, dir) -> dir + File.pathSeparator + path);
        }
        return builder;
    }
}
