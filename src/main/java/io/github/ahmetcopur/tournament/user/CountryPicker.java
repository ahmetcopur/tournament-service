package io.github.ahmetcopur.tournament.user;

import io.github.ahmetcopur.tournament.config.GameProperties;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class CountryPicker {

    private final List<String> countries;

    public CountryPicker(GameProperties props) {
        this.countries = List.copyOf(props.countries());
    }

    public String pick() {
        return countries.get(ThreadLocalRandom.current().nextInt(countries.size()));
    }
}
