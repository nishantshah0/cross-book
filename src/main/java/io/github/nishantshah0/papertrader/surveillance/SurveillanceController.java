package io.github.nishantshah0.papertrader.surveillance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SurveillanceController {

    private final Surveillance surveillance;

    SurveillanceController(Surveillance surveillance) {
        this.surveillance = surveillance;
    }

    @GetMapping("/api/surveillance/detectors")
    List<String> detectors() {
        return surveillance.names();
    }

    // what a detector flags in the market as it stands
    @GetMapping("/api/surveillance/alerts")
    List<Alert> alerts(@RequestParam(defaultValue = "spoofing-rules") String detector) {
        return surveillance.scan(detector);
    }

    // Every detector on a log from somewhere else, such as a market in another simulator
    // (tools/abides/), in the same shape detectors/README.md gives outside detectors. It
    // touches nothing in this exchange; grading is up to the caller, who has the labels.
    @PostMapping("/api/surveillance/scan")
    Map<String, List<Alert>> scan(@RequestBody MarketLog log) {
        Map<String, List<Alert>> alerts = new LinkedHashMap<>();
        surveillance.names().forEach(name -> alerts.put(name, surveillance.detector(name).scan(log)));
        return alerts;
    }
}
