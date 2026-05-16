package client.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;

/**
 * Centralized theme initializer for the SketchSync client UI.
 * <p>
 * This class configures the global Swing look-and-feel using FlatLaf.
 * It must be invoked before any Swing components are created to ensure
 * consistent styling across the entire application.
 */
public final class AppTheme {

    /** Utility class — not meant to be instantiated. */
    private AppTheme() {}

    /**
     * Applies the application's visual theme and custom UI tweaks.
     * <p>
     * FlatDarkLaf provides a modern dark theme, and additional UIManager
     * properties adjust component rounding and layout for a cleaner look.
     */
    public static void setup() {
        FlatDarkLaf.setup();

        // Rounded corners for general components and specific UI elements.
        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 10);

        // Slightly thicker scrollbar for better usability.
        UIManager.put("ScrollBar.width", 12);

        // Makes the title bar blend with the window background on supported systems.
        UIManager.put("TitlePane.unifiedBackground", true);
    }
}