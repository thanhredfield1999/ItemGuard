package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogObservationControllerTest {
    @Test void profileOpensRecordedLocationsThenDetailAndReturnsToSameCatalog() {
        var data=new CatalogControllerTest.Data(); var ui=new CatalogControllerTest.Port();
        new CatalogController(data,ui).open();
        data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123")),false)); ui.drain(); ui.click(9);
        assertNotNull(ui.screen.icons().get(24).action(),"recorded locations must be navigable");
        ui.click(24); assertEquals("ABC123",data.observationCode); assertEquals("uuid-ABC123",data.observationUuid);
        data.observations.complete(new CatalogObservations(List.of(observation(7)),false)); ui.drain();
        assertTrue(ui.screen.title().contains("Quan sát"));
        assertTrue(ui.screen.icons().get(4).lore().contains("Không xác minh người giữ hiện tại"));
        ui.click(9); assertTrue(ui.screen.icons().get(22).lore().contains("Quan sát #7"));
        assertTrue(ui.screen.icons().get(22).lore().contains("Ô: 7"));
        ui.click(45); ui.click(45); ui.click(45);
        assertTrue(ui.screen.title().contains("Kho")); assertEquals(1,data.queries.size());
    }
    @Test void pagesAllRetainedRowsAndLabelsPartialUnknownAndPruningGaps() {
        var data=new CatalogControllerTest.Data(); var ui=new CatalogControllerTest.Port();
        new CatalogController(data,ui).open(); data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123")),false)); ui.drain(); ui.click(9); ui.click(24);
        var rows=new ArrayList<CatalogObservation>(); for(int i=0;i<100;i++) rows.add(observation(i));
        rows.set(99,new CatalogObservation(99,30,true,"FUTURE_PLUGIN","opaque",9,0));
        data.observations.complete(new CatalogObservations(rows,true)); ui.drain();
        assertTrue(ui.screen.icons().get(4).lore().contains("Đã cắt: chỉ 100 quan sát đầu"));
        assertTrue(ui.screen.icons().get(9).lore().contains("Đợt quét chưa hoàn tất"));
        ui.click(50); ui.click(50); assertNull(ui.screen.icons().get(50)); ui.click(36);
        assertTrue(ui.screen.icons().get(22).title().contains("chưa hỗ trợ"));
        assertTrue(ui.screen.icons().get(22).lore().contains("Chưa ghi thời gian"));
        assertTrue(ui.screen.icons().get(22).lore().contains("Quan sát #99"));
        ui.click(45); ui.click(48); assertTrue(ui.screen.title().endsWith("2"));
    }

    @Test void failedObservationRetryKeepsIdentityAndNeverExposesPriorItemOrFilters() {
        var data=new CatalogControllerTest.Data(); var ui=new CatalogControllerTest.Port();
        new CatalogController(data,ui).open(); data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123"),CatalogControllerTest.row("DEF456")),false)); ui.drain(); ui.click(9); ui.click(24);
        data.observations.complete(new CatalogObservations(List.of(observation(7)),false)); ui.drain(); var stale=ui.screen.icons().get(9).action();
        ui.click(45); ui.click(45); ui.click(10); data.observations=new java.util.concurrent.CompletableFuture<>(); ui.click(24);
        data.observations.completeExceptionally(new IllegalStateException("busy")); ui.drain(); var error=ui.screen;
        stale.run(); assertSame(error,ui.screen); assertNull(ui.screen.icons().get(2)); assertNull(ui.screen.icons().get(9));
        data.observations=new java.util.concurrent.CompletableFuture<>(); ui.click(49); assertEquals("DEF456",data.observationCode);
        data.observations.complete(new CatalogObservations(List.of(),false)); ui.drain();
        assertTrue(ui.screen.icons().get(22).lore().contains("Không chứng minh item không tồn tại"));
        ui.click(45); ui.click(45); assertEquals(1,data.queries.size());
    }

    @Test void deniedSubmissionAndLateCompletionAndStaleDetailFailClosed() {
        for(String mode:List.of("denied","closed","revoked","detail","show")) {
            var data=new CatalogControllerTest.Data();
            var ui=new CatalogControllerTest.Port() { @Override public void show(CatalogScreen screen) { super.show(screen); if(mode.equals("show") && screen.title().equals("Quan sát")) historyAllowed=false; } };
            var controller=new CatalogController(data,ui); controller.open(); data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123")),false)); ui.drain(); ui.click(9);
            if(mode.equals("denied")) ui.historyAllowed=false;
            ui.click(24);
            if(mode.equals("denied") || mode.equals("show")) { assertEquals(0,data.observationCalls); assertTrue(ui.closed); continue; }
            var loading=ui.screen;
            if(mode.equals("closed")) controller.close();
            if(mode.equals("revoked")) ui.historyAllowed=false;
            data.observations.complete(new CatalogObservations(List.of(observation(7)),false)); ui.drain();
            if(mode.equals("detail")) { ui.historyAllowed=false; ui.click(9); assertTrue(ui.closed); }
            else assertSame(loading,ui.screen);
        }
    }

    @Test void synchronousSubmissionFailureIsAnErrorNotEmptySuccess() {
        var data=new CatalogControllerTest.Data() { @Override public java.util.concurrent.CompletableFuture<CatalogObservations> observations(String code,String uuid) { throw new IllegalStateException("closed"); } };
        var ui=new CatalogControllerTest.Port(); new CatalogController(data,ui).open(); data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123")),false)); ui.drain(); ui.click(9);
        assertDoesNotThrow(()->ui.click(24)); assertTrue(ui.screen.title().contains("Lỗi")); assertNotNull(ui.screen.icons().get(49));
    }
    @Test void mixedEpochsAreNotPresentedAsConcurrentCopiesAndProfileLabelsDifferentSource() {
        var data=new CatalogControllerTest.Data(); var ui=new CatalogControllerTest.Port();
        new CatalogController(data,ui).open(); data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123")),false)); ui.drain(); ui.click(9);
        assertTrue(ui.screen.icons().get(24).lore().getFirst().startsWith("Bản ghi tổng hợp: "),"profile location must label its separate source");
        ui.click(24);
        data.observations.complete(new CatalogObservations(List.of(
            new CatalogObservation(2,6,false,"CONTAINER","chest",-1,2000),
            new CatalogObservation(1,5,true,"PLAYER","player",4,1000)),false)); ui.drain();
        assertTrue(ui.screen.icons().get(4).lore().contains("Khác đợt ≠ đồng thời; không kết luận dupe"),"cross-epoch observations need explicit warning");
        assertTrue(ui.screen.icons().get(9).lore().contains("Đợt quét: 6"));
        assertTrue(ui.screen.icons().get(10).lore().contains("Đợt quét: 5"));
        assertTrue(ui.screen.icons().get(9).lore().contains("Ô: chưa xác định"));
        ui.click(9); assertTrue(ui.screen.icons().get(22).lore().contains("Ô: chưa xác định"));
    }
    @Test void sameEpochMovementDoesNotImplySimultaneousCopies() {
        var data=new CatalogControllerTest.Data(); var ui=new CatalogControllerTest.Port();
        new CatalogController(data,ui).open(); data.pages.remove().complete(new CatalogPage(List.of(CatalogControllerTest.row("ABC123")),false)); ui.drain(); ui.click(9); ui.click(24);
        data.observations.complete(new CatalogObservations(List.of(
            new CatalogObservation(2,1788950000000L,false,"PLAYER","player",3,2000),
            new CatalogObservation(1,1788950000000L,false,"CONTAINER","chest",7,1000)),false)); ui.drain();
        assertTrue(ui.screen.icons().get(4).lore().contains("Cùng đợt vẫn có thể khác thời điểm"),"same-epoch scans need non-simultaneous warning");
        assertTrue(ui.screen.icons().get(4).lore().contains("Item di chuyển có thể hiện nhiều nơi"));
        assertEquals(2,ui.screen.icons().values().stream().filter(i->i.lore().contains("Đợt quét chưa hoàn tất")).count());
    }
    static CatalogObservation observation(int id) { return new CatalogObservation(id,20,false,"PLAYER","player-id",id,1000); }
}
