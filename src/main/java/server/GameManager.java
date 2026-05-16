package server;

import shared.*;

import java.util.List;
import java.util.concurrent.*;

/**
 * GameManager — all game logic for one Room.
 *
 * Each Room owns its own GameManager so multiple rooms can run independent
 * games simultaneously.
 *
 * Responsibilities:
 *   - Round flow (drawer rotation, timers, scoring, transitions).
 *   - Word choice (3 options, timeout fallback).
 *   - Hint system (masked word, limited reveals).
 *   - Handling guesses and awarding points.
 *   - Detecting disconnects and too-few-player conditions.
 *   - Saving persistent leaderboard + game state.
 *   - Scheduling next rounds and automatic restarts.
 *
 * No networking or UI code lives here — all communication happens via Room.
 */
public class GameManager {

    // Configuration
    // Game configuration constants (round length, scoring, limits).
    // These define the rules for all rooms.
    public static final int ROUND_TIME = 60;
    public static final int MAX_ROUNDS = 3;
    public static final int MIN_PLAYERS = 2;
    public static final int GUESS_SCORE = 100;
    public static final int WORD_CHOICE_SECS = 15;
    public static final int MAX_HINTS_PER_ROUND = 2;
    public static final int DRAWER_SCORE_PER_GUESSER = 20; // points drawer earns per correct guesser

    // Dependencies
    /** The Room this GameManager belongs to — used for broadcasting. */
    private final Room room;
    private final DataManager dataManager;

    // Game State
    // Volatile fields ensure visibility across timer threads and room threads.
    // currentDrawer, secretWord, timers, and roundActive are accessed concurrently.
    private volatile boolean gameRunning   = false;
    /** Optional session-scoped word list. If set, overrides the default WordRepository. */
    private volatile java.util.List<String> customWordList = null;
    private final java.util.Random customRandom = new java.util.Random();
    private volatile int currentRound  = 0;
    private volatile String secretWord    = "";
    private volatile ClientHandler currentDrawer = null;
    private volatile boolean roundActive   = false;

    // Hint state (reset each round)
    private volatile char[] hintMask;
    private volatile int hintsGiven = 0;

    // Word-choice state
    private volatile boolean awaitingWordChoice = false;
    private volatile java.util.List<String> currentWordOptions = java.util.List.of();
    private ScheduledExecutorService wordChoiceTimer;

    // Timers
    private volatile int timeRemaining = ROUND_TIME;
    private ScheduledExecutorService roundTimer;
    private ScheduledExecutorService nextRoundScheduler;

    public GameManager(Room room, DataManager dataManager) {
        this.room = room;
        this.dataManager = dataManager;
    }

    /** Sets a session-scoped custom word list (overrides default repository). */
    public synchronized void setCustomWordList(java.util.List<String> words) {
        this.customWordList = (words == null || words.isEmpty()) ? null : new java.util.ArrayList<>(words);
        System.out.println("[GameManager] Custom word list set: " + (this.customWordList != null ? this.customWordList.size() + " words" : "cleared"));
    }

    public boolean isGameRunning()    { return gameRunning; }
    public ClientHandler getCurrentDrawer() { return currentDrawer; }

    /**
     * Called when a new player joins the room.
     * Auto-starts the game when MIN_PLAYERS threshold is reached.
     */
    public synchronized void onPlayerJoined(List<ClientHandler> clients, ClientHandler newPlayer) {
        if (!gameRunning && clients.size() >= MIN_PLAYERS) {
            startGame(clients);
        }
        saveState(clients);
    }

    /**
     * Handles drawer disconnects, too-few-player pauses,
     * and mid-round cleanup when a player leaves.
     */
    public synchronized void onPlayerLeft(List<ClientHandler> clients, ClientHandler departed) {
        if (!gameRunning) return;

        if (clients.size() < MIN_PLAYERS) {
            stopGame(clients);
            return;
        }

        if (departed == currentDrawer) {
            cancelRoundTimer();
            cancelWordChoiceTimer();
            roundActive        = false;
            awaitingWordChoice = false;
            room.broadcast(new Message(MessageType.ROUND_END, "Server", "The drawer disconnected — round ended early."));
            room.broadcastScores();
            scheduleNextRound(clients, 3);
        }
    }

