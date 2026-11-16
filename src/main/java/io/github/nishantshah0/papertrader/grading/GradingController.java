package io.github.nishantshah0.papertrader.grading;

import io.github.nishantshah0.papertrader.surveillance.Surveillance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GradingController {

    record EvaluationRequest(@NotEmpty @Size(max = 50) List<Long> seeds, @Min(1) @Max(5_760) Integer ticks) {
    }

    private final Surveillance surveillance;
    private final Grader grader;
    private final Evaluation evaluation;

    GradingController(Surveillance surveillance, Grader grader, Evaluation evaluation) {
        this.surveillance = surveillance;
        this.grader = grader;
        this.evaluation = evaluation;
    }

    // a detector's alerts on the market as it stands, graded against its answer key
    @GetMapping("/api/surveillance/score")
    Score score(@RequestParam(defaultValue = "spoofing-rules") String detector) {
        return grader.grade(detector, surveillance.scan(detector));
    }

    // runs a spoofed and a control market for each seed and scores every detector on
    // both; this resets the exchange for every market, and takes about a minute each
    @PostMapping("/api/surveillance/evaluations")
    Evaluation.Report evaluate(@Valid @RequestBody EvaluationRequest request) {
        return evaluation.evaluate(new Evaluation.Request(request.seeds(), request.ticks()));
    }
}
