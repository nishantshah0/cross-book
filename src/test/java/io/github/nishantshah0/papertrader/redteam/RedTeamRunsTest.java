package io.github.nishantshah0.papertrader.redteam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nishantshah0.papertrader.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RedTeamRunsTest {

    @TempDir
    Path dir;

    private RedTeamRuns runs() {
        return new RedTeamRuns(dir.toString(), JsonMapper.builder().build());
    }

    @Test
    void listsRunsNewestFirstFromProgressOrResult() throws Exception {
        Path live = Files.createDirectories(dir.resolve("20261116-170000"));
        Files.writeString(live.resolve("progress.json"), """
                {"budget": 10, "model": "claude-opus-5-5", "via": "claude-code", "heldOutSeeds": [1, 2],
                 "trials": [{}, {}], "heldOut": null, "status": {"phase": "thinking"}}""");
        Path done = Files.createDirectories(dir.resolve("20261116-160000-random-blind"));
        Files.writeString(done.resolve("result.json"), """
                {"model": null, "searcher": "random", "blind": true, "firstSuccess": 2, "heldOutSeeds": [1, 2],
                 "trials": [{}, {}, {}],
                 "heldOut": {"cheater": {"flaggedSpoofers": 1, "lift": 2.5}, "built-in": {"lift": 3.0}}}""");
        Files.createDirectories(dir.resolve("20261116-150000")); // started, nothing written yet
        Files.createDirectories(dir.resolve("not-a-run"));

        var list = runs().list();
        assertThat(list).extracting(RedTeamRuns.Summary::name).containsExactly("20261116-170000", "20261116-160000-random-blind");
        var running = list.get(0);
        assertThat(running.phase()).isEqualTo("thinking");
        assertThat(running.trials()).isEqualTo(1);
        assertThat(running.budget()).isEqualTo(10);
        assertThat(running.flaggedHeldOut()).isNull();
        var finished = list.get(1);
        assertThat(finished.phase()).isEqualTo("done");
        assertThat(finished.model()).isNull();
        assertThat(finished.searcher()).isEqualTo("random");
        assertThat(finished.blind()).isTrue();
        assertThat(finished.firstSuccess()).isEqualTo(2);
        assertThat(running.searcher()).isEqualTo("claude");
        assertThat(running.firstSuccess()).isNull();
        assertThat(finished.flaggedHeldOut()).isEqualTo(1);
        assertThat(finished.spoofersHeldOut()).isEqualTo(4);
        assertThat(finished.lift()).isEqualTo(2.5);
        assertThat(finished.builtInLift()).isEqualTo(3.0);
        assertThat(runs().get("20261116-160000-random-blind").path("name").asString()).isEqualTo("20261116-160000-random-blind");
    }

    @Test
    void onlyRunNamesResolve() {
        assertThatThrownBy(() -> runs().get("../../etc")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> runs().get("20990101-000000")).isInstanceOf(NotFoundException.class);
        assertThat(new RedTeamRuns(dir.resolve("missing").toString(), JsonMapper.builder().build()).list()).isEmpty();
    }
}