    /** Host may force-start even if auto-start conditions aren't met. */
    public synchronized void forceStart(List<ClientHandler> clients) {
        if (gameRunning) {
            // Already running — ignore
            return;
        }
        startGame(clients);
    }

    // Resets all game state and begins round 1.
    private void startGame(List<ClientHandler> clients) {
        cancelNextRound();
        gameRunning = true;
        currentRound = 0;
        secretWord = "";
        currentDrawer = null;
        roundActive = false;
        awaitingWordChoice = false;
        currentWordOptions = java.util.List.of();
        timeRemaining = ROUND_TIME;
        clients.forEach(c -> c.getPlayer().resetRoundState());
        room.broadcast(new Message(MessageType.CHAT, "Server", "Game starting!"));
        startNextRound(clients);
    }

    /**
     * Begins the next round:
     *  - Advances round counter
     *  - Assigns drawer (rotating by round index)
     *  - Clears canvas
     *  - Sends role assignments + round start messages
     *  - Initiates word choice phase
     */
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    private void startNextRound(List<ClientHandler> clients) {
        if (!gameRunning || clients.size() < MIN_PLAYERS) return;

        currentRound++;
        if (currentRound > MAX_ROUNDS) {
            endGame(clients);
            return;
        }

        clients.forEach(c -> c.getPlayer().setHasGuessedCorrectly(false));
        hintsGiven = 0;
        hintMask   = null;

        int drawerIndex = (currentRound - 1) % clients.size();
        currentDrawer = clients.get(drawerIndex);

        room.broadcast(new Message(MessageType.CLEAR_CANVAS, "Server", null));

        for (ClientHandler c : clients) {
            boolean isDrawer = (c == currentDrawer);
            c.getPlayer().setDrawer(isDrawer);
            c.sendMessage(new Message(MessageType.ROLE_ASSIGNMENT, "Server", isDrawer ? "Drawer" : "Guesser"));
            c.sendMessage(new Message(MessageType.ROUND_START, "Server", String.valueOf(currentRound)));
        }

        room.broadcast(new Message(MessageType.CHAT, "Server",
                "Round " + currentRound + " — " + currentDrawer.getUsername() + " is choosing a word..."));

        startWordChoice(clients);
        saveState(clients);
    }

    /**
     * Sends 3 word options to the drawer.
     * If drawer does not choose within WORD_CHOICE_SECS,
     * a fallback word is automatically assigned.
     */
    private void startWordChoice(List<ClientHandler> clients) {
        List<String> options;
        if (customWordList != null && !customWordList.isEmpty()) {
            // Pick 3 random words from the custom list without repeating
            java.util.List<String> shuffled = new java.util.ArrayList<>(customWordList);
            java.util.Collections.shuffle(shuffled, customRandom);
            options = shuffled.subList(0, Math.min(3, shuffled.size()));
        } else {
            options = dataManager.getThreeWords();
        }
        String fallback = options.get(0);

        currentWordOptions = new java.util.ArrayList<>(options);
        awaitingWordChoice = true;

        String payload = String.join(",", options);
        currentDrawer.sendMessage(new Message(MessageType.WORD_CHOICE, "Server", payload));

        cancelWordChoiceTimer();
        wordChoiceTimer = Executors.newSingleThreadScheduledExecutor();
        wordChoiceTimer.schedule(() -> {
            synchronized (GameManager.this) {
                if (awaitingWordChoice) {
                    awaitingWordChoice = false;
                    room.broadcast(new Message(MessageType.CHAT, "Server",
                            currentDrawer.getUsername() + " took too long — word assigned automatically."));
                    beginRoundWithWord(clients, fallback);
                }
            }
            wordChoiceTimer.shutdown();
        }, WORD_CHOICE_SECS, TimeUnit.SECONDS);
    }

    public synchronized void handleWordSelected(List<ClientHandler> clients, ClientHandler sender, String word) {
        if (!awaitingWordChoice || sender != currentDrawer || word == null) return;
        awaitingWordChoice = false;
        cancelWordChoiceTimer();
        beginRoundWithWord(clients, word.trim());
    }

