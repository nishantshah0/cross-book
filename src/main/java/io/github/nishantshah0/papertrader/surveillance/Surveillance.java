package io.github.nishantshah0.papertrader.surveillance;

import io.github.nishantshah0.papertrader.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;

// The detectors, by name.
@Service
public class Surveillance {

    private final List<Detector> detectors;
    private final MarketLogReader reader;

    Surveillance(List<Detector> detectors, MarketLogReader reader) {
        this.detectors = detectors;
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
