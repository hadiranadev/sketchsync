package server;

import shared.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RoomManager owns all rooms on the server.
 *
 * ServerMain delegates every lobby-level action here:
 *   - CREATE_ROOM   → createRoom()
 *   - JOIN_ROOM     → joinRoom()
 *   - LEAVE_ROOM    → leaveRoom()
 *   - SET_MODE      → setMode()
 *   - START_GAME    → startGame()
 *   - KICK          → kick()
 *
 * RoomManager also pushes a fresh ROOM_LIST to every lobby client whenever
 * the room list changes, so the lobby UI stays up to date.
 *
 * Thread-safety: all public methods are synchronized.
 */
public class RoomManager {

    /** Clients who are connected but not yet in any room (in the lobby). */
    private final List<ClientHandler> lobbyClients;

    /** All active rooms, keyed by lowercase room name. */
    private final Map<String, Room> rooms;

    private final DataManager dataManager;
    private final ServerMain  server;

    public RoomManager(ServerMain server, DataManager dataManager,
                       List<ClientHandler> lobbyClients) {
        this.server       = server;
        this.dataManager  = dataManager;
        this.lobbyClients = lobbyClients;
        this.rooms        = new ConcurrentHashMap<>();
    }

    /**
     * Called by ServerMain when a new client's JOIN handshake completes.
     * Adds the client to the lobby and pushes the current room list.
     */
    public synchronized void onClientConnected(ClientHandler handler) {
        lobbyClients.add(handler);
        sendRoomList(handler);
        System.out.println("[RoomManager] " + handler.getUsername() + " entered the lobby.");
    }

    /**
     * Called when a client disconnects.
     * Removes them from lobby or room, cleans up empty rooms,
     * and updates the lobby room list.
     */
    public synchronized void onClientDisconnected(ClientHandler handler) {
        lobbyClients.remove(handler);

        Room room = handler.getRoom();
        if (room != null) {
            room.remove(handler);
            removeIfEmpty(room);
            broadcastRoomListToLobby();
        }
    }

    /**
     * Creates a new room if:
     *   - name is valid
     *   - no duplicate room exists
     *   - creator is not already in a room
     */
    public synchronized void createRoom(ClientHandler creator, String roomName) {
        if (roomName == null || roomName.isBlank()) {
            creator.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Room name cannot be empty."));
            return;
        }
        roomName = roomName.trim();

        if (rooms.containsKey(roomName.toLowerCase())) {
            creator.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "A room named '" + roomName + "' already exists."));
            return;
        }

        if (creator.getRoom() != null) {
            creator.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Leave your current room before creating a new one."));
            return;
        }

        Room room = new Room(roomName, server, dataManager);
        rooms.put(roomName.toLowerCase(), room);

        lobbyClients.remove(creator);

        // Should never fail for a fresh room, but guarded anyway
        String error = room.tryAdd(creator);
        if (error != null) {
            rooms.remove(roomName.toLowerCase());
            lobbyClients.add(creator);
            creator.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server", error));
            return;
        }

        System.out.println("[RoomManager] Room '" + roomName + "' created by "
                + creator.getUsername());
        broadcastRoomListToLobby();
    }

    /**
     * Attempts to join an existing room.
     * Validates:
     *   - room exists
     *   - user is not already in a room
     *   - room.tryAdd() accepts them
     */
    public synchronized void joinRoom(ClientHandler joiner, String roomName) {
        if (roomName == null) {
            joiner.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Room name missing."));
            return;
        }

        Room room = rooms.get(roomName.trim().toLowerCase());
        if (room == null) {
            joiner.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Room '" + roomName + "' does not exist."));
            return;
        }

        if (joiner.getRoom() != null) {
            joiner.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Leave your current room before joining another."));
            return;
        }

        String error = room.tryAdd(joiner);
        if (error != null) {
            joiner.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server", error));
            return;
        }

        lobbyClients.remove(joiner);
        broadcastRoomListToLobby();
    }

    /**
     * Removes a client from their room and returns them to the lobby.
     * Also destroys the room if it becomes empty.
     */
    public synchronized void leaveRoom(ClientHandler handler) {
        Room room = handler.getRoom();
        if (room == null) {
            handler.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "You are not in a room."));
            return;
        }

        room.remove(handler);
        removeIfEmpty(room);

        handler.sendMessage(new Message(MessageType.LEAVE_ROOM, "Server", room.getName()));
        broadcastRoomListToLobby();

        if (!lobbyClients.contains(handler)) {
            lobbyClients.add(handler);
        }
        sendRoomList(handler);
    }

    /**
     * Host changes the room mode (DOODLE / GAME).
     * Validates mode string and delegates to Room.
     */
    public synchronized void setMode(ClientHandler requester, String modeStr) {
        Room room = requester.getRoom();
        if (room == null) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "You are not in a room."));
            return;
        }

        RoomInfo.Mode mode;
        try {
            mode = RoomInfo.Mode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "Unknown mode '" + modeStr + "'. Use DOODLE or GAME."));
            return;
        }

        room.setMode(requester, mode);
        broadcastRoomListToLobby();
    }

    /**
     * Host starts the selected mode (GAME or DOODLE).
     * Delegates to Room.hostStartGame().
     */
    public synchronized void startGame(ClientHandler requester) {
        Room room = requester.getRoom();
        if (room == null) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "You are not in a room."));
            return;
        }
        room.hostStartGame(requester);
        broadcastRoomListToLobby();
    }

    /**
     * Sends the room list to a single client.
     * Useful when lobby UI first loads or refreshes.
     */
    public synchronized void refreshRooms(ClientHandler requester) {
        sendRoomList(requester);
    }

    /**
     * Host kicks a player from the room.
     * Delegates to Room.kick().
     */
    public synchronized void kick(ClientHandler requester, String targetUsername) {
        Room room = requester.getRoom();
        if (room == null) {
            requester.sendMessage(new Message(MessageType.LOBBY_ERROR, "Server",
                    "You are not in a room."));
            return;
        }
        room.kick(requester, targetUsername);
        broadcastRoomListToLobby();
    }

    /** Removes and destroys a room if it has no members left. */
    private void removeIfEmpty(Room room) {
        if (room.isEmpty()) {
            rooms.remove(room.getName().toLowerCase());
            System.out.println("[RoomManager] Room '" + room.getName() + "' destroyed (empty).");
        }
    }

    /** Sends the current room list to a single client. */
    private void sendRoomList(ClientHandler handler) {
        List<RoomInfo> infos = buildRoomList();
        handler.sendMessage(new Message(MessageType.ROOM_LIST, "Server", new ArrayList<>(infos)));
    }

    /** Broadcasts the current room list to every lobby client. */
    public synchronized void broadcastRoomListToLobby() {
        List<RoomInfo> infos = buildRoomList();
        Message msg = new Message(MessageType.ROOM_LIST, "Server", new ArrayList<>(infos));
        for (ClientHandler c : lobbyClients) c.sendMessage(msg);
    }

    /** Builds a sorted list of RoomInfo objects for lobby display. */
    private List<RoomInfo> buildRoomList() {
        List<RoomInfo> list = new ArrayList<>();
        for (Room r : rooms.values()) list.add(r.toInfo());
        list.sort(Comparator.comparing(RoomInfo::getName));
        return list;
    }

    /**
     * After a kick, returns the client to the lobby and refreshes their room list.
     */
    public synchronized void returnKickedClientToLobby(ClientHandler handler) {
        if (handler == null) return;
        broadcastRoomListToLobby();
        if (!lobbyClients.contains(handler)) {
            lobbyClients.add(handler);
        }
        sendRoomList(handler);
    }
}