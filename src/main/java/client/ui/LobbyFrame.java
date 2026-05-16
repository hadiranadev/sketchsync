package client.ui;

import client.ClientConnection;
import shared.Message;
import shared.MessageType;
import shared.RoomInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * LobbyFrame — the room browser shown after a successful server connection.
 *
 * Responsibilities:
 *   - Display available rooms and allow creating/joining them.
 *   - Show the user's current room (if any) with chat + host controls.
 *   - Launch GameFrame when a room transitions into an active game.
 *   - Remain alive in the background while the game runs.
 *
 * All server → UI updates are dispatched onto the EDT via invokeLater().
 * LobbyFrame regains visibility when GameFrame closes.
 */
public class LobbyFrame extends JFrame {

    private final ClientConnection connection;
    private final String username;

    private final DefaultListModel<RoomInfo> roomListModel = new DefaultListModel<>();
    private final JList<RoomInfo> roomList = new JList<>(roomListModel);

    private JPanel     roomPanel;
    private JLabel     roomNameLabel;
    private JLabel     roomModeLabel;
    private JLabel     roomPlayersLabel;
    private JButton    startGameButton;
    private JButton    setDoodleButton;
    private JButton    setGameButton;
    private JButton    leaveRoomButton;
    private JTextArea  roomChatArea;
    private JTextField roomChatField;
    private JTextArea  customWordArea;  // host types custom words here
    private JButton    customWordButton;

    private JPanel     lobbyPanel;
    private JTextField createRoomField;
    private JButton    createRoomButton;
    private JButton    joinRoomButton;
    private JButton    refreshRoomsButton;
    private JLabel     statusLabel;

    private RoomInfo currentRoom = null;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private boolean inGame = false;

    private GameFrame activeGameFrame = null;

    public LobbyFrame(ClientConnection connection, String username) {
        this.connection = connection;
        this.username = username;

        setTitle("SketchSync Lobby — " + username);
        setSize(760, 520);
        setMinimumSize(new Dimension(680, 440));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);

        buildUi();
        bindWindowEvents();
        setVisible(true);

