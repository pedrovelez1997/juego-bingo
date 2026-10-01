package com.tuproyecto;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Utilidades de estilo visual centralizadas para el rediseño de la UI.
 * Mantiene colores, fuentes y bordes en un solo lugar para que tablas,
 * patrones y controles se vean consistentes en toda la app, sin tener
 * que rehacer los paneles existentes.
 */
public final class ModernUIUtils {

    private ModernUIUtils() {}

    // ===================== PALETA =====================
    public static final Color CYAN_ACCENT  = new Color(0x00, 0xEA, 0xD3);
    /** Color al marcar un número: naranja brillante (reemplaza el antiguo amarillo). */
    public static final Color MARKED_COLOR = new Color(0xFF, 0x7A, 0x00);
    public static final Color BLUE_BUTTON  = new Color(0x3B, 0x82, 0xF6);
    public static final Color PATTERN_BG   = new Color(0x0F, 0x17, 0x2A);
    public static final Color PATTERN_NEON = new Color(0x00, 0xEA, 0xD3);

    // Colores de columna B-I-N-G-O, con ~80% de opacidad (alpha ≈ 204)
    private static final Color COL_B = new Color(0x2F, 0x6F, 0xED, 204); // Azul
    private static final Color COL_I = new Color(0xE5, 0x3E, 0x3E, 204); // Rojo
    private static final Color COL_N = new Color(0x22, 0xB1, 0x5B, 204); // Verde
    private static final Color COL_G = new Color(0xE0, 0xB4, 0x0E, 204); // Amarillo
    private static final Color COL_O = new Color(0x9B, 0x3F, 0xE0, 204); // Púrpura

    public static Color headerColorFor(String letter) {
        switch (letter) {
            case "B": return COL_B;
            case "I": return COL_I;
            case "N": return COL_N;
            case "G": return COL_G;
            case "O": return COL_O;
            default:  return COL_B;
        }
    }

    public static final Font HEADER_FONT = new Font("Monospaced", Font.BOLD, 14);
    public static final Font NUMBER_FONT = new Font("Monospaced", Font.BOLD, 13);

    /** Aplica el estilo de cabecera (B/I/N/G/O) a una celda ya creada. */
    public static void styleHeaderCell(JTextField cell, String letter, Color darkBorder) {
        cell.setBackground(headerColorFor(letter));
        cell.setForeground(Color.WHITE);
        cell.setFont(HEADER_FONT);
        cell.setBorder(BorderFactory.createLineBorder(darkBorder, 2));
    }

    /** Aplica fuente/borde a una celda numérica (NO toca el color de fondo,
     *  para no interferir con el color guardado/marcado de la partida). */
    public static void styleNumberCell(JTextField cell, Color darkBorder) {
        cell.setFont(NUMBER_FONT);
        cell.setBorder(BorderFactory.createLineBorder(darkBorder, 2));
    }

    /** Borde con esquinas redondeadas para contenedores (paneles, live view). */
    public static Border roundedLineBorder(Color color, int thickness, int radius) {
        return new Border() {
            @Override public Insets getBorderInsets(Component c) {
                return new Insets(thickness + 2, thickness + 2, thickness + 2, thickness + 2);
            }
            @Override public boolean isBorderOpaque() { return false; }
            @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(thickness));
                g2.drawRoundRect(x + thickness / 2, y + thickness / 2,
                                  w - thickness - 1, h - thickness - 1, radius, radius);
                g2.dispose();
            }
        };
    }

    /** Borde con efecto de resplandor ("glow") para celdas activas del panel de patrones. */
    public static Border glowBorder(Color neon) {
        return new Border() {
            @Override public Insets getBorderInsets(Component c) { return new Insets(3, 3, 3, 3); }
            @Override public boolean isBorderOpaque() { return false; }
            @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int i = 3; i >= 1; i--) {
                    int alpha = Math.max(10, 55 - i * 15);
                    g2.setColor(new Color(neon.getRed(), neon.getGreen(), neon.getBlue(), alpha));
                    g2.setStroke(new BasicStroke(i * 2f));
                    g2.drawRect(x + i, y + i, w - 2 * i - 1, h - 2 * i - 1);
                }
                g2.setColor(neon);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRect(x, y, w - 1, h - 1);
                g2.dispose();
            }
        };
    }
}
