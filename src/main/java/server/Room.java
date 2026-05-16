package server;

import shared.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Room owns a list of connected clients and (optionally) a GameManager.
 *
 * Lifecycle:
 *   1. Host creates room → Room is constructed, host added, mode defaults to GAME.
 *   2. Other players join via RoomManager.
 *   3. Host sets mode (DOODLE / GAME) with SET_MODE.
 *   4. Host sends START_GAME to begin either GAME mode or a live DOODLE session.
 *   5. When the room empties it is destroyed by RoomManager.
 *
 * Thread-safety:
 *   All public mutating methods are synchronized.
 *   The client list is a CopyOnWriteArrayList so read-only iteration is always safe.
 */
public class Room {

    private final String name;
    private final List<ClientHandler> members = new CopyOnWriteArrayList<>();

    private RoomInfo.Mode mode = RoomInfo.Mode.GAME;
    private GameManager gameManager;
    private final ServerMain server;
    private final DataManager dataManager;

    // Track kicked usernames so they cannot immediately rejoin
    private final List<String> kickedUsernames = new ArrayList<>();

    // Doodle mode has its own "started" state separate from GAME mode
    private boolean doodleActive = false;

    // Canvas history for late joiners
    private final List<DrawData> canvasHistory = new ArrayList<>();

    public Room(String name, ServerMain server, DataManager dataManager) {
        this.name = name;
        this.server = server;
        this.dataManager = dataManager;
    }

    public String getName() { return name; }
    public RoomInfo.Mode getMode() { return mode; }
    public List<ClientHandler> getMembers() { return members; }

    public synchronized ClientHandler getHost() {
        return members.isEmpty() ? null : members.get(0);
    }

    public synchronized boolean isEmpty() { return members.isEmpty(); }
    public synchronized int size() { return members.size(); }

    public synchronized boolean isInProgress() {
        return doodleActive || (gameManager != null && gameManager.isGameRunning());
    }

    public synchronized RoomInfo toInfo() {
        List<String> names = new ArrayList<>();
        for (ClientHandler c : members) names.add(c.getUsername());
        return new RoomInfo(name, names.isEmpty() ? "" : names.get(0), mode, names, isInProgress());
    }

    /**
     * Adds a client to the room. Returns an error string if refused, null on success.
     * Refused reasons: duplicate username, GAME already in progress, previously kicked.
     */
    public synchronized String tryAdd(ClientHandler handler) {
        if (kickedUsernames.contains(handler.getUsername())) {
            return "You were kicked from room '" + name + "' and cannot rejoin.";
        }

        for (ClientHandler m : members) {
            if (m.getUsername().equalsIgnoreCase(handler.getUsername())) {
                return "Username '" + handler.getUsername() + "' is already in this room.";
            }
        }

        members.add(handler);
        handler.setRoom(this);
        handler.getPlayer().resetRoundState();
        broadcastRoomUpdate();

        // If a session is already running, sync the new player to the current state
        if (isInProgress()) {
            sendCanvasStateTo(handler);
            if (mode == RoomInfo.Mode.GAME && gameManager != null) {
                gameManager.syncClientToCurrentState(handler, members);
            }
        }

        broadcast(new Message(MessageType.JOIN, "Server",
                handler.getUsername() + " joined the room."));

        return null;
    }

    public synchronized void remove(ClientHandler handler) {
        if (!members.remove(handler)) return;

        handler.setRoom(null);

        broadcast(new Message(MessageType.LEAVE, "Server",
                handler.getUsername() + " left the room."));

        // If room becomes empty, reset and destroy game state
        if (members.isEmpty()) {
            doodleActive = false;
            canvasHistory.clear();
            stopGameIfRunning("Room is now empty.");
            return;
        }

        broadcastRoomUpdate();

        // Notify GameManager so it can handle drawer disconnects, etc.
        if (mode == RoomInfo.Mode.GAME && gameManager != null) {
            gameManager.onPlayerLeft(members, handler);
        }
    }

