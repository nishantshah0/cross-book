#!/usr/bin/env bash
# Every experiment behind the AI-cheater results in the README, in order. Each step
# writes to target/agent/ (cheater runs) or target/lab/ (lab runs) and logs to
# target/experiments/. Markets replay exactly from their seeds and finished ones are
# cached, so a step that dies can simply be run again. Expect most of a day with 6 workers.
#
#   ./mvnw -DskipTests package && caffeinate -i tools/agent/experiments.sh [step ...]
#
# Steps: v2-false-alarms random blind round2 profit repeat (default: all, in that order)
set -u
cd "$(dirname "$0")/../.."
mkdir -p target/experiments
log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a target/experiments/progress.log; }
step() {
  local name=$1
  shift
  log "start $name: $*"
  if "$@" > "target/experiments/$name.log" 2>&1; then log "done $name"; else log "FAILED $name (see target/experiments/$name.log)"; fi
}

steps=("$@")
[ ${#steps[@]} -eq 0 ] && steps=(v2-false-alarms random blind round2 profit repeat)

for s in "${steps[@]}"; do
  case $s in
    v2-false-alarms) # does the patched detector stay quiet on honest traders? same held-out seeds as v1
      step v2-false-alarms python3 tools/lab/lab.py --workers 6 evaluate --seeds 101-105 ;;
    random) # the baseline: random tactics, same 10-trial budget, stop at the first that works
      for seed in 1 2 3 4 5; do
        step "random-$seed" uv run --quiet tools/agent/cheater.py --searcher random --search-seed "$seed" \
          --stop-on-success --no-held-out
      done ;;
    blind) # Claude told only flagged or not: is it reasoning, or reading the alert text?
      step blind uv run --quiet tools/agent/cheater.py --via claude-code --blind ;;
    round2) # the arms race: Claude against the detector patched after round one
      step round2 uv run --quiet tools/agent/cheater.py --via claude-code --detector spoofing-rules-v2 ;;
    profit) # 60 more spoofing pairs, for 90 with the first 30: enough power to see a $50 edge
      step profit python3 tools/lab/lab.py --workers 6 spoof-pairs --seeds 231-290 ;;
    repeat) # a second Claude run against v1, so "Claude" is two runs, not one
      step repeat uv run --quiet tools/agent/cheater.py --via claude-code ;;
    *) log "unknown step $s" ;;
  esac
done
log "all done"
