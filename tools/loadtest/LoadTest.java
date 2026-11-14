import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Load test for a running PaperTrader. Each simulated trader is a virtual thread with
// its own practice account, sending requests back to back with no pause. It keeps about
// 10 shares of each stock: below that it buys, above it it sells, so it neither runs out
// of cash nor of shares. Each request is one of:
//
//   40% market orders of 1 to 5 shares
//   45% limit orders 0.05% to 0.3% away from the price, which mostly rest in the book
//   15% cancels of its oldest resting order, and always once it has more than 5 resting
//
// It runs each load level for a fixed time, then checks the ledger audit, and prints a
// table of throughput, latency and outcomes. No dependencies; run it with Java 21+:
//
//   java tools/loadtest/LoadTest.java --levels 1,10,50,200 --seconds 20
//
// Options: --url (default http://localhost:8080), --levels (traders per level),
// --seconds (per level), --warmup (seconds, not reported), --seed, --symbols
// (comma-separated; default every quoted symbol).
public class LoadTest {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern FILLED = Pattern.compile("\"filledQuantity\"\\s*:\\s*(\\d+)");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final Pattern QUOTE = Pattern.compile(
            "\\{\"symbol\":\"([A-Z]+)\",\"price\":([0-9.]+)");
    private static final Pattern BALANCED = Pattern.compile("\"balanced\"\\s*:\\s*(true|false)");
    private static final Pattern TRADES = Pattern.compile("\"trades\"\\s*:\\s*(\\d+)");

    // CONFLICT is a cancel that arrived after the order had already filled: a normal race
    enum Outcome { OK, REFUSED, CONFLICT, BUSY, ERROR }

    enum Kind { PLACE, CANCEL }

    // one completed request
    record Sample(Kind kind, Outcome outcome, long nanos) {
    }

    record LevelResult(int traders, int seconds, List<Sample> samples, boolean balanced, long trades,
            long auditMillis) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final Map<String, Double> prices = new ConcurrentHashMap<>();
    private final String run = Long.toString(System.currentTimeMillis() % 1_000_000, 36);
    private int levelsRun;

