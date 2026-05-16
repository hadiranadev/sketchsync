package client;

import client.ui.AppTheme;
import client.ui.LauncherFrame;

import javax.swing.SwingUtilities;

/**
 * Application entry point.
 *
 * Responsibilities:
 *   - Apply the global UI theme before any Swing components are created.
 *   - Launch the initial launcher window on the EDT.
 */
public class ClientMain {
    public static void main(String[] args) {
        // Must be applied before any Swing UI is constructed.
        AppTheme.setup();

        // Create the launcher window on the Swing event dispatch thread.
        SwingUtilities.invokeLater(LauncherFrame::new);
    }
}