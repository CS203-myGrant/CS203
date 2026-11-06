package mygrant.rules;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Turns on @Async for the rule backfill and reassessment, which must not hold up startup or an
 * import. No other code used @Async before CS203-86. The work runs on Spring Boot's
 * applicationTaskExecutor: declaring another Executor bean would make Boot drop that one.
 */
@Configuration
@EnableAsync
class RulesAsyncConfig {
}