    /**
     * Initializes hint mask, notifies drawer of secret word,
     * sends word length to guessers, and starts the round timer.
     */
    private void beginRoundWithWord(List<ClientHandler> clients, String word) {
        currentWordOptions = java.util.List.of();
        secretWord = word.toLowerCase();

        hintMask = new char[secretWord.length()];
        for (int i = 0; i < secretWord.length(); i++) {
            hintMask[i] = (secretWord.charAt(i) == ' ') ? ' ' : '_';
        }

        roundActive = true;

        currentDrawer.sendMessage(new Message(MessageType.SECRET_WORD, "Server", secretWord));
        broadcastHint(clients);

        // Send word length to guessers as a dedicated message (shown in hint area)
        int letterCount = secretWord.replace(" ", "").length();
        for (ClientHandler c : clients) {
            if (c != currentDrawer) {
                c.sendMessage(new Message(MessageType.WORD_LENGTH, "Server", letterCount));
            }
        }

        room.broadcast(new Message(MessageType.CHAT, "Server",
                currentDrawer.getUsername() + " is drawing! Word has " + letterCount + " letters."));

        startRoundTimer(clients);
    }

    /**
     * Drawer reveals one hidden letter at a time (max MAX_HINTS_PER_ROUND).
     * Hint mask is broadcast only to guessers.
     */
    public synchronized void handleHintRequest(List<ClientHandler> clients, ClientHandler sender) {
        if (!roundActive || sender != currentDrawer) return;

        if (hintsGiven >= MAX_HINTS_PER_ROUND) {
            sender.sendMessage(new Message(MessageType.CHAT, "Server",
                    "No more hints available this round (max " + MAX_HINTS_PER_ROUND + ")."));
            return;
        }

        java.util.List<Integer> hidden = new java.util.ArrayList<>();
        for (int i = 0; i < hintMask.length; i++) {
            if (hintMask[i] == '_') hidden.add(i);
        }
        if (hidden.isEmpty()) return;

        int reveal = hidden.get((int) (Math.random() * hidden.size()));
        hintMask[reveal] = secretWord.charAt(reveal);
        hintsGiven++;

        broadcastHint(clients);
        room.broadcast(new Message(MessageType.CHAT, "Server", "Hint revealed! (" + hintsGiven + "/" + MAX_HINTS_PER_ROUND + " used)"));
    }

