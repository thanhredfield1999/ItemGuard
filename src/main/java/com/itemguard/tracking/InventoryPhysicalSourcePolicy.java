package com.itemguard.tracking;

import java.security.MessageDigest;

public final class InventoryPhysicalSourcePolicy {

    public boolean samePhysicalHandle(
        Object expectedPhysicalHandle,
        Object currentPhysicalHandle
    ) {
        return expectedPhysicalHandle != null
            && expectedPhysicalHandle == currentPhysicalHandle;
    }

    public boolean matches(
        Object expectedPhysicalHandle,
        Object currentPhysicalHandle,
        byte[] expectedDigest,
        byte[] currentDigest
    ) {
        return samePhysicalHandle(expectedPhysicalHandle, currentPhysicalHandle)
            && expectedDigest != null
            && currentDigest != null
            && MessageDigest.isEqual(expectedDigest, currentDigest);
    }

    public boolean matches(
        boolean authoritative,
        Object expectedPhysicalHandle,
        Object currentPhysicalHandle,
        byte[] expectedDigest,
        byte[] currentDigest
    ) {
        return authoritative && matches(
            expectedPhysicalHandle,
            currentPhysicalHandle,
            expectedDigest,
            currentDigest
        );
    }
}
