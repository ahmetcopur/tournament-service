package io.github.ahmetcopur.tournament.tournament;

import io.github.ahmetcopur.tournament.config.TimeSource;
import io.github.ahmetcopur.tournament.security.CurrentPlayer;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tournaments")
public class TournamentController {

    private final JoinService joins;
    private final TournamentLifecycleService lifecycle;
    private final TimeSource time;

    public TournamentController(JoinService joins, TournamentLifecycleService lifecycle, TimeSource time) {
        this.joins = joins;
        this.lifecycle = lifecycle;
        this.time = time;
    }

    /**
     * Not in the doc: clients need today's tournament id for the rank and country endpoints.
     * Creates today's row if neither the midnight job nor startup has yet (a fresh instance can
     * report healthy before its startup tasks finish). The insert is idempotent.
     */
    @GetMapping("/current")
    public TournamentResponse current() {
        Instant now = time.now();
        Tournament t = lifecycle.todays(now).orElseGet(() -> lifecycle.ensureTournamentFor(now));
        return new TournamentResponse(t.getId(), t.getStartsAt(), t.getEndsAt(), t.getStatus(), t.isOpenAt(now));
    }

    @PostMapping("/current/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse join(@Valid @RequestBody JoinRequest request) {
        CurrentPlayer.requireSelf(request.userId());
        return joins.join(request.userId());
    }
}
