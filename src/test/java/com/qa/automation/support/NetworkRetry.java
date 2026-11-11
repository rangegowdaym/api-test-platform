package com.qa.automation.support;

import io.cucumber.testng.PickleWrapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.testng.IAnnotationTransformer;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.ITestAnnotation;

/**
 * Retries a scenario ONCE, and only for network errors (never for assertion failures: those are real results).
 * Registered as a listener in testng.xml. Retries are recorded and shown in evidence/RCA (flaky detection).
 */
public class NetworkRetry implements IRetryAnalyzer, IAnnotationTransformer {
  private static final int MAX = Integer.getInteger("retry.network", 1);
  private int count;

  @Override
  public boolean retry(ITestResult result) {
    if (count >= MAX || !isNetwork(result.getThrowable())) return false;
    count++;
    Object[] params = result.getParameters();
    if (params.length > 0 && params[0] instanceof PickleWrapper pw) ErrorRegistry.retried(pw.getPickle().getName());
    return true;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public void transform(ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
    annotation.setRetryAnalyzer(NetworkRetry.class);
  }

  private static boolean isNetwork(Throwable t) {
    while (t != null) {
      if (t instanceof ConnectException || t instanceof SocketTimeoutException || t instanceof UnknownHostException) return true;
      t = t.getCause();
    }
    return false;
  }
}
