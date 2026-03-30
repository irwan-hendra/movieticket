package com.sevnis.movieticket.shared;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {

    Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
        .collect(Collectors.toMap(
            FieldError::getField,
            fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
            (first, second) -> first
        ));

    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "One or more fields failed validation"
    );
    pd.setTitle("Validation Failed");
    pd.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
    pd.setProperty("timestamp", Instant.now());
    pd.setProperty("fieldErrors", fieldErrors);

    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception at {}", request.getRequestURI(), ex);

    ProblemDetail pd = ProblemDetail
        .forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    pd.setTitle("Internal Server Error");
    pd.setInstance(URI.create(request.getRequestURI()));
    pd.setProperty("timestamp", Instant.now());

    return pd;
  }
}