    /**
     * Host changes the room mode (DOODLE or GAME).
     * Only allowed when no session is running.
     */
    public synchronized void setMode(ClientHandler requester, RoomInfo.Mode newMode) {
        if (requester != getHost()) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Only the host can change the room mode."));
            return;
        }
        if (isInProgress()) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Cannot change mode while a session is in progress."));
            return;
        }

        this.mode = newMode;
        this.doodleActive = false;
        broadcastRoomUpdate();

        String label = (newMode == RoomInfo.Mode.DOODLE) ? "Doodle (free draw)" : "Guessing Game";
        broadcast(new Message(MessageType.CHAT, "Server",
                "Room mode set to: " + label));
    }

    /**
     * Host explicitly starts the currently selected room mode.
     * GAME mode starts GameManager; DOODLE mode starts a free-draw session.
     */
    public synchronized void hostStartGame(ClientHandler requester) {
        if (requester != getHost()) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Only the host can start the session."));
            return;
        }

        if (isInProgress()) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "This room is already running."));
            return;
        }

        // DOODLE mode start
        if (mode == RoomInfo.Mode.DOODLE) {
            doodleActive = true;
            broadcastRoomUpdate();
            broadcast(new Message(MessageType.CLEAR_CANVAS, "Server", null));
            broadcast(new Message(MessageType.CHAT, "Server", "Doodle session started."));
            return;
        }

        // GAME mode start
        if (members.size() < 2) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Need at least 2 players to start Game mode."));
            return;
        }

        ensureGameManager();
        gameManager.forceStart(members);
        broadcastRoomUpdate();
        syncActiveGameStateToAll();
    }

    /**
     * Host kicks a player from the room.
     * Kicked players cannot immediately rejoin.
     */
    public synchronized void kick(ClientHandler requester, String targetUsername) {
        if (requester != getHost()) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Only the host can kick players."));
            return;
        }
        if (targetUsername == null || targetUsername.equalsIgnoreCase(requester.getUsername())) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "You cannot kick yourself."));
            return;
        }

        ClientHandler target = members.stream()
                .filter(c -> c.getUsername().equalsIgnoreCase(targetUsername))
                .findFirst().orElse(null);

        if (target == null) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Player '" + targetUsername + "' not found in this room."));
            return;
        }

        kickedUsernames.add(target.getUsername());

        target.sendMessage(new Message(MessageType.KICKED_TO_LOBBY, "Server",
                "You were kicked from room '" + name + "' by the host."));

        broadcast(new Message(MessageType.CHAT, "Server",
                targetUsername + " was kicked by the host."));

        remove(target);
        server.getRoomManager().returnKickedClientToLobby(target);
    }

    /**
     * Host sets a custom word list for this session.
     * Words are comma-separated and apply only to the current game.
     */
    public synchronized void handleCustomWordList(ClientHandler sender, String payload) {
        if (sender != getHost()) {
            sender.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Only the host can set a custom word list."));
            return;
        }
        if (payload == null || payload.isBlank()) {
            ensureGameManager();
            gameManager.setCustomWordList(null);
            broadcast(new Message(MessageType.CHAT, "Server", "Custom word list cleared — using default words."));
            return;
        }

        List<String> words = new ArrayList<>();
        for (String w : payload.split(",")) {
            String trimmed = w.trim();
            if (!trimmed.isEmpty()) words.add(trimmed);
        }

        if (words.isEmpty()) {
            sender.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "No valid words found in the custom word list."));
            return;
        }

        ensureGameManager();
        gameManager.setCustomWordList(words);
        broadcast(new Message(MessageType.CHAT, "Server",
                "Custom word list set by host (" + words.size() + " words). Game will use these words."));
    }

    /**
     * Routes chat or guesses depending on room mode.
     * DOODLE → broadcast chat
     * GAME   → treat chat as guesses
     */
    public synchronized void handleChat(ClientHandler sender, String text) {
        if (mode == RoomInfo.Mode.DOODLE) {
            broadcast(new Message(MessageType.CHAT, sender.getUsername(), text));
        } else {
            ensureGameManager();
            gameManager.handleGuess(members, sender, text);
        }
    }

    /**
     * Broadcasts drawing data to all other clients.
     * Also stores it for late joiners.
     */
    public synchronized void handleDrawData(ClientHandler sender, DrawData data) {
        if (data == null) return;

        canvasHistory.add(data);

        Message msg = new Message(MessageType.DRAW_DATA, sender.getUsername(), data);
        for (ClientHandler c : members) {
            if (c != sender) c.sendMessage(msg);
        }
    }

    /**
     * Clears the canvas.
     * In GAME mode only the drawer may clear; in DOODLE mode anyone may.
     */
    public synchronized void handleClearCanvas(ClientHandler sender) {
        if (mode == RoomInfo.Mode.GAME) {
            ensureGameManager();
            gameManager.handleClearCanvas(members, sender);
        } else {
            broadcast(new Message(MessageType.CLEAR_CANVAS, "Server", null));
        }
    }

    public synchronized void handleWordSelected(ClientHandler sender, String word) {
        ensureGameManager();
        gameManager.handleWordSelected(members, sender, word);
    }

    public synchronized void handleHintRequest(ClientHandler sender) {
        ensureGameManager();
        gameManager.handleHintRequest(members, sender);
    }

    /**
     * Broadcasts a message to all room members.
     * Clearing the canvas also clears stored history.
     */
    public synchronized void broadcast(Message message) {
        if (message != null && message.getType() == MessageType.CLEAR_CANVAS) {
            canvasHistory.clear();
        }
        for (ClientHandler c : members) c.sendMessage(message);
    }

    /**
     * Sends SCORE_UPDATE to all clients.
     */
    public synchronized void broadcastScores() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler c : members) {
            sb.append(c.getUsername()).append(": ")
                    .append(c.getPlayer().getScore()).append("  ");
        }
        broadcast(new Message(MessageType.SCORE_UPDATE, "Server", sb.toString().trim()));
    }

    /**
     * Sends ROOM_UPDATE to all clients.
     */
    public synchronized void broadcastRoomUpdate() {
        RoomInfo info = toInfo();
        for (ClientHandler c : members) {
            c.sendMessage(new Message(MessageType.ROOM_UPDATE, "Server", info));
        }
    }

    /**
     * Syncs all players to the current game state (roles, hints, timer, etc.).
     */
    public synchronized void syncActiveGameStateToAll() {
        if (mode != RoomInfo.Mode.GAME || gameManager == null || !gameManager.isGameRunning()) return;
        for (ClientHandler c : members) {
            gameManager.syncClientToCurrentState(c, members);
        }
    }

    /**
     * Sends full canvas history to a newly joined client.
     */
    private void sendCanvasStateTo(ClientHandler target) {
        target.sendMessage(new Message(MessageType.CLEAR_CANVAS, "Server", null));
        for (DrawData data : canvasHistory) {
            target.sendMessage(new Message(MessageType.DRAW_DATA, "Server", data));
        }
    }

    /**
     * Lazily creates a GameManager when needed.
     */
    private void ensureGameManager() {
        if (gameManager == null) {
            gameManager = new GameManager(this, dataManager);
        }
    }

    /**
     * Stops any running game and resets state when the room empties.
     */
    private void stopGameIfRunning(String reason) {
        doodleActive = false;
        if (gameManager != null && gameManager.isGameRunning()) {
            broadcast(new Message(MessageType.ROUND_END, "Server", reason));
        }
        gameManager = null;
    }
}