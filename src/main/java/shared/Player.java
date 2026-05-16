package shared;

import java.io.Serial;
import java.io.Serializable;

/**
 * Represents a connected player in the game.
 *
 * Stores:
 *   - username (immutable identity)
 *   - score (persists across rounds)
 *   - role flags (drawer / guesser)
 *   - guess status for the current round
 *
 * Serializable so Player objects can be sent across sockets
 * as part of Message payloads (e.g., SCORE_UPDATE, GAME_STATE).
 */
public class Player implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // --- Identity ---
    /** Unique username chosen by the player. */
    private final String username;

    // --- Score & round state ---
    /** Total score accumulated during the current game session. */
    private int score;

    /** True if this player is the drawer for the current round. */
    private boolean isDrawer;

    /** True if this player has already guessed correctly this round. */
    private boolean hasGuessedCorrectly;

    // --- Constructor ---

    /** Creates a new player with zero score and no assigned role. */
    public Player(String username) {
        this.username = username;
        this.score = 0;
        this.isDrawer = false;
        this.hasGuessedCorrectly = false;
    }

    // --- Getters ---

    public String getUsername() { return username; }
    public int getScore() { return score; }
    public boolean isDrawer() { return isDrawer; }
    public boolean hasGuessedCorrectly() { return hasGuessedCorrectly; }

    // --- Mutators ---

    /** Adds points to the player's total score. */
    public void addScore(int points) { this.score += points; }

    /** Sets whether this player is the drawer for the current round. */
    public void setDrawer(boolean drawer) { this.isDrawer = drawer; }

    /** Marks whether the player has guessed correctly this round. */
    public void setHasGuessedCorrectly(boolean guessed) { this.hasGuessedCorrectly = guessed; }

    /**
     * Resets per‑round state:
     *   - drawer flag
     *   - guessed‑correctly flag
     *
     * Called at the start of each new round.
     */
    public void resetRoundState() {
        this.isDrawer = false;
        this.hasGuessedCorrectly = false;
    }

    @Override
    public String toString() {
        return username + " (score=" + score + ", drawer=" + isDrawer + ")";
    }
}