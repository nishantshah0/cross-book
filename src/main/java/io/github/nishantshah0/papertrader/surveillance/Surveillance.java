package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.NotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

// The detectors, by name: the built-in ones, and any outside programs named in the
// settings (see ExternalDetectorSettings), which every evaluation and trial runs too.
@Service
public class Surveillance {

    private final List<Detector> detectors;
    private final MarketLogReader reader;

    Surveillance(List<Detector> builtIn, MarketLogReader reader, ExternalDetectorSettings settings,
            ObjectMapper json) {
        List<Detector> all = new ArrayList<>(builtIn);
        settings.external().forEach(spec -> all.add(new ExternalDetector(spec, json)));
        Set<String> seen = new HashSet<>();
        all.forEach(d -> {
            if (!seen.add(d.name())) {
                throw new IllegalStateException("two detectors are called " + d.name());
            }
        });
        this.detectors = List.copyOf(all);
        this.reader = reader;
    }

    public List<String> names() {
        return detectors.stream().map(Detector::name).sorted().toList();
    }

    public Detector detector(String name) {
        return detectors.stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new NotFoundException("no detector called " + name + "; try " + names()));
    }

    public MarketLog log() {
        return reader.read();
    }

    public List<Alert> scan(String detector) {
        return detector(detector).scan(log());
    }
}
