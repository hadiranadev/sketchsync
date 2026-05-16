package client.ui;

import client.ClientConnection;
import shared.DrawData;
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

/**
 * Main Swing window for SketchSync.
 *
 * Owns all in-game UI: canvas, chat, player list, toolbar, hints, timer, etc.
 * The network connection is provided by LobbyFrame; this class does not manage
 * connection lifecycle.
 *
 * Two modes:
 *   - GAME: full guessing-game UI (roles, timer, secret word, hints, scores)
 *   - DOODLE: simple collaborative drawing + chat (no roles, no timer)
 *
 * LobbyFrame forwards server messages into {@link #handleIncomingMessage(Message)}.
 */
public class GameFrame extends JFrame {


    // 16 preset colours shown as clickable swatches in the toolbar.
    private static final Color[] PALETTE_COLORS = {
            Color.BLACK,
            new Color(80,  80,  80),   // Dark gray
            new Color(160, 160, 160),  // Gray
            Color.WHITE,
            new Color(220,  30,  30),  // Red
            new Color(255, 140,   0),  // Orange
            new Color(255, 230,   0),  // Yellow
            new Color(  0, 185,   0),  // Green
            new Color(  0, 210, 210),  // Cyan
            new Color( 30, 100, 255),  // Blue
            new Color(140,   0, 240),  // Purple
            new Color(255,   0, 190),  // Pink / Magenta
            new Color(150,  75,   0),  // Brown
            new Color(  0, 120,   0),  // Dark green
            new Color(  0,   0, 160),  // Dark blue
            new Color(255, 190, 140),  // Peach / Skin
    };

    private final RoomInfo.Mode mode;

    private JLabel roleLabel;
    private JLabel roundLabel;
    private JLabel timerLabel;
    private JLabel secretWordLabel;

    // Hint Display (Guessers only)
    private JLabel hintLabel;
    private JLabel wordLengthLabel; // shows "X letters" for guessers

    private DrawingPanel drawingPanel;

    private JPanel toolBar;

    // Shape tool toggle buttons (one active at a time via ButtonGroup)
    private JToggleButton penButton;
    private JToggleButton lineButton;
    private JToggleButton rectButton;
    private JToggleButton ovalButton;
    private JToggleButton eraserButton;

    // Colour palette — one small button per colour + a Custom... button
    private JButton[]     colorSwatches;          // PALETTE_COLORS.length buttons
    private JButton       customColorButton;       // opens JColorChooser
    private int           selectedSwatchIndex = 0; // which swatch is active

    // Other toolbar controls
    private JButton  clearButton;
    private JButton  hintButton;
    private JSpinner strokeSpinner;

    // Current pen colour (kept so Custom… can pre-fill with it)
    private Color currentPenColor = Color.BLACK;

    private JList<String>            playerList;
    private DefaultListModel<String> playerListModel;
    private JTextArea  chatArea;
    private JTextField guessField;
    private JButton    sendButton;

    private JTextArea leaderboardArea;
    private JDialog   leaderboardDialog;

    private final ClientConnection connection;
    private final String           username;

    private boolean isHost = false;
    private static final int TIMER_WARNING_SECS = 10;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int TIMER_URGENT_SECS  = 3;
    private boolean suppressLeaveOnClose = false;

    /**
     * @param connection Shared connection owned by LobbyFrame.
     * @param username   The local player's username.
     * @param mode       DOODLE or GAME — controls which UI elements are shown.
     */
    public GameFrame(ClientConnection connection, String username, RoomInfo.Mode mode) {
        this.connection = connection;
        this.username = (username == null || username.isBlank()) ? "Player" : username.trim();
        this.mode = (mode != null) ? mode : RoomInfo.Mode.GAME;

        String modeLabel = (this.mode == RoomInfo.Mode.DOODLE) ? "Doodle" : "Game";
        setTitle("SketchSync — " + this.username + " [" + modeLabel + "]");
        setSize(1100, 720);
        setMinimumSize(new Dimension(980, 640));
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);

        initComponents();
        layoutComponents();
        buildLeaderboardDialog();
        bindActions();
        bindWindowEvents();

