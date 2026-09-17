package com.itemguard.multiserver;

/**
 * What a cross-server check can honestly conclude from observations.
 *
 * <p>The names matter: there is deliberately no {@code DUPLICATE} and no {@code COPY} here. Two
 * observations of one identity on two servers look identical whether the item was legitimately
 * moved or duplicated, so a verdict is not available from this data — only the fact of where it
 * was seen. A constant named {@code DUPLICATE} would be a claim the evidence does not support, and
 * one that would eventually be printed in a chat message to a staff member who then banned someone.
 */
public enum CrossServerStatus {
    /** Nothing to report: the identity was seen on at most one server inside the window. */
    NONE,

    /** The same identity was seen on two or more servers inside the window. Not a verdict. */
    SEEN_ON_MULTIPLE_SERVERS
}
