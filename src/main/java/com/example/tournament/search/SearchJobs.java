package com.example.tournament.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the search index in step with Postgres. Search being down never stops the app. */
@Component
@ConditionalOnProperty(name = "game.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SearchJobs {

    private static final Logger log = LoggerFactory.getLogger(SearchJobs.class);

    private final PlayerSearchService search;

    public SearchJobs(PlayerSearchService search) {
        this.search = search;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reconcile();
    }

    @Scheduled(initialDelayString = "${game.search.reconcile-every:PT10M}",
               fixedDelayString = "${game.search.reconcile-every:PT10M}")
    public void reconcile() {
        try {
            search.reconcile();
        } catch (RuntimeException ex) {
            log.warn("Search index reconcile failed, will retry: {}", ex.getMessage());
        }
    }
}
