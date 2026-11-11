package com.qa.automation.client;

import com.qa.platform.common.Masking;
import com.qa.platform.model.Evidence;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rest Assured filter that hands a masked copy of the exact wire request/response to an {@link ExchangeRecorder}. */
final class CaptureFilter implements Filter {
  private static final int MAX_BODY = 20_000;
  private final ExchangeRecorder recorder;

  CaptureFilter(ExchangeRecorder recorder) { this.recorder = recorder; }

  @Override
  public Response filter(FilterableRequestSpecification req, FilterableResponseSpecification res, FilterContext ctx) {
    Map<String, String> reqHeaders = new LinkedHashMap<>();
    for (Header h : req.getHeaders()) reqHeaders.put(h.getName(), h.getValue());
    Object body = req.getBody();
    Evidence.HttpRequest captured = new Evidence.HttpRequest(req.getMethod(), req.getURI(), Masking.headers(reqHeaders),
        body == null ? null : Masking.text(cap(body.toString())));

    long start = System.nanoTime();
    Response r = ctx.next(req, res);
    long ms = (System.nanoTime() - start) / 1_000_000;

    Map<String, String> resHeaders = new LinkedHashMap<>();
    for (Header h : r.getHeaders()) resHeaders.put(h.getName(), h.getValue());
    recorder.record(captured, new Evidence.HttpResponse(r.statusCode(), Masking.headers(resHeaders),
        Masking.text(cap(r.asString())), ms));
    return r;
  }

  private static String cap(String s) { return s.length() > MAX_BODY ? s.substring(0, MAX_BODY) + "...[truncated]" : s; }
}
