package org.example.visiontrack;

/**
 * Thrown when CUDA is not available: heavy work runs on an NVIDIA GPU only.
 * The message is shown to the user as is.
 */
public class GpuRequiredException extends RuntimeException {

    public GpuRequiredException(String feature, Throwable cause) {
        super(message(feature), cause);
    }

    public GpuRequiredException(String feature) {
        this(feature, null);
    }

    /** "Speech recognition needs an NVIDIA graphics card with CUDA (driver 528 or newer). ..." */
    public static String message(String feature) {
        return feature + " needs an NVIDIA graphics card with CUDA (driver 528 or newer). No compatible GPU was found."
                + " If the computer has one, update the driver from nvidia.com.";
    }
}
