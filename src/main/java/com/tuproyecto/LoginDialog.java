package com.tuproyecto;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;

public class LoginDialog extends JDialog {
    private boolean licenseValid = false;

    // Paleta 2026
    private static final Color BG_DARK      = new Color(15, 18, 32);
    private static final Color BG_PANEL     = new Color(24, 28, 48);
    private static final Color BG_FIELD     = new Color(34, 39, 64);
    private static final Color ACCENT       = new Color(0, 224, 255);   // cian neón
    private static final Color ACCENT_2     = new Color(124, 92, 255);  // violeta
    private static final Color TEXT_MAIN    = new Color(235, 238, 245);
    private static final Color TEXT_MUTED   = new Color(150, 158, 180);

    public LoginDialog(JFrame parent) {
        super(parent, "Validación de Licencia", true);
        setResizable(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // Verificar si ya tiene licencia guardada
        String savedLicense = HardwareLicenseManager.loadLicense();
        if (savedLicense != null && HardwareLicenseManager.verifyLicense(savedLicense)) {
            licenseValid = true;
            return; // No se construye ni muestra la ventana, BingoGUI continúa de inmediato
        }
        if (savedLicense != null && HardwareLicenseManager.isLicenseSignatureValid(savedLicense)
                && HardwareLicenseManager.isExpired(savedLicense)) {
            // La licencia guardada expiró: se marca como usada para que no pueda reutilizarse
            HardwareLicenseManager.markLicenseUsed(savedLicense);
        }

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG_DARK);
        root.setBorder(BorderFactory.createEmptyBorder(28, 32, 24, 32));
        setContentPane(root);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 0);

