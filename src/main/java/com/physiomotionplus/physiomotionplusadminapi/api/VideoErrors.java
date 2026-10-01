package com.physiomotionplus.physiomotionplusadminapi.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {VideoController.class, ModuleController.class})
public class VideoErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "error", exception.getStatusCode().toString(),
                "message", exception.getReason() == null ? "Video request failed" : exception.getReason()));
    }
}
