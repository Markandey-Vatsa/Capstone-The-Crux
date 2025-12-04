package com.perplexity.newsaggregator.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling only when app.scheduling.enabled=true (default true if missing).
 * Tests can disable by setting app.scheduling.enabled=false.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(value = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
