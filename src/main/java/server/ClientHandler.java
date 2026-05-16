package server;

import shared.DrawData;
import shared.Message;
import shared.MessageType;
import shared.Player;

import java.io.*;
import java.net.Socket;

/**
 * One thread per connected client.
 *
 * Responsibilities:
 *   - Own the socket and object streams for a single client.
 *   - Continuously read incoming Message objects.
 *   - Delegate all message handling to ServerMain / RoomManager / Room.
 *   - Provide sendMessage() for server-side code to push updates to this client.
 *   - Track which Room the client is currently in (null = lobby).
 *
 * This class contains no game logic; it only routes messages upward.
 */
public class ClientHandler implements Runnable {

    private final Socket     socket;
    private final ServerMain server;
    private final Player     player;

    private ObjectOutputStream output;
    private ObjectInputStream  input;

    /** The room this client currently occupies; null while in the lobby. */
    private volatile Room currentRoom = null;

    public ClientHandler(Socket socket, ServerMain server, String username) {
        this.socket = socket;
        this.server = server;
        this.player = new Player(username);
    }

    /**
     * Allows ServerMain to inject pre-created streams (useful for testing or
     * when streams must be created in a specific order).
     */
    public void injectStreams(ObjectOutputStream out, ObjectInputStream in) {
        this.output = out;
        this.input  = in;
    }

    public Player getPlayer()   { return player; }
    public String getUsername() { return player.getUsername(); }

    public Room  getRoom()          { return currentRoom; }
    public void  setRoom(Room room) { this.currentRoom = room; }

    /**
     * Sends a Message to the client.
     *
     * Synchronized to prevent concurrent writes from multiple server threads.
     * output.reset() clears the OOS cache so repeated payloads (e.g., same String)
     * are serialized fresh instead of as back-references.
     */
    public synchronized void sendMessage(Message message) {
        if (output == null) return;
        try {
            output.reset();
            output.writeObject(message);
            output.flush();
        } catch (IOException e) {
            System.err.println("[ClientHandler] send failed for " + getUsername()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Main loop for this client.
     *
     * Steps:
     *   1. Initialize streams if not injected.
     *   2. Notify ServerMain that the client connected.
     *   3. Read Message objects in a loop.
     *   4. Route each message to the appropriate subsystem.
     *
     * Loop exits cleanly on disconnect (EOF or SocketException).
     */
    @Override
    public void run() {
        try {
            if (output == null) {
                output = new ObjectOutputStream(socket.getOutputStream());
                output.flush();
            }
            if (input == null) {
                input = new ObjectInputStream(socket.getInputStream());
            }

            server.onClientConnected(this);

            //noinspection InfiniteLoopStatement
            while (true) {
                Message message = (Message) input.readObject();
                handleMessage(message);
            }

        } catch (EOFException | java.net.SocketException e) {
            // Normal disconnect — no stack trace needed.
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[ClientHandler] error for " + getUsername()
                    + ": " + e.getMessage());
        } finally {
            server.onClientDisconnected(this);
            close();
        }
    }

    /**
     * Routes incoming messages to the correct subsystem.
     *
     * Lobby-level messages always go to RoomManager.
     * Room-level messages go to the client's current Room.
     * If the client is not in a room, room-level messages are ignored.
     */
    private void handleMessage(Message message) {
        switch (message.getType()) {

            // Lobby-level operations
            case CREATE_ROOM ->
                    server.getRoomManager().createRoom(this, (String) message.getPayload());

            case JOIN_ROOM ->
                    server.getRoomManager().joinRoom(this, (String) message.getPayload());

            case LEAVE_ROOM ->
                    server.getRoomManager().leaveRoom(this);

            case SET_MODE ->
                    server.getRoomManager().setMode(this, (String) message.getPayload());

            case START_GAME ->
                    server.getRoomManager().startGame(this);

            case REFRESH_ROOMS ->
                    server.getRoomManager().refreshRooms(this);

            // Room-level operations
            case CHAT -> {
                Room r = currentRoom;
                if (r != null) r.handleChat(this, (String) message.getPayload());
            }

            case DRAW_DATA -> {
                Room r = currentRoom;
                if (r != null) r.handleDrawData(this, (DrawData) message.getPayload());
            }

            case CLEAR_CANVAS -> {
                Room r = currentRoom;
                if (r != null) r.handleClearCanvas(this);
            }

            case WORD_SELECTED -> {
                Room r = currentRoom;
                if (r != null) r.handleWordSelected(this, (String) message.getPayload());
            }

            case HINT_REQUEST -> {
                Room r = currentRoom;
                if (r != null) r.handleHintRequest(this);
            }

            case KICK -> {
                Room r = currentRoom;
                if (r != null) r.kick(this, (String) message.getPayload());
            }

            case CUSTOM_WORD_LIST -> {
                Room r = currentRoom;
                if (r != null) r.handleCustomWordList(this, (String) message.getPayload());
            }

            // Client explicitly leaving (rare; usually LEAVE_ROOM is used)
            case LEAVE ->
                    server.onClientDisconnected(this);

            default ->
                    System.out.println("[ClientHandler] unhandled message type: "
                            + message.getType() + " from " + getUsername());
        }
    }

    /**
     * Closes the socket. Streams close automatically with it.
     * Safe to call multiple times.
     */
    public void close() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("[ClientHandler] close error: " + e.getMessage());
        }
    }
}