package com.itemguard.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

/** Common transaction/admission seam shared by the local and Premium JDBC owners. */
public interface JdbcConnectionOwner extends AutoCloseable {
    @FunctionalInterface
    interface JdbcOperation<T> {
        T apply(Connection connection) throws Exception;
    }

    void execute(JdbcOperation<Void> operation);

    <T> T call(JdbcOperation<T> operation);

    <T> CompletableFuture<T> callAsync(JdbcOperation<T> operation);

    /** Runs identity-affecting work under the backend's identity lock when one exists. */
    default <T> T callIdentityLocked(String code, JdbcOperation<T> operation) {
        return call(operation);
    }

    default <T> CompletableFuture<T> callIdentityLockedAsync(
        String code,
        JdbcOperation<T> operation
    ) {
        return callAsync(operation);
    }

    default void executeIdentityLocked(String code, JdbcOperation<Void> operation) {
        execute(operation);
    }

    default <T> T callIdentityLockedOrCreate(String code, JdbcOperation<T> operation) {
        return callIdentityLocked(code, operation);
    }

    default <T> CompletableFuture<T> callIdentityLockedOrCreateAsync(
        String code,
        JdbcOperation<T> operation
    ) {
        return callIdentityLockedAsync(code, operation);
    }

    void flush();

    /** Closes within the requested bound; SQLite uses the result to preserve its lock contract. */
    default boolean close(java.time.Duration timeout) {
        close();
        return true;
    }

    @Override
    void close();
}
