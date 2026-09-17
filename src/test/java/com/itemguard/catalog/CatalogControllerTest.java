package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class CatalogControllerTest {
    @Test void catalogOpensRealProfileThenHistoryAndReturnsToSamePage() {
        var data = new Data(); var ui = new Port(); var controller = new CatalogController(data, ui);
        controller.open();
        assertEquals(1, data.queries.size(), "open must request catalog");
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")), false)); ui.drain();
        assertEquals("ABC123", ui.screen.icons().get(9).lore().getFirst());
        ui.click(9);
        assertTrue(ui.screen.title().contains("Hồ sơ"));
        ui.click(20);
        assertEquals("ABC123", data.historyCode);
        data.history.complete(List.of(new CatalogEvent(1, "DROP", "Lan", "actor-uuid", "world (1,2,3)", 10, "detail"))); ui.drain();
        assertTrue(ui.screen.title().contains("Lịch sử"));
        ui.click(9); assertTrue(ui.screen.title().contains("Sự kiện"));
        ui.click(45); ui.click(45); ui.click(45);
        assertTrue(ui.screen.title().contains("Kho"));
        assertEquals(1, data.queries.size(), "back uses selected catalog state, not fresh query");
    }
    @Test void pagesAndFiltersKeepCursorAndResetAtFirstPage() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),true)); ui.drain();
        assertNotNull(ui.screen.icons().get(50), "catalog needs next page");
        ui.click(50); assertEquals("ABC123",data.queries.getLast().afterCode());
        data.pages.remove().complete(new CatalogPage(List.of(row("DEF456")),false)); ui.drain();
        ui.click(48); assertEquals("",data.queries.getLast().afterCode());
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),true)); ui.drain();
        ui.click(2); assertNotNull(ui.prompt); ui.prompt.accept("Blade");
        assertEquals("Blade",data.queries.getLast().text());
        data.pages.remove().complete(new CatalogPage(List.of(),false)); ui.drain();
        ui.click(4); ui.click(20);
        assertEquals(CatalogCategory.ARMOR,data.queries.getLast().category());
    }

    @Test void historyExposesAllHundredEventsAcrossPages() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),false)); ui.drain(); ui.click(9); ui.click(20);
        var events=new ArrayList<CatalogEvent>();
        for(int i=0;i<100;i++) events.add(new CatalogEvent(i,"DROP","Lan","uuid","loc",i+1,"detail"));
        data.history.complete(events); ui.drain();
        assertNotNull(ui.screen.icons().get(50), "history needs next page");
        ui.click(50); ui.click(50); ui.click(36);
        assertTrue(ui.screen.icons().get(22).lore().contains("Sự kiện #99"));
    }

    @Test void staleActionsAndRevokedHistoryPermissionCannotRender() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),false)); ui.drain();
        Runnable stale=ui.screen.icons().get(9).action(); ui.click(4);
        var current=ui.screen; stale.run(); assertSame(current,ui.screen,"stale slot action must not navigate");
        ui.click(45); ui.click(9); ui.click(20);
        data.history.complete(List.of(new CatalogEvent(1,"DROP","Lan","uuid","loc",1,"detail"))); ui.drain();
        ui.historyAllowed=false; ui.click(9);
        assertTrue(ui.closed,"revoked history permission must close before detail");
    }

    @Test void closeAndPermissionLossDropAsyncResults() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        var loading=ui.screen; c.close(); data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),false)); ui.drain();
        assertSame(loading,ui.screen);
        var data2=new Data(); var ui2=new Port(); var c2=new CatalogController(data2,ui2); c2.open();
        ui2.allowed=false; data2.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),false)); ui2.drain();
        assertTrue(ui2.closed);
    }

    @Test void deniedOpenAndQueryFailureNeverLookLikeSuccessfulEmptyPage() {
        var data=new Data(); var ui=new Port(); ui.allowed=false; new CatalogController(data,ui).open();
        assertTrue(data.queries.isEmpty()); assertTrue(ui.closed);
        var ui2=new Port(); var c=new CatalogController(data,ui2); c.open();
        data.pages.remove().completeExceptionally(new IllegalStateException("failure")); ui2.drain();
        assertTrue(ui2.screen.title().contains("Lỗi")); assertNotNull(ui2.screen.icons().get(2));
    }

    @Test void pageCapAndCancelInvalidChatPreserveContext() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        for (int i=0;i<128;i++) {
            data.pages.remove().complete(new CatalogPage(List.of(row(String.format("C%05d",i))),true)); ui.drain();
            if (i<127) ui.click(50);
        }
        assertNull(ui.screen.icons().get(50).action());
        assertTrue(ui.screen.icons().get(50).title().contains("128"));
        ui.click(2); var old=ui.prompt; old.accept("Hủy"); assertEquals(128,data.queries.size());
        old.accept("late"); assertEquals(128,data.queries.size());
        ui.click(2); ui.prompt.accept("x".repeat(65)); assertEquals(128,data.queries.size());
        ui.click(7); assertEquals("",data.queries.getLast().afterCode());
    }

    @Test void synchronousDatabaseShutdownGetsErrorScreen() {
        var data=new Data() {
            @Override public CompletableFuture<CatalogPage> find(CatalogQuery query) { throw new IllegalStateException("closed"); }
        };
        var ui=new Port();
        assertDoesNotThrow(()->new CatalogController(data,ui).open(),"DB submission failure must reach error screen");
        assertTrue(ui.screen.title().contains("Lỗi"));
    }

    @Test void rejectedShowNeverSubmitsQuery() {
        var data=new Data();
        var ui=new Port() { @Override public void show(CatalogScreen screen) { super.show(screen); allowed=false; } };
        new CatalogController(data,ui).open();
        assertTrue(data.queries.isEmpty(),"lost permission during show must not submit");
    }

    @Test void failedNextPageCanRetryExactTargetOrReturnToCommittedPage() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),true)); ui.drain();
        var staleNext=ui.screen.icons().get(50).action();
        ui.click(50); var target=data.queries.getLast();
        staleNext.run(); assertEquals(2,data.queries.size());
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        assertNotNull(ui.screen.icons().get(49),"failed page must offer retry of exact target");
        ui.click(49); assertEquals(target,data.queries.getLast());
        data.pages.remove().completeExceptionally(new IllegalStateException("busy again")); ui.drain();
        ui.click(45);
        assertEquals("Trang 1",ui.screen.icons().get(49).title());
        assertEquals("ABC123",ui.screen.icons().get(9).lore().getFirst());
        assertNull(ui.screen.icons().get(48));
        ui.click(50); assertEquals(target,data.queries.getLast());
        data.pages.remove().complete(new CatalogPage(List.of(row("DEF456")),false)); ui.drain();
        assertEquals("Trang 2",ui.screen.icons().get(49).title());
    }

    @Test void failedPreviousAndFilterLeaveCachedPageAndFiltersPaired() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),true)); ui.drain();
        ui.click(50); data.pages.remove().complete(new CatalogPage(List.of(row("DEF456")),true)); ui.drain();
        ui.click(48); data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        ui.click(45); assertEquals("Trang 2",ui.screen.icons().get(49).title());
        ui.click(2); ui.prompt.accept("Blade"); var failedFilter=data.queries.getLast();
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        ui.click(49); assertEquals(failedFilter,data.queries.getLast());
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        ui.click(2); ui.prompt.accept("Hủy");
        assertEquals("Trang 2",ui.screen.icons().get(49).title());
        assertEquals(List.of("Chưa lọc"),ui.screen.icons().get(2).lore());
        assertEquals("DEF456",ui.screen.icons().get(9).lore().getFirst());
        ui.click(4); ui.click(20);
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain(); ui.click(45);
        assertEquals("Loại đồ: Tất cả",ui.screen.icons().get(4).title());
        ui.click(50); assertEquals("DEF456",data.queries.getLast().afterCode());
        assertEquals("",data.queries.getLast().text());
    }

    @Test void firstFailureCancelStaysErrorAndHistoryRetryRechecksPermission() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        assertNull(ui.screen.icons().get(45));
        ui.click(2); ui.prompt.accept("Hủy"); assertTrue(ui.screen.title().contains("Lỗi"));
        ui.click(49); data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),false)); ui.drain();
        ui.click(9); ui.click(20); data.history.completeExceptionally(new IllegalStateException("busy")); ui.drain();
        assertEquals("Về hồ sơ",ui.screen.icons().get(45).title());
        data.history=new CompletableFuture<>(); ui.click(49); assertEquals("ABC123",data.historyCode);
        data.history.completeExceptionally(new IllegalStateException("busy")); ui.drain();
        int count=data.historyCalls; ui.historyAllowed=false; ui.click(49);
        assertTrue(ui.closed); assertEquals(count,data.historyCalls);
    }

    @Test void retryCallbackAfterCloseDoesNotRender() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        ui.click(49); var loading=ui.screen; c.close();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),false)); ui.drain();
        assertSame(loading,ui.screen);
    }

    @Test void itemBHistoryFailureNeverExposesItemACacheOrCatalogFilters() {
        var data=new Data(); var ui=new Port(); var c=new CatalogController(data,ui); c.open();
        data.pages.remove().complete(new CatalogPage(List.of(row("ABC123"),row("DEF456")),false)); ui.drain();
        ui.click(9); ui.click(20);
        data.history.complete(List.of(new CatalogEvent(1,"A_ONLY","Lan","uuid","loc",1,"detail"))); ui.drain();
        var staleEvent=ui.screen.icons().get(9).action(); ui.click(45); ui.click(45);
        ui.click(10); data.history=new CompletableFuture<>(); ui.click(20);
        data.history.completeExceptionally(new IllegalStateException("busy")); ui.drain();
        var error=ui.screen; staleEvent.run(); assertSame(error,ui.screen);
        assertNull(ui.screen.icons().get(2),"history errors must not mix catalog filter controls");
        assertNull(ui.screen.icons().get(9));
        data.history=new CompletableFuture<>(); ui.click(49); assertEquals("DEF456",data.historyCode);
        data.history.complete(List.of(new CatalogEvent(2,"B_ONLY","Lan","uuid","loc",2,"detail"))); ui.drain();
        assertEquals("B_ONLY",ui.screen.icons().get(9).title());
        ui.click(45); ui.click(45); ui.click(6);
        data.pages.remove().completeExceptionally(new IllegalStateException("busy")); ui.drain();
        assertEquals("Về trang trước đó",ui.screen.icons().get(45).title());
    }

    @Test void realAdmissionGateRepeatedDenialsDoNotConsumePageStack() {
        var clock=new java.util.concurrent.atomic.AtomicLong(); var gate=new CatalogReadGate(clock::get);
        var raw=new Data();
        CatalogController.Data gated=new CatalogController.Data() {
            public CompletableFuture<CatalogPage> find(CatalogQuery query) { return gate.submit(()->raw.find(query)); }
            public CompletableFuture<List<CatalogEvent>> history(String code,String uuid) { return gate.submit(()->raw.history(code,uuid)); }
            public CompletableFuture<CatalogObservations> observations(String code,String uuid) { return gate.submit(()->raw.observations(code,uuid)); }
        };
        var ui=new Port(); var c=new CatalogController(gated,ui); c.open();
        raw.pages.remove().complete(new CatalogPage(List.of(row("ABC123")),true)); ui.drain(); ui.click(50); ui.drain();
        for(int i=0;i<5;i++) { ui.click(49); ui.drain(); assertTrue(ui.screen.title().contains("Lỗi")); }
        assertEquals(1,raw.queries.size()); clock.set(1_000_000_000L);
        ui.click(49); assertEquals("ABC123",raw.queries.getLast().afterCode());
        raw.pages.remove().complete(new CatalogPage(List.of(row("DEF456")),false)); ui.drain();
        assertEquals("Trang 2",ui.screen.icons().get(49).title());
    }

    static CatalogRow row(String code) { return new CatalogRow(code, "uuid-"+code, "DIAMOND_SWORD", "Sword", "Lan", "world (1,2,3)", 1); }
    static class Data implements CatalogController.Data {
        List<CatalogQuery> queries = new ArrayList<>();
        Queue<CompletableFuture<CatalogPage>> pages = new ArrayDeque<>();
        CompletableFuture<List<CatalogEvent>> history = new CompletableFuture<>(); String historyCode; int historyCalls;
        CompletableFuture<CatalogObservations> observations = new CompletableFuture<>(); String observationCode, observationUuid; int observationCalls;
        public CompletableFuture<CatalogObservations> observations(String code,String uuid) {
            observationCalls++; observationCode=code; observationUuid=uuid; return observations;
        }
        public CompletableFuture<CatalogPage> find(CatalogQuery query) {
            queries.add(query); var f = new CompletableFuture<CatalogPage>(); pages.add(f); return f;
        }
        public CompletableFuture<List<CatalogEvent>> history(String code, String uuid) { historyCode=code; historyCalls++; return history; }
    }
    static class Port implements CatalogController.Port {
        CatalogScreen screen; boolean allowed=true, historyAllowed=true, closed;
        Queue<Runnable> tasks=new ArrayDeque<>(); Consumer<String> prompt;
        public boolean allowed(boolean history) { return allowed && (!history || historyAllowed); }
        public void main(Runnable action) { tasks.add(action); }
        public void show(CatalogScreen screen) { this.screen=screen; }
        public void close() { closed=true; }
        public void prompt(boolean owner, Consumer<String> completion) { prompt=completion; }
        public void message(String message) {}
        void click(int slot) { var action=screen.icons().get(slot).action(); assertNotNull(action); action.run(); }
        void drain() { while(!tasks.isEmpty()) tasks.remove().run(); }
    }
}
