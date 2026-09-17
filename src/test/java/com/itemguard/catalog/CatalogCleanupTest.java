package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCleanupTest {
    @Test void originalQueryErrorSurvivesBothCleanupFailures() {
        var original=new SQLException("query interrupted");
        var clear=new SQLException("clear failed"); var destroy=new SQLException("destroy failed");
        assertDoesNotThrow(()->CatalogRepository.cleanup(original,()->{throw clear;},()->{throw destroy;}),
            "cleanup must retain original query error");
        assertArrayEquals(new Throwable[]{clear,destroy},original.getSuppressed());
    }
    @Test void successfulQueryStillFailsIfCleanupFailsAndRunsBothActions() {
        var clear=new SQLException("clear failed"); var destroy=new SQLException("destroy failed");
        var failure=assertThrows(SQLException.class,()->CatalogRepository.cleanup(null,()->{throw clear;},()->{throw destroy;}));
        assertSame(clear,failure); assertArrayEquals(new Throwable[]{destroy},failure.getSuppressed());
    }
}
