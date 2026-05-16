package shared;

import java.io.*;
import java.util.*;

/**
 * Handles loading and managing the word bank for the game.
 * Reads words from resources/words.txt and provides them to the game logic.
 *
 * If the file is missing or unreadable, a small fallback list is used.
 */
public class WordRepository {

    /** In‑memory list of all available words. */
    private final List<String> words;

    /** Random generator used for selecting words. */
    private final Random random;

    /** Loads the word list on construction. */
    public WordRepository() {
        this.words = new ArrayList<>();
        this.random = new Random();
        loadWords();
    }

    /**
     * Loads words from resources/words.txt.
     * Each non‑empty trimmed line becomes a word.
     * Falls back to a small default list if the file is missing or unreadable.
     */
    private void loadWords() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("words.txt")) {

            if (is == null) {
                System.err.println("[WordRepository] words.txt not found — using fallback words.");
                words.addAll(List.of("apple", "banana", "cat", "dog", "elephant"));
                return;
            }

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
            String line;

            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    words.add(line);
                }
            }

        } catch (IOException e) {
            System.err.println("Failed to load words: " + e.getMessage());
            words.addAll(List.of("apple", "banana", "cat", "dog", "elephant"));
        }
    }

    /** Returns a single random word, or "default" if the list is empty. */
    public String getRandomWord() {
        if (words.isEmpty()) return "default";
        return words.get(random.nextInt(words.size()));
    }

    /**
     * Returns 3 distinct random words for the word‑choice feature.
     * If fewer than 3 words exist, returns as many as available.
     */
    public List<String> getThreeRandomWords() {
        if (words.isEmpty()) return List.of("apple", "banana", "cat");

        List<String> shuffled = new ArrayList<>(words);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, Math.min(3, shuffled.size()));
    }

    /** Returns a defensive copy of the full word list. */
    public List<String> getAllWords() {
        return new ArrayList<>(words);
    }

    /** Returns the total number of words loaded. */
    public int getWordCount() {
        return words.size();
    }
}