package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class CatalogReadGateTest {
    @Test void globalBusyAndCooldownPreventQueueFloodAndFailureReleasesGate() {
        long[] clock={0}; var gate=new CatalogReadGate(()->clock[0]);
        var pending=new CompletableFuture<String>();
        assertSame(pending,gate.submit(()->pending));
        int[] starts={0};
        assertTrue(gate.submit(()->{starts[0]++;return CompletableFuture.completedFuture("bad");}).isCompletedExceptionally(),"busy must reject");
        pending.completeExceptionally(new IllegalStateException("db"));
        assertTrue(gate.submit(()->{starts[0]++;return CompletableFuture.completedFuture("bad");}).isCompletedExceptionally());
        assertEquals(0,starts[0]);
        clock[0]=1_000_000_000L;
        assertEquals("ok",gate.submit(()->CompletableFuture.completedFuture("ok")).join());
        clock[0]+=1_000_000_000L;
        assertTrue(gate.submit(()->{throw new IllegalStateException("closed");}).isCompletedExceptionally());
        clock[0]+=1_000_000_000L;
        assertEquals("recovered",gate.submit(()->CompletableFuture.completedFuture("recovered")).join());
    }
}
