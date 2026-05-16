package client.ui;

import java.util.prefs.Preferences;

/**
 * Handles lightweight persistent storage for the client launcher.
 * <p>
 * This class stores and retrieves user-specific settings (username and host)
 * using the Java Preferences API. Values persist across application runs,
 * allowing the launcher to pre-fill fields for convenience.
 */
public class ClientSettings {

    /** Root node under which all SketchSync client preferences are stored. */
    private static final String PREF_NODE = "sketchsync/client";

    /** Preference key for storing the last-used username. */
    private static final String KEY_USERNAME = "username";

    /** Preference key for storing the last-used host address. */
    private static final String KEY_HOST = "host";

    /** Preferences instance scoped to this application's node. */
    private final Preferences preferences;

    /**
     * Creates a new settings manager bound to the SketchSync preference node.
     * <p>
     * Using {@code userRoot()} ensures settings are stored per-user rather than system-wide.
     */
    public ClientSettings() {
        this.preferences = Preferences.userRoot().node(PREF_NODE);
    }

    /**
     * Retrieves the stored username.
     *
     * @return the saved username, or an empty string if none exists.
     */
    public String getUsername() {
        return preferences.get(KEY_USERNAME, "").trim();
    }

    /**
     * Retrieves the stored host address.
     *
     * @return the saved host, or {@code "localhost"} if none exists.
     */
    public String getHost() {
        return preferences.get(KEY_HOST, "localhost").trim();
    }

    /**
     * Saves the provided username and host to persistent storage.
     * <p>
     * - Null usernames are stored as empty strings.
     * - Null or blank hosts default to {@code "localhost"} to ensure a valid value.
     *
     * @param username the username to store (may be null)
     * @param host     the host address to store (may be null or blank)
     */
    public void save(String username, String host) {
        preferences.put(KEY_USERNAME, username == null ? "" : username.trim());
        preferences.put(KEY_HOST, host == null || host.trim().isEmpty()
                ? "localhost"
                : host.trim());
    }
}