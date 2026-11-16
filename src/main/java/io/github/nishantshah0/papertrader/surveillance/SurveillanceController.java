package io.github.nishantshah0.papertrader.surveillance;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
}
