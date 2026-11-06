package com.physiomotionplus.physiomotionplusadminapi.care;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = CareController.class)
public class CareErrors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> expected(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(Map.of("message", e.getReason() == null ? "Request failed" : e.getReason()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unavailable(Exception e) {
    if (e instanceof org.springframework.http.converter.HttpMessageNotReadableException
        || e
            instanceof
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException)
      return ResponseEntity.badRequest().body(Map.of("message", "Invalid request."));
    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    org.slf4j.LoggerFactory.getLogger(getClass()).error("Care request failed", e);
    return ResponseEntity.status(503)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(Map.of("message", "Private care is temporarily unavailable. Please retry."));
  }
}