    private void broadcastHint(List<ClientHandler> clients) {
        if (hintMask == null) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hintMask.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hintMask[i]);
        }
        String hint = sb.toString();

        for (ClientHandler c : clients) {
            if (c != currentDrawer) {
                c.sendMessage(new Message(MessageType.HINT, "Server", hint));
            }
        }
    }

    /**
     * Validates guesses:
     *  - Correct guess → award points, update drawer score, broadcast
     *  - All guessers correct → end round early
     *  - Incorrect guess → treated as chat
     */
    public synchronized void handleGuess(List<ClientHandler> clients, ClientHandler guesser, String guess) {
        if (guess == null) return;

        if (!gameRunning || !roundActive
                || guesser == currentDrawer
                || guesser.getPlayer().hasGuessedCorrectly()) {
            room.broadcast(new Message(MessageType.CHAT, guesser.getUsername(), guess));
            return;
        }

        if (guess.trim().equalsIgnoreCase(secretWord)) {
            guesser.getPlayer().setHasGuessedCorrectly(true);
            guesser.getPlayer().addScore(GUESS_SCORE);

            // Drawer earns points for each person who guesses correctly.
            if (currentDrawer != null) {
                currentDrawer.getPlayer().addScore(DRAWER_SCORE_PER_GUESSER);
            }

            room.broadcast(new Message(MessageType.CHAT, "Server", guesser.getUsername() + " guessed correctly!"));
            room.broadcastScores();

            boolean allDone = clients.stream()
                    .filter(c -> c != currentDrawer)
                    .allMatch(c -> c.getPlayer().hasGuessedCorrectly());

            if (allDone) {
                cancelRoundTimer();
                endRound(clients, true);
            }
        } else {
            room.broadcast(new Message(MessageType.CHAT, guesser.getUsername(), guess));
        }
    }

    /** Only the drawer may clear the canvas during an active round. */
    public synchronized void handleClearCanvas(List<ClientHandler> clients, ClientHandler sender) {
        if (gameRunning && sender != currentDrawer) return;
        room.broadcast(new Message(MessageType.CLEAR_CANVAS, "Server", null));
    }

    /**
     * Sends all relevant state to a player who joined mid-game:
     *  - Role, round number, scores
     *  - Hints, timer, secret word (if drawer)
     *  - Word choice options (if drawer is choosing)
     */
    public synchronized void syncClientToCurrentState(ClientHandler target, List<ClientHandler> clients) {
        if (target == null || clients == null || !clients.contains(target)) return;

        boolean isDrawer = target == currentDrawer;
        target.getPlayer().setDrawer(isDrawer);
        if (!isDrawer) {
            target.getPlayer().setHasGuessedCorrectly(false);
        }

        if (!gameRunning) {
            target.sendMessage(new Message(MessageType.SCORE_UPDATE, "Server", buildScorePayload(clients)));
            return;
        }

        target.sendMessage(new Message(MessageType.ROLE_ASSIGNMENT, "Server", isDrawer ? "Drawer" : "Guesser"));
        if (currentRound > 0) {
            target.sendMessage(new Message(MessageType.ROUND_START, "Server", String.valueOf(currentRound)));
        }
        target.sendMessage(new Message(MessageType.SCORE_UPDATE, "Server", buildScorePayload(clients)));

        if (awaitingWordChoice && isDrawer && !currentWordOptions.isEmpty()) {
            target.sendMessage(new Message(MessageType.WORD_CHOICE, "Server",
                    String.join(",", currentWordOptions)));
            return;
        }

        if (roundActive) {
            if (isDrawer) {
                target.sendMessage(new Message(MessageType.SECRET_WORD, "Server", secretWord));
            } else if (hintMask != null) {
                target.sendMessage(new Message(MessageType.HINT, "Server", buildHintPayload()));
            }
            target.sendMessage(new Message(MessageType.TIMER_UPDATE, "Server", String.valueOf(timeRemaining)));
        }
    }

    private String buildScorePayload(List<ClientHandler> clients) {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler c : clients) {
            sb.append(c.getUsername()).append(": ")
                    .append(c.getPlayer().getScore()).append("  ");
        }
        return sb.toString().trim();
    }

    private String buildHintPayload() {
        if (hintMask == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hintMask.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hintMask[i]);
        }
        return sb.toString();
    }

    /**
     * Sends TIMER_UPDATE every second.
     * When time reaches zero → endRound(false).
     */
    private void startRoundTimer(List<ClientHandler> clients) {
        cancelRoundTimer();
        roundTimer = Executors.newSingleThreadScheduledExecutor();

        timeRemaining = ROUND_TIME;
        room.broadcast(new Message(MessageType.TIMER_UPDATE, "Server", String.valueOf(timeRemaining)));

        final int[] timeLeft = {ROUND_TIME};
        roundTimer.scheduleAtFixedRate(() -> {
            timeLeft[0]--;
            timeRemaining = timeLeft[0];
            room.broadcast(new Message(MessageType.TIMER_UPDATE, "Server", String.valueOf(timeLeft[0])));

            if (timeLeft[0] <= 0) {
                roundTimer.shutdown();
                synchronized (GameManager.this) {
                    endRound(clients, false);
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Ends the round, broadcasts result, updates scores,
     * and schedules the next round after a short delay.
     */
    private synchronized void endRound(List<ClientHandler> clients, boolean allGuessed) {
        if (!gameRunning) return;
        roundActive = false;
        timeRemaining = 0;

        String reason = allGuessed
                ? "Everyone guessed correctly! The word was: " + secretWord
                : "Time's up! The word was: " + secretWord;

        room.broadcast(new Message(MessageType.ROUND_END, "Server", reason));
        room.broadcastScores();
        scheduleNextRound(clients, 4);
        saveState(clients);
    }

    private void scheduleNextRound(List<ClientHandler> clients, int delaySecs) {
        cancelNextRound();
        nextRoundScheduler = Executors.newSingleThreadScheduledExecutor();
        nextRoundScheduler.schedule(() -> {
            synchronized (GameManager.this) {
                if (gameRunning && clients.size() >= MIN_PLAYERS) {
                    startNextRound(clients);
                }
            }
            nextRoundScheduler.shutdown();
        }, delaySecs, TimeUnit.SECONDS);
    }

    /**
     * Final round reached:
     *  - Broadcast final scores
     *  - Save leaderboard
     *  - Reset state
     *  - Schedule automatic new game after delay
     */
    private synchronized void endGame(List<ClientHandler> clients) {
        gameRunning = false;
        roundActive = false;
        cancelNextRound();
        cancelRoundTimer();

        room.broadcast(new Message(MessageType.ROUND_END, "Server", "Game over! Final scores:"));
        room.broadcastScores();

        List<Player> playerList = clients.stream().map(ClientHandler::getPlayer).toList();
        dataManager.updateAndSaveLeaderboard(playerList);
        String lbText = dataManager.getLeaderboardText();
        room.broadcast(new Message(MessageType.LEADERBOARD_UPDATE, "Server", lbText));

        clients.forEach(c -> c.getPlayer().resetRoundState());
        currentDrawer = null;
        secretWord = "";
        currentRound = 0;
        awaitingWordChoice = false;
        currentWordOptions = java.util.List.of();
        timeRemaining = ROUND_TIME;
        saveState(clients);

        scheduleNewGame(clients, 10);
    }

    private void scheduleNewGame(List<ClientHandler> clients, int delaySecs) {
        cancelNextRound();
        nextRoundScheduler = Executors.newSingleThreadScheduledExecutor();
        nextRoundScheduler.schedule(() -> {
            synchronized (GameManager.this) {
                if (!gameRunning && clients.size() >= MIN_PLAYERS) {
                    room.broadcast(new Message(MessageType.CHAT, "Server", "Starting a new game!"));
                    startGame(clients);
                }
            }
            nextRoundScheduler.shutdown();
        }, delaySecs, TimeUnit.SECONDS);
    }

    /**
     * Stops all timers and pauses the game until enough players rejoin.
     */
    private synchronized void stopGame(List<ClientHandler> clients) {
        gameRunning = false;
        roundActive = false;
        awaitingWordChoice = false;
        cancelNextRound();
        cancelRoundTimer();
        cancelWordChoiceTimer();
        currentDrawer = null;
        secretWord = "";
        currentWordOptions = java.util.List.of();
        timeRemaining = ROUND_TIME;
        room.broadcast(new Message(MessageType.CLEAR_CANVAS, "Server", null));
        room.broadcast(new Message(MessageType.ROUND_END, "Server", "Not enough players — game paused. Waiting for more players…"));
    }

    // Ensures no orphaned scheduled executors remain running.
    private void cancelRoundTimer() {
        if (roundTimer != null && !roundTimer.isShutdown()) {
            roundTimer.shutdownNow();
        }
    }

    // Also ensures no orphaned scheduled executors remain running.
    private void cancelNextRound() {
        if (nextRoundScheduler != null && !nextRoundScheduler.isShutdown()) {
            nextRoundScheduler.shutdownNow();
        }
    }

    // Also ensures no orphaned scheduled executors remain running as well.
    private void cancelWordChoiceTimer() {
        if (wordChoiceTimer != null && !wordChoiceTimer.isShutdown()){
            wordChoiceTimer.shutdownNow();
        }
    }

    /**
     * Saves current game state (scores, round, drawer, secret word)
     * so the server can restore state after restart.
     */
    private void saveState(List<ClientHandler> clients) {
        try {
            List<Player> playerList = clients.stream().map(ClientHandler::getPlayer).toList();
            String drawerName = (currentDrawer != null) ? currentDrawer.getUsername() : "";
            dataManager.updateGameState(playerList, currentRound, secretWord, drawerName, gameRunning);
            dataManager.saveGameState();
        } catch (Exception e) {
            System.err.println("[GameManager] Failed to save state: " + e.getMessage());
        }
    }
}