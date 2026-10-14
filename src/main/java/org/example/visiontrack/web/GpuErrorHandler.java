package org.example.visiontrack.web;

import org.example.visiontrack.GpuRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Answers 503 with the "NVIDIA GPU required" message as the problem detail, which the page shows as is. */
@RestControllerAdvice
public class GpuErrorHandler {

    @ExceptionHandler(GpuRequiredException.class)
    public ProblemDetail gpuRequired(GpuRequiredException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("NVIDIA GPU required");
        return problem;
    }
}
