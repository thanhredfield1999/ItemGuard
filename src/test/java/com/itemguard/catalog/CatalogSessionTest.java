package com.itemguard.catalog;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogSessionTest {
    @Test void closedAndReplacedRequestsCannotRender() {
        var session = new CatalogSession();
        long first = session.begin();
        assertTrue(session.accepts(first));
        long second = session.begin();
        assertFalse(session.accepts(first), "old result cannot render after newer request");
        assertTrue(session.accepts(second));
        session.close();
        assertFalse(session.accepts(second), "close revokes pending callback");
    }
}
