package io.github.nishantshah0.papertrader.surveillance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// A detector that runs a program. It writes the market log to the program's stdin as
// {"orders": [...], "usernames": {...}}, every order as LoggedOrder has it, and reads a
// JSON array of alerts from its stdout: accountId and score are required; summary,
// orders (order ids) and evidence (event-log sequence numbers) are optional. The program
// sees exactly what the built-in detectors see, nothing from the answer key.
final class ExternalDetector implements Detector {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalDetector.class);

    // what the program prints for each account it flags
    record Output(Long accountId, Double score, String summary, List<Long> orders, List<Long> evidence) {
    }

    private final ExternalDetectorSettings.Spec spec;
    private final ObjectMapper json;

    ExternalDetector(ExternalDetectorSettings.Spec spec, ObjectMapper json) {
        this.spec = spec;
        this.json = json;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public List<Alert> scan(MarketLog log) {
        Path in = null, err = null;
        try {
            // through files, not pipes: a large log and a chatty program can't block each other
            in = Files.createTempFile("market-log", ".json");
            err = Files.createTempFile("detector", ".err");
            json.writeValue(in.toFile(), log);
            Process process = new ProcessBuilder(spec.command()).redirectInput(in.toFile())
                    .redirectError(err.toFile()).start();
            byte[] out = process.getInputStream().readAllBytes();
            if (!process.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(name() + " took longer than " + spec.timeout());
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(name() + " exited with " + process.exitValue() + ": " + tail(err));
            }
            String warnings = tail(err);
            if (!warnings.isEmpty()) {
                // a program that swallows its own errors and prints [] would otherwise look like a clean market
                LOG.warn("{} wrote to stderr: {}", name(), warnings);
            }
            List<Output> outputs = json.readValue(new String(out, StandardCharsets.UTF_8), new TypeReference<>() {
            });
            List<Alert> alerts = new ArrayList<>();
            for (Output o : outputs) {
                if (o.accountId() == null || o.score() == null) {
                    throw new IllegalStateException(name() + " printed an alert without an accountId and a score");
                }
                alerts.add(new Alert(name(), o.accountId(), log.usernames().get(o.accountId()), o.score(),
                        o.summary() == null ? "" : o.summary(), o.orders() == null ? List.of() : o.orders(),
                        o.evidence() == null ? List.of() : o.evidence()));
            }
            return alerts;
        } catch (IOException e) {
            throw new IllegalStateException(name() + " could not run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name() + " was interrupted", e);
        } finally {
            delete(in);
            delete(err);
        }
    }

    private static String tail(Path err) throws IOException {
        String text = Files.readString(err).strip();
        return text.length() <= 2_000 ? text : "..." + text.substring(text.length() - 2_000);
    }

    private static void delete(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // a temp file left behind is not worth failing a scan over
        }
    }
}
