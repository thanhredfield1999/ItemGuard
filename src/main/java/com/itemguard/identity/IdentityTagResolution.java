package com.itemguard.identity;

import java.util.UUID;

public record IdentityTagResolution(
    IdentityTagStatus status,
    String code,
    UUID itemUuid
) {}
