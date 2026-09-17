package com.itemguard.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservationEpochScannerTest {

    @Test
    void scansPlayerAndContainerContiguouslyBeforeFinalizingEpoch() {
        List<String> calls = new ArrayList<>();
        ObservationEpochScanner<String> scanner = new ObservationEpochScanner<>(
            (player, epoch) -> calls.add("player:" + player + ':' + epoch),
            (player, epoch) -> calls.add("container:" + player + ':' + epoch),
            epoch -> calls.add("finalize:" + epoch)
        );

        scanner.scan(42L, List.of("a", "b"));

        assertEquals(List.of(
            "player:a:42",
            "container:a:42",
            "player:b:42",
            "container:b:42",
            "finalize:42"
        ), calls);
    }
}
