package io.github.nishantshah0.papertrader.grading;

import io.github.nishantshah0.papertrader.bots.BotKind;
import java.util.Map;

// How one detector did on one market, against the answer key.
//
// By account: of the accounts it flagged, how many cheated (precision), and of the
// cheaters, how many it flagged (recall). falseAlarms counts the honest accounts it
// flagged, by what they really were.
//
// By order: of the orders labelled SPOOF, how many it pointed at (recall), and of the
// orders it pointed at, how many were SPOOF (precision). A spoofer's REAL orders count
// as neither: pointing at one is not wrong, but it is not what the label says either.
// Null for a detector that only judges accounts.
public record Score(String detector, int flagged, int cheaters, int caught, int missed, int falseAlarms,
        Double precision, Double recall, Map<BotKind, Integer> falseAlarmsByKind, OrderScore orders) {

    public record OrderScore(int spoofOrders, int pointedAt, int pointedAtSpoof, Double precision, Double recall) {
    }
}
