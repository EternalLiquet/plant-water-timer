package com.eternalliquet.plantcare.garden;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = GardenController.class)
class GardenErrors {
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "message",
                e.getMessage() == null ? "Please check what you entered." : e.getMessage()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> unreadable(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Please check the name, date and number of days."));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(
            Map.of(
                "message", e.getReason() == null ? "That action is unavailable." : e.getReason()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> size(Exception e) {
    return ResponseEntity.status(413)
        .body(Map.of("message", "Choose a JPG or PNG photo smaller than 5 MB."));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> failure(Exception e) {
    return ResponseEntity.status(500)
        .body(Map.of("message", "That didn't save. Please try again."));
  }
}
