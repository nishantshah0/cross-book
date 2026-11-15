package io.github.nishantshah0.papertrader.bots;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Runs a bot market and returns its report once it is over (409 unless the app is in
// book mode on a simulated clock). The answer key is for grading a detector afterwards.
@RestController
class BotController {

    private final BotRunner runner;
    private final AnswerKey answerKey;

    BotController(BotRunner runner, AnswerKey answerKey) {
        this.runner = runner;
        this.answerKey = answerKey;
    }

    @PostMapping("/api/bots/runs")
    BotRunReport run(@Valid @RequestBody BotRunRequest request) {
        return runner.run(request);
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
