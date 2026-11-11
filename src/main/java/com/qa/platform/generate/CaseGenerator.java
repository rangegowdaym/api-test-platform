package com.qa.platform.generate;

import com.qa.platform.generate.rules.AuthRule;
import com.qa.platform.generate.rules.BoundaryRule;
import com.qa.platform.generate.rules.EnumRule;
import com.qa.platform.generate.rules.MissingRequiredRule;
import com.qa.platform.generate.rules.NotFoundRule;
import com.qa.platform.generate.rules.PositiveRule;
import com.qa.platform.generate.rules.TypeMismatchRule;
import com.qa.platform.model.ApiModel;
import com.qa.platform.model.Endpoint;
import com.qa.platform.model.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CaseGenerator {

  public record Options(Set<String> includeTags, Set<String> excludeOps, Set<String> categories) {
    public static Options all() { return new Options(Set.of(), Set.of(), Set.of()); }
  }

  private final List<CaseRule> rules = List.of(
      new PositiveRule(), new MissingRequiredRule(), new TypeMismatchRule(), new BoundaryRule(),
      new EnumRule(), new AuthRule(), new NotFoundRule());

  /** endpoint operationId -> its cases, in spec order. */
  public Map<Endpoint, List<TestCase>> generate(ApiModel model, Options o) {
    CaseFactory factory = new CaseFactory(new ValueGenerator(), model.specName(), model.specHash());
    Map<Endpoint, List<TestCase>> out = new LinkedHashMap<>();
    for (Endpoint e : model.endpoints()) {
      if (!o.includeTags().isEmpty() && e.tags().stream().noneMatch(o.includeTags()::contains)) continue;
      if (o.excludeOps().contains(e.operationId())) continue;
      List<TestCase> cases = new ArrayList<>();
      for (CaseRule r : rules) {
        if (!o.categories().isEmpty() && !o.categories().contains(r.category())) continue;
        cases.addAll(r.apply(e, factory));
      }
      out.put(e, dedupe(cases));
    }
    return out;
  }

  private static List<TestCase> dedupe(List<TestCase> cases) {
    Map<String, TestCase> byId = new LinkedHashMap<>();
    for (TestCase c : cases) byId.putIfAbsent(c.id(), c);
    return new ArrayList<>(byId.values());
  }
}
