package com.emie.crm.common.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<?> business(ApiException e) {
    return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<?> database(DataAccessException e) {
    log.error("Database operation failed: {}", e.getClass().getSimpleName());
    return ResponseEntity.badRequest().body(Map.of("error", "数据库操作失败，请检查数据后重试"));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> invalid() {
    return ResponseEntity.badRequest().body(Map.of("error", "请求JSON格式无效"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> unexpected(Exception e) {
    log.error("Request failed", e);
    return ResponseEntity.internalServerError().body(Map.of("error", "服务处理失败，请稍后重试"));
  }
}
