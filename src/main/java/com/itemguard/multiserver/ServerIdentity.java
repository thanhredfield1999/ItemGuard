package com.itemguard.multiserver;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who this server is, in the only place a multi-server finding can name it.
 *
 * <p>D1 of the design keeps server identity as a column rather than a table, so the value is a
 * string an operator reads in a finding ("survival-1"). That puts two requirements on it: it has
 * to survive a restart <em>without changing</em> (a name that changed every boot would turn every
 * finding into noise), and it has to be representable in a chat line and a `WHERE server_id = ?`
 * without quoting gymnastics.
 *
 * <p>This class only decides <em>which</em> name wins. Where a generated or remembered name is
 * stored is the connection owner's business (D1 says: not a `servers` table), and nothing here
 * writes anything.
 */
public final class ServerIdentity {

    /** Long enough for a hostname, short enough to read in a finding. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

    private final String name;
    private final boolean newlyGenerated;

    private ServerIdentity(String name, boolean newlyGenerated) {
        this.name = name;
        this.newlyGenerated = newlyGenerated;
    }

    /** The configured name, if there is one. */
    public static ServerIdentity configured(String name) {
        return new ServerIdentity(validate(name, "configured"), false);
    }

    /** The name remembered from an earlier start, used when nothing is configured. */
    public static ServerIdentity remembered(String name) {
        return new ServerIdentity(validate(name, "remembered"), false);
    }

    /**
     * A name nothing else knows yet. The caller is responsible for storing it before the next
     * start — otherwise the next start generates another one and the findings of the two runs
     * cannot be told apart, which is the failure this flag exists to make visible.
     */
    public static ServerIdentity generated(String name) {
        return new ServerIdentity(validate(name, "generated"), true);
    }

    public String name() {
        return name;
    }

    public boolean newlyGenerated() {
        return newlyGenerated;
    }

    static String validate(String name, String origin) {
        Objects.requireNonNull(name, origin + " server id");
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("The " + origin + " server id is blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "The " + origin + " server id is longer than " + MAX_LENGTH + " characters: "
                    + trimmed.length()
            );
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                "The " + origin + " server id may contain letters, digits, dot, underscore and "
                    + "hyphen only (it is printed in findings and compared byte-wise): " + trimmed
            );
        }
        return trimmed;
    }
}
