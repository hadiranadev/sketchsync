package shared;

import java.awt.*;
import java.io.Serializable;

/**
 * Represents a single drawing action sent from a client to the server.
 *
 * shapeType values:
 *   freehand — a single drag segment
 *   line     — straight line from (x1,y1) to (x2,y2)
 *   rect     — rectangle defined by (x1,y1) → (x2,y2)
 *   oval     — oval inside the bounding box (x1,y1) → (x2,y2)
 *
 * Serializable so it can be transmitted over ObjectOutputStream.
 */
public class DrawData implements Serializable {

    /** Serialization bumped when fields changed. */
    private static final long serialVersionUID = 2L;

    // --- Geometry ---
    /** Start point of the stroke or shape. */
    private final int x1;
    private final int y1;

    /** End point of the stroke or shape. */
    private final int x2;
    private final int y2;

    // --- Style ---
    /** Colour used for this draw action. */
    private final Color color;

    /** Stroke width in pixels. */
    private final int strokeWidth;

    /** Shape type: "freehand", "line", "rect", or "oval". */
    private final String shapeType;

    /**
     * Full constructor for all drawing attributes.
     * shapeType defaults to "freehand" if null.
     */
    public DrawData(int x1, int y1, int x2, int y2,
                    Color color, int strokeWidth, String shapeType) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.color = color;
        this.strokeWidth = strokeWidth;
        this.shapeType = (shapeType != null) ? shapeType : "freehand";
    }

    /**
     * Convenience constructor for freehand strokes.
     */
    public DrawData(int x1, int y1, int x2, int y2,
                    Color color, int strokeWidth) {
        this(x1, y1, x2, y2, color, strokeWidth, "freehand");
    }

    // --- Getters ---

    public int getX1() { return x1; }
    public int getY1() { return y1; }
    public int getX2() { return x2; }
    public int getY2() { return y2; }
    public Color getColor() { return color; }
    public int getStrokeWidth() { return strokeWidth; }
    public String getShapeType() { return shapeType; }
}