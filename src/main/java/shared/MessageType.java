package shared;

/**
 * Enum representing all message types exchanged between client and server.
 *
 * Room / lobby additions:
 *   ROOM_LIST       — Server → Client : full list of open rooms (RoomInfo[])
 *   CREATE_ROOM     — Client → Server : payload = room name String
 *   JOIN_ROOM       — Client → Server : payload = room name String
 *   LEAVE_ROOM      — Client → Server : no payload; returns client to lobby
 *   ROOM_UPDATE     — Server → Client : updated RoomInfo (members, mode, host)
 *   SET_MODE        — Host   → Server : payload = "DOODLE" or "GAME"
 *   START_GAME      — Host   → Server : host manually starts the session
 *   LOBBY_ERROR     — Server → Client : human-readable error message
 *   KICKED_TO_LOBBY — Server → Client : kicked but still connected (sent to lobby)
 */
public enum MessageType {

    // --- Core chat / presence ---
    CHAT,
    JOIN,
    LEAVE,

    // --- Canvas operations ---
    DRAW_DATA,
    CLEAR_CANVAS,

    // --- Game roles & word selection ---
    ROLE_ASSIGNMENT,
    SECRET_WORD,
    WORD_CHOICE,
    WORD_SELECTED,

    // --- Round flow ---
    ROUND_START,
    ROUND_END,
    SCORE_UPDATE,
    TIMER_UPDATE,

    // --- Hints ---
    HINT,
    HINT_REQUEST,

    // --- Moderation ---
    KICK,
    KICKED,
    KICKED_TO_LOBBY,

    // --- End-of-game ---
    LEADERBOARD_UPDATE,

    // --- Lobby / Room system ---
    ROOM_LIST,
    CREATE_ROOM,
    JOIN_ROOM,
    LEAVE_ROOM,
    ROOM_UPDATE,
    SET_MODE,
    START_GAME,
    REFRESH_ROOMS,
    LOBBY_ERROR,

    // --- New feature messages ---
    WORD_LENGTH,        // Server → Guessers: number of letters (int)
    CUSTOM_WORD_LIST    // Host → Server: comma-separated custom words
}