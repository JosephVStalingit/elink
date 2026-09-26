package com.example.mcp2p.identity;

import java.util.Locale;

/**
 * An account as the account service knows it: the authoritative name and the account id.
 *
 * @param uuid the account id, without dashes as the API returns it
 * @param name the in-game name that belongs to the account
 */
public record PlayerIdentity(String uuid, String name) {
    /** @return the uuid in the dashed form, or the input when it is not a plain 32 character id */
    public String dashedUuid() {
        if (uuid == null || uuid.length() != 32) {
            return uuid;
        }
        return uuid.substring(0, 8)
                + '-'
                + uuid.substring(8, 12)
                + '-'
                + uuid.substring(12, 16)
                + '-'
                + uuid.substring(16, 20)
                + '-'
                + uuid.substring(20);
    }

    /** @return the name, or {@code null} when there is none */
    public String nameOrNull() {
        return name == null || name.isBlank() ? null : name;
    }

    /** @return {@code true} when the given name or uuid belongs to this identity */
    public boolean matches(final String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        final String needle = candidate.trim().toLowerCase(Locale.ROOT);
        return needle.equals(name == null ? "" : name.toLowerCase(Locale.ROOT))
                || needle.equals(uuid == null ? "" : uuid.toLowerCase(Locale.ROOT))
                || needle.equals(dashedUuid() == null ? "" : dashedUuid().toLowerCase(Locale.ROOT));
    }
}
