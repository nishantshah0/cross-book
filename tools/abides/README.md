# Detectors in someone else's market

Every detector here was built and tested in CrossBook's own bot market, against bots
and a spoofer written by the same author. That's circular. This runs them in a market
built by someone else: [ABIDES](https://github.com/jpmorganchase/abides-jpmc-public)
(J.P. Morgan's open-source agent-based market simulator, BSD-3), in its standard
`rmsc04` configuration (1,000 noise agents, 102 value agents, 12 momentum agents and
two adaptive market makers), with our layering spoofer added as an ABIDES agent.

## Setup

```bash
git clone --depth 1 https://github.com/jpmorganchase/abides-jpmc-public target/external/abides/src
cd target/external/abides
uv venv --python 3.9 venv && export VIRTUAL_ENV=$PWD/venv
uv pip install "numpy==1.22.4" "pandas==1.4.4" "scipy==1.10.0" tqdm coloredlogs psutil "cython<3" joblib "networkx<3" pyyaml "setuptools<70" wheel
uv pip install --no-build-isolation "pomegranate==0.14.8"   # needs Cython 2
uv pip install -e src/abides-core -e src/abides-markets
```

## Running

```bash
./mvnw -DskipTests package
target/external/abides/venv/bin/python tools/abides/run.py --seeds 1-10 \
  --external mar-layering='python3 detectors/mar_layering.py' \
  --external claude-written='python3 detectors/claude_written.py'
```

About two minutes for ten one-hour markets, each run with the spoofers and with their
honest twins. Results: [docs/results/abides-rmsc04-seeds-1-10.json](../../docs/results/abides-rmsc04-seeds-1-10.json).
