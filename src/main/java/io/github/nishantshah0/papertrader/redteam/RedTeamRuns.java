package io.github.nishantshah0.papertrader.redteam;

import io.github.nishantshah0.papertrader.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// The AI cheater's runs (tools/agent/cheater.py), read from the folder it writes them
// to. A run in progress has progress.json, rewritten after every market and decision; a
// finished one also has result.json. This only reads them: the runs happen in their own
// lab of app copies, so watching one never touches this app's market.
@Service
public class RedTeamRuns {

    // the cheater names runs by the time they started, plus tags like -dry, -random, -blind
    private static final Pattern NAME = Pattern.compile("\\d{8}-\\d{6}(-[a-z0-9]+)*");

    public record Summary(String name, String phase, String model, String via, String searcher, boolean blind,
            String detector, int trials, int budget, Integer firstSuccess, Integer flaggedHeldOut,
            Integer spoofersHeldOut, Double lift, Double builtInLift, long updatedAt) {
    }

    private final Path dir;
    private final ObjectMapper json;

    RedTeamRuns(@Value("${papertrader.red-team.runs-dir:target/agent}") String dir, ObjectMapper json) {
        this.dir = Path.of(dir);
        this.json = json;
    }

    public List<Summary> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Summary> runs = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.sorted(Comparator.reverseOrder()).toList()) {
                String name = child.getFileName().toString();
                if (NAME.matcher(name).matches()) {
                    JsonNode run = read(child);
                    if (run != null) {
                        runs.add(summary(name, run, updatedAt(child)));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir, e);
        }
        return runs;
    }

    public JsonNode get(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new NotFoundException("red-team run " + name + " not found");
        }
        JsonNode run = read(dir.resolve(name));
        if (run == null) {
            throw new NotFoundException("red-team run " + name + " not found");
        }
        return run;
    }

    // progress.json while a run is going (it holds everything so far), else result.json
    private JsonNode read(Path run) {
        try {
            Path progress = run.resolve("progress.json");
            Path result = run.resolve("result.json");
            JsonNode node;
            if (Files.exists(progress)) {
                node = json.readTree(progress.toFile());
            } else if (Files.exists(result)) {
                node = json.readTree(result.toFile());
                ((ObjectNode) node).putObject("status").put("phase", "done");
            } else {
                return null;
            }
            ((ObjectNode) node).put("name", run.getFileName().toString());
            if (!node.hasNonNull("budget")) {
                ((ObjectNode) node).put("budget", Math.max(0, node.path("trials").size() - 1));
            }
            return node;
        } catch (RuntimeException e) {
            return null; // a file caught mid-write is skipped until the next look
        }
    }

    private static long updatedAt(Path run) {
        long latest = 0;
        for (String file : List.of("progress.json", "result.json")) {
            try {
                latest = Math.max(latest, Files.getLastModifiedTime(run.resolve(file)).toMillis());
            } catch (IOException ignored) {
                // not written (yet)
            }
        }
        return latest;
    }

    private static Summary summary(String name, JsonNode run, long updatedAt) {
        JsonNode cheater = run.path("heldOut").path("cheater");
        JsonNode builtIn = run.path("heldOut").path("built-in");
        boolean graded = cheater.isObject();
        return new Summary(name, run.path("status").path("phase").asString("done"),
                run.path("model").isNull() ? null : run.path("model").asString(null),
                run.path("via").isNull() ? null : run.path("via").asString(null),
                run.path("searcher").asString(run.path("model").isNull() ? "scripted" : "claude"),
                run.path("blind").asBoolean(false), run.path("detector").asString("spoofing-rules"),
                Math.max(0, run.path("trials").size() - 1), run.path("budget").asInt(0),
                run.path("firstSuccess").isIntegralNumber() ? run.path("firstSuccess").asInt() : null,
                graded ? cheater.path("flaggedSpoofers").asInt() : null,
                graded ? 2 * run.path("heldOutSeeds").size() : null,
                graded && cheater.hasNonNull("lift") ? cheater.path("lift").asDouble() : null,
                graded && builtIn.hasNonNull("lift") ? builtIn.path("lift").asDouble() : null, updatedAt);
    }
}
