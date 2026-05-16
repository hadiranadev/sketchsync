package server;

import shared.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ServerMain — networking only.
 *
 * Responsibilities:
 *   - Bind the server socket and accept incoming connections.
 *   - Perform the initial JOIN handshake (read the first message to get the username).
 *   - Spawn a ClientHandler thread per client.
 *   - Notify RoomManager of client connect / disconnect events.
 *   - Provide broadcast helpers that Room and GameManager can use.
 *
 * No game logic lives here. All game rules live in GameManager.
 * All room management lives in RoomManager.
 */
public class ServerMain {

    private static final int PORT = 5000;

    /** All connected clients regardless of whether they are in a room. */
    private final List<ClientHandler> allClients = new CopyOnWriteArrayList<>();

    /** Clients who are in the lobby (not inside any room). */
    private final List<ClientHandler> lobbyClients = new CopyOnWriteArrayList<>();

    private final DataManager dataManager;
    private final RoomManager roomManager;

    public ServerMain() {
        this.dataManager = new DataManager();
        this.roomManager = new RoomManager(this, dataManager, lobbyClients);
    }

    public static void main(String[] args) {
        new ServerMain().start();
    }

    public RoomManager getRoomManager() { return roomManager; }

    @SuppressWarnings("InfiniteLoopStatement")
    public void start() {
        System.out.println("[Server] Loaded " + dataManager.getWordCount() + " words.");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("[Server] Listening on port " + PORT);

            // Accept loop — each accepted socket becomes a ClientHandler
            while (true) {
                Socket clientSocket = serverSocket.accept();
                handleNewConnection(clientSocket);
            }

        } catch (IOException e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
        }
    }

    /**
     * Performs the initial handshake for a new socket:
     *   1. Build OOS/OIS in the correct order (OOS first, flush, then OIS).
     *   2. Read the JOIN message to extract the username.
     *   3. Reject duplicate usernames that are already connected.
     *   4. Create ClientHandler, inject streams, start thread.
     *
     * This method handles ONLY the handshake — all further communication
     * is handled by ClientHandler.
     */
    private void handleNewConnection(Socket clientSocket) {
        try {
            // OOS must be created first to avoid stream deadlock
            ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());

            // First message must be JOIN
            Message joinMsg = (Message) in.readObject();
            String username = joinMsg.getSender();

            // Reject duplicate usernames across the entire server
            for (ClientHandler existing : allClients) {
                if (existing.getUsername().equalsIgnoreCase(username)) {
                    Message err = new Message(MessageType.LOBBY_ERROR, "Server",
                            "Username '" + username + "' is already connected. Choose another.");
                    out.writeObject(err);
                    out.flush();
                    clientSocket.close();
                    System.out.println("[Server] Rejected duplicate username: " + username);
                    return;
                }
            }

            System.out.println("[Server] " + username + " connected.");

            // Create handler and inject streams
            ClientHandler handler = new ClientHandler(clientSocket, this, username);
            handler.injectStreams(out, in);

            // One thread per client
            Thread t = new Thread(handler, "ClientHandler-" + username);
            t.setDaemon(true);
            t.start();

        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Server] Handshake error: " + e.getMessage());
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Called by ClientHandler once its thread starts.
     * Adds the client to the global list and notifies RoomManager.
     */
    public synchronized void onClientConnected(ClientHandler handler) {
        allClients.add(handler);
        roomManager.onClientConnected(handler);
    }

    /**
     * Called when a ClientHandler terminates (disconnect or error).
     * Removes from global list and delegates cleanup to RoomManager.
     */
    public synchronized void onClientDisconnected(ClientHandler handler) {
        if (!allClients.remove(handler)) return;  // already removed — guard re-entry
        System.out.println("[Server] " + handler.getUsername() + " disconnected.");
        roomManager.onClientDisconnected(handler);
    }

    /**
     * Sends a message to every client currently in the same room as the given handler.
     * If no room is set, sends to all connected clients (fallback — rarely needed).
     */
    public void broadcastToRoom(Room room, Message message) {
        if (room == null) return;
        room.broadcast(message);
    }
}