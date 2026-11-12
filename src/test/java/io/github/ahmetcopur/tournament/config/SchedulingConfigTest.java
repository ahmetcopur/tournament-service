package io.github.ahmetcopur.tournament.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetcopur.tournament.tournament.TournamentJobs;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Review finding: every scheduled job shared Spring Boot's one scheduler thread, so a long
 * maintenance job could delay 20:00 settlement. Settlement now has its own thread.
 */
@SpringJUnitConfig({SchedulingConfig.class, SchedulingConfigTest.Jobs.class})
class SchedulingConfigTest {

    @Component
    static class Jobs {
        final CountDownLatch slowJobStarted = new CountDownLatch(1);
        final AtomicInteger settlementRuns = new AtomicInteger();
        final Set<String> settlementThreads = ConcurrentHashMap.newKeySet();

        /** A maintenance job that hogs its thread, like a huge rebuild. */
        @Scheduled(fixedDelay = 60_000)
        void slowMaintenance() throws InterruptedException {
            slowJobStarted.countDown();
            Thread.sleep(3_000);
        }

        @Scheduled(fixedDelay = 50, scheduler = "settlementScheduler")
        void settle() {
            settlementRuns.incrementAndGet();
            settlementThreads.add(Thread.currentThread().getName());
        }
    }

    @Autowired
    private Jobs jobs;

    @Test
    void settlementKeepsRunningWhileAMaintenanceJobIsStuck() throws Exception {
        assertThat(jobs.slowJobStarted.await(5, TimeUnit.SECONDS)).isTrue();
        int before = jobs.settlementRuns.get();
        Thread.sleep(1_000); // the maintenance job is still sleeping for another ~2 s

        assertThat(jobs.settlementRuns.get() - before).as("settlement runs during the stuck job").isGreaterThanOrEqualTo(5);
        assertThat(jobs.settlementThreads).allSatisfy(name -> assertThat(name).startsWith("settlement-"));
    }

    @Test
    void everyTournamentJobUsesTheSettlementScheduler() {
        Method[] scheduled = Arrays.stream(TournamentJobs.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toArray(Method[]::new);
        assertThat(scheduled).hasSize(3);
        assertThat(scheduled).allSatisfy(m ->
                assertThat(m.getAnnotation(Scheduled.class).scheduler()).as(m.getName()).isEqualTo("settlementScheduler"));
    }
}
