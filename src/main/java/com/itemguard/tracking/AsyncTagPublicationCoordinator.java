package com.itemguard.tracking;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class AsyncTagPublicationCoordinator {

    private static final int MAX_RESERVE_ATTEMPTS = 3;

    private final TagPublicationStore store;
    private final Consumer<Runnable> mainThreadDispatcher;
    private final BooleanSupplier pluginEnabled;
    private final LongSupplier clock;
    private final Set<String> inFlightSources = ConcurrentHashMap.newKeySet();

    public AsyncTagPublicationCoordinator(
        TagPublicationStore store,
        Consumer<Runnable> mainThreadDispatcher,
        BooleanSupplier pluginEnabled,
        LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.mainThreadDispatcher = Objects.requireNonNull(
            mainThreadDispatcher,
            "mainThreadDispatcher"
        );
        this.pluginEnabled = Objects.requireNonNull(pluginEnabled, "pluginEnabled");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean request(
        Supplier<TagPublication> proposalFactory,
        TagPublicationTarget target
    ) {
        Objects.requireNonNull(proposalFactory, "proposalFactory");
        Objects.requireNonNull(target, "target");
        String sourceKey = Objects.requireNonNull(target.sourceKey(), "sourceKey");
        if (!inFlightSources.add(sourceKey)) {
            return false;
        }

        final TagPublication proposed;
        try {
            proposed = Objects.requireNonNull(proposalFactory.get(), "publication");
            if (!sourceKey.equals(proposed.sourceKey())) {
                throw new IllegalArgumentException("Publication source key mismatch");
            }
        } catch (RuntimeException failure) {
            inFlightSources.remove(sourceKey);
            throw failure;
        }

        try {
            reserve(proposed, proposalFactory, target, sourceKey, proposed.sourceDigest(), 1);
        } catch (RuntimeException submissionFailure) {
            inFlightSources.remove(sourceKey);
            throw submissionFailure;
        }
        return true;
    }

    private void reserve(
        TagPublication proposed,
        Supplier<TagPublication> proposalFactory,
        TagPublicationTarget target,
        String sourceKey,
        byte[] originalDigest,
        int attempt
    ) {
        store.reserve(proposed).whenComplete((reserved, failure) -> {
            try {
                mainThreadDispatcher.accept(() -> {
                    if (failure == null && reserved != null) {
                        try {
                            validateProposal(reserved, sourceKey, originalDigest);
                        } catch (RuntimeException invalidReservation) {
                            preparationFailed(target, sourceKey, invalidReservation);
                            return;
                        }
                        publishOnMainThread(reserved, target);
                        return;
                    }
                    Throwable cause = unwrapCompletion(failure);
                    if (cause instanceof TagIdentityCollisionException && attempt < MAX_RESERVE_ATTEMPTS) {
                        final TagPublication fresh;
                        try {
                            if (!pluginEnabled.getAsBoolean()) {
                                inFlightSources.remove(sourceKey);
                                return;
                            }
                            if (!target.matches(originalDigest.clone())) {
                                throw new IllegalStateException("Source changed before identity retry");
                            }
                            fresh = Objects.requireNonNull(proposalFactory.get(), "publication");
                            validateProposal(fresh, sourceKey, originalDigest);
                        } catch (RuntimeException retryFailure) {
                            preparationFailed(target, sourceKey, retryFailure);
                            return;
                        }
                        try {
                            reserve(fresh, proposalFactory, target, sourceKey, originalDigest, attempt + 1);
                        } catch (RuntimeException submissionFailure) {
                            preparationFailed(target, sourceKey, submissionFailure);
                        }
                    } else {
                        preparationFailed(target, sourceKey, cause);
                    }
                });
            } catch (RuntimeException dispatchFailure) {
                inFlightSources.remove(sourceKey);
            }
        });
    }

    private Throwable unwrapCompletion(Throwable failure) {
        for (int depth = 0; depth < 8
            && (failure instanceof CompletionException || failure instanceof ExecutionException)
            && failure.getCause() != null; depth++) {
            failure = failure.getCause();
        }
        return failure;
    }

    private void validateProposal(TagPublication publication, String sourceKey, byte[] originalDigest) {
        if (!sourceKey.equals(publication.sourceKey())
            || !MessageDigest.isEqual(originalDigest, publication.sourceDigest())
            || publication.state() != TagPublicationState.PREPARED) {
            throw new IllegalArgumentException("Publication source, digest or state mismatch");
        }
    }

    private void preparationFailed(TagPublicationTarget target, String sourceKey, Throwable failure) {
        try {
            target.preparationFailed(failure);
        } finally {
            inFlightSources.remove(sourceKey);
        }
    }

    private void publishOnMainThread(
        TagPublication publication,
        TagPublicationTarget target
    ) {
        String sourceKey = publication.sourceKey();
        if (!pluginEnabled.getAsBoolean()) {
            inFlightSources.remove(sourceKey);
            return;
        }
        try {
            if (!target.matches(publication.sourceDigest())) {
                abort(publication, "SOURCE_CHANGED");
                return;
            }
        } catch (RuntimeException revalidationFailure) {
            abort(publication, "PHYSICAL_REVALIDATION_FAILED");
            return;
        }
        try {
            target.write(publication);
        } catch (RuntimeException writeFailure) {
            abort(publication, "PHYSICAL_WRITE_FAILED");
            return;
        }

        try {
            store.publish(publication.publicationId(), clock.getAsLong())
                .whenComplete((published, failure) -> {
                    try {
                        mainThreadDispatcher.accept(() -> {
                            try {
                                if (failure == null && Boolean.TRUE.equals(published)) {
                                    target.published(publication);
                                } else {
                                    target.publishFailed(publication, failure);
                                }
                            } finally {
                                inFlightSources.remove(sourceKey);
                            }
                        });
                    } catch (RuntimeException dispatchFailure) {
                        inFlightSources.remove(sourceKey);
                    }
                });
        } catch (RuntimeException submissionFailure) {
            try {
                target.publishFailed(publication, submissionFailure);
            } finally {
                inFlightSources.remove(sourceKey);
            }
        }
    }

    private void abort(TagPublication publication, String detail) {
        try {
            store.abort(publication.publicationId(), clock.getAsLong(), detail)
                .whenComplete((aborted, failure) ->
                    inFlightSources.remove(publication.sourceKey())
                );
        } catch (RuntimeException submissionFailure) {
            inFlightSources.remove(publication.sourceKey());
        }
    }
}
