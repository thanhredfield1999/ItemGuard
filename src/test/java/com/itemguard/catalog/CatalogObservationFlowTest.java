package com.itemguard.catalog;

import com.itemguard.persistence.SqliteConnectionOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC -> shared gate -> controller/port, not Paper or client rendering. */
class CatalogObservationFlowTest {
    @TempDir Path dir;

    @Test void sharedReadAdmissionRetriesExactPhysicalObservationThenSurvivesPruning() throws Exception {
        try(var owner=new SqliteConnectionOwner(dir.resolve("flow.db"))) {
            owner.call(c->{try(var s=c.createStatement()) {
                s.execute("INSERT INTO tracked_items(code,item_uuid,material,last_seen_at,created_at) VALUES('ABC123','identity','PAPER',1000,0)");
                s.execute("INSERT INTO item_observations(item_uuid,code,scan_epoch,holder_type,holder_id,slot,observed_at) VALUES('identity','ABC123',1,'CONTAINER','BLOCK:world-id:10:20:30',4,1000)");
            }return null;});
            var repository=new CatalogRepository(owner); var clock=new AtomicLong(); var gate=new CatalogReadGate(clock::get);
            class Data implements CatalogController.Data {
                CompletableFuture<?> completion;
                public CompletableFuture<CatalogPage> find(CatalogQuery query) {
                    var f=gate.submit(()->repository.find(query)); completion=f; return f;
                }
                public CompletableFuture<List<CatalogEvent>> history(String code,String uuid) { return gate.submit(()->repository.history(code,uuid)); }
                public CompletableFuture<CatalogObservations> observations(String code,String uuid) {
                    var f=gate.submit(()->repository.observations(code,uuid)); completion=f; return f;
                }
            }
            var data=new Data(); var ui=new CatalogControllerTest.Port() {
                @Override public void main(Runnable action) { synchronized(tasks) { tasks.add(action); tasks.notifyAll(); } }
                @Override void drain() {
                    synchronized(tasks) {
                        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                        while(tasks.isEmpty()) {
                            long left=until-System.nanoTime(); assertTrue(left>0,"async completion never reached main port");
                            try { TimeUnit.NANOSECONDS.timedWait(tasks,left); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        }
                        super.drain();
                    }
                }
            };
            new CatalogController(data,ui).open(); data.completion.get(5,TimeUnit.SECONDS); ui.drain(); ui.click(9); ui.click(24); ui.drain();
            assertTrue(ui.screen.title().contains("Lỗi"),"shared cooldown applies across catalog/observations");
            clock.set(TimeUnit.SECONDS.toNanos(1)); ui.click(49); data.completion.get(5,TimeUnit.SECONDS); ui.drain();
            assertTrue(ui.screen.icons().get(9).lore().contains("BLOCK:world-id:10:20:30")); ui.click(9);
            assertTrue(ui.screen.icons().get(22).lore().contains("Ô: 4"));
            new com.itemguard.persistence.ItemSqliteRepository(owner).completeObservationEpoch(2);
            owner.call(c->null); // writer barrier
            ui.click(45); assertNotNull(ui.screen.icons().get(9),"cached view remains a retained snapshot, not live presence");
            ui.click(45); clock.set(TimeUnit.SECONDS.toNanos(2)); ui.click(24); data.completion.get(5,TimeUnit.SECONDS); ui.drain();
            assertTrue(ui.screen.icons().get(22).lore().contains("Không chứng minh item không tồn tại"));
        }
    }
}
