# Surveillance lab

The exchange logs every order event in the same transaction as the change, and on the simulated clock a market replays exactly from its seed. That makes it a testbed for market surveillance: run a market with planted cheaters, run detectors on its event log, and grade them against an answer key that says who cheated, order by order. Live: the [surveillance desk](https://nishantshah0.github.io/cross-book/).

## Bot markets

One command runs a whole market of simulated traders, in book mode on the simulated clock:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=bots   # wipes the local database first
```

It resets the exchange, runs 142 bots across all twelve stocks for an hour of market time, logs a report, and leaves the app up so you can browse the result. `BOT_SEED`, `BOT_TICKS`, `BOT_NOISE_TRADERS`, `BOT_MARKET_MAKERS`, `BOT_BOOK_READERS`, `BOT_VALUE_TRADERS`, `BOT_SPOOFERS` and `BOT_LOOKALIKES` change the run. From a running app, `POST /api/bots/runs` starts one as a job: it answers `202` with a job id at once, and `GET /api/jobs/{id}` has the report when the run is over.

| Bots | Kind | Sees | Does |
| ---: | --- | --- | --- |
| 100 | Noise trader | the book | Buys or sells at random, sometimes at market, sometimes with a limit a little off the middle |
| 8 | Market maker | the feed | Quotes a bid and an ask in a few stocks around the feed price, leaning against its inventory |
| 20 | Book reader | the book | Trades in the direction of the most lopsided book: lots of resting bids means buy |
| 10 | Value trader | the feed | Trades a stock back towards its feed price when the book drifts more than 15 bps away, and cancels whatever doesn't fill at once |
| 2 | **Spoofer** (cheater) | the book | Stacks large fake orders on one side, trades a small real order on the other, then cancels the fakes |
| 2 | Lookalike | the book | The spoofer's honest twin: same starting stake, same timing, same real orders, no fakes |

The feed price stands in for a stock's true value, and only makers and value traders see it. Every honest bot has a position limit: it can sell down to zero or buy up to twice what it started with.

### Does the spoofing work?

A spoofer is only worth catching if its fake orders actually do something. So each spoofed run is paired with a **control**: the same seed and the same bot, with the same timing and the same real orders, but zero fake layers (`"spoofLayers": 0`). The control places no fake orders, so it's honest. Over 30 seeds (201 to 230), an hour of market time each, with both spoofers' numbers added together:

| | With fakes | Control | Difference, 95% CI | Seeds where fakes helped |
| --- | ---: | ---: | ---: | ---: |
| Real orders that traded | 35% | 19% | **+16 points** (+13 to +19) | **30 of 30** |
| Shares the real orders traded | 283 | 113 | **+170** (+131 to +209) | **30 of 30** |
| Spoofer trading P&L | +$37 | −$13 | +$50 (−$15 to +$115) | 18 of 30 |
| Book readers' P&L, all 20 | −$1,346 | −$1,180 | −$166 (−$562 to +$230) | 17 of 30 |
| Worst stock vs its feed | 75 bps | 73 bps | +2 bps (−33 to +37) | – |

The confidence intervals are paired t intervals over the 30 seeds.

What the fakes clearly do is get the spoofer's real orders traded. On every one of the 30 seeds the real orders filled more often, and they traded 2.5 times the shares on average (from 1.2 to 5.9 times, depending on the seed). The fakes make the book look heavy on one side, and the book readers trade into the spoofer's real order.

What they don't clearly do, yet, is make money. The spoofer made $50 more an hour on average, but that's on $200,000 of starting cash, and the interval includes zero: in 12 of the 30 seeds it did no better than its honest twin. The book readers' extra losses and the effect on prices are just as unclear. Real spoofing cases match this: the edge was about a tick a trade, made back over thousands of trades. So a longer run might show it, but these runs don't. To check, run `python3 tools/lab/lab.py spoof-pairs --seeds 201-230` (about 50 minutes with 6 workers).

Honest markets aren't tight either. The worst stock in a control market ended 73 bps from its feed on average and once 3.0%, so the 30 seeds include markets where an honest stock wandered as far as a manipulated one.

### What a run looks like

The default market with the spoofers, seed 1, on an Apple M3 laptop with a fresh database:

| | |
| --- | --- |
| Wall time | 65 seconds for an hour of market time, about 756 actions a second |
| Actions | 49,240 |
| Trades between bots | 12,268 |
| Refused orders | 12 |
| Last trade vs feed price | within 39 bps for every stock |
| Spoofing episodes | 155, with 570 fake orders, each pulled 2 to 6 seconds after it was placed |
| Ledger | balanced |

The bots call the order service directly rather than going over HTTP, so their orders are checked, matched, logged and audited like anyone's.

### What makes a run fair to grade a detector on

- **It replays exactly.** The same request writes the same rows, down to the ids and the millisecond timestamps. A test runs a market with every kind of bot twice and compares a hash of every event, trade, balance and label.
- **Bots don't share random numbers.** Each bot has its own random streams, seeded from the run's seed and the bot's name: one for when it wakes and one for what it does. Rerunning a market without one bot leaves every other bot's schedule exactly as it was, so the difference is that bot's effect.
- **Bots act through each tick.** Each bot wakes at random intervals, and the clock moves to each wake, so the event log has realistic timing between orders and cancels.
- **Only the answer key says who is who.** Accounts are named `trader_0001` and up, opened in an order shuffled by the seed, so neither the name nor the id gives a bot away. `answer_key` records each account's bot and whether it cheats. `answer_key_orders` labels every order a spoofer placed as `SPOOF` or `REAL`, by episode, so a detector can be graded on exact orders. A detector reads only the market data.
- **Nothing but the spoofing gives a spoofer away.** A spoofer starts with two stocks at a quarter of its cash each, a shape no honest kind had, and starting stakes are in the trade log. So every market also has lookalikes, honest twins with the same stake, timing and real orders but no fakes. Of the accounts that start like a spoofer, half are honest, and a test checks it. A detector that flags lookalikes is flagging how the spoofer trades, not the spoofing. (An outside review found this leak.)

### Bugs the measurements caught

The honest market has to stay near its feed price, or a detector graded on it learns nothing. Twice, it didn't:

1. **A noise trader that couldn't sell bought instead.** That one line tilted the market towards buying, and every stock drifted up, by as much as 4.7% in an hour. Fix: do nothing at the limit.
2. **Bots could buy far more than they could sell.** Each had tens of thousands in cash but only a few shares of an expensive stock. Once those were sold it could only buy, and COST ended 5.5% above its feed, with the makers and value traders holding none. Fix: the symmetric position limit above.

Both were found by tracing which bots held what, not by tuning numbers until the drift went away. Leaning the makers harder against their inventory was tried first, and it made things worse.

What's still simplified: noise traders are purely random, with no momentum or herding. Nobody can short. Bots act one at a time, which is what makes a run exact and caps it at about 700 actions a second. Layering is the only cheat so far.

## Catching the spoofers

Three detectors read the order event log, and only the event log. A test fails if the detector code ever mentions the answer key or the bots. Grading happens in a separate package that holds the alerts and the answer key side by side.

- **Spoofing rules.** These follow how exchange rules describe spoofing: large orders with no intent to trade, while the same trader trades a smaller order the other way. An order is suspect if it's a limit order that was cancelled less than a quarter filled within 10 seconds, and the same account placed an order at least 3 times smaller on the other side of that stock while it rested. That other order has to come strictly later, so a market maker posting its bid and ask together doesn't count. Three or more such episodes raise an alert, and the alert cites the exact event-log lines behind it.
- **Spoofing rules v2.** The patch after the AI red team got past v1 (below). Instead of one order at a time, it adds up all of an account's cancelled orders on the other side within 15 seconds of an order, held up to 60 seconds, placed before or after it but not in the same instant, and asks whether together they're at least 3 times that order.
- **Cancel-ratio baseline.** The naive check every rule has to beat: flag anyone who cancels at least 80% of their limit orders.

v1's thresholds were set on seeds 1 to 3 and then frozen. Scored on five seeds it never saw (101 to 105), with one hour of market time each and every spoofed market paired with its control market:

| Detector | Spoofers caught | False alarms | Precision | Fake orders found |
| --- | ---: | ---: | ---: | ---: |
| **Spoofing rules** | **10 / 10** | **0** | **100%** | **87%** (2,278 of 2,617, none wrong) |
| Spoofing rules v2* | 10 / 10 | 0 | 100% | 97% (2,543 of 2,617) |
| Cancel-ratio baseline | 10 / 10 | 107 | 9% | – |

\* Not a held-out result: v2's fix for market makers was found on seed 101, one of these five. So v2 was also scored on five fresh seeds (106 to 110) after the fix: 10 of 10 spoofers caught, no false alarms on either market, and 96% of the fakes found (v1: 86%, the baseline: 108 false alarms).

The baseline's 107 false alarms are 77 honest market makers, which re-quote all day, and all 30 lookalikes and control spoofers, which run the spoofer's code with the fakes turned off. v1 flagged none of the 1,410 honest accounts across the ten markets, the lookalikes and control spoofers included. On seed 101 it missed 47 fakes. 27 were less than 3 times the real order, usually a spoofer's last fake ask, cut short when it ran low on shares. 12 got a quarter or more filled before the cancel, and 8 came from schemes where no real order was placed. An account is still caught by its other episodes, so these only cost order-level recall.

The catch: the rules were written knowing what this spoofer does. Held-out seeds test whether they hold up in different markets, not against a cheater who changes how it spoofs. That's what the AI red team is for.

```bash
curl -X POST localhost:8080/api/surveillance/evaluations -H 'Content-Type: application/json' \
  -d '{"seeds": [101, 102, 103, 104, 105]}'                  # a job: about 12 minutes; resets the exchange
curl localhost:8080/api/jobs/1                                          # its status, then its report
curl 'localhost:8080/api/surveillance/alerts?detector=spoofing-rules'   # on the market as it stands
curl 'localhost:8080/api/surveillance/score?detector=spoofing-rules'    # the same, graded
```

## The AI red team

The detectors above were written knowing how the spoofer works. So the last test is a cheater that adapts: Claude (Opus 5.5) gets control of the spoofer and tries to get it past the detector without the fakes stopping working.

<img src="media/red-team-replay.gif" alt="Replay of an AI red-team run on the surveillance desk" width="900">

<sub>The <a href="https://nishantshah0.github.io/cross-book/">public surveillance desk</a> replaying the first run. <a href="media/red-team-replay.mp4">MP4</a>.</sub>

**How a run works.** Claude never places orders or touches code. It sets the spoofer's tactics, nine knobs in all: how many fake layers, how big and how far from the best price, the real order's size, which goes first, the timing between steps and the rest between schemes. The Java spoofer runs them, so every market still replays from its seed and every fake is still labelled. Each trial runs three training markets, each paired with its control (same bot, fakes off). Claude sees whether each spoofer was flagged, what the alert said, and the **lift**: the shares its real orders traded with the fakes, divided by the control's. The goal is no flags on any training market with a lift of at least 1.5, in 10 trials. Then its tactics are graded on 10 markets it never saw.

**Round 1, against v1.** Claude met the goal on its third try. On the 10 unseen markets:

| Spoofer | Flagged | Lift | Extra real shares per market, 95% CI | Markets where the fakes helped |
| --- | ---: | ---: | ---: | ---: |
| **Claude's tactics** | **0 of 20** | 1.61 | **+287** (+155 to +419) | **10 of 10** |
| Built-in spoofer | 20 of 20 | 2.05 | +140 (+76 to +204) | 9 of 10 |

It worked out that v1 judges one fake at a time, so it split its fakes into three, each 2 times the real order: 6 times together, but never 3 times in any single order. Its first suggested fix was to judge the fakes together, which is v2. Not everything it concluded was right: it put some flags down to partial fills, which v1 doesn't look at. It found the hole by experiment, not by understanding every detail.

**Is that better than guessing?** The same budget, with tactics drawn at random (5 runs, stopping at the first success):

| Searcher | Trials that met the goal | Runs that met it within 10 trials |
| --- | ---: | ---: |
| Claude | 5 of 9 | 1 of 1, on trial 3 |
| Claude, blind (told only flagged or not, never the alert) | 5 of 9 | 1 of 1, on trial 3 |
| Random | 2 of 42 | 2 of 5, on trials 7 and 5 |

And on the 10 unseen markets:

| Tactics | Flagged | Lift | Extra real shares per market, 95% CI | Markets where the fakes helped |
| --- | ---: | ---: | ---: | ---: |
| Claude | 0 of 20 | 1.61 | +287 (+155 to +419) | 10 of 10 |
| Claude, blind | 0 of 20 | 2.06 | +52 (+28 to +76) | 10 of 10 |
| Random winner 1 | 3 of 20 | 1.23 | +142 (−31 to +315) | 7 of 10 |
| Random winner 2 | 0 of 20 | 1.85 | +324 (+260 to +388) | 10 of 10 |

So v1 can be beaten by luck, and one of the two lucky draws held up on unseen markets as well as Claude's tactics did. The honest claim is narrower: Claude's trials hit the goal about ten times as often, it got there without seeing the alerts' text, and it says why. It doesn't find anything random search can't. One run each is also a small sample.

**Patching the detector.** The first version of v2 flagged honest market makers 27 times on five markets. A maker near its position limit shrinks one side of its quote, so the full-size other side looked 3 times bigger. On a full market, every one of the makers' 85 flagged episodes was a bid and ask posted in the same instant, and every one of the spoofer's 142 had its fakes before or after the real order. So v2 ignores the other side of a quote posted at once (v1 already required the real order to come later). That brought the false alarms to zero, on the five markets where the fix was found and on five fresh ones. The blind spot is a spoofer that posts its real order and fakes in the same instant: this cheater can't (its steps are at least 200 ms apart), but a person could. Requiring the real order to trade was tried first, and it threw away most of the real catches.

**Round 2, against v2.** Claude found nothing that both stayed hidden and still worked. Its submitted tactics went unflagged on the 10 unseen markets but did nothing (lift 1.04, +8 extra shares, −57 to +73). It used only 4 of its 10 trials and stopped by its own choice, so this says less about v2 than a full search would. Its notes for the defenders: keep the fakes' total under the real order's size, or let them live 40 to 60 seconds, and v2 doesn't see them.

```bash
./mvnw -DskipTests package                                        # the lab runs the packaged app
uv run tools/agent/cheater.py --via claude-code                   # on a claude.ai login (or --via api with ANTHROPIC_API_KEY)
uv run tools/agent/cheater.py --searcher random --search-seed 1 --stop-on-success --no-held-out
uv run tools/agent/cheater.py --via claude-code --detector spoofing-rules-v2   # round 2
```

A run takes one to two hours on a laptop. The app's desk (`/surveillance`, Red team) shows it live, and `tools/agent/publish.py` adds a finished run to the [public desk](https://nishantshah0.github.io/cross-book/).

## Testing outside my own market

Everything above is circular: the bots, the spoofer, the detectors and the grader were all written by the same author, so a detector passing says little about anything else. So each detector was also tested against things it wasn't built for. The built-in detectors were frozen first (git tag `detectors-frozen`), and everything below came after.

- **Someone else's detectors.** Any program can be a detector: it reads the order log as JSON and prints alerts ([detectors/README.md](../detectors/README.md)). Two were added: [mar_layering.py](../detectors/mar_layering.py), written from the EU Market Abuse Regulation's description of layering (Delegated Regulation 2016/522, Annex II), with its thresholds committed before its first run; and [claude_written.py](../detectors/claude_written.py), which Claude wrote blind, with only the data format and the task, and which is saved as written.
- **Someone else's market.** [ABIDES](https://github.com/jpmorganchase/abides-jpmc-public), J.P. Morgan's open-source market simulator, in its standard configuration (1,000 noise agents, value and momentum agents, two adaptive market makers), with the same layering spoofer added as an ABIDES agent ([tools/abides/](../tools/abides/)). Its agents are the honest traders, and none of them was written here.
- **Someone else's cheating.** Two spoofers modelled on enforcement cases, written after the freeze: **Coscia** (CFTC, 2013), a small real order first, then large fakes on the other side, pulled within milliseconds; and **Sarao** (CFTC, 2015), five large orders kept 3 or more cents from the best price, repriced as it moves for minutes at a time, switched on and off.

Each cell is spoofers caught, then false alarms on honest accounts (the spoofed and control markets together). All markets are an hour long; the CrossBook ones are seeds 106 to 110, which no detector was tuned on.

| Detector | CrossBook | ABIDES (10 markets) | Coscia-style | Sarao-style |
| --- | --- | --- | --- | --- |
| Spoofing rules v1 | 10/10, 0 | 20/20, 0 | **1/10**, 0 | 10/10, 0 |
| Spoofing rules v2 | 10/10, 0 | 20/20, **23** | 10/10, 0 | 10/10, 0 |
| Claude, written blind | 10/10, 0 | 20/20, 0 | 10/10, 0 | **0/10**, 0 |
| EU regulation, as written | 10/10, **80** | 20/20, **30** | 10/10, **80** | 10/10, **80** |
| Cancel-ratio baseline | 10/10, 108 | 20/20, 49 | 10/10, 108 | 10/10, 91 |

No detector passes all four, and each fails a different way:

- **v1** only counts fakes placed before the real order. Coscia placed the real order first, so v1 catches 1 of 10.
- **v2's fix for market makers was tuned to this market's makers**, which post a bid and ask in the same instant. ABIDES' market maker posts its two ladders a microsecond apart, and v2 reads one side as fakes against the other. Every test inside CrossBook said v2 was fine.
- **The detector Claude wrote blind** looks for fakes placed just before a trade and pulled within seconds of it. Sarao's sit for minutes and get repriced, so it misses all 10.
- **The regulation's text, applied literally,** can't tell layering from market making: a maker that gets filled on its bid and then pulls its ask matches "orders on one side, removed once a trade on the other side has taken place". The published indicators describe what an investigator should look at, judged with intent in mind; as a mechanical rule they flag every market maker.

Two limits. In ABIDES the spoofing barely works (the real orders traded 1.05 times the control's shares), because its agents don't trade on order book imbalance, so there it's a detection test only. And the enforcement-case spoofers follow the public filings' descriptions, not the real algorithms.

Raw results are in [results/](results/). To rerun:

```bash
python3 tools/lab/lab.py --workers 3 --external mar-layering='python3 detectors/mar_layering.py' \
  --external claude-written='python3 detectors/claude_written.py' evaluate --seeds 106-110 --style coscia
target/external/abides/venv/bin/python tools/abides/run.py --seeds 1-10   # setup: tools/abides/README.md
```

