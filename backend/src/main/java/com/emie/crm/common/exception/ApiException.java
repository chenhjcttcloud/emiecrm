package com.emie.crm.common.exception;

public class ApiException extends RuntimeException {
  private final int status;

  public ApiException(String message) {
    this(400, message);
  }

  public ApiException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new ApiException(message);
  }
}
