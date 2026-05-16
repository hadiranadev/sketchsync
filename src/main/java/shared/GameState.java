package shared;

import java.io.*;
import java.util.*;

/**
 * Represents the current state of the game for persistence.
 * Can be saved to and loaded from files using a simple text format.
 */
public class GameState {

    // ---- Core persisted fields ----

    /** Snapshot of all players and their scores/roles. */
    private List<Player> players;

    /** Current round number (1‑based). */
    private int currentRound;

    /** The secret word for the current round (empty if none). */
    private String currentWord;

    /** Username of the current drawer. */
    private String currentDrawerUsername;

    /** Whether a game session is currently running. */
    private boolean gameRunning;

    /** Timestamp when this snapshot was created. */
    private long timestamp;

    // ---- Constructors ----

    /** Creates an empty/default game state. */
    public GameState() {
        this.players = new ArrayList<>();
        this.currentRound = 0;
        this.currentWord = "";
        this.currentDrawerUsername = "";
        this.gameRunning = false;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Creates a populated game state snapshot.
     * Used by DataManager when saving the current session.
     */
    public GameState(List<Player> players, int currentRound, String currentWord,
                     String currentDrawerUsername, boolean gameRunning) {
        this.players = new ArrayList<>(players);
        this.currentRound = currentRound;
        this.currentWord = currentWord;
        this.currentDrawerUsername = currentDrawerUsername;
        this.gameRunning = gameRunning;
        this.timestamp = System.currentTimeMillis();
    }

    // ---- Getters ----

    /** Returns a defensive copy of the player list. */
    public List<Player> getPlayers() {
        return new ArrayList<>(players);
    }

    public int getCurrentRound() { return currentRound; }
    public String getCurrentWord() { return currentWord; }
    public String getCurrentDrawerUsername() { return currentDrawerUsername; }
    public boolean isGameRunning() { return gameRunning; }
    public long getTimestamp() { return timestamp; }

    // ---- Setters ----

    /** Replaces the player list with a defensive copy. */
    public void setPlayers(List<Player> players) {
        this.players = new ArrayList<>(players);
    }

    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public void setCurrentWord(String currentWord) { this.currentWord = currentWord; }
    public void setCurrentDrawerUsername(String currentDrawerUsername) { this.currentDrawerUsername = currentDrawerUsername; }
    public void setGameRunning(boolean gameRunning) { this.gameRunning = gameRunning; }

    // ---- Persistence: Save ----

    /**
     * Saves the current game state to a text file.
     * Format is simple key=value pairs for easy debugging and manual editing.
     */
    public boolean saveToFile(String filename) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {

            writer.println("currentRound=" + currentRound);
            writer.println("currentWord=" + currentWord);
            writer.println("currentDrawerUsername=" + currentDrawerUsername);
            writer.println("gameRunning=" + gameRunning);
            writer.println("timestamp=" + timestamp);
            writer.println("playerCount=" + players.size());

            // Write each player's data
            for (int i = 0; i < players.size(); i++) {
                Player player = players.get(i);
                writer.println("player" + i + "_username=" + player.getUsername());
                writer.println("player" + i + "_score=" + player.getScore());
                writer.println("player" + i + "_isDrawer=" + player.isDrawer());
                writer.println("player" + i + "_hasGuessed=" + player.hasGuessedCorrectly());
            }

            return true;

        } catch (IOException e) {
            System.err.println("failed to save: " + e.getMessage());
            return false;
        }
    }

    // ---- Persistence: Load ----

    /**
     * Loads a GameState from a text file.
     * Missing or malformed fields fall back to safe defaults.
     */
    public static GameState loadFromFile(String filename) {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {

            GameState state = new GameState();
            Map<String, String> data = new HashMap<>();

            // Parse key=value lines
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        data.put(parts[0], parts[1]);
                    }
                }
            }

            // Restore primitive fields
            state.currentRound = Integer.parseInt(data.getOrDefault("currentRound", "0"));
            state.currentWord = data.getOrDefault("currentWord", "");
            state.currentDrawerUsername = data.getOrDefault("currentDrawerUsername", "");
            state.gameRunning = Boolean.parseBoolean(data.getOrDefault("gameRunning", "false"));
            state.timestamp = Long.parseLong(data.getOrDefault("timestamp", "0"));

            int playerCount = Integer.parseInt(data.getOrDefault("playerCount", "0"));

            // Restore players
            state.players = new ArrayList<>();
            for (int i = 0; i < playerCount; i++) {

                String username = data.get("player" + i + "_username");
                if (username == null) continue; // skip incomplete entries

                Player player = new Player(username);

                // Score
                String scoreStr = data.get("player" + i + "_score");
                if (scoreStr != null) {
                    int score = Integer.parseInt(scoreStr);
                    player.addScore(score);
                }

                // Drawer flag
                String isDrawerStr = data.get("player" + i + "_isDrawer");
                if (isDrawerStr != null) {
                    player.setDrawer(Boolean.parseBoolean(isDrawerStr));
                }

                // Guess flag
                String hasGuessedStr = data.get("player" + i + "_hasGuessed");
                if (hasGuessedStr != null) {
                    player.setHasGuessedCorrectly(Boolean.parseBoolean(hasGuessedStr));
                }

                state.players.add(player);
            }

            System.out.println("loaded game state from " + filename);
            return state;

        } catch (IOException | NumberFormatException e) {
            System.err.println("failed to load: " + e.getMessage());
            return null;
        }
    }

    /** Saves to the default gamestate.txt file. */
    public boolean save() {
        return saveToFile("gamestate.txt");
    }

    /** Loads from the default gamestate.txt file. */
    public static GameState load() {
        return loadFromFile("gamestate.txt");
    }

    @Override
    public String toString() {
        return "GameState{" +
                "players=" + players.size() +
                ", round=" + currentRound +
                ", word='" + currentWord + '\'' +
                ", drawer='" + currentDrawerUsername + '\'' +
                ", running=" + gameRunning +
                ", timestamp=" + new Date(timestamp) +
                '}';
    }
}