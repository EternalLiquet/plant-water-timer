package com.eternalliquet.plantcare.plants;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PlantController.class)
class PlantApiExceptionHandler {

  @ExceptionHandler(PlantNotFoundException.class)
  ProblemDetail plantNotFound() {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The plant was not found.");
    problem.setTitle("Plant not found");
    return problem;
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  ProblemDetail invalidRequest(Exception exception) {
    var problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "The request contains missing or invalid values.");
    problem.setTitle("Invalid request");
    return problem;
  }
}
