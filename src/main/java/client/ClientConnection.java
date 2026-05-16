package client;

import shared.Message;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.function.Consumer;

/**
 * Handles the client's TCP connection to the SketchSync server.
 *
 * Responsibilities:
 *   - Open/close the socket.
 *   - Send Message objects to the server.
 *   - Run a background listener thread that receives messages.
 *   - Forward incoming messages to a GUI/controller callback.
 *
 * Threading:
 *   - sendMessage() is synchronized to avoid concurrent writes.
 *   - Listener runs on a daemon thread so it never blocks UI shutdown.
 */
public class ClientConnection {

    /** Callback for delivering incoming messages to the UI/controller. */
    private Consumer<Message> messageHandler;

    /** Server address. */
    private final int port;
    private final String host;

    /** Socket + object streams for serialized Message transfer. */
    private Socket socket;
    private ObjectOutputStream output;
    private ObjectInputStream input;

    public ClientConnection(int port, String host, Consumer<Message> messageHandler) {
        this.port = port;
        this.host = host;
        this.messageHandler = messageHandler;
    }

    /**
     * Attempts to open a socket and initialize object streams.
     * Starts the background listener thread on success.
     *
     * @return true if connection succeeded, false otherwise.
     */
    public boolean connect() {
        try {
            socket = new Socket(host, port);

            // Output stream must be created first for OOS/OIS handshake.
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush();

            input = new ObjectInputStream(socket.getInputStream());

            startListening();
            return true;

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Sends a Message to the server.
     *
     * Synchronized because both the EDT and background threads may call this.
     * output.reset() clears the serialization cache so repeated objects
     * (e.g., DrawData segments) are always sent fresh.
     */
    public synchronized void sendMessage(Message message) {
        if (output == null) return;

        try {
            output.reset();
            output.writeObject(message);
            output.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts a daemon thread that continuously reads Message objects
     * from the server and forwards them to the messageHandler callback.
     *
     * The loop exits cleanly on socket close or EOF.
     */
    private void startListening() {
        Thread listenerThread = new Thread(() -> {
            try {
                while (true) {
                    Message message = (Message) input.readObject();
                    if (messageHandler != null) {
                        messageHandler.accept(message);
                    }
                }
            } catch (SocketException | EOFException e) {
                // Normal shutdown or server disconnect.
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("[ClientConnection] Listener error: " + e.getMessage());
            }
        });

        listenerThread.setDaemon(true); // does not prevent JVM exit
        listenerThread.start();
    }

    /**
     * Closes the socket. Streams close automatically with it.
     * Safe to call multiple times.
     */
    public void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}