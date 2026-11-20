package dev.dineshrama.tracelens;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ProblemHandler {
  @ExceptionHandler(Problem.class) public ResponseEntity<?> known(Problem e) {
    return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));
  }
  @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) public ResponseEntity<?> badRequest(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","The request body or identifier is invalid."));
  }
  @ExceptionHandler({HttpMediaTypeNotSupportedException.class,HttpRequestMethodNotSupportedException.class,NoResourceFoundException.class}) public ResponseEntity<?> protocol(Exception e) {
    var error=(ErrorResponse)e;return ResponseEntity.status(error.getStatusCode()).body(Map.of("code","HTTP_"+error.getStatusCode().value(),"message","Unsupported route, method, or content type."));
  }
  @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e) {
    LoggerFactory.getLogger(getClass()).error("Request failed: {}",e.getClass().getSimpleName());
    return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","The request could not be completed."));
  }
}