        applyMode();
        setVisible(true);
    }

    // Adjusts UI after construction based on DOODLE vs GAME mode.
    // DOODLE: full drawing freedom, no roles/timer/secret word.
    // GAME: drawing restricted to the assigned Drawer; guessers cannot draw.
    private void applyMode() {
        if (mode == RoomInfo.Mode.DOODLE) {
            drawingPanel.setDrawingEnabled(true);
            setDrawingToolsEnabled(true);
            setGuessingEnabled(true);

            hintLabel.getParent().setVisible(false); // hintPanel
            secretWordLabel.setVisible(false);
            roleLabel.setText("Mode: Doodle");
            roundLabel.setVisible(false);
            timerLabel.setVisible(false);
            if (hintButton != null) hintButton.setVisible(false);
        } else {
            drawingPanel.setDrawingEnabled(false);
            setDrawingToolsEnabled(false);
            setGuessingEnabled(false);
        }
    }

    private void initComponents() {
        // Status bar elements (role, round, timer, secret word)
        // These are updated live via server messages.
        roleLabel = new JLabel("Role: Waiting…");
        roundLabel = new JLabel("Round: -");
        timerLabel = new JLabel("Time: -");
        secretWordLabel = new JLabel("Word: -");
        secretWordLabel.setFont(secretWordLabel.getFont().deriveFont(Font.BOLD));
        secretWordLabel.setVisible(false);

        hintLabel = new JLabel(" ");
        hintLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 18));
        hintLabel.setHorizontalAlignment(SwingConstants.CENTER);

        wordLengthLabel = new JLabel(" ");
        wordLengthLabel.setFont(wordLengthLabel.getFont().deriveFont(Font.ITALIC, 13f));
        wordLengthLabel.setHorizontalAlignment(SwingConstants.CENTER);
        wordLengthLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        // Canvas where all drawing occurs. Disabled until role assignment in GAME mode.
        drawingPanel = new DrawingPanel();
        drawingPanel.setBorder(BorderFactory.createTitledBorder("Canvas"));
        drawingPanel.setDrawingEnabled(false);

        // Shape tools (mutually exclusive via ButtonGroup)
        penButton    = new JToggleButton("✏ Pen");
        lineButton   = new JToggleButton("╱ Line");
        rectButton   = new JToggleButton("▭ Rect");
        ovalButton   = new JToggleButton("◯ Oval");
        eraserButton = new JToggleButton("⌫ Erase");

        // Only 1 tool active at a time
        ButtonGroup toolGroup = new ButtonGroup();
        toolGroup.add(penButton);
        toolGroup.add(lineButton);
        toolGroup.add(rectButton);
        toolGroup.add(ovalButton);
        toolGroup.add(eraserButton);
        penButton.setSelected(true);  // default tool

        // 16 preset swatches; clicking one updates the DrawingPanel colour.
        colorSwatches = new JButton[PALETTE_COLORS.length];
        for (int i = 0; i < PALETTE_COLORS.length; i++) {
            JButton swatch = new JButton();
            swatch.setPreferredSize(new Dimension(24, 24));
            swatch.setBackground(PALETTE_COLORS[i]);
            swatch.setOpaque(true);
            swatch.setBorderPainted(true);
            swatch.setFocusPainted(false);
            swatch.setToolTipText(colorName(i));
            colorSwatches[i] = swatch;
        }
        // Highlight the default selected swatch (black)
        highlightSwatch(0);

        // Custom… opens JColorChooser and clears swatch selection.
        customColorButton = new JButton("Custom…");
        customColorButton.setToolTipText("Pick any colour with the colour chooser");
        customColorButton.putClientProperty("JButton.buttonType", "roundRect");

        // Other toolbar controls
        clearButton = new JButton("🗑 Clear");
        hintButton = new JButton("Hint 💡");
        strokeSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 40, 1));
        strokeSpinner.setPreferredSize(new Dimension(55, 26));

        for (JButton b : new JButton[]{ clearButton, hintButton }) {
            b.putClientProperty("JButton.buttonType", "roundRect");
        }

        // Chat area (read-only) + input field.
        playerListModel = new DefaultListModel<>();
        playerList = new JList<>(playerListModel);

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setMargin(new Insets(8, 8, 8, 8));

        // In GAME mode, guessField doubles as the guessing input.
        guessField = new JTextField();
        guessField.putClientProperty("JTextField.placeholderText", mode == RoomInfo.Mode.DOODLE ? "Type a message…" : "Type a guess or message…");

        sendButton = new JButton("Send");
        sendButton.putClientProperty("JButton.buttonType", "roundRect");

        setGuessingEnabled(false);
    }

    private void layoutComponents() {
        setLayout(new BorderLayout(8, 8));

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // North: Status bar
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 6));
        statusPanel.setBorder(BorderFactory.createTitledBorder("Game Status"));
        statusPanel.add(roleLabel);
        statusPanel.add(new JSeparator(SwingConstants.VERTICAL));
        statusPanel.add(roundLabel);
        statusPanel.add(new JSeparator(SwingConstants.VERTICAL));
        statusPanel.add(timerLabel);
        statusPanel.add(new JSeparator(SwingConstants.VERTICAL));
        statusPanel.add(secretWordLabel);

        // Toolbar: two rows stacked inside a titled panel
        toolBar = new JPanel();
        toolBar.setLayout(new BoxLayout(toolBar, BoxLayout.Y_AXIS));
        toolBar.setBorder(BorderFactory.createTitledBorder("Tools"));

        // Row 1: Shape tools + size + clear + hint
        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolRow.add(penButton);
        toolRow.add(lineButton);
        toolRow.add(rectButton);
        toolRow.add(ovalButton);
        toolRow.add(eraserButton);
        toolRow.add(Box.createHorizontalStrut(12));
        toolRow.add(new JLabel("Size:"));
        toolRow.add(strokeSpinner);
        toolRow.add(Box.createHorizontalStrut(12));
        toolRow.add(clearButton);
        toolRow.add(hintButton);

        // Row 2: Colour swatches
        JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        colorRow.add(new JLabel("Color:"));
        for (JButton swatch : colorSwatches) {
            colorRow.add(swatch);
        }
        colorRow.add(Box.createHorizontalStrut(6));
        colorRow.add(customColorButton);

        toolBar.add(toolRow);
        toolBar.add(colorRow);

        JPanel hintPanel = new JPanel(new BorderLayout(2, 2));
        hintPanel.setBorder(BorderFactory.createTitledBorder("Word Hint"));
        hintPanel.add(hintLabel, BorderLayout.CENTER);
        hintPanel.add(wordLengthLabel, BorderLayout.SOUTH);

        JPanel centerColumn = new JPanel(new BorderLayout(4, 4));
        centerColumn.add(hintPanel, BorderLayout.NORTH);
        centerColumn.add(drawingPanel, BorderLayout.CENTER);
        centerColumn.add(toolBar, BorderLayout.SOUTH);

        setDrawingToolsEnabled(false);

        playerList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane playerScrollPane = new JScrollPane(playerList);

        JButton kickButton = new JButton("Kick Selected");
        kickButton.putClientProperty("JButton.buttonType", "roundRect");
        kickButton.setEnabled(false);
        kickButton.setName("kickButton");
        kickButton.addActionListener(e -> {
            String selected = playerList.getSelectedValue();
            if (selected == null) {
                return;
            }
            String target = selected.replaceAll("^[^a-zA-Z0-9]*", "").split(":")[0].trim();
            connection.sendMessage(new Message(MessageType.KICK, username, target));
        });

        JPanel playerPanel = new JPanel(new BorderLayout(4, 4));
        playerPanel.setBorder(BorderFactory.createTitledBorder("Players"));
        playerPanel.add(playerScrollPane, BorderLayout.CENTER);
        playerPanel.add(kickButton, BorderLayout.SOUTH);

        // Chat/Guess
        JPanel chatPanel = new JPanel(new BorderLayout(4, 4));
        chatPanel.setBorder(BorderFactory.createTitledBorder(
                mode == RoomInfo.Mode.DOODLE ? "Chat" : "Chat / Guess"));
        chatPanel.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JPanel inputRow = new JPanel(new BorderLayout(4, 4));
        inputRow.add(guessField, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);
        chatPanel.add(inputRow, BorderLayout.SOUTH);

        JSplitPane rightPanel = new JSplitPane(JSplitPane.VERTICAL_SPLIT, playerPanel, chatPanel);
        rightPanel.setDividerLocation(220);
        rightPanel.setResizeWeight(0.3);
        rightPanel.setPreferredSize(new Dimension(310, 0));

        mainPanel.add(statusPanel,  BorderLayout.NORTH);
        mainPanel.add(centerColumn, BorderLayout.CENTER);
        mainPanel.add(rightPanel,   BorderLayout.EAST);

        add(mainPanel, BorderLayout.CENTER);
        getRootPane().setDefaultButton(sendButton);
    }

    private void buildLeaderboardDialog() {
        // Small non-modal dialogue shown when server sends LEADERBOARD_UPDATE.
        leaderboardDialog = new JDialog(this, "🏆 All-Time Leaderboard", false);
        leaderboardDialog.setSize(340, 320);
        leaderboardDialog.setLocationRelativeTo(this);

        leaderboardArea = new JTextArea();
        leaderboardArea.setEditable(false);
        leaderboardArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        leaderboardArea.setMargin(new Insets(12, 12, 12, 12));

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> leaderboardDialog.setVisible(false));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.add(new JScrollPane(leaderboardArea), BorderLayout.CENTER);
        panel.add(closeBtn, BorderLayout.SOUTH);
        leaderboardDialog.setContentPane(panel);
    }

    // If the user closes the window normally, notify server they left the room.
    // If kicked or returned to lobby, suppress this message.
    private void bindWindowEvents() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (!suppressLeaveOnClose) {
                    connection.sendMessage(new Message(MessageType.LEAVE_ROOM, username, null));
                }
            }
        });
    }

    // All UI -> server interactions are wired here.
    // DrawingPanel emits DrawData via drawListener; forwarded to server.
    private void bindActions() {
        // Chat/guess
        sendButton.addActionListener(e -> sendCurrentInput());
        guessField.addActionListener(e -> sendCurrentInput());

        // Clear entire canvas
        clearButton.addActionListener(e -> {
            drawingPanel.clearCanvas();
            connection.sendMessage(new Message(MessageType.CLEAR_CANVAS, username, null));
        });

        hintButton.addActionListener(e ->
                connection.sendMessage(new Message(MessageType.HINT_REQUEST, username, null)));

        // Size of stroke (pen)
        strokeSpinner.addChangeListener(e ->
                drawingPanel.setCurrentStrokeWidth((int) strokeSpinner.getValue()));

        // Shape tool buttons: each one sets the DrawingPanel's tool
        penButton.addActionListener(e -> {
            drawingPanel.setTool(DrawingPanel.Tool.FREEHAND);
        });
        lineButton.addActionListener(e -> {
            drawingPanel.setTool(DrawingPanel.Tool.LINE);
        });
        rectButton.addActionListener(e -> {
            drawingPanel.setTool(DrawingPanel.Tool.RECT);
        });
        ovalButton.addActionListener(e -> {
            drawingPanel.setTool(DrawingPanel.Tool.OVAL);
        });
        eraserButton.addActionListener(e -> {
            drawingPanel.setTool(DrawingPanel.Tool.ERASER);
        });

        for (int i = 0; i < PALETTE_COLORS.length; i++) {
            final int index = i;
            colorSwatches[i].addActionListener(e -> {
                selectPaletteColor(index);
            });
        }

        customColorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, "Pick a colour", currentPenColor);
            if (chosen != null) {
                currentPenColor = chosen;
                drawingPanel.setCurrentColor(currentPenColor);

                // Deselect all swatches visually (no palette swatch matches custom)
                for (int i = 0; i < colorSwatches.length; i++) {
                    colorSwatches[i].setBorder(UIManager.getBorder("Button.border"));
                }
                selectedSwatchIndex = -1;

                // Switch back to pen if the eraser was active
                if (drawingPanel.getTool() == DrawingPanel.Tool.ERASER) {
                    drawingPanel.setTool(DrawingPanel.Tool.FREEHAND);
                    penButton.setSelected(true);
                }
            }
        });

        // Forward freehand/shape data to the server
        drawingPanel.setDrawListener(drawData ->
                connection.sendMessage(new Message(MessageType.DRAW_DATA, username, drawData)));
    }

    // Selects a palette colour, updates DrawingPanel, and exits eraser mode if active.
    private void selectPaletteColor(int index) {
        currentPenColor = PALETTE_COLORS[index];
        drawingPanel.setCurrentColor(currentPenColor);

        // if eraser was on, switch back to pen tool
        if (drawingPanel.getTool() == DrawingPanel.Tool.ERASER) {
            drawingPanel.setTool(DrawingPanel.Tool.FREEHAND);
            penButton.setSelected(true);
        }

        highlightSwatch(index);
        selectedSwatchIndex = index;
    }

    // Gives the selected swatch a thick coloured border so you can see which one is active.
    // All others get the default button border.
    // Visually marks the active swatch with a cyan border.
    private void highlightSwatch(int index) {
        for (int i = 0; i < colorSwatches.length; i++) {
            if (i == index) {
                colorSwatches[i].setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.CYAN.darker(), 2),
                        BorderFactory.createLineBorder(Color.WHITE, 1)
                ));
            } else {
                colorSwatches[i].setBorder(UIManager.getBorder("Button.border"));
            }
        }
    }

    private static String colorName(int index) {
        String[] names = {
                "Black", "Dark Gray", "Gray", "White", "Red", "Orange", "Yellow", "Green",
                "Cyan", "Blue", "Purple", "Pink", "Brown", "Dark Green", "Dark Blue", "Peach"
        };
        return (index >= 0 && index < names.length) ? names[index] : "Color";
    }

    // Main entry point for all server → client updates.
    // Always executed on the EDT via SwingUtilities.invokeLater.
    // Note to self: called by LobbyFrame (on the EDT) to handle game-level server messages.
    public void handleIncomingMessage(Message message) {
        SwingUtilities.invokeLater(() -> {

            switch (message.getType()) {
                case CHAT, JOIN, LEAVE -> {
                    String chatText = String.valueOf(message.getPayload());
                    addChatMessage("[" + message.getSender() + "]: " + chatText);
                    // Play a ding when someone guesses correctly
                    if ("Server".equals(message.getSender()) && chatText.contains("guessed correctly")) {
                        SoundPlayer.playCorrect();
                    }
                }

                case DRAW_DATA -> drawingPanel.addRemoteStroke((DrawData) message.getPayload());

                case CLEAR_CANVAS -> drawingPanel.clearCanvas();

                case WORD_CHOICE -> {
                    String[] options = String.valueOf(message.getPayload()).split(",");
                    showWordChoiceDialog(options);
                }

                case ROLE_ASSIGNMENT -> {
                    if (mode == RoomInfo.Mode.DOODLE) break;
                    String role = String.valueOf(message.getPayload());
                    setRoleLabel(role);
                    boolean isDrawer = role.equalsIgnoreCase("Drawer");
                    drawingPanel.setDrawingEnabled(isDrawer);
                    setDrawingToolsEnabled(isDrawer);
                    setSecretWordVisible(isDrawer);
                    if (!isDrawer) setSecretWordText("-");
                    setGuessingEnabled(true);
                    hintLabel.setText(" ");
                    wordLengthLabel.setText(" ");
                }

                case SECRET_WORD -> {
                    setSecretWordText(String.valueOf(message.getPayload()));
                    setSecretWordVisible(true);
                }

                case HINT -> hintLabel.setText(String.valueOf(message.getPayload()));

                case WORD_LENGTH -> {
                    int len = (int) message.getPayload();
                    wordLengthLabel.setText(len + " letters");
                }

                case ROUND_START -> setRoundLabel(String.valueOf(message.getPayload()));

                case SCORE_UPDATE -> updatePlayerList(String.valueOf(message.getPayload()));

                case ROUND_END -> {
                    addChatMessage("[System]: " + message.getPayload());
                    if (mode == RoomInfo.Mode.GAME) {
                        drawingPanel.setDrawingEnabled(false);
                        setDrawingToolsEnabled(false);
                        setSecretWordText("-");
                        setSecretWordVisible(false);
                        setGuessingEnabled(false);
                        hintLabel.setText(" ");
                        wordLengthLabel.setText(" ");
                        timerLabel.setForeground(UIManager.getColor("Label.foreground"));
                    }
                    SoundPlayer.playRoundEnd();
                }

                case TIMER_UPDATE -> {
                    if (mode == RoomInfo.Mode.DOODLE) break;
                    String timeStr = String.valueOf(message.getPayload());
                    setTimerLabel(timeStr);
                    try {
                        int secs = Integer.parseInt(timeStr);
                        if (secs <= TIMER_URGENT_SECS) {
                            timerLabel.setForeground(Color.RED);
                            SoundPlayer.playUrgent();
                        } else if (secs <= TIMER_WARNING_SECS) {
                            timerLabel.setForeground(Color.RED);
                            SoundPlayer.playTick();
                        } else {
                            timerLabel.setForeground(UIManager.getColor("Label.foreground"));
                        }
                    } catch (NumberFormatException ignored) {}
                }

                case LEADERBOARD_UPDATE -> {
                    String lb = String.valueOf(message.getPayload());
                    leaderboardArea.setText("🏆 All-Time Leaderboard\n\n" + lb);
                    leaderboardDialog.setVisible(true);
                }

                case KICKED, KICKED_TO_LOBBY -> {
                    suppressLeaveOnClose = true;
                    dispose();
                }
            }
        });
    }

    // Drawer chooses one of several words sent by the server.
    private void showWordChoiceDialog(String[] options) {
        if (options == null || options.length == 0) return;

        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("Choose a word to draw:"));

        ButtonGroup  group = new ButtonGroup();
        JRadioButton[] rbs = new JRadioButton[options.length];
        for (int i = 0; i < options.length; i++) {
            rbs[i] = new JRadioButton(options[i].trim());
            if (i == 0) rbs[i].setSelected(true);
            group.add(rbs[i]);
            panel.add(rbs[i]);
        }

        int result = JOptionPane.showConfirmDialog(this, panel, "Word Choice", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        String chosen = options[0].trim();
        if (result == JOptionPane.OK_OPTION) {
            for (JRadioButton rb : rbs) {
                if (rb.isSelected()) {
                    chosen = rb.getText().trim(); break;
                }
            }
        }
        connection.sendMessage(new Message(MessageType.WORD_SELECTED, username, chosen));
    }

    // Updates player list and determines whether this client is host.
    // Host can kick players; others cannot.
    private void updatePlayerList(String scorePayload) {
        playerListModel.clear();
        if (scorePayload == null || scorePayload.isBlank()) return;

        String[] entries = scorePayload.trim().split("\\s{2,}");
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i].trim();
            if (i == 0) {
                entry = "👑 " + entry;
                isHost = entries[0].trim().startsWith(username + ":");
                updateKickButton();
            }
            playerListModel.addElement(entry);
        }
    }

    private void updateKickButton() {
        JButton kb = findKickButton(getContentPane());
        if (kb != null) kb.setEnabled(isHost);
    }

    // Recursively searches UI tree for the kick button by name.
    private JButton findKickButton(Component c) {
        if (c instanceof JButton b && "kickButton".equals(b.getName())) {
            return b;
        }
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                JButton found = findKickButton(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    public void setRoleLabel(String role) {
        roleLabel.setText("Role: " + role);
    }
    public void setRoundLabel(String round) {
        roundLabel.setText("Round: " + round);
    }
    public void setTimerLabel(String time) {
        timerLabel.setText("Time: " + time);
    }
    public void setSecretWordText(String word) {
        secretWordLabel.setText("Word: " + word);
    }
    public void setSecretWordVisible(boolean v) {
        secretWordLabel.setVisible(v);
    }

    public void setGuessingEnabled(boolean enabled) {
        guessField.setEditable(enabled);
        guessField.setEnabled(enabled);
        sendButton.setEnabled(enabled);
    }

    private void setDrawingToolsEnabled(boolean enabled) {
        if (toolBar != null) {
            toolBar.setVisible(enabled);
        }

        penButton.setEnabled(enabled);
        lineButton.setEnabled(enabled);
        rectButton.setEnabled(enabled);
        ovalButton.setEnabled(enabled);
        eraserButton.setEnabled(enabled);

        for (JButton swatch : colorSwatches) {
            swatch.setEnabled(enabled);
        }
        customColorButton.setEnabled(enabled);

        // Other controls
        clearButton.setEnabled(enabled);
        strokeSpinner.setEnabled(enabled);

        if (hintButton != null) {
            hintButton.setEnabled(enabled && mode == RoomInfo.Mode.GAME);
        }

        // When disabling, reset to the default pen tool
        if (!enabled) {
            drawingPanel.setTool(DrawingPanel.Tool.FREEHAND);
            penButton.setSelected(true);
        }
    }

    // Appends a timestamped chat message and scrolls to bottom.
    public void addChatMessage(String message) {
        String timestamp = "[" + LocalTime.now().format(TIME_FMT) + "] ";
        chatArea.append(timestamp + message + "\n");
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    // Sends the current input text as a CHAT message.
    private void sendCurrentInput() {
        String text = guessField.getText().trim();
        if (text.isEmpty()) return;
        connection.sendMessage(new Message(MessageType.CHAT, username, text));
        guessField.setText("");
    }

    // Updates player list + host status when LobbyFrame sends a room refresh.
    public void handleRoomUpdate(RoomInfo roomInfo) {
        if (roomInfo == null) return;

        SwingUtilities.invokeLater(() -> {
            playerListModel.clear();

            java.util.List<String> members = roomInfo.getMemberUsernames();
            for (int i = 0; i < members.size(); i++) {
                String name = members.get(i);
                String entry = (i == 0 ? "👑 " : "") + name;
                playerListModel.addElement(entry);
            }

            isHost = roomInfo.getHostUsername() != null
                    && roomInfo.getHostUsername().equalsIgnoreCase(username);
            updateKickButton();
        });
    }

    public void markClosingFromLobby() {
        suppressLeaveOnClose = true;
    }
}