        // ===== Encabezado =====
        JLabel logo = new JLabel("BINGO", SwingConstants.CENTER);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 32));
        logo.setForeground(ACCENT);
        root.add(logo, gbc);

        gbc.gridy++;
        gbc.insets = new Insets(4, 0, 20, 0);
        JLabel subtitle = new JLabel("ACTIVACIÓN DE LICENCIA", SwingConstants.CENTER);
        subtitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        subtitle.setForeground(TEXT_MUTED);
        root.add(subtitle, gbc);

        // ===== Código de hardware =====
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 6, 0);
        root.add(sectionLabel("Código de este equipo (enviar al proveedor)"), gbc);

        String hardwareCode = HardwareLicenseManager.getHardwareCode();
        JTextArea hardwareArea = new JTextArea(hardwareCode);
        hardwareArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        hardwareArea.setEditable(false);
        hardwareArea.setLineWrap(true);
        hardwareArea.setWrapStyleWord(true);
        hardwareArea.setBackground(BG_FIELD);
        hardwareArea.setForeground(TEXT_MAIN);
        hardwareArea.setCaretColor(ACCENT);
        hardwareArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        hardwareArea.setRows(3);
        JScrollPane hardwareScroll = new JScrollPane(hardwareArea);
        hardwareScroll.setBorder(new RoundedLineBorder(BG_FIELD, 12));
        hardwareScroll.setPreferredSize(new Dimension(420, 70));

        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 10, 0);
        root.add(hardwareScroll, gbc);

        JButton copyButton = createSecondaryButton("Copiar código");
        copyButton.addActionListener(e -> {
            hardwareArea.selectAll();
            hardwareArea.copy();
            hardwareArea.select(0, 0);
            JOptionPane.showMessageDialog(this, "Código copiado al portapapeles.");
        });

        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 18, 0);
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        root.add(copyButton, gbc);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.CENTER;

        // ===== Separador =====
        JSeparator separator = new JSeparator();
        separator.setForeground(new Color(50, 56, 80));
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 18, 0);
        root.add(separator, gbc);

        // ===== Campo de licencia =====
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 6, 0);
        root.add(sectionLabel("Ingrese la licencia que le enviaron"), gbc);

        JTextArea licenseArea = new JTextArea(3, 40);
        licenseArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        licenseArea.setLineWrap(true);
        licenseArea.setWrapStyleWord(true);
        licenseArea.setBackground(BG_FIELD);
        licenseArea.setForeground(TEXT_MAIN);
        licenseArea.setCaretColor(ACCENT);
        licenseArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane licenseScroll = new JScrollPane(licenseArea);
        licenseScroll.setBorder(new RoundedLineBorder(ACCENT_2, 12));
        licenseScroll.setPreferredSize(new Dimension(420, 70));

        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 20, 0);
        root.add(licenseScroll, gbc);

        // ===== Botón activar =====
        JButton activateButton = createPrimaryButton("ACTIVAR LICENCIA");
        activateButton.addActionListener(e -> {
            String license = licenseArea.getText().trim();

            if (HardwareLicenseManager.isLicenseSignatureValid(license) && HardwareLicenseManager.isLicenseUsed(license)) {
                JOptionPane.showMessageDialog(this,
                    "ESTA LICENCIA YA FUE UTILIZADA\n\n" +
                    "Esta licencia ya se activó previamente y no puede reutilizarse.\n" +
                    "Solicite una nueva licencia al proveedor.\n\n" +
                    "Contacte al proveedor: 0968749443",
                    "Licencia ya usada", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (HardwareLicenseManager.verifyLicense(license)) {
                HardwareLicenseManager.saveLicense(license);
                HardwareLicenseManager.markLicenseUsed(license);
                licenseValid = true;
                JOptionPane.showMessageDialog(this, "¡Licencia activada correctamente!");
                dispose();
            } else if (HardwareLicenseManager.isLicenseSignatureValid(license) && HardwareLicenseManager.isExpired(license)) {
                HardwareLicenseManager.markLicenseUsed(license);
                JOptionPane.showMessageDialog(this,
                    "LICENCIA EXPIRADA\n\n" +
                    "Esta licencia ya expiró y no puede usarse.\n" +
                    "Solicite una nueva licencia al proveedor.\n\n" +
                    "Contacte al proveedor: 0968749443",
                    "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                    "LICENCIA INVÁLIDA\n\n" +
                    "Posibles causas:\n" +
                    "• La licencia no corresponde a este equipo\n" +
                    "• La licencia ha expirado\n" +
                    "• La licencia está mal escrita\n\n" +
                    "Contacte al proveedor: 0968749443",
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 14, 0);
        root.add(activateButton, gbc);

        // ===== Contacto =====
        JLabel contactLabel = new JLabel("¿Problemas? Contacte: 0968749443", SwingConstants.CENTER);
        contactLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        contactLabel.setForeground(TEXT_MUTED);

        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 0, 0);
        root.add(contactLabel, gbc);

        pack();
        setMinimumSize(new Dimension(520, getPreferredSize().height));
        setLocationRelativeTo(null);
        setVisible(true);
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 12));
        l.setForeground(TEXT_MAIN);
        return l;
    }

    private JButton createPrimaryButton(String text) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, ACCENT_2, getWidth(), 0, ACCENT);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(new Font("Segoe UI", Font.BOLD, 14));
        b.setForeground(BG_DARK);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder(12, 24, 12, 24));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return b;
    }

    private JButton createSecondaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setForeground(ACCENT);
        b.setBackground(BG_PANEL);
        b.setFocusPainted(false);
        b.setBorder(new RoundedLineBorder(ACCENT.darker(), 10, new Insets(8, 18, 8, 18)));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        return b;
    }

    public boolean isLoginSuccessful() {
        return licenseValid;
    }

    /** Borde redondeado simple para campos y botones secundarios. */
    private static class RoundedLineBorder extends AbstractBorder {
        private final Color color;
        private final int radius;
        private final Insets insets;

        RoundedLineBorder(Color color, int radius) {
            this(color, radius, new Insets(2, 2, 2, 2));
        }

        RoundedLineBorder(Color color, int radius, Insets insets) {
            this.color = color;
            this.radius = radius;
            this.insets = insets;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return insets;
        }
    }
}