    LoadTest(String base) {
        this.base = base;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        String url = options.getOrDefault("url", "http://localhost:8080");
        int[] levels = Arrays.stream(options.getOrDefault("levels", "1,10,50,200").split(","))
                .mapToInt(Integer::parseInt).toArray();
        int seconds = Integer.parseInt(options.getOrDefault("seconds", "20"));
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "10"));
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));

        LoadTest test = new LoadTest(url);
        test.refreshPrices();
        List<String> symbols = options.containsKey("symbols")
                ? List.of(options.get("symbols").split(","))
                : test.prices.keySet().stream().sorted().toList();
        System.out.printf("PaperTrader load test against %s: %d symbols, %d s per level, seed %d%n", url,
                symbols.size(), seconds, seed);

        if (warmup > 0) {
            System.out.printf("warming up for %d s ...%n", warmup);
            test.level(10, warmup, symbols, seed - 1);
        }
        List<LevelResult> results = new ArrayList<>();
        for (int traders : levels) {
            System.out.printf("%d traders for %d s ...%n", traders, seconds);
            results.add(test.level(traders, seconds, symbols, seed + traders));
        }
        System.out.println();
        System.out.println(table(results));
    }

    LevelResult level(int traders, int seconds, List<String> symbols, long seed) throws Exception {
        List<Long> accounts = new ArrayList<>();
        int level = levelsRun++;
        for (int i = 0; i < traders; i++) {
            accounts.add(createAccount("load_" + run + "_" + level + "_" + i));
        }
        AtomicBoolean running = new AtomicBoolean(true);
        List<List<Sample>> perTrader = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> {
                while (running.get()) {
                    sleep(1_000);
                    refreshPrices();
                }
                return null;
            });
            Instant until = Instant.now().plusSeconds(seconds);
            for (int i = 0; i < traders; i++) {
                List<Sample> samples = new ArrayList<>();
                perTrader.add(samples);
                long account = accounts.get(i);
                Random random = new Random(seed * 1_000 + i);
                pool.submit(() -> {
                    trade(account, symbols, random, until, samples);
                    return null;
                });
            }
            sleep(seconds * 1_000L);
            running.set(false);
        }
        List<Sample> all = new ArrayList<>();
        perTrader.forEach(all::addAll);
        long start = System.nanoTime();
        String ledger = get("/api/ledger");
        long auditMillis = (System.nanoTime() - start) / 1_000_000;
        return new LevelResult(traders, seconds, all, find(BALANCED, ledger).equals("true"),
                Long.parseLong(find(TRADES, ledger)), auditMillis);
    }

    // one trader, sending requests back to back until the deadline
    private void trade(long account, List<String> symbols, Random random, Instant until, List<Sample> samples) {
        Map<String, Integer> free = new HashMap<>(); // shares held and not on a resting sell
        Deque<long[]> resting = new ArrayDeque<>(); // {orderId, symbol index, shares if a sell, else 0}
        while (Instant.now().isBefore(until)) {
            int s = random.nextInt(symbols.size());
            String symbol = symbols.get(s);
            double roll = random.nextDouble();
            int held = free.getOrDefault(symbol, 0);
            if (!resting.isEmpty() && (roll < 0.15 || resting.size() > 5)) {
                long[] oldest = resting.pollFirst();
                Sample sample = send(Kind.CANCEL, "DELETE", "/api/accounts/" + account + "/orders/" + oldest[0], null);
                samples.add(sample);
                if (sample.outcome() == Outcome.OK && oldest[2] > 0) {
                    free.merge(symbols.get((int) oldest[1]), (int) oldest[2], Integer::sum);
                }
                continue;
            }
            int shares = 1 + random.nextInt(5);
            boolean sell = held >= 10 + shares || (held >= shares && random.nextDouble() < 0.3);
            String body;
            if (roll < 0.55) {
                body = order(symbol, sell, shares, null);
            } else {
                double away = 0.0005 + random.nextDouble() * 0.0025;
                double price = prices.getOrDefault(symbol, 100.0) * (sell ? 1 + away : 1 - away);
                body = order(symbol, sell, shares, String.format(Locale.ROOT, "%.2f", price));
            }
            String[] response = new String[1];
            Sample sample = send(Kind.PLACE, "POST", "/api/accounts/" + account + "/orders", body, response);
            samples.add(sample);
            if (sample.outcome() != Outcome.OK) {
                continue;
            }
            int filled = Integer.parseInt(find(FILLED, response[0]));
            if (sell) {
                free.merge(symbol, -shares, Integer::sum); // filled shares are gone, the rest are held
            } else {
                free.merge(symbol, filled, Integer::sum);
            }
            if (find(STATUS, response[0]).equals("OPEN")) {
                resting.addLast(new long[] {Long.parseLong(find(ID, response[0])), s, sell ? shares - filled : 0});
            }
        }
    }

    private static String order(String symbol, boolean sell, int shares, String limit) {
        return "{\"symbol\":\"" + symbol + "\",\"side\":\"" + (sell ? "SELL" : "BUY") + "\",\"quantity\":" + shares
                + (limit == null ? "" : ",\"type\":\"LIMIT\",\"limitPrice\":" + limit) + "}";
    }

    private Sample send(Kind kind, String method, String path, String body) {
        return send(kind, method, path, body, new String[1]);
    }

    private Sample send(Kind kind, String method, String path, String body, String[] response) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        long start = System.nanoTime();
        try {
            HttpResponse<String> reply = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            long nanos = System.nanoTime() - start;
            response[0] = reply.body();
            int code = reply.statusCode();
            Outcome outcome = code / 100 == 2 ? Outcome.OK
                    : code == 422 ? Outcome.REFUSED
                    : code == 409 ? Outcome.CONFLICT
                    : code == 503 ? Outcome.BUSY
                    : Outcome.ERROR;
            return new Sample(kind, outcome, nanos);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Sample(kind, Outcome.ERROR, System.nanoTime() - start);
        }
    }

    private long createAccount(String username) throws Exception {
        HttpResponse<String> reply = http.send(HttpRequest.newBuilder(URI.create(base + "/api/accounts"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (reply.statusCode() != 201) {
            throw new IllegalStateException("could not create account " + username + ": " + reply.statusCode());
        }
        return Long.parseLong(find(ID, reply.body()));
    }

    private void refreshPrices() throws Exception {
        Matcher quote = QUOTE.matcher(get("/api/quotes"));
        while (quote.find()) {
            prices.put(quote.group(1), Double.parseDouble(quote.group(2)));
        }
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String table(List<LevelResult> results) {
        StringBuilder out = new StringBuilder();
        out.append("| Traders | Requests/s | Orders placed/s | p50 ms | p95 ms | p99 ms | Max ms | Refused | Cancel too late | Busy (503) | Errors | Books after |\n");
        out.append("| ------: | ---------: | --------------: | -----: | -----: | -----: | -----: | ------: | --------------: | ---------: | -----: | ----------- |\n");
        for (LevelResult r : results) {
            long[] nanos = r.samples().stream().mapToLong(Sample::nanos).sorted().toArray();
            long placed = r.samples().stream()
                    .filter(s -> s.kind() == Kind.PLACE && s.outcome() == Outcome.OK).count();
            out.append(String.format(Locale.ROOT,
                    "| %d | %,.0f | %,.0f | %.1f | %.1f | %.1f | %.0f | %.1f%% | %d | %d | %d | %s, %,d trades, audit %,d ms |%n",
                    r.traders(), (double) nanos.length / r.seconds(), (double) placed / r.seconds(),
                    percentile(nanos, 0.50), percentile(nanos, 0.95), percentile(nanos, 0.99),
                    nanos.length == 0 ? 0 : nanos[nanos.length - 1] / 1e6,
                    100.0 * count(r, Outcome.REFUSED) / Math.max(1, nanos.length),
                    count(r, Outcome.CONFLICT), count(r, Outcome.BUSY), count(r, Outcome.ERROR),
                    r.balanced() ? "balanced" : "OUT OF BALANCE", r.trades(), r.auditMillis()));
        }
        return out.toString();
    }

    private static long count(LevelResult r, Outcome outcome) {
        return r.samples().stream().filter(s -> s.outcome() == outcome).count();
    }

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))] / 1e6;
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : "0";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return options;
    }
}
