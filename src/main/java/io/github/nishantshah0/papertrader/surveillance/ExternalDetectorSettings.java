package io.github.nishantshah0.papertrader.surveillance;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Detectors that aren't part of this codebase: any program that reads a market's log as
// JSON on stdin and prints its alerts as JSON on stdout (see detectors/README.md). They
// are named here, at startup, and never through the API, so a request can't make the app
// run a command. For example, on the command line:
//
//   --papertrader.surveillance.external[0].name=guidance
//   --papertrader.surveillance.external[0].command=python3,detectors/guidance.py
//
// The comma form splits at every comma, so an argument that has one goes in on its own:
// --papertrader.surveillance.external[0].command[2]=...
@ConfigurationProperties("papertrader.surveillance")
public record ExternalDetectorSettings(List<Spec> external) {

    public ExternalDetectorSettings {
        external = external == null ? List.of() : List.copyOf(external);
    }

    public record Spec(String name, List<String> command, Duration timeout) {

        public Spec {
            if (name == null || name.isBlank() || command == null || command.isEmpty()) {
                throw new IllegalArgumentException("an external detector needs a name and a command");
            }
            timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        }
    }
}
