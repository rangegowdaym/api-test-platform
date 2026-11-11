package com.qa.automation.client;

/**
 * Thrown when a call could not be made or its response could not be read. The original exception stays as the
 * cause and its type is in the message, so NetworkRetry and the RCA EnvironmentRule still recognise
 * ConnectException, SocketTimeoutException and friends.
 */
public class ApiClientException extends RuntimeException {
  public ApiClientException(String message, Throwable cause) { super(message, cause); }
}
