package com.example.datalake.mrpot;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Disabled context load smoke test; kept for future enablement once external
 * integrations can be spun up reliably in CI.
 */
@SpringBootTest
@Disabled("Skip expensive context startup until external services are available")
class MrPotApplicationTests {

  @Test
  void contextLoads() {
    // intentionally skipped
  }
}
