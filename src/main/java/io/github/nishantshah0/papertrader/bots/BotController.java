package io.github.nishantshah0.papertrader.bots;

import io.github.nishantshah0.papertrader.jobs.Jobs;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Starts a bot market as a job and answers 202 with the job at once; GET /api/jobs/{id}
// has the report when the run is over. 409 straight away unless the app is in book mode
// on a simulated clock. The answer key is for grading a detector afterwards.
@RestController
class BotController {

    private final BotRunner runner;
    private final AnswerKey answerKey;
    private final Jobs jobs;

    BotController(BotRunner runner, AnswerKey answerKey, Jobs jobs) {
        this.runner = runner;
        this.answerKey = answerKey;
        this.jobs = jobs;
    }

    @PostMapping("/api/bots/runs")
    ResponseEntity<Jobs.Job> run(@Valid @RequestBody BotRunRequest request) {
        runner.requireRunnable();
        Jobs.Job job = jobs.submit("bot-run", () -> runner.run(request));
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }

    @GetMapping("/api/bots/answer-key")
    List<AnswerKey.Entry> answerKey() {
        return answerKey.entries();
    }

    @GetMapping("/api/bots/answer-key/orders")
    List<AnswerKey.LabelledOrder> labelledOrders() {
        return answerKey.orders();
    }
}
