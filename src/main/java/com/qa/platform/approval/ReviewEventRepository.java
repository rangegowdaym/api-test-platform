package com.qa.platform.approval;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewEventRepository extends JpaRepository<ReviewEvent, Long> {
  List<ReviewEvent> findByCaseIdOrderByAtDesc(String caseId);
  List<ReviewEvent> findTop50ByOrderByAtDesc();
}
