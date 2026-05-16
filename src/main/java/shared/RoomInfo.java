package shared;

import java.io.Serializable;
import java.util.List;

/**
 * Lightweight, serializable snapshot of a Room sent to lobby clients.
 *
 * The full Room object lives only on the server.
 * RoomInfo is the trimmed-down version sent over the network inside:
 *   - ROOM_LIST   (lobby list of all rooms)
 *   - ROOM_UPDATE (updates when members/mode change)
 *
 * Clients use RoomInfo to render lobby UI without needing server-side classes.
 */
public class RoomInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Room modes available to the host. */
    public enum Mode { DOODLE, GAME }

    // --- Core room metadata ---

    /** Room name (unique, case-insensitive). */
    private final String name;

    /** Username of the host (always memberUsernames.get(0)). */
    private final String hostUsername;

    /** Current mode: DOODLE or GAME. */
    private final Mode mode;

    /** Ordered list of usernames in the room; index 0 = host. */
    private final List<String> memberUsernames;

    /** True if a game or doodle session is currently active. */
    private final boolean inProgress;

    /**
     * Creates an immutable snapshot of a room's public state.
     *
     * @param name             room name
     * @param hostUsername     username of the host
     * @param mode             DOODLE or GAME
     * @param memberUsernames  ordered list of members (host first)
     * @param inProgress       true if a session is running
     */
    public RoomInfo(String name, String hostUsername, Mode mode,
                    List<String> memberUsernames, boolean inProgress) {
        this.name            = name;
        this.hostUsername    = hostUsername;
        this.mode            = mode;
        this.memberUsernames = List.copyOf(memberUsernames); // defensive copy
        this.inProgress      = inProgress;
    }

    // --- Getters ---

    public String       getName()            { return name; }
    public String       getHostUsername()    { return hostUsername; }
    public Mode         getMode()            { return mode; }
    public List<String> getMemberUsernames() { return memberUsernames; }

    /** Convenience: number of players in the room. */
    public int getPlayerCount() { return memberUsernames.size(); }

    /** True if a game round or doodle session is active. */
    public boolean isInProgress() { return inProgress; }

    @Override
    public String toString() {
        return name + " [" + mode + "] " + memberUsernames.size() + " player(s)"
                + (inProgress ? " — in progress" : "");
    }
}