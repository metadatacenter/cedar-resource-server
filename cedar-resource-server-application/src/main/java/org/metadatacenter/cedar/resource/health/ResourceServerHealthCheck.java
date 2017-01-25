package org.metadatacenter.cedar.resource.health;

import com.codahale.metrics.health.HealthCheck;

public class ResourceServerHealthCheck extends HealthCheck {

  public ResourceServerHealthCheck() {
  }

  @Override
  protected Result check() throws Exception {
    if (2 * 2 == 5) {
      return Result.unhealthy("Unhealthy, because 2 * 2 == 5");
    }
    return Result.healthy();
  }
}