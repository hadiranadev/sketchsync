package client.ui;

import client.ClientConnection;
import shared.Message;
import shared.MessageType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Initial entry screen for SketchSync.
 *
 * Responsibilities:
 *  - Display title + Play / Settings / Quit.
 *  - Let the user configure username + host.
 *  - Establish the initial server connection.
 *  - Perform the handshake (JOIN → wait for first reply).
 *  - On success, open LobbyFrame and hide itself.
 *
 * Connection lifecycle is owned by LobbyFrame after handoff.
 */
public class LauncherFrame extends JFrame {

    private final ClientSettings settings;

    public LauncherFrame() {
        settings = new ClientSettings();

        setTitle("SketchSync");
        setSize(560, 360);
        setMinimumSize(new Dimension(520, 320));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initUi();
        setVisible(true);
    }

    /** Builds the main launcher UI (title + buttons). */
    private void initUi() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(new EmptyBorder(24, 24, 24, 24));
        setContentPane(root);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("SketchSync");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 30f));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton playButton = new JButton("Play");
        JButton settingsButton = new JButton("Settings");
        JButton quitButton = new JButton("Quit");

        // Consistent button styling
        for (JButton b : new JButton[]{playButton, settingsButton, quitButton}) {
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            b.setMaximumSize(new Dimension(220, 42));
            b.setPreferredSize(new Dimension(220, 42));
        }

        playButton.setFont(playButton.getFont().deriveFont(Font.BOLD, 16f));

        playButton.addActionListener(e -> connectAndLaunchLobby());
        settingsButton.addActionListener(e -> showSettingsDialog());
        quitButton.addActionListener(e -> dispose());

        content.add(titleLabel);
        content.add(Box.createVerticalStrut(24));
        content.add(playButton);
        content.add(Box.createVerticalStrut(10));
        content.add(settingsButton);
        content.add(Box.createVerticalStrut(10));
        content.add(quitButton);

        root.add(content);
    }

    /**
     * Shows the username/host settings dialog.
     * Returns true if the user pressed OK and values were saved.
     */
    private boolean showSettingsDialog() {
        JTextField usernameField = new JTextField(settings.getUsername(), 18);
        JTextField hostField = new JTextField(settings.getHost(), 18);
        hostField.putClientProperty("JTextField.placeholderText", "localhost or 192.168.x.x");

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 8, 0, 8));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Username"), gbc);
        gbc.gridy = 1;
        panel.add(usernameField, gbc);
        gbc.gridy = 2;
        panel.add(new JLabel("Server Host"), gbc);
        gbc.gridy = 3;
        panel.add(hostField, gbc);

        gbc.gridy = 4;
        JLabel hint = new JLabel("Examples: localhost, 127.0.0.1, 192.168.0.24");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(hint, gbc);

        int result = JOptionPane.showConfirmDialog(
                this, panel, "Client Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) return false;

        String u = usernameField.getText().trim();
        String h = hostField.getText().trim();
        if (u.isEmpty()) u = "Player";
        if (h.isEmpty()) h = "localhost";

        settings.save(u, h);
        return true;
    }

    /**
     * Validates settings, then starts a background thread to perform the
     * connection handshake. This avoids blocking the EDT while waiting for
     * the server's first reply.
     */
    private void connectAndLaunchLobby() {
        String username = settings.getUsername();
        String host = settings.getHost();

        // Ensure required fields exist
        if (username.isBlank()) {
            if (!showSettingsDialog()) return;
            username = settings.getUsername();
            host = settings.getHost();
        }

        if (host.isBlank()) {
            host = "localhost";
            settings.save(username, host);
        }

        final String finalUsername = username;
        final String finalHost = host;

        // Run connection logic off the EDT
        new Thread(() -> doConnect(finalUsername, finalHost), "connect-thread").start();
    }

    /**
     * Performs the actual connection + handshake.
     *
     * Steps:
     *   1. Open socket.
     *   2. Send JOIN.
     *   3. Wait for the *first* server message using CountDownLatch.
     *      - LOBBY_ERROR → rejected (duplicate username, etc.)
     *      - ROOM_LIST   → accepted
     *   4. If accepted, open LobbyFrame on the EDT.
     *
     * All subsequent messages are forwarded directly to the lobby.
     */
    private void doConnect(String finalUsername, String finalHost) {
        CountDownLatch handshakeLatch = new CountDownLatch(1);
        Message[] firstMessage = {null};
        LobbyFrame[] lobbyRef = {null};

        // Listener captures the first message, then forwards the rest to the lobby
        ClientConnection conn = new ClientConnection(5000, finalHost, message -> {
            if (handshakeLatch.getCount() > 0) {
                firstMessage[0] = message;
                handshakeLatch.countDown();
                return;
            }
            LobbyFrame lobby = lobbyRef[0];
            if (lobby != null) lobby.handleMessage(message);
        });

        boolean ok = conn.connect();
        if (!ok) {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(
                            LauncherFrame.this,
                            "Could not connect to server at " + finalHost + ".\nCheck the server is running.",
                            "Connection Failed",
                            JOptionPane.ERROR_MESSAGE
                    ));
            return;
        }

        conn.sendMessage(new Message(MessageType.JOIN, finalUsername, finalUsername + " connected."));

        // Wait for the server's first reply (max 5 seconds)
        try {
            boolean arrived = handshakeLatch.await(5, TimeUnit.SECONDS);
            if (!arrived) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(
                                LauncherFrame.this,
                                "Server did not respond in time.\nCheck your connection and try again.",
                                "Timeout",
                                JOptionPane.ERROR_MESSAGE
                        ));
                conn.close();
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            conn.close();
            return;
        }

        Message response = firstMessage[0];

        // Server rejected the JOIN
        if (response != null && response.getType() == MessageType.LOBBY_ERROR) {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(
                            LauncherFrame.this,
                            response.getPayload().toString(),
                            "Connection Rejected",
                            JOptionPane.ERROR_MESSAGE
                    ));
            conn.close();
            return;
        }

        // Accepted — open lobby
        SwingUtilities.invokeLater(() -> {
            LobbyFrame lobby = new LobbyFrame(conn, finalUsername);
            lobbyRef[0] = lobby;

            // Deliver the first ROOM_LIST immediately
            if (response != null) lobby.handleMessage(response);

            // When lobby closes, return to launcher
            lobby.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    LauncherFrame.this.setVisible(true);
                }
            });

            setVisible(false);
        });
    }
}