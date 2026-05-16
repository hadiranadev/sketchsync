package shared;

import java.io.*;
import java.util.*;

/**
 * Persistent all‑time leaderboard stored in leaderboard.txt.
 *
 * Format (one entry per line):
 *   username=totalScore
 *
 * Scores accumulate across every game session. When a game ends,
 * GameManager calls addScores() with per‑session totals, then save().
 */
public class Leaderboard {

    /** File where all‑time scores are stored. */
    private static final String FILE = "leaderboard.txt";

    /** Map of username → all‑time score (kept in insertion order). */
    private final Map<String, Integer> scores;

    public Leaderboard() {
        scores = new LinkedHashMap<>();
        load();
    }

    /**
     * Adds the final per‑session scores to the all‑time totals.
     * Called by GameManager at the end of each game.
     */
    public synchronized void addScores(List<Player> players) {
        for (Player p : players) {
            scores.merge(p.getUsername(), p.getScore(), Integer::sum);
        }
    }

    /**
     * Saves the leaderboard to disk.
     * Sorted in descending score order for readability.
     */
    public synchronized boolean save() {
        try (PrintWriter w = new PrintWriter(new FileWriter(FILE))) {
            scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> w.println(e.getKey() + "=" + e.getValue()));
            return true;
        } catch (IOException e) {
            System.err.println("[Leaderboard] save failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Loads leaderboard.txt if it exists.
     * Missing or malformed lines are ignored safely.
     */
    private void load() {
        File f = new File(FILE);
        if (!f.exists()) return;

        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    try {
                        scores.put(parts[0], Integer.parseInt(parts[1]));
                    } catch (NumberFormatException ignored) {
                        // Skip malformed score entries
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Leaderboard] load failed: " + e.getMessage());
        }
    }

    /**
     * Returns a formatted leaderboard string for broadcasting to clients.
     * Example:
     *   1. Alice — 450
     *   2. Bob — 300
     *   3. Carol — 200
     */
    public synchronized String format() {
        if (scores.isEmpty()) return "No scores yet.";

        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .toList();

        for (int i = 0; i < Math.min(sorted.size(), 10); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            sb.append(i + 1).append(". ")
                    .append(e.getKey()).append(" — ").append(e.getValue())
                    .append("\n");
        }
        return sb.toString().trim();
    }
}