package io.github.nishantshah0.papertrader.grading;

import io.github.nishantshah0.papertrader.bots.BotRunRequest;
import io.github.nishantshah0.papertrader.bots.BotRunner;
import io.github.nishantshah0.papertrader.jobs.Jobs;
import io.github.nishantshah0.papertrader.surveillance.Surveillance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
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
    private final BotRunner runner;
    private final Jobs jobs;

    GradingController(Surveillance surveillance, Grader grader, Evaluation evaluation, BotRunner runner, Jobs jobs) {
        this.surveillance = surveillance;
        this.grader = grader;
        this.evaluation = evaluation;
        this.runner = runner;
        this.jobs = jobs;
    }

    // a detector's alerts on the market as it stands, graded against its answer key
    @GetMapping("/api/surveillance/score")
    Score score(@RequestParam(defaultValue = "spoofing-rules") String detector) {
        return grader.grade(detector, surveillance.scan(detector));
    }

    // Runs a spoofed and a control market for each seed and scores every detector on
    // both, as a job (202 at once; GET /api/jobs/{id} for the report). It resets the
    // exchange for every market, and an hour-long market takes about a minute.
    @PostMapping("/api/surveillance/evaluations")
    ResponseEntity<Jobs.Job> evaluate(@Valid @RequestBody EvaluationRequest request) {
        runner.requireRunnable();
        Jobs.Job job = jobs.submit("evaluation",
                () -> evaluation.evaluate(new Evaluation.Request(request.seeds(), request.ticks())));
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }

    // One market, as a job, with every detector's verdict on each spoofer: the loop an
    // adaptive cheater (tools/agent/) runs to test tactics against surveillance.
    @PostMapping("/api/surveillance/trials")
    ResponseEntity<Jobs.Job> trial(@Valid @RequestBody BotRunRequest request) {
        runner.requireRunnable();
        Jobs.Job job = jobs.submit("trial", () -> evaluation.trial(request));
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }
}
