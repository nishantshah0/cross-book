# Outside detectors

A detector doesn't have to live in this codebase. Any program can be one: it reads a
market's order log as JSON on stdin and prints its alerts as JSON on stdout. Once the app
is told about it, it is listed, scanned, graded against the answer key, evaluated on
held-out markets and attacked by the AI red team exactly like the built-in detectors.

## Input (stdin)

```json
{
  "orders": [
    {"id": 1, "accountId": 7, "symbol": "AAPL", "side": "BUY", "type": "LIMIT", "quantity": 40,
     "limitPrice": 231.90, "firstSeq": 11, "placedAt": "2026-01-05T14:30:00Z",
     "endedAt": "2026-01-05T14:30:03Z", "end": "CANCELLED", "filled": 0}
  ],
  "usernames": {"7": "trader_0007"}
}
```

Every order the market saw, in the order they were placed, as the event log tells it:
`end` is `FILLED`, `CANCELLED`, `REJECTED` or null if still open; `filled` is the shares
that traded; `limitPrice` is null for a market order; `firstSeq` is the event-log line
where it was placed. That's all a detector gets: nothing says who is a bot or who cheats.

## Output (stdout)

```json
[{"accountId": 7, "score": 3, "summary": "3 layering episodes", "orders": [1, 4, 9], "evidence": [11, 15, 40]}]
```

One entry per account it flags. `accountId` and `score` (higher is more suspicious) are
required. `orders` (the order ids it holds against the account) lets it be graded order
by order too; `summary` and `evidence` (event-log lines) are for whoever reads the alert.
Print `[]` to flag nobody. A non-zero exit fails the market, with stderr as the reason.

## Running one

In the lab, on held-out markets, next to the built-in detectors:

```bash
python3 tools/lab/lab.py --external example='python3 detectors/example.py' evaluate --seeds 101-105
```

Against the AI red team:

```bash
uv run tools/agent/cheater.py --via claude-code --external example='python3 detectors/example.py' --detector example
```

In a running app, name it at startup:

```bash
java -jar target/cross-book-*.jar \
  --papertrader.surveillance.external[0].name=example \
  --papertrader.surveillance.external[0].command=python3,detectors/example.py
```

A detector should be deterministic, so a market always gets the same verdict. Results are
cached by the jar, the command and the contents of any file in it, so editing a detector
reruns its markets.

## The detectors here

| Detector | What it is |
| --- | --- |
| [example.py](example.py) | The contract in 30 lines: flags accounts that cancel nearly every order. A starting point, not a serious detector. |
| [mar_layering.py](mar_layering.py) | Layering and spoofing as the EU Market Abuse Regulation's indicators describe it (Delegated Regulation 2016/522, Annex II, 5(e)): multiple or large orders on one side, pulled once a trade on the other side has happened. Specified from the text, with thresholds fixed before its first run. |
| [claude_written.py](claude_written.py) | Written by Claude (Opus 5.5) blind: it got only this contract and the task, with no access to this repository, the bots or the other detectors (tools/agent/write_detector.py). Saved as written, with its prompt and its own description at the top. Note: on an unexpected error it prints `[]` instead of failing; the app logs anything a detector writes to stderr so that can't pass silently. |