        requestRoomRefresh();
    }

    private void buildUi() {
        // Root layout: header + split pane (room list | right panel)
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Lobby");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        statusLabel = new JLabel("Connected as " + username + ".");
        statusLabel.setFont(statusLabel.getFont().deriveFont(13f));

        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JSplitPane centre = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildRoomListPanel(), buildRightPanel());
        centre.setDividerLocation(360);
        centre.setResizeWeight(0.55);
        root.add(centre, BorderLayout.CENTER);

        setContentPane(root);
    }

    // Left side: list of rooms with refresh button.
    // Uses custom renderer to show icons + lock indicator.
    private JPanel buildRoomListPanel() {
        roomList.setCellRenderer(new RoomListCellRenderer());
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JScrollPane scroll = new JScrollPane(roomList);
        scroll.setBorder(BorderFactory.createTitledBorder("Available Rooms"));

        refreshRoomsButton = new JButton("Refresh Rooms");
        refreshRoomsButton.putClientProperty("JButton.buttonType", "roundRect");
        refreshRoomsButton.addActionListener(e -> requestRoomRefresh());

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(scroll, BorderLayout.CENTER);
        p.add(refreshRoomsButton, BorderLayout.SOUTH);
        return p;
    }

    // Right side switches between:
    //   - lobbyPanel (create/join room)
    //   - roomPanel (inside a room)
    // roomPanel stays hidden until the user joins a room.
    private JPanel buildRightPanel() {
        JPanel right = new JPanel(new BorderLayout(8, 8));

        lobbyPanel = new JPanel(new GridBagLayout());
        lobbyPanel.setBorder(BorderFactory.createTitledBorder("Join or Create"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        createRoomField = new JTextField();
        createRoomField.putClientProperty("JTextField.placeholderText", "Room name…");

        createRoomButton = new JButton("Create Room");
        joinRoomButton = new JButton("Join Selected Room");

        for (JButton b : new JButton[]{createRoomButton, joinRoomButton}) {
            b.putClientProperty("JButton.buttonType", "roundRect");
        }

        gbc.gridx = 0;
        gbc.gridy = 0;
        lobbyPanel.add(new JLabel("New room name:"), gbc);
        gbc.gridy = 1;
        lobbyPanel.add(createRoomField, gbc);
        gbc.gridy = 2;
        lobbyPanel.add(createRoomButton, gbc);

        JSeparator sep = new JSeparator();
        gbc.gridy = 3;
        gbc.insets = new Insets(12, 6, 12, 6);
        lobbyPanel.add(sep, gbc);
        gbc.insets = new Insets(6, 6, 6, 6);

        gbc.gridy = 4;
        lobbyPanel.add(joinRoomButton, gbc);

        roomPanel = new JPanel(new BorderLayout(6, 6));
        roomPanel.setBorder(BorderFactory.createTitledBorder("Your Room"));

        roomNameLabel = new JLabel("Room: —");
        roomModeLabel = new JLabel("Mode: —");
        roomPlayersLabel = new JLabel("Players: —");

        roomNameLabel.setFont(roomNameLabel.getFont().deriveFont(Font.BOLD, 14f));

        JPanel infoRow = new JPanel(new GridLayout(3, 1, 4, 4));
        infoRow.add(roomNameLabel);
        infoRow.add(roomModeLabel);
        infoRow.add(roomPlayersLabel);

        setDoodleButton = new JButton("Switch to Doodle Mode");
        setGameButton = new JButton("Switch to Game Mode");
        startGameButton = new JButton("▶  Start");
        leaveRoomButton = new JButton("Leave Room");

        startGameButton.setFont(startGameButton.getFont().deriveFont(Font.BOLD, 13f));

        for (JButton b : new JButton[]{setDoodleButton, setGameButton, startGameButton, leaveRoomButton}) {
            b.putClientProperty("JButton.buttonType", "roundRect");
        }

        JPanel modeButtons = new JPanel(new GridLayout(2, 1, 4, 4));
        modeButtons.add(setDoodleButton);
        modeButtons.add(setGameButton);

        JPanel controlButtons = new JPanel(new GridLayout(2, 1, 4, 4));
        controlButtons.add(startGameButton);
        controlButtons.add(leaveRoomButton);

        roomChatArea = new JTextArea(6, 20);
        roomChatArea.setEditable(false);
        roomChatArea.setLineWrap(true);
        roomChatArea.setWrapStyleWord(true);
        roomChatArea.setMargin(new Insets(6, 6, 6, 6));

        roomChatField = new JTextField();
        roomChatField.putClientProperty("JTextField.placeholderText", "Chat in room…");

        JPanel chatRow = new JPanel(new BorderLayout(4, 4));
        chatRow.add(roomChatField, BorderLayout.CENTER);
        JButton chatSend = new JButton("Send");
        chatSend.putClientProperty("JButton.buttonType", "roundRect");
        chatSend.addActionListener(e -> sendRoomChat());
        roomChatField.addActionListener(e -> sendRoomChat());
        chatRow.add(chatSend, BorderLayout.EAST);

        // Custom word list (host only, session-scoped)
        customWordArea = new JTextArea(3, 20);
        customWordArea.setLineWrap(true);
        customWordArea.setWrapStyleWord(true);
        customWordArea.setMargin(new Insets(4, 4, 4, 4));
        customWordArea.putClientProperty("JTextArea.placeholderText", "apple, banana, rocket (comma-separated, optional)");
        customWordArea.setEnabled(false);

        customWordButton = new JButton("Set Custom Words");
        customWordButton.putClientProperty("JButton.buttonType", "roundRect");
        customWordButton.setEnabled(false);
        customWordButton.addActionListener(e -> {
            String text = customWordArea.getText().trim();
            connection.sendMessage(new Message(MessageType.CUSTOM_WORD_LIST, username, text));
        });

        JPanel customWordPanel = new JPanel(new BorderLayout(4, 4));
        customWordPanel.setBorder(BorderFactory.createTitledBorder("Custom Words (host only, this session)"));
        customWordPanel.add(new JScrollPane(customWordArea), BorderLayout.CENTER);
        customWordPanel.add(customWordButton, BorderLayout.SOUTH);

        JPanel topArea = new JPanel(new BorderLayout(6, 6));
        topArea.add(infoRow, BorderLayout.NORTH);
        topArea.add(modeButtons, BorderLayout.CENTER);
        topArea.add(controlButtons, BorderLayout.SOUTH);

        JPanel roomCenterPanel = new JPanel(new BorderLayout(4, 4));
        roomCenterPanel.add(customWordPanel, BorderLayout.NORTH);
        roomCenterPanel.add(new JScrollPane(roomChatArea), BorderLayout.CENTER);

        roomPanel.add(topArea, BorderLayout.NORTH);
        roomPanel.add(roomCenterPanel, BorderLayout.CENTER);
        roomPanel.add(chatRow, BorderLayout.SOUTH);
        roomPanel.setVisible(false);

        createRoomButton.addActionListener(e -> {
            String name = createRoomField.getText().trim();
            if (!name.isBlank()) {
                connection.sendMessage(new Message(MessageType.CREATE_ROOM, username, name));
                createRoomField.setText("");
            }
        });

        joinRoomButton.addActionListener(e -> {
            RoomInfo selected = roomList.getSelectedValue();
            if (selected != null) {
                connection.sendMessage(new Message(MessageType.JOIN_ROOM, username, selected.getName()));
            } else {
                JOptionPane.showMessageDialog(this,
                        "Select a room from the list first.",
                        "No Room Selected",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });

        setDoodleButton.addActionListener(e ->
                connection.sendMessage(new Message(MessageType.SET_MODE, username, "DOODLE")));

        setGameButton.addActionListener(e ->
                connection.sendMessage(new Message(MessageType.SET_MODE, username, "GAME")));

        startGameButton.addActionListener(e ->
                connection.sendMessage(new Message(MessageType.START_GAME, username, null)));

        leaveRoomButton.addActionListener(e ->
                connection.sendMessage(new Message(MessageType.LEAVE_ROOM, username, null)));

        right.add(lobbyPanel, BorderLayout.NORTH);
        right.add(roomPanel, BorderLayout.CENTER);
        return right;
    }

    public void handleMessage(Message message) {
        // Always forward to EDT for thread safety.
        SwingUtilities.invokeLater(() -> dispatch(message));
    }
    @SuppressWarnings("unchecked")
    private void dispatch(Message message) {
        switch (message.getType()) {

            case ROOM_LIST -> {
                // Update the room browser list.
                List<RoomInfo> rooms = (List<RoomInfo>) message.getPayload();
                roomListModel.clear();
                if (rooms != null) rooms.forEach(roomListModel::addElement);
            }

            case ROOM_UPDATE -> {
                // Update current room info.
                // Switch UI from lobby → room if needed.
                // If room has started, open GameFrame.
                currentRoom = (RoomInfo) message.getPayload();
                refreshRoomPanel();

                if (!roomPanel.isVisible() && !inGame) {
                    lobbyPanel.setVisible(false);
                    roomPanel.setVisible(true);
                }

                if (activeGameFrame != null) {
                    activeGameFrame.handleRoomUpdate(currentRoom);
                }

                // Only open the game window when the room has actually started.
                if (!inGame && currentRoom != null && currentRoom.isInProgress()) {
                    openGameFrame();
                }
            }

            case JOIN, LEAVE, CHAT -> {
                // If in-game, forward to GameFrame.
                // Otherwise, append to room chat.
                if (activeGameFrame != null) {
                    activeGameFrame.handleIncomingMessage(message);
                } else {
                    appendRoomChat("[" + message.getSender() + "]: " + message.getPayload());
                }
            }

            case LEAVE_ROOM -> {
                // Returned to lobby; reset room UI.
                currentRoom = null;
                inGame = false;
                roomPanel.setVisible(false);
                lobbyPanel.setVisible(true);
                roomChatArea.setText("");
                requestRoomRefresh();
            }

            case KICKED_TO_LOBBY -> {
                // Host kicked us; close game if open and return to lobby.
                String reason = String.valueOf(message.getPayload());
                currentRoom = null;
                inGame = false;
                roomPanel.setVisible(false);
                lobbyPanel.setVisible(true);
                roomChatArea.setText("");

                if (activeGameFrame != null) {
                    activeGameFrame.markClosingFromLobby();
                    activeGameFrame.dispose();
                    activeGameFrame = null;
                }

                requestRoomRefresh();
                JOptionPane.showMessageDialog(this, reason, "Kicked", JOptionPane.WARNING_MESSAGE);
            }

            case LOBBY_ERROR -> {
                // Show server error (duplicate room, invalid name, etc.)
                String err = String.valueOf(message.getPayload());
                JOptionPane.showMessageDialog(this, err, "Error", JOptionPane.ERROR_MESSAGE);
            }

            default -> {
                // Forward any game-related messages to GameFrame.
                if (activeGameFrame != null) {
                    activeGameFrame.handleIncomingMessage(message);
                }
            }
        }
    }

    // Host-only controls: switch mode, start game, custom words.
    // Enabled only when user is host and game not in progress.
    // Updates room info labels and enables/disables host controls.
    private void refreshRoomPanel() {
        if (currentRoom == null) return;

        roomNameLabel.setText("Room: " + currentRoom.getName());
        roomModeLabel.setText("Mode: " + currentRoom.getMode());
        roomPlayersLabel.setText("Players: "
                + String.join(", ", currentRoom.getMemberUsernames()));

        boolean isHost = currentRoom.getHostUsername() != null
                && currentRoom.getHostUsername().equalsIgnoreCase(username);

        // Host-only controls: switch mode, start game, custom words.
        // Disabled for non-hosts or once the game starts.
        setDoodleButton.setEnabled(isHost && !currentRoom.isInProgress());
        setGameButton.setEnabled(isHost && !currentRoom.isInProgress());
        startGameButton.setEnabled(isHost && !currentRoom.isInProgress());
        if (customWordArea != null) customWordArea.setEnabled(isHost && !currentRoom.isInProgress());
        if (customWordButton != null) customWordButton.setEnabled(isHost && !currentRoom.isInProgress());
    }

    // Append timestamped chat message to room chat area.
    private void appendRoomChat(String text) {
        String ts = "[" + LocalTime.now().format(TIME_FMT) + "] ";
        roomChatArea.append(ts + text + "\n");
        roomChatArea.setCaretPosition(roomChatArea.getDocument().getLength());
    }

    // Chat visible only inside a room.
    // If a GameFrame is active, chat messages are forwarded to it instead.
    // Send chat message to server.
    private void sendRoomChat() {
        String text = roomChatField.getText().trim();
        if (text.isBlank()) return;
        connection.sendMessage(new Message(MessageType.CHAT, username, text));
        roomChatField.setText("");
    }

    // Ask server for updated room list.
    private void requestRoomRefresh() {
        connection.sendMessage(new Message(MessageType.REFRESH_ROOMS, username, null));
    }

    // Opens GameFrame when the room transitions into in-progress state.
    // LobbyFrame hides but stays alive; becomes visible again when game closes.
    private void openGameFrame() {
        inGame = true;

        RoomInfo.Mode mode = (currentRoom != null) ? currentRoom.getMode() : RoomInfo.Mode.GAME;
        activeGameFrame = new GameFrame(connection, username, mode);

        if (currentRoom != null) {
            activeGameFrame.handleRoomUpdate(currentRoom);
        }

        activeGameFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                inGame = false;
                activeGameFrame = null;
                LobbyFrame.this.setVisible(true);
            }
        });

        setVisible(false);
    }

    // Closing the lobby closes the connection.
    // Activating the window triggers a room list refresh.
    private void bindWindowEvents() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                connection.close();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                requestRoomRefresh();
            }
        });
    }

    // Custom renderer adds icons and lock indicator for in-progress GAME rooms.
    private static class RoomListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {

            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof RoomInfo info) {
                String modeIcon = (info.getMode() == RoomInfo.Mode.DOODLE) ? "🎨" : "🎮";
                String lock = (info.isInProgress() && info.getMode() == RoomInfo.Mode.GAME) ? " 🔒" : "";
                setText(modeIcon + " " + info.getName() + " — " + info.getPlayerCount() + " player(s)" + lock);
            }
            return this;
        }
    }
}