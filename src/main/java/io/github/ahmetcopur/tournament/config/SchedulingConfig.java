package io.github.ahmetcopur.tournament.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Two schedulers instead of Spring Boot's single shared thread.
 *
 * <ul>
 *   <li>{@code taskScheduler}: the default for every {@code @Scheduled} job (leaderboard repair,
 *       search reconcile, metrics). Two threads, so one slow job doesn't hold up the others.</li>
 *   <li>{@code settlementScheduler}: tournament creation and settlement only. Its own thread, so a
 *       long rebuild or reconcile can never delay 20:00.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "game.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler(2, "maintenance-");
    }

    @Bean
    public ThreadPoolTaskScheduler settlementScheduler() {
        return scheduler(1, "settlement-");
    }

    private static ThreadPoolTaskScheduler scheduler(int threads, String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threads);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
