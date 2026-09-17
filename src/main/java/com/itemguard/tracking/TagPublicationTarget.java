package com.itemguard.tracking;

public interface TagPublicationTarget {

    String sourceKey();

    boolean matches(byte[] expectedDigest);

    void write(TagPublication publication);

    default void preparationFailed(Throwable failure) {
    }

    default void published(TagPublication publication) {
    }

    default void publishFailed(TagPublication publication, Throwable failure) {
    }
}
