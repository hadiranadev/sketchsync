package shared;

import java.util.List;

/**
 * Central point for data operations in the game.
 * Coordinates between WordRepository, GameState, and Leaderboard.
 */
public class DataManager {

    /** Provides random words and word lists for the game. */
    private final WordRepository wordRepository;

    /** Stores and persists all‑time player scores. */
    private final Leaderboard leaderboard;

    /** Snapshot of the current game session (round, drawer, word, scores). */
    private GameState currentGameState;

    public DataManager() {
        this.wordRepository    = new WordRepository();
        this.leaderboard       = new Leaderboard();
        this.currentGameState  = new GameState();
    }

    // --- Words ---

    /** Returns a single random word from the repository. */
    public String getRandomWord()       { return wordRepository.getRandomWord(); }

    /** Returns three random words for the drawer to choose from. */
    public List<String> getThreeWords() { return wordRepository.getThreeRandomWords(); }

    /** Returns the full list of available words. */
    public List<String> getAllWords()   { return wordRepository.getAllWords(); }

    /** Returns the total number of words available. */
    public int getWordCount()           { return wordRepository.getWordCount(); }

    // --- Game State ---

    /**
     * Updates the in‑memory snapshot of the current game state.
     * Called by GameManager at key transitions (round start/end, drawer change, etc.).
     */
    public void updateGameState(List<Player> players, int currentRound,
                                String currentWord, String drawerUsername, boolean running) {
        currentGameState = new GameState(players, currentRound, currentWord, drawerUsername, running);
    }

    /** Returns the current in‑memory game state snapshot. */
    public GameState getCurrentGameState() { return currentGameState; }

    /** Saves the current game state to the default file. */
    public boolean saveGameState()                   { return currentGameState.save(); }

    /** Saves the current game state to a custom filename. */
    public boolean saveGameState(String filename)    { return currentGameState.saveToFile(filename); }

    /**
     * Loads game state from the default file.
     * Returns true if successful and updates currentGameState.
     */
    public boolean loadGameState() {
        GameState loaded = GameState.load();
        if (loaded != null) { currentGameState = loaded; return true; }
        return false;
    }

    /**
     * Loads game state from a custom filename.
     * Returns true if successful and updates currentGameState.
     */
    public boolean loadGameState(String filename) {
        GameState loaded = GameState.loadFromFile(filename);
        if (loaded != null) { currentGameState = loaded; return true; }
        return false;
    }

    // --- Leaderboard ---

    /** Merge session scores into all‑time totals and persist to disk. */
    public boolean updateAndSaveLeaderboard(List<Player> players) {
        leaderboard.addScores(players);
        return leaderboard.save();
    }

    /** Returns a formatted leaderboard string (top 10) for broadcasting to clients. */
    public String getLeaderboardText() {
        return leaderboard.format();
    }
}