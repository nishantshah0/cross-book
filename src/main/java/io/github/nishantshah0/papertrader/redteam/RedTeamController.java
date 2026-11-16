package io.github.nishantshah0.papertrader.redteam;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

// Read-only: the surveillance page's red-team tab polls these while a run goes.
@RestController
class RedTeamController {

    private final RedTeamRuns runs;

    RedTeamController(RedTeamRuns runs) {
        this.runs = runs;
    }

    @GetMapping("/api/red-team/runs")
    List<RedTeamRuns.Summary> runs() {
        return runs.list();
    }

    @GetMapping("/api/red-team/runs/{name}")
    JsonNode run(@PathVariable String name) {
        return runs.get(name);
    }
}
