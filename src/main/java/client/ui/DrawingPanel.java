package client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import shared.DrawData;

/**
 * Canvas component responsible for local drawing, shape previews,
 * and broadcasting draw operations to the server.
 *
 * Supports five tools:
 *  - FREEHAND: continuous stroke, commits each drag segment
 *  - ERASER: same as freehand but draws in canvas background color
 *  - LINE, RECT, OVAL: shape tools with live preview; commit on mouse release
 *
 * Remote strokes are added via addRemoteStroke() and rendered identically.
 */
public class DrawingPanel extends JPanel {

    public enum Tool { FREEHAND, LINE, RECT, OVAL, ERASER }

    private static final Color CANVAS_BG = Color.WHITE;

    /** All finalized drawing operations (local + remote). */
    private final List<DrawData> committed;

    /** Current drawing configuration. */
    private Tool currentTool;
    private Color currentColor;
    private int currentStrokeWidth;
    private boolean drawingEnabled;

    /** Mouse state for drawing and shape previews. */
    private int pressX, pressY;
    private int dragX, dragY;
    private boolean dragging;

    /** Callback used to send DrawData to the server. */
    private Consumer<DrawData> drawListener;

    public DrawingPanel() {
        committed = new ArrayList<>();
        currentTool = Tool.FREEHAND;
        currentColor = Color.BLACK;
        currentStrokeWidth = 4;
        drawingEnabled = true;

        setBackground(CANVAS_BG);

        MouseAdapter mouseHandler = new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                if (!drawingEnabled) return;

                pressX = e.getX();
                pressY = e.getY();
                dragX = pressX;
                dragY = pressY;
                dragging = true;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!drawingEnabled || !dragging) return;

                int currentX = e.getX();
                int currentY = e.getY();

                if (currentTool == Tool.FREEHAND || currentTool == Tool.ERASER) {
                    // Freehand tools commit each segment immediately for real-time sync.
                    Color strokeColor = (currentTool == Tool.ERASER) ? CANVAS_BG : currentColor;

                    DrawData seg = new DrawData(
                            pressX, pressY, currentX, currentY,
                            strokeColor, currentStrokeWidth, "freehand"
                    );

                    committed.add(seg);
                    if (drawListener != null) drawListener.accept(seg);

                    // Continue stroke from the new point.
                    pressX = currentX;
                    pressY = currentY;

                } else {
                    // Shape tools only update preview coordinates.
                    dragX = currentX;
                    dragY = currentY;
                }

                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!drawingEnabled || !dragging) return;
                dragging = false;

                int endX = e.getX();
                int endY = e.getY();

                // Shape tools finalize the shape on release.
                if (currentTool == Tool.LINE || currentTool == Tool.RECT || currentTool == Tool.OVAL) {
                    String type = toolToShapeType(currentTool);
                    DrawData shape = new DrawData(
                            pressX, pressY, endX, endY,
                            currentColor, currentStrokeWidth, type
                    );

                    committed.add(shape);
                    if (drawListener != null) drawListener.accept(shape);
                }

                repaint();
            }
        };

        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }

    /** Removes all strokes and previews from the canvas. */
    public void clearCanvas() {
        committed.clear();
        dragging = false;
        repaint();
    }

    /** Adds a stroke or shape received from another client. */
    public void addRemoteStroke(DrawData data) {
        if (data == null) return;
        committed.add(data);
        repaint();
    }

    public void setTool(Tool tool) {
        this.currentTool = tool;
    }

    public Tool getTool() {
        return currentTool;
    }

    public void setCurrentColor(Color color) {
        this.currentColor = color;
    }

    public void setCurrentStrokeWidth(int width) {
        this.currentStrokeWidth = width;
    }

    /**
     * Enables or disables drawing. Used when the user is not the host.
     * Disabling drawing also cancels any active drag.
     */
    public void setDrawingEnabled(boolean enabled) {
        this.drawingEnabled = enabled;
        if (!enabled) {
            dragging = false;
            repaint();
        }
    }

    /** Sets the callback used to broadcast drawing operations. */
    public void setDrawListener(Consumer<DrawData> listener) {
        this.drawListener = listener;
    }

    /** Convenience toggle between eraser and freehand. */
    public void setEraserMode(boolean erasing) {
        currentTool = erasing ? Tool.ERASER : Tool.FREEHAND;
    }

    public boolean isEraserMode() {
        return currentTool == Tool.ERASER;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        // Draw all finalized operations.
        for (DrawData d : committed) {
            paintDrawData(g2, d);
        }

        // Draw live preview for shape tools.
        if (dragging && drawingEnabled &&
                (currentTool == Tool.LINE || currentTool == Tool.RECT || currentTool == Tool.OVAL)) {

            DrawData preview = new DrawData(
                    pressX, pressY, dragX, dragY,
                    currentColor, currentStrokeWidth,
                    toolToShapeType(currentTool)
            );

            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
            paintDrawData(g2, preview);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
        }
    }

    /**
     * Renders a single DrawData object (freehand segment or shape).
     * Unknown types fall back to a simple line to avoid breaking rendering.
     */
    private void paintDrawData(Graphics2D g2, DrawData d) {
        g2.setColor(d.getColor());
        g2.setStroke(new BasicStroke(
                d.getStrokeWidth(),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
        ));

        String type = d.getShapeType();
        if (type == null) type = "freehand";

        switch (type) {
            case "freehand", "line" -> {
                g2.drawLine(d.getX1(), d.getY1(), d.getX2(), d.getY2());
            }
            case "rect" -> {
                int x = Math.min(d.getX1(), d.getX2());
                int y = Math.min(d.getY1(), d.getY2());
                int w = Math.abs(d.getX2() - d.getX1());
                int h = Math.abs(d.getY2() - d.getY1());
                g2.drawRect(x, y, w, h);
            }
            case "oval" -> {
                int x = Math.min(d.getX1(), d.getX2());
                int y = Math.min(d.getY1(), d.getY2());
                int w = Math.abs(d.getX2() - d.getX1());
                int h = Math.abs(d.getY2() - d.getY1());
                g2.drawOval(x, y, w, h);
            }
            default -> {
                g2.drawLine(d.getX1(), d.getY1(), d.getX2(), d.getY2());
            }
        }
    }

    /** Maps a tool enum to the DrawData shape type string. */
    private static String toolToShapeType(Tool tool) {
        return switch (tool) {
            case LINE -> "line";
            case RECT -> "rect";
            case OVAL -> "oval";
            default -> "freehand";
        };
    }
}