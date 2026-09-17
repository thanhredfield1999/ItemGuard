package com.itemguard.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SqliteProcessLock implements AutoCloseable {

    private static final String LOCK_SUFFIX = ".itemguard.lock";
    private static final Set<SqliteProcessLock> RETAINED_UNTIL_EXIT =
        ConcurrentHashMap.newKeySet();

    private final Path lockPath;
    private final FileChannel channel;
    private final FileLock lock;

    private SqliteProcessLock(Path lockPath, FileChannel channel, FileLock lock) {
        this.lockPath = lockPath;
        this.channel = channel;
        this.lock = lock;
    }

    static SqliteProcessLock acquire(Path databasePath) {
        Path lockPath = deriveLockPath(databasePath);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            );
            FileLock lock = channel.tryLock();
            if (lock == null) {
                closeQuietly(channel);
                throw alreadyOwned(lockPath, null);
            }
            return new SqliteProcessLock(lockPath, channel, lock);
        } catch (OverlappingFileLockException overlapping) {
            closeQuietly(channel);
            throw alreadyOwned(lockPath, overlapping);
        } catch (IOException failure) {
            closeQuietly(channel);
            throw new IllegalStateException(
                "Cannot acquire ItemGuard SQLite process lock: " + lockPath,
                failure
            );
        }
    }

    private static Path deriveLockPath(Path databasePath) {
        Objects.requireNonNull(databasePath, "databasePath");
        Path absolute = databasePath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path fileName = absolute.getFileName();
        if (parent == null || fileName == null) {
            throw new IllegalArgumentException("SQLite database path must have a parent and file name");
        }
        try {
            Files.createDirectories(parent);
            Path realParent = parent.toRealPath();
            return realParent.resolve(fileName + LOCK_SUFFIX);
        } catch (IOException failure) {
            throw new IllegalStateException(
                "Cannot resolve ItemGuard SQLite database directory: " + parent,
                failure
            );
        }
    }

    @Override
    public void close() {
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException releaseFailure) {
            failure = releaseFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) {
                failure = closeFailure;
            } else {
                failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) {
            throw new IllegalStateException(
                "Cannot release ItemGuard SQLite process lock: " + lockPath,
                failure
            );
        }
    }

    void retainUntilProcessExit() {
        RETAINED_UNTIL_EXIT.add(this);
    }

    private static IllegalStateException alreadyOwned(Path lockPath, Throwable cause) {
        return new IllegalStateException(
            "ItemGuard SQLite database is already owned: " + lockPath,
            cause
        );
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Preserve the acquisition failure as the primary signal.
        }
    }
}
