package shared;

import java.io.Serializable;

/**
 * Wrapper for data sent between client and server.
 *
 * Each Message contains:
 *   - a MessageType (what kind of action this represents)
 *   - a sender username
 *   - a payload object (content varies by message type)
 *
 * Serializable so it can be transmitted over ObjectOutputStream.
 */
public class Message implements Serializable {

    /** Serialization version for network compatibility. */
    private static final long serialVersionUID = 1L;

    /** Type of message (CHAT, DRAW_DATA, JOIN_ROOM, etc). */
    private final MessageType type;

    /** Username of the sender. */
    private final String sender;

    /** Payload object (String, DrawData, RoomInfo, etc). */
    private final Object payload;

    /**
     * Creates a new message wrapper.
     *
     * @param type    how the receiver should interpret the payload
     * @param sender  username of the sender
     * @param payload actual data being transmitted
     */
    public Message(MessageType type, String sender, Object payload) {
        this.type = type;
        this.sender = sender;
        this.payload = payload;
    }

    // ---- Getters ----

    public MessageType getType()   { return type; }
    public String getSender()      { return sender; }
    public Object getPayload()     { return payload; }
}