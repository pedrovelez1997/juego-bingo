package com.tuproyecto;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.io.*;
import java.awt.event.*;

// JavaFX para el visor de Facebook Live
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.scene.web.WebEngine;
import javafx.scene.layout.StackPane;

public class BingoGUI extends JFrame {
    private static final int SIZE = 5;
    // Sin "private": PDFTableLoader y GeminiPDFImporter también la usan, para
    // validar los números importados con el MISMO rango que usa el resto del
    // programa (creación manual, edición de tablas y estadísticas).
    static final int[][] COLUMN_RANGES = {
        {1, 15}, {16, 30}, {31, 45}, {46, 60}, {61, 75}
    };

    // =====================================================================
    //  FORMATO DE GUARDADO (independiente de Swing / la versión de Java)
    // =====================================================================
    // IMPORTANTE: nunca serializar componentes Swing/AWT (JTextField, JButton,
    // etc.) directamente. Su forma serializada no está garantizada entre
    // versiones de Java ni de Look&Feel, por eso una partida guardada en una
    // PC podía fallar ("no es compatible") al cargarla en otra PC con un JDK
    // distinto. En su lugar guardamos solo datos simples (texto, colores en
    // RGB, booleanos) y reconstruimos la interfaz al cargar.
    private static class TableSaveData implements Serializable {
        private static final long serialVersionUID = 1L;
        String code;
        boolean special;
        String[][] texts = new String[SIZE][SIZE];
        int[][] bgColors = new int[SIZE][SIZE];
        List<boolean[][]> completedPatterns = new ArrayList<>();
    }

    private static class GameSaveData implements Serializable {
        private static final long serialVersionUID = 1L;
        List<TableSaveData> tableDataList = new ArrayList<>();
        Map<String, boolean[][]> patternCodes = new LinkedHashMap<>();
        Set<Integer> calledNumbers = new HashSet<>();
        List<Integer> markedNumbersHistory = new ArrayList<>();
    }

    // ===== Paleta 2026 =====
    private static final Color BG_DARK    = new Color(15, 18, 32);
    private static final Color BG_PANEL   = new Color(24, 28, 48);
    private static final Color BG_FIELD   = new Color(34, 39, 64);
    private static final Color ACCENT     = new Color(0, 224, 255);
    private static final Color ACCENT_2   = new Color(124, 92, 255);
    private static final Color SUCCESS    = new Color(0, 230, 140);
    private static final Color DANGER     = new Color(255, 90, 110);
    private static final Color TEXT_MAIN  = new Color(235, 238, 245);
    private static final Color TEXT_MUTED = new Color(150, 158, 180);

    private JPanel savedTablesPanel;
    private JPanel savedPatternsPanel;
    private JTextField numberInputField;
    private Set<JTextField> allCells;
    private List<boolean[][]> winPatterns;
    private List<JButton[][]> patternButtons;
    private Set<JTextField[][]> tables;
    private Set<Integer> calledNumbers;
    private Map<JTextField[][], List<boolean[][]>> completedPatternsByTable = new HashMap<>();
    private int patternCounter;
    private Map<JTextField[][], String> tableCodes = new HashMap<>();
    // Marca qué tablas son "Especiales" (figuras): permiten casillas en blanco
    // y no siguen los patrones dibujados, solo juegan los números colocados.
    private Map<JTextField[][], Boolean> specialTables = new HashMap<>();
    // Referencia directa al panel visual de cada tabla, para poder eliminarla/ubicarla
    // sin depender de comparar el texto de la etiqueta (que puede variar, p.ej. "(Especial)").
    private Map<JTextField[][], JPanel> tableContainers = new HashMap<>();
    private JButton finishEditButton;
    private ImageIcon backgroundImage;
    private int currentEditingPatternIndex = -1;

    private Map<String, boolean[][]> patternCodes = new HashMap<>();
    private Map<String, JButton[][]> patternButtonCodes = new HashMap<>();
    private Stack<Integer> markedNumbersHistory = new Stack<>();
    private JTextArea textPanel;
    private JButton editTextButton;
    private JButton finishTextEditButton;

    // Visor Facebook Live
    private JFXPanel fxPanel;
    private WebEngine webEngine;
    private JTextField urlField;

    public BingoGUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmAndExit();
            }
        });

        LoginDialog loginDialog = new LoginDialog(this);
        if (!loginDialog.isLoginSuccessful()) {
            JOptionPane.showMessageDialog(this, "Login fallido. El programa se cerrará.", "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        }

        patternCounter = 1;
        setTitle("Bingo Moderno 2026");
        setLayout(new BorderLayout());
        setSize(1200, 700);
        getContentPane().setBackground(BG_DARK);

        backgroundImage = loadBackgroundImage();
        applyModernColors();
        setUIFont(new javax.swing.plaf.FontUIResource(new Font("Segoe UI", Font.PLAIN, 14)));

        allCells = new HashSet<>();
        tables = new HashSet<>();
        winPatterns = new ArrayList<>();
        patternButtons = new ArrayList<>();
        calledNumbers = new HashSet<>();
        completedPatternsByTable = new HashMap<>();
        specialTables = new HashMap<>();
        tableContainers = new HashMap<>();

        JPanel mainPanel = new JPanel(new GridLayout(1, 3, 12, 12));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(BG_DARK);

        // Panel izquierdo: Tablas
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(BG_PANEL);
        leftPanel.setBorder(titledPanelBorder("TABLAS"));
        savedTablesPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        savedTablesPanel.setBackground(BG_PANEL);
        JScrollPane tablesScrollPane = new JScrollPane(savedTablesPanel);
        tablesScrollPane.setBorder(BorderFactory.createEmptyBorder());
        tablesScrollPane.getViewport().setBackground(BG_PANEL);
        leftPanel.add(tablesScrollPane, BorderLayout.CENTER);

        JPanel tableButtonsPanel = new JPanel(new GridLayout(4, 1, 8, 8));
        tableButtonsPanel.setBackground(BG_PANEL);
        tableButtonsPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JButton createNewTableButton = createStyledButton("Crear Nueva Tabla");
        createNewTableButton.addActionListener(e -> createNewTable());
        JButton editTableButton = createStyledButton("Editar Tabla");
        editTableButton.addActionListener(e -> editTable());
        JButton deleteTableButton = createStyledButton("Eliminar Tabla");
        deleteTableButton.addActionListener(e -> deleteTable());
        JButton importPDFButton = createStyledButton("📄 Importar PDF");
        importPDFButton.setBackground(new Color(0, 160, 100));
        importPDFButton.addActionListener(e -> importTablesFromPDF());
        JButton importAIButton = createStyledButton("🧪 Importar con IA (prueba)");
        importAIButton.putClientProperty("accentColor", new Color(155, 63, 224));
        importAIButton.setToolTipText("Usa la IA de Google (Gemini) para leer tablas escaneadas con marca de agua. Función experimental.");
        importAIButton.addActionListener(e -> importTablesFromPDFWithGemini());
        tableButtonsPanel.add(createNewTableButton);
        tableButtonsPanel.add(editTableButton);
        tableButtonsPanel.add(deleteTableButton);
        tableButtonsPanel.add(importPDFButton);
        tableButtonsPanel.add(importAIButton);
        leftPanel.add(tableButtonsPanel, BorderLayout.SOUTH);

        // Panel central con visor
        JPanel centerPanel = setupCenterPanel();

        // Panel derecho: Patrones
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(BG_PANEL);
        rightPanel.setBorder(titledPanelBorder("PATRONES"));
        savedPatternsPanel = new JPanel(new GridLayout(0, 2, 10, 10));
        savedPatternsPanel.setBackground(BG_PANEL);
        JScrollPane patternsScrollPane = new JScrollPane(savedPatternsPanel);
        patternsScrollPane.setBorder(BorderFactory.createEmptyBorder());
        patternsScrollPane.getViewport().setBackground(BG_PANEL);
        rightPanel.add(patternsScrollPane, BorderLayout.CENTER);

        JPanel patternButtonsPanel = new JPanel(new GridLayout(4, 1, 8, 8));
        patternButtonsPanel.setBackground(BG_PANEL);
        patternButtonsPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JButton addPatternButton = createStyledButton("Agregar Patrón");
        addPatternButton.addActionListener(e -> openPatternWindow());
        JButton editPatternButton = createStyledButton("Editar Patrón");
        editPatternButton.addActionListener(e -> editPattern());
        finishEditButton = createStyledButton("Finalizar Edición");
        finishEditButton.setVisible(false);
        finishEditButton.addActionListener(e -> finishEditing());
        JButton deletePatternButton = createStyledButton("Eliminar Patrón");
        deletePatternButton.addActionListener(e -> deletePattern());
        patternButtonsPanel.add(addPatternButton);
        patternButtonsPanel.add(editPatternButton);
        patternButtonsPanel.add(finishEditButton);
        patternButtonsPanel.add(deletePatternButton);
        rightPanel.add(patternButtonsPanel, BorderLayout.SOUTH);

        mainPanel.add(leftPanel);
        mainPanel.add(centerPanel);
        mainPanel.add(rightPanel);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        bottomPanel.setBackground(BG_DARK);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        JButton resetButton = createStyledButton("Reiniciar Juego");
        resetButton.addActionListener(e -> resetGame());
        JButton statsButton = createStyledButton("Mostrar Estadísticas");
        statsButton.addActionListener(e -> showStats());
        JButton saveButton = createStyledButton("Guardar Juego");
        saveButton.addActionListener(e -> saveGame());
        JButton loadButton = createStyledButton("Cargar Juego");
        loadButton.addActionListener(e -> loadGame());
        bottomPanel.add(resetButton);
        bottomPanel.add(statsButton);
        bottomPanel.add(saveButton);
        bottomPanel.add(loadButton);

        add(mainPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        setVisible(true);
        startLicenseWatcher();
    }

    private void startLicenseWatcher() {
        javax.swing.Timer licenseTimer = new javax.swing.Timer(15000, null);
        licenseTimer.addActionListener(e -> {
            String currentLicense = HardwareLicenseManager.loadLicense();
            if (currentLicense == null || !HardwareLicenseManager.verifyLicense(currentLicense)) {
                ((javax.swing.Timer) e.getSource()).stop();
                showLicenseRenewalDialog((javax.swing.Timer) e.getSource());
            }
        });
        licenseTimer.start();
    }

    /**
     * Muestra el diálogo de renovación de licencia SIN cerrar ni destruir la
     * ventana principal. Las tablas, patrones y el juego se conservan intactos.
     * Solo bloquea la interacción hasta que la licencia sea válida.
     */
    private void showLicenseRenewalDialog(javax.swing.Timer watcherTimer) {
        final Color BG_TOP    = new Color(0x1E, 0x29, 0x3B);
        final Color BG_BOTTOM = new Color(0x0F, 0x17, 0x2A);
        final Color AMBER     = new Color(0xF5, 0x9E, 0x0B);
        final Color BLUE      = new Color(0x25, 0x63, 0xEB);
        final Color FIELD_BG  = new Color(0x02, 0x06, 0x17);
        final Color FIELD_FG  = new Color(0x94, 0xA3, 0xB8);
        final String LICENSE_PLACEHOLDER = "Pega aquí tu nueva clave de activación...";

        JDialog licDialog = new JDialog(this, "Licencia expirada", true);
        licDialog.setUndecorated(true);
        licDialog.setSize(480, 560);
        licDialog.setLocationRelativeTo(this);
        licDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        licDialog.setBackground(new Color(0, 0, 0, 0));

        // ===== Panel raíz: degradado + esquinas redondeadas + "sombra" (borde suave oscuro) =====
        JPanel root = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, BG_TOP, 0, getHeight(), BG_BOTTOM);
                g2.setPaint(gp);
                g2.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 22, 22);
                g2.setColor(new Color(0, 0, 0, 120));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 22, 22);
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(18, 26, 20, 26));

        // ===== Botón "X" para cerrar manualmente (misma acción que "Cerrar programa") =====
        JButton closeX = new JButton("✕");
        closeX.setFont(new Font("Segoe UI", Font.BOLD, 13));
        closeX.setForeground(TEXT_MUTED);
        closeX.setContentAreaFilled(false);
        closeX.setBorderPainted(false);
        closeX.setFocusPainted(false);
        closeX.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.add(closeX, BorderLayout.EAST);

        // ===== Cabecera =====
        JLabel icon = new JLabel("🔒", JLabel.CENTER);
        icon.setFont(new Font("Segoe UI", Font.PLAIN, 36));
        icon.setForeground(AMBER);

        JLabel title = new JLabel("Suscripción Expirada", JLabel.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(Color.WHITE);

        JLabel subtitle = new JLabel(
            "<html><div style='text-align:center;'>Por favor, renueva tu licencia para continuar usando Bingo Moderno.<br>"
            + "<span style='font-size:11px;color:#94A3B8;'>Sus tablas y el progreso del juego se conservarán.</span></div></html>",
            JLabel.CENTER);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(new Color(0xCB, 0xD5, 0xE1));

        JPanel headerPanel = new JPanel();
        headerPanel.setOpaque(false);
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        headerPanel.add(icon);
        headerPanel.add(Box.createVerticalStrut(8));
        headerPanel.add(title);
        headerPanel.add(Box.createVerticalStrut(6));
        headerPanel.add(subtitle);

        // ===== Código de hardware (ID de equipo) =====
        JLabel hwLabel = new JLabel("Código de este equipo (enviar al proveedor):");
        hwLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        hwLabel.setForeground(TEXT_MUTED);
        hwLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        String hardwareCode = HardwareLicenseManager.getHardwareCode();
        JTextField hardwareField = new JTextField(hardwareCode);
        hardwareField.setEditable(false);
        hardwareField.setFont(new Font("Consolas", Font.PLAIN, 13));
        hardwareField.setBackground(FIELD_BG);
        hardwareField.setForeground(FIELD_FG);
        hardwareField.setCaretColor(FIELD_FG);
        hardwareField.setBorder(BorderFactory.createCompoundBorder(
            ModernUIUtils.roundedLineBorder(new Color(60, 68, 100), 1, 10),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        JButton copyHwButton = new JButton("📋");
        copyHwButton.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        copyHwButton.setForeground(TEXT_MAIN);
        copyHwButton.setBackground(new Color(60, 68, 100));
        copyHwButton.setFocusPainted(false);
        copyHwButton.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        copyHwButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        copyHwButton.setToolTipText("Copiar código");

        JPanel hwRow = new JPanel(new BorderLayout(8, 0));
        hwRow.setOpaque(false);
        hwRow.add(hardwareField, BorderLayout.CENTER);
        hwRow.add(copyHwButton, BorderLayout.EAST);
        hwRow.setMaximumSize(new Dimension(400, 40));
        hwRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        // ===== Toast flotante ("¡Copiado al portapapeles!") =====
        JLabel toast = new JLabel("¡Copiado al portapapeles!", JLabel.CENTER);
        toast.setOpaque(true);
        toast.setBackground(new Color(16, 185, 129));
        toast.setForeground(Color.WHITE);
        toast.setFont(new Font("Segoe UI", Font.BOLD, 12));
        toast.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        toast.setVisible(false);
        JPanel glass = new JPanel(null);
        glass.setOpaque(false);
        glass.add(toast);
        licDialog.setGlassPane(glass);
        glass.setVisible(true);

        copyHwButton.addActionListener(ev -> {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(hardwareCode);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);

            String original = copyHwButton.getText();
            copyHwButton.setText("✅");
            javax.swing.Timer revert = new javax.swing.Timer(1200, e -> copyHwButton.setText(original));
            revert.setRepeats(false);
            revert.start();

            toast.setSize(toast.getPreferredSize());
            toast.setLocation((licDialog.getWidth() - toast.getWidth()) / 2, licDialog.getHeight() - 70);
            toast.setVisible(true);
            javax.swing.Timer hideToast = new javax.swing.Timer(2000, e -> toast.setVisible(false));
            hideToast.setRepeats(false);
            hideToast.start();
        });

        // ===== Campo de clave de licencia (con placeholder + borde azul al enfocar) =====
        JTextField licField = new JTextField();
        licField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        licField.setForeground(TEXT_MUTED);
        licField.setBackground(BG_FIELD);
        licField.setText(LICENSE_PLACEHOLDER);
        licField.setHorizontalAlignment(JTextField.CENTER);
        licField.setPreferredSize(new Dimension(380, 46));
        licField.setMaximumSize(new Dimension(380, 46));
        licField.setAlignmentX(Component.CENTER_ALIGNMENT);
        javax.swing.border.Border normalBorder = BorderFactory.createCompoundBorder(
            ModernUIUtils.roundedLineBorder(new Color(60, 68, 100), 1, 10),
            BorderFactory.createEmptyBorder(4, 10, 4, 10));
        javax.swing.border.Border focusBorder = BorderFactory.createCompoundBorder(
            ModernUIUtils.roundedLineBorder(BLUE, 2, 10),
            BorderFactory.createEmptyBorder(4, 10, 4, 10));
        licField.setBorder(normalBorder);
        licField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) {
                licField.setBorder(focusBorder);
                if (licField.getText().equals(LICENSE_PLACEHOLDER)) {
                    licField.setText("");
                    licField.setForeground(TEXT_MAIN);
                }
            }
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                licField.setBorder(normalBorder);
                if (licField.getText().trim().isEmpty()) {
                    licField.setForeground(TEXT_MUTED);
                    licField.setText(LICENSE_PLACEHOLDER);
                }
            }
        });

        // ===== Botones de acción =====
        JButton renewBtn = new JButton("Renovar licencia") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? BLUE.brighter() : BLUE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        renewBtn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        renewBtn.setForeground(Color.WHITE);
        renewBtn.setContentAreaFilled(false);
        renewBtn.setFocusPainted(false);
        renewBtn.setBorder(BorderFactory.createEmptyBorder(11, 26, 11, 26));
        renewBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JButton exitBtn = new JButton("Cerrar programa") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? new Color(70, 78, 100) : new Color(0, 0, 0, 0));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(new Color(90, 98, 120));
                g2.setStroke(new BasicStroke(1.3f));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        exitBtn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        exitBtn.setForeground(TEXT_MUTED);
        exitBtn.setContentAreaFilled(false);
        exitBtn.setFocusPainted(false);
        exitBtn.setBorderPainted(false);
        exitBtn.setBorder(BorderFactory.createEmptyBorder(11, 20, 11, 20));
        exitBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        Runnable doExit = () -> { licDialog.dispose(); dispose(); System.exit(0); };

        renewBtn.addActionListener(ev -> {
            String newKey = licField.getText().trim();
            if (!newKey.equals(LICENSE_PLACEHOLDER) && HardwareLicenseManager.verifyLicense(newKey)) {
                HardwareLicenseManager.saveLicense(newKey);
                licDialog.dispose();
                watcherTimer.restart();
                JOptionPane.showMessageDialog(this,
                    "✅ Licencia renovada. ¡El juego continúa!",
                    "Licencia válida", JOptionPane.INFORMATION_MESSAGE);
            } else {
                licField.setBorder(BorderFactory.createCompoundBorder(
                    ModernUIUtils.roundedLineBorder(DANGER, 2, 10),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10)));
                licField.setForeground(TEXT_MAIN);
                licField.setText("");
                JOptionPane.showMessageDialog(licDialog,
                    "La clave ingresada no es válida.", "Error", JOptionPane.ERROR_MESSAGE);
                licField.setBorder(normalBorder);
            }
        });
        exitBtn.addActionListener(ev -> doExit.run());
        closeX.addActionListener(ev -> doExit.run());

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(headerPanel);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(hwLabel);
        centerPanel.add(Box.createVerticalStrut(6));
        centerPanel.add(hwRow);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(licField);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(renewBtn);
        btnPanel.add(exitBtn);

        root.add(topBar,      BorderLayout.NORTH);
        root.add(centerPanel, BorderLayout.CENTER);
        root.add(btnPanel,    BorderLayout.SOUTH);

        licDialog.setContentPane(root);
        licDialog.getRootPane().setBorder(BorderFactory.createEmptyBorder());
        licDialog.setVisible(true);
    }

    private ImageIcon loadBackgroundImage() {
        String[] resourcePaths = {
            "/resources/bingo45.jpg",
            "/resources/bingo9.jpg",
            "/bingo45.jpg"
        };
        for (String resPath : resourcePaths) {
            java.net.URL url = getClass().getResource(resPath);
            if (url != null) return new ImageIcon(url);
        }
        String[] filePaths = {
            "src/main/java/resources/bingo45.jpg",
            "src/main/resources/bingo45.jpg",
            "src/main/resource/bingo45.jpg"
        };
        for (String filePath : filePaths) {
            File f = new File(filePath);
            if (f.exists()) return new ImageIcon(filePath);
        }
        java.awt.image.BufferedImage blank = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        return new ImageIcon(blank);
    }

    private void confirmAndExit() {
        int option = JOptionPane.showConfirmDialog(
            this,
            "¿Estás seguro que quieres salir del programa?",
            "Confirmar salida",
            JOptionPane.YES_NO_OPTION
        );
        if (option == JOptionPane.YES_OPTION) {
            dispose();
            System.exit(0);
        }
    }

    // =====================================================================
    //  PANEL CENTRAL con visor de Facebook Live integrado
    // =====================================================================
    private JPanel setupCenterPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(BG_PANEL);
        centerPanel.setBorder(titledPanelBorder("MARCAR NÚMEROS"));

        // --- NORTE: fila de marcar número ---
        // NOTA: se usan DOS filas fijas (en vez de un solo FlowLayout con 4
        // elementos) porque si el botón "Retroceder Jugada" no cabe en una
        // sola línea, FlowLayout lo manda a una 2da línea que queda
        // recortada/invisible (el panel ya reservó altura para 1 sola fila).
        // Con dos filas separadas, el botón de retroceder SIEMPRE es visible.
        JPanel markNumberPanel = new JPanel();
        markNumberPanel.setLayout(new BoxLayout(markNumberPanel, BoxLayout.Y_AXIS));
        markNumberPanel.setBackground(BG_PANEL);

        JPanel markRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        markRow.setBackground(BG_PANEL);

        JLabel numberLabel = new JLabel("Número:");
        numberLabel.setForeground(TEXT_MAIN);
        numberLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));

        numberInputField = new JTextField(5);
        numberInputField.setBackground(BG_FIELD);
        numberInputField.setForeground(TEXT_MAIN);
        numberInputField.setCaretColor(ModernUIUtils.CYAN_ACCENT);
        numberInputField.setFont(new Font("Consolas", Font.BOLD, 22));
        numberInputField.setHorizontalAlignment(JTextField.CENTER);
        numberInputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 3, 0, ModernUIUtils.CYAN_ACCENT),
            BorderFactory.createEmptyBorder(8, 10, 6, 10)));

        JButton markButton = createStyledButton("Marcar");
        markButton.putClientProperty("accentColor", ModernUIUtils.BLUE_BUTTON);
        markButton.addActionListener(e -> markNumber());

        markRow.add(numberLabel);
        markRow.add(numberInputField);
        markRow.add(markButton);

        JPanel undoRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        undoRow.setBackground(BG_PANEL);

        JButton undoButton = createStyledButton("↺ Retroceder Jugada");
        undoButton.putClientProperty("outlined", Boolean.TRUE);
        undoButton.setForeground(TEXT_MUTED);
        undoButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        undoButton.addActionListener(e -> undoLastMove());
        undoRow.add(undoButton);

        markNumberPanel.add(markRow);
        markNumberPanel.add(undoRow);

        // --- CENTRO: visor de Facebook Live (JavaFX WebView) ---
        JPanel livePanel = new JPanel(new BorderLayout(0, 4));
        livePanel.setBackground(BG_PANEL);
        livePanel.setBorder(BorderFactory.createCompoundBorder(
            ModernUIUtils.roundedLineBorder(ModernUIUtils.CYAN_ACCENT, 2, 14),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)));

        // Barra de URL
        JPanel urlBar = new JPanel(new BorderLayout(6, 0));
        urlBar.setBackground(BG_FIELD);
        urlBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT_2, 1, true),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        JLabel fbIcon = new JLabel("📺 URL del Live:");
        fbIcon.setForeground(ACCENT);
        fbIcon.setFont(new Font("Segoe UI", Font.BOLD, 12));

        urlField = new JTextField("https://www.facebook.com/");
        urlField.setBackground(BG_FIELD);
        urlField.setForeground(TEXT_MAIN);
        urlField.setCaretColor(ACCENT);
        urlField.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        urlField.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        // Cargar al presionar Enter en el campo URL
        urlField.addActionListener(e -> loadUrl());

        JButton loadUrlBtn = createStyledButton("Cargar");
        loadUrlBtn.addActionListener(e -> loadUrl());
        loadUrlBtn.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        urlBar.add(fbIcon, BorderLayout.WEST);
        urlBar.add(urlField, BorderLayout.CENTER);
        urlBar.add(loadUrlBtn, BorderLayout.EAST);

        // Panel JavaFX (WebView)
        fxPanel = new JFXPanel();
        fxPanel.setBackground(BG_DARK);
        fxPanel.setPreferredSize(new Dimension(380, 220));

        // Inicializar WebView en el hilo de JavaFX
        Platform.runLater(() -> {
            WebView webView = new WebView();
            webEngine = webView.getEngine();
            webEngine.load("https://www.facebook.com/");
            Scene scene = new Scene(new StackPane(webView));
            fxPanel.setScene(scene);
        });

        livePanel.add(urlBar, BorderLayout.NORTH);
        livePanel.add(fxPanel, BorderLayout.CENTER);

        // --- SUR: panel de texto editable ---
        JPanel textEditPanel = new JPanel(new BorderLayout());
        textEditPanel.setBackground(BG_PANEL);

        textPanel = new JTextArea(3, 10);
        textPanel.setEditable(false);
        textPanel.setLineWrap(true);
        textPanel.setWrapStyleWord(true);
        textPanel.setBackground(BG_FIELD);
        textPanel.setForeground(TEXT_MAIN);
        textPanel.setCaretColor(ACCENT);
        textPanel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        textPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT_2, 1, true),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        JScrollPane textScrollPane = new JScrollPane(textPanel);
        textScrollPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel textButtonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        textButtonsPanel.setBackground(BG_PANEL);

        editTextButton = createStyledButton("Editar");
        editTextButton.addActionListener(e -> {
            textPanel.setEditable(true);
            textPanel.requestFocusInWindow();
        });

        finishTextEditButton = createStyledButton("Finalizar");
        finishTextEditButton.addActionListener(e -> textPanel.setEditable(false));

        textButtonsPanel.add(editTextButton);
        textButtonsPanel.add(finishTextEditButton);

        textEditPanel.add(textScrollPane, BorderLayout.CENTER);
        textEditPanel.add(textButtonsPanel, BorderLayout.SOUTH);

        // Ensamblar panel central
        centerPanel.add(markNumberPanel, BorderLayout.NORTH);
        centerPanel.add(livePanel, BorderLayout.CENTER);
        centerPanel.add(textEditPanel, BorderLayout.SOUTH);

        return centerPanel;
    }

    /** Carga la URL escrita en el campo en el WebView */
    private void loadUrl() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
            urlField.setText(url);
        }
        final String finalUrl = url;
        Platform.runLater(() -> {
            if (webEngine != null) {
                webEngine.load(finalUrl);
            }
        });
    }

    // =====================================================================
    //  Resto de métodos (sin cambios)
    // =====================================================================

    private void saveGame() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Guardar Juego");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(fileToSave))) {
                GameSaveData data = new GameSaveData();

                for (JTextField[][] table : tables) {
                    TableSaveData td = new TableSaveData();
                    td.code = tableCodes.get(table);
                    td.special = Boolean.TRUE.equals(specialTables.get(table));
                    for (int row = 0; row < SIZE; row++) {
                        for (int col = 0; col < SIZE; col++) {
                            td.texts[row][col] = table[row][col].getText();
                            td.bgColors[row][col] = table[row][col].getBackground().getRGB();
                        }
                    }
                    List<boolean[][]> completed = completedPatternsByTable.get(table);
                    if (completed != null) td.completedPatterns.addAll(completed);
                    data.tableDataList.add(td);
                }

                data.patternCodes.putAll(patternCodes);
                data.calledNumbers.addAll(calledNumbers);
                data.markedNumbersHistory.addAll(markedNumbersHistory);

                oos.writeObject(data);
                JOptionPane.showMessageDialog(this, "Juego guardado correctamente en: " + fileToSave.getAbsolutePath());
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Error al guardar el juego: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void loadGame() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Cargar Juego");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int userSelection = fileChooser.showOpenDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToLoad = fileChooser.getSelectedFile();
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(fileToLoad))) {
                Object raw = ois.readObject();
                if (!(raw instanceof GameSaveData)) {
                    JOptionPane.showMessageDialog(this,
                        "El archivo no tiene un formato de partida reconocido.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                GameSaveData data = (GameSaveData) raw;

                // Limpia todo el estado y la interfaz actuales antes de reconstruir.
                savedTablesPanel.removeAll();
                savedPatternsPanel.removeAll();
                tables = new HashSet<>();
                tableCodes = new HashMap<>();
                specialTables = new HashMap<>();
                tableContainers = new HashMap<>();
                completedPatternsByTable = new HashMap<>();
                allCells = new HashSet<>();
                winPatterns = new ArrayList<>();
                patternCodes = new HashMap<>();
                patternButtonCodes = new HashMap<>();
                patternButtons = new ArrayList<>();
                calledNumbers = new HashSet<>(data.calledNumbers);
                markedNumbersHistory = new Stack<>();
                markedNumbersHistory.addAll(data.markedNumbersHistory);

                for (TableSaveData td : data.tableDataList) {
                    rebuildTableUI(td);
                }
                for (Map.Entry<String, boolean[][]> entry : data.patternCodes.entrySet()) {
                    rebuildPatternUI(entry.getKey(), entry.getValue());
                }

                if (!patternCodes.isEmpty()) {
                    patternCounter = patternCodes.keySet().stream()
                        .mapToInt(Integer::parseInt).max().orElse(0) + 1;
                } else {
                    patternCounter = 1;
                }

                savedTablesPanel.revalidate();
                savedTablesPanel.repaint();
                savedPatternsPanel.revalidate();
                savedPatternsPanel.repaint();

                JOptionPane.showMessageDialog(this, "Juego cargado correctamente desde: " + fileToLoad.getAbsolutePath());
            } catch (IOException | ClassNotFoundException e) {
                JOptionPane.showMessageDialog(this, "Error al cargar el juego: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Reconstruye la interfaz (JTextField[][]) de una tabla guardada y la registra en todos los mapas. */
    private JTextField[][] rebuildTableUI(TableSaveData td) {
        JPanel tableContainer = new JPanel(new BorderLayout());
        tableContainer.setBackground(BG_FIELD);
        tableContainer.setBorder(BorderFactory.createLineBorder(new Color(60, 68, 100), 1, true));
        JPanel gridPanel = new JPanel(new GridLayout(SIZE + 1, SIZE, 0, 0));
        gridPanel.setPreferredSize(new Dimension(155, 155));
        JTextField[][] cells = new JTextField[SIZE][SIZE];

        String[] bingoLetters = {"B", "I", "N", "G", "O"};
        for (String letter : bingoLetters) {
            JTextField cell = new JTextField(letter);
            cell.setHorizontalAlignment(JTextField.CENTER);
            cell.setEditable(false);
            ModernUIUtils.styleHeaderCell(cell, letter, BG_DARK);
            gridPanel.add(cell);
        }

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                String text = td.texts[row][col] == null ? "" : td.texts[row][col];
                JTextField cell = new JTextField(text);
                cell.setHorizontalAlignment(JTextField.CENTER);
                cell.setEditable(false);
                cell.setBackground(new Color(td.bgColors[row][col]));
                cell.setForeground(Color.BLACK);
                ModernUIUtils.styleNumberCell(cell, BG_DARK);
                gridPanel.add(cell);
                cells[row][col] = cell;
                allCells.add(cell);
            }
        }

        tableContainer.add(gridPanel, BorderLayout.CENTER);
        String codeLabelText = "Código: " + td.code + (td.special ? "  (Especial)" : "");
        JLabel codeLabel = new JLabel(codeLabelText, JLabel.CENTER);
        codeLabel.setForeground(td.special ? ACCENT : TEXT_MAIN);
        codeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        tableContainer.add(codeLabel, BorderLayout.SOUTH);

        savedTablesPanel.add(tableContainer);

        tables.add(cells);
        tableCodes.put(cells, td.code);
        specialTables.put(cells, td.special);
        tableContainers.put(cells, tableContainer);
        completedPatternsByTable.put(cells, new ArrayList<>(td.completedPatterns));

        return cells;
    }

    /** Reconstruye la interfaz (JButton[][]) de un patrón guardado y lo registra en todos los mapas. */
    private JButton[][] rebuildPatternUI(String patternCode, boolean[][] pattern) {
        JPanel patternContainer = new JPanel(new BorderLayout());
        patternContainer.setBackground(ModernUIUtils.PATTERN_BG);
        patternContainer.setBorder(BorderFactory.createLineBorder(new Color(60, 68, 100), 1, true));
        JPanel patternPanel = new JPanel(new GridLayout(SIZE, SIZE, 3, 3));
        patternPanel.setBackground(ModernUIUtils.PATTERN_BG);
        patternPanel.setPreferredSize(new Dimension(155, 155));
        JButton[][] patternCells = new JButton[SIZE][SIZE];

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                JButton cell = new JButton();
                cell.setPreferredSize(new Dimension(40, 40));
                cell.setOpaque(true);
                cell.setBorder(BorderFactory.createLineBorder(BG_DARK, 2));
                final int r = row, c = col;
                if (row == 2 && col == 2) {
                    cell.setEnabled(false);
                    cell.setBackground(new Color(60, 68, 100));
                } else {
                    boolean active = pattern[row][col];
                    cell.setBackground(active ? ModernUIUtils.PATTERN_NEON : ModernUIUtils.PATTERN_BG);
                    cell.setBorder(active ? ModernUIUtils.glowBorder(ModernUIUtils.PATTERN_NEON)
                                           : BorderFactory.createLineBorder(BG_DARK, 2));
                    cell.addActionListener(e -> {
                        pattern[r][c] = !pattern[r][c];
                        boolean nowActive = pattern[r][c];
                        cell.setBackground(nowActive ? ModernUIUtils.PATTERN_NEON : ModernUIUtils.PATTERN_BG);
                        cell.setBorder(nowActive ? ModernUIUtils.glowBorder(ModernUIUtils.PATTERN_NEON)
                                                  : BorderFactory.createLineBorder(BG_DARK, 2));
                    });
                }
                patternCells[row][col] = cell;
                patternPanel.add(cell);
            }
        }

        patternContainer.add(patternPanel, BorderLayout.CENTER);
        JLabel codeLabel = new JLabel("Código: " + patternCode, JLabel.CENTER);
        codeLabel.setForeground(TEXT_MAIN);
        codeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        patternContainer.add(codeLabel, BorderLayout.SOUTH);

        savedPatternsPanel.add(patternContainer);

        winPatterns.add(pattern);
        patternButtons.add(patternCells);
        patternCodes.put(patternCode, pattern);
        patternButtonCodes.put(patternCode, patternCells);

        disablePatternEditing(patternCells);
        return patternCells;
    }

    private void updateUI() {
        savedTablesPanel.removeAll();
        savedPatternsPanel.removeAll();
        tableContainers.clear();

        for (JTextField[][] table : tables) {
            JPanel tableContainer = new JPanel(new BorderLayout());
            tableContainer.setBackground(BG_FIELD);
            tableContainer.setBorder(BorderFactory.createLineBorder(new Color(60, 68, 100), 1, true));
            JPanel gridPanel = new JPanel(new GridLayout(SIZE + 1, SIZE, 0, 0));
            gridPanel.setPreferredSize(new Dimension(155, 155));

            String[] bingoLetters = {"B", "I", "N", "G", "O"};
            for (String letter : bingoLetters) {
                JTextField cell = new JTextField(letter);
                cell.setHorizontalAlignment(JTextField.CENTER);
                cell.setEditable(false);
                ModernUIUtils.styleHeaderCell(cell, letter, BG_DARK);
                gridPanel.add(cell);
            }

            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    gridPanel.add(table[row][col]);
                }
            }

            tableContainer.add(gridPanel, BorderLayout.CENTER);
            boolean isSpecial = Boolean.TRUE.equals(specialTables.get(table));
            String labelText = "Código: " + tableCodes.get(table) + (isSpecial ? "  (Especial)" : "");
            JLabel tableLabel = new JLabel(labelText, JLabel.CENTER);
            tableLabel.setForeground(isSpecial ? ACCENT : TEXT_MAIN);
            tableLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            tableLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            tableContainer.add(tableLabel, BorderLayout.SOUTH);
            savedTablesPanel.add(tableContainer);
            tableContainers.put(table, tableContainer);
        }

        for (Map.Entry<String, boolean[][]> entry : patternCodes.entrySet()) {
            String patternCode = entry.getKey();
            boolean[][] pattern = entry.getValue();
            JButton[][] patternCells = patternButtonCodes.get(patternCode);

            JPanel patternContainer = new JPanel(new BorderLayout());
            patternContainer.setBackground(ModernUIUtils.PATTERN_BG);
            patternContainer.setBorder(BorderFactory.createLineBorder(new Color(60, 68, 100), 1, true));
            JPanel patternPanel = new JPanel(new GridLayout(SIZE, SIZE, 0, 0));
            patternPanel.setPreferredSize(new Dimension(155, 155));

            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    patternPanel.add(patternCells[row][col]);
                }
            }

            patternContainer.add(patternPanel, BorderLayout.CENTER);
            JLabel codeLabel = new JLabel("Código: " + patternCode, JLabel.CENTER);
            codeLabel.setForeground(TEXT_MAIN);
            codeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            patternContainer.add(codeLabel, BorderLayout.SOUTH);
            savedPatternsPanel.add(patternContainer);
        }

        savedTablesPanel.revalidate();
        savedTablesPanel.repaint();
        savedPatternsPanel.revalidate();
        savedPatternsPanel.repaint();
    }


    private void importTablesFromPDF() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Seleccionar PDF de tablas de Bingo");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Archivos PDF", "pdf"));
        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        java.io.File pdfFile = fileChooser.getSelectedFile();

        // Mostrar progreso
        JDialog progressDialog = new JDialog(this, "Procesando PDF...", false);
        JPanel progressPanel = new JPanel(new BorderLayout(10, 10));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        progressPanel.setBackground(BG_PANEL);
        JLabel progressLabel = new JLabel("Leyendo tablas del PDF...", JLabel.CENTER);
        progressLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        progressLabel.setForeground(TEXT_MAIN);
        JLabel subLabel = new JLabel("Esto puede tardar 10-30 segundos por página.", JLabel.CENTER);
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subLabel.setForeground(TEXT_MUTED);
        progressPanel.add(progressLabel, BorderLayout.CENTER);
        progressPanel.add(subLabel, BorderLayout.SOUTH);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);
        progressDialog.setVisible(true);

        SwingWorker<java.util.List<PDFTableLoader.BingoTableData>, String> worker =
            new SwingWorker<>() {
                @Override
                protected java.util.List<PDFTableLoader.BingoTableData> doInBackground() throws Exception {
                    return PDFTableLoader.loadTablesFromPDF(pdfFile);
                }

                @Override
                protected void done() {
                    progressDialog.dispose();
                    try {
                        java.util.List<PDFTableLoader.BingoTableData> tablesData = get();
                        if (tablesData.isEmpty()) {
                            JOptionPane.showMessageDialog(BingoGUI.this,
                                "No se encontraron tablas en el PDF.\n\n" +
                                "Verifique que:\n" +
                                "  • El PDF tenga texto real (no sea una foto/escaneo)\n" +
                                "  • Cada tabla tenga su código (varios dígitos) visible\n" +
                                "  • La tabla sea de 5×5 con encabezados B I N G O",
                                "Sin resultados", JOptionPane.WARNING_MESSAGE);
                            return;
                        }

                        int importadas = 0;
                        int duplicadas = 0;
                        StringBuilder detalle = new StringBuilder();

                        for (PDFTableLoader.BingoTableData data : tablesData) {
                            String code = data.getCode();
                            if (!isTableCodeUnique(code)) {
                                duplicadas++;
                                detalle.append("  • #").append(code).append(" — ya existe, omitida\n");
                                continue;
                            }
                            addTableFromPDFData(data);
                            importadas++;
                            String tipo = data.isSpecial() ? "Especial" : "Normal";
                            detalle.append("  • #").append(code).append(" — importada ✓ (").append(tipo).append(")\n");
                        }

                        String msg = "✅ Importación completada:\n\n" +
                            "  Tablas importadas:  " + importadas + "\n" +
                            "  Tablas duplicadas:  " + duplicadas + "\n\n" +
                            detalle.toString();
                        JOptionPane.showMessageDialog(BingoGUI.this, msg,
                            "PDF importado", JOptionPane.INFORMATION_MESSAGE);

                    } catch (Exception ex) {
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                        JOptionPane.showMessageDialog(BingoGUI.this,
                            "Error al procesar el PDF:\n\n" + msg +
                            "\n\nRECUERDE: El PDF debe ser texto (generado desde Word/Gemini).\n" +
                            "No funciona con PDFs de imagen escaneada.",
                            "Error", JOptionPane.ERROR_MESSAGE);
                        ex.printStackTrace();
                    }
                }
            };
        worker.execute();
    }

    /**
    /**
     * BOTÓN DE PRUEBA: igual que importTablesFromPDF(), pero usando la IA de
     * Google (Gemini) en vez de leer el texto del PDF. Pensado para los PDFs
     * escaneados con mucha marca de agua que PDFTableLoader no puede leer.
     * No modifica ni depende de importTablesFromPDF().
     */
    private void importTablesFromPDFWithGemini() {
        String apiKey = GeminiPDFImporter.loadApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = promptForGeminiApiKey();
            if (apiKey == null) return; // usuario canceló
            GeminiPDFImporter.saveApiKey(apiKey);
        }
        final String finalApiKey = apiKey;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Seleccionar PDF (con IA) — puede tener marca de agua");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Archivos PDF", "pdf"));
        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        java.io.File pdfFile = fileChooser.getSelectedFile();

        JDialog progressDialog = new JDialog(this, "Procesando PDF con IA...", false);
        JPanel progressPanel = new JPanel(new BorderLayout(10, 10));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        progressPanel.setBackground(BG_PANEL);
        JLabel progressLabel = new JLabel("🧪 Enviando la imagen del PDF a la IA de Google...", JLabel.CENTER);
        progressLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        progressLabel.setForeground(TEXT_MAIN);
        JLabel subLabel = new JLabel("Puede tardar 20-60 segundos por página (nivel gratuito).", JLabel.CENTER);
        subLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subLabel.setForeground(TEXT_MUTED);
        progressPanel.add(progressLabel, BorderLayout.CENTER);
        progressPanel.add(subLabel, BorderLayout.SOUTH);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);
        progressDialog.setVisible(true);

        SwingWorker<java.util.List<PDFTableLoader.BingoTableData>, String> worker =
            new SwingWorker<>() {
                @Override
                protected java.util.List<PDFTableLoader.BingoTableData> doInBackground() throws Exception {
                    return GeminiPDFImporter.importWithGemini(pdfFile, finalApiKey);
                }

                @Override
                protected void done() {
                    progressDialog.dispose();
                    try {
                        java.util.List<PDFTableLoader.BingoTableData> tablesData = get();
                        if (tablesData.isEmpty()) {
                            JOptionPane.showMessageDialog(BingoGUI.this,
                                "La IA no devolvió ninguna tabla.\nIntente con una imagen más nítida, o revise su clave de API.",
                                "Sin resultados", JOptionPane.WARNING_MESSAGE);
                            return;
                        }

                        int importadas = 0;
                        int duplicadas = 0;
                        StringBuilder detalle = new StringBuilder();

                        for (PDFTableLoader.BingoTableData data : tablesData) {
                            String code = data.getCode();
                            if (!isTableCodeUnique(code)) {
                                duplicadas++;
                                detalle.append("  • #").append(code).append(" — ya existe, omitida\n");
                                continue;
                            }
                            addTableFromPDFData(data);
                            importadas++;
                            String tipo = data.isSpecial() ? "Especial" : "Normal";
                            detalle.append("  • #").append(code).append(" — importada ✓ (").append(tipo).append(")\n");
                        }

                        String msg = "🧪 Importación con IA completada:\n\n" +
                            "  Tablas importadas:  " + importadas + "\n" +
                            "  Tablas duplicadas:  " + duplicadas + "\n\n" +
                            detalle.toString() +
                            "\n⚠️ Función experimental: revise los números contra el PDF original antes de jugar.";
                        JOptionPane.showMessageDialog(BingoGUI.this, msg,
                            "PDF importado con IA", JOptionPane.INFORMATION_MESSAGE);

                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                        JOptionPane.showMessageDialog(BingoGUI.this,
                            "Error al importar con IA:\n\n" + msg,
                            "Error", JOptionPane.ERROR_MESSAGE);
                        ex.printStackTrace();
                    }
                }
            };
        worker.execute();
    }

    /** Pide al usuario su clave de API de Gemini (gratuita, de aistudio.google.com) la primera vez. */
    private String promptForGeminiApiKey() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JLabel info = new JLabel("<html><div style='width:320px;'>"
            + "Pegue aquí su clave de API gratuita de Google AI Studio.<br>"
            + "(La consigue en <b>aistudio.google.com</b> → \"Get API key\").<br>"
            + "Se guarda solo en este equipo, nunca se comparte.</div></html>");
        JTextField keyField = new JTextField(30);
        panel.add(info, BorderLayout.NORTH);
        panel.add(keyField, BorderLayout.CENTER);

        int option = JOptionPane.showConfirmDialog(this, panel, "Clave de API de Gemini",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) return null;
        String key = keyField.getText().trim();
        if (key.isEmpty()) return null;
        return key;
    }

    /**
     * Crea una tabla visual a partir de los datos extraídos del PDF
     * y la agrega al panel de tablas.
     */
    private void addTableFromPDFData(PDFTableLoader.BingoTableData data) {
        TableSaveData td = new TableSaveData();
        td.code = data.getCode();
        td.special = data.isSpecial();
        int[][] numbers = data.getNumbers();
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                td.texts[row][col] = numbers[row][col] > 0 ? String.valueOf(numbers[row][col]) : "";
                td.bgColors[row][col] = Color.WHITE.getRGB();
            }
        }
        rebuildTableUI(td);
        savedTablesPanel.revalidate();
        savedTablesPanel.repaint();
    }

    private void createNewTable() {
        Object[] tipoOptions = {"Normal", "Especial"};
        int tipoChoice = JOptionPane.showOptionDialog(this,
            "¿Qué tipo de tabla desea crear?\n\n" +
            "Normal: se deben llenar todas las casillas y sigue los patrones dibujados.\n" +
            "Especial: es una figura, puede dejar casillas en blanco y no sigue patrones,\n" +
            "se juega llenando todos los números que usted coloque.",
            "Tipo de Tabla", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, tipoOptions, tipoOptions[0]);
        if (tipoChoice == JOptionPane.CLOSED_OPTION) return;
        boolean isSpecial = (tipoChoice == 1);

        String tableCode;
        if (isSpecial) {
            Integer digits = getCodeDigits("¿De cuántos dígitos es el código único de esta tabla especial?");
            if (digits == null) return;
            tableCode = getTableCode(digits);
        } else {
            Integer digits = getCodeDigits("¿De cuántos dígitos quiere el código único de esta tabla?");
            if (digits == null) return;
            tableCode = getTableCode(digits);
        }
        if (tableCode == null) return;

        JPanel tableContainer = new JPanel(new BorderLayout());
        tableContainer.setBackground(BG_FIELD);
        tableContainer.setBorder(BorderFactory.createLineBorder(new Color(60, 68, 100), 1, true));
        JPanel gridPanel = new JPanel(new GridLayout(SIZE + 1, SIZE, 0, 0));
        gridPanel.setPreferredSize(new Dimension(155, 155));
        JTextField[][] cells = new JTextField[SIZE][SIZE];

        String[] bingoLetters = {"B", "I", "N", "G", "O"};
        for (String letter : bingoLetters) {
            JTextField cell = new JTextField(letter);
            cell.setHorizontalAlignment(JTextField.CENTER);
            cell.setEditable(false);
            ModernUIUtils.styleHeaderCell(cell, letter, BG_DARK);
            gridPanel.add(cell);
        }

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                JTextField cell = new JTextField();
                cell.setHorizontalAlignment(JTextField.CENTER);
                cell.setEditable(false);
                cell.setBackground(Color.WHITE);
                cell.setForeground(Color.BLACK);
                ModernUIUtils.styleNumberCell(cell, BG_DARK);
                gridPanel.add(cell);
                cells[row][col] = cell;
                allCells.add(cell);
            }
        }

        tableContainer.add(gridPanel, BorderLayout.CENTER);
        String codeLabelText = "Código: " + tableCode + (isSpecial ? "  (Especial)" : "");
        JLabel codeLabel = new JLabel(codeLabelText, JLabel.CENTER);
        codeLabel.setForeground(isSpecial ? ACCENT : TEXT_MAIN);
        codeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        tableContainer.add(codeLabel, BorderLayout.SOUTH);

        savedTablesPanel.add(tableContainer);
        savedTablesPanel.revalidate();
        savedTablesPanel.repaint();

        tables.add(cells);
        tableCodes.put(cells, tableCode);
        specialTables.put(cells, isSpecial);
        tableContainers.put(cells, tableContainer);
        completedPatternsByTable.put(cells, new ArrayList<>());
        if (isSpecial) {
            SwingUtilities.invokeLater(() -> fillTableSpecial(cells));
        } else {
            SwingUtilities.invokeLater(() -> fillTableManually(cells));
        }
    }

    /** Pregunta cuántos dígitos tendrá el código único de una tabla. */
    private Integer getCodeDigits(String message) {
        while (true) {
            String input = JOptionPane.showInputDialog(this, message);
            if (input == null) return null;
            try {
                int n = Integer.parseInt(input.trim());
                if (n >= 1 && n <= 15) return n;
                JOptionPane.showMessageDialog(this, "Ingrese un número de dígitos válido (entre 1 y 15).");
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Ingrese un número válido.");
            }
        }
    }

    private String getTableCode(int digits) {
        String code;
        while (true) {
            code = JOptionPane.showInputDialog(this, "Ingrese un código único de " + digits + " dígitos:");
            if (code == null) return null;
            if (code.matches("\\d{" + digits + "}")) {
                if (isTableCodeUnique(code)) return code;
                else JOptionPane.showMessageDialog(this, "El código ya existe. Intente con otro.");
            } else {
                JOptionPane.showMessageDialog(this, "El código debe ser exactamente de " + digits + " dígitos.");
            }
        }
    }

    private boolean isTableCodeUnique(String code) {
        return !tableCodes.containsValue(code);
    }

    private void fillTableManually(JTextField[][] cells) {
        Set<Integer> usedNumbers = new HashSet<>();
        for (int col = 0; col < SIZE; col++) {
            for (int row = 0; row < SIZE; row++) {
                if (row == 2 && col == 2) {
                    cells[row][col].setText(" ");
                    continue;
                }
                while (true) {
                    String input = JOptionPane.showInputDialog(this, "Ingrese un número para (" + (row + 1) + ", " + (col + 1) + "):");
                    if (input == null) return;
                    try {
                        int number = Integer.parseInt(input);
                        if (number < COLUMN_RANGES[col][0] || number > COLUMN_RANGES[col][1]) {
                            JOptionPane.showMessageDialog(this, "Número fuera de rango para esta columna.");
                            continue;
                        }
                        if (!usedNumbers.contains(number)) {
                            usedNumbers.add(number);
                            cells[row][col].setText(String.valueOf(number));
                            break;
                        } else {
                            JOptionPane.showMessageDialog(this, "Número repetido. Intente con otro.");
                        }
                    } catch (NumberFormatException ex) {
                        JOptionPane.showMessageDialog(this, "Ingrese un número válido.");
                    }
                }
            }
        }
    }

    /**
     * Llenado para tablas ESPECIALES (figuras): primero el usuario elige, en una
     * cuadrícula 5x5, en qué casillas quiere colocar números (el resto queda en
     * blanco permanentemente). Luego se piden los números solo para esas casillas.
     */
    private void fillTableSpecial(JTextField[][] cells) {
        boolean[][] selected = new boolean[SIZE][SIZE];
        boolean accepted = showCellSelectionDialog(selected);
        if (!accepted) return;

        boolean anySelected = false;
        for (int row = 0; row < SIZE; row++)
            for (int col = 0; col < SIZE; col++)
                if (selected[row][col]) anySelected = true;
        if (!anySelected) {
            JOptionPane.showMessageDialog(this, "No seleccionó ninguna casilla. La tabla quedó totalmente en blanco.");
            return;
        }

        Set<Integer> usedNumbers = new HashSet<>();
        for (int col = 0; col < SIZE; col++) {
            for (int row = 0; row < SIZE; row++) {
                if (!selected[row][col]) {
                    // Casilla no elegida: permanece en blanco.
                    continue;
                }
                while (true) {
                    String input = JOptionPane.showInputDialog(this, "Ingrese un número para (" + (row + 1) + ", " + (col + 1) + "):");
                    if (input == null) break; // deja esta casilla en blanco si cancela
                    try {
                        int number = Integer.parseInt(input);
                        if (number < COLUMN_RANGES[col][0] || number > COLUMN_RANGES[col][1]) {
                            JOptionPane.showMessageDialog(this, "Número fuera de rango para esta columna.");
                            continue;
                        }
                        if (!usedNumbers.contains(number)) {
                            usedNumbers.add(number);
                            cells[row][col].setText(String.valueOf(number));
                            break;
                        } else {
                            JOptionPane.showMessageDialog(this, "Número repetido. Intente con otro.");
                        }
                    } catch (NumberFormatException ex) {
                        JOptionPane.showMessageDialog(this, "Ingrese un número válido.");
                    }
                }
            }
        }
    }

    /**
     * Muestra una cuadrícula 5x5 de botones para que el usuario marque qué
     * casillas tendrán número (figura de la tabla especial). Devuelve true si
     * aceptó la selección, false si canceló.
     */
    private boolean showCellSelectionDialog(boolean[][] selected) {
        final boolean[] accepted = {false};
        JDialog dialog = new JDialog(this, "Seleccione la figura (casillas con número)", true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(BG_DARK);

        JLabel instructions = new JLabel(
            "<html><div style='text-align:center;'>Haga clic en las casillas que tendrán número.<br>"
            + "Las demás quedarán en blanco.</div></html>", JLabel.CENTER);
        instructions.setForeground(TEXT_MAIN);
        instructions.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dialog.add(instructions, BorderLayout.NORTH);

        JPanel gridPanel = new JPanel(new GridLayout(SIZE, SIZE, 4, 4));
        gridPanel.setBackground(BG_DARK);
        gridPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JButton[][] gridButtons = new JButton[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                JButton cellButton = new JButton();
                cellButton.setPreferredSize(new Dimension(45, 45));
                cellButton.setBackground(BG_FIELD);
                cellButton.setOpaque(true);
                cellButton.setBorder(BorderFactory.createLineBorder(BG_DARK, 2));
                final int r = row, c = col;
                if (row == 2 && col == 2) {
                    // El centro nunca se juega en el bingo, ni siquiera en tablas especiales.
                    cellButton.setEnabled(false);
                    cellButton.setBackground(new Color(60, 68, 100));
                } else {
                    cellButton.addActionListener(e -> {
                        selected[r][c] = !selected[r][c];
                        cellButton.setBackground(selected[r][c] ? ACCENT : BG_FIELD);
                    });
                }
                gridButtons[row][col] = cellButton;
                gridPanel.add(cellButton);
            }
        }
        dialog.add(gridPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBackground(BG_DARK);
        JButton acceptButton = createStyledButton("Aceptar");
        acceptButton.addActionListener(e -> { accepted[0] = true; dialog.dispose(); });
        JButton cancelButton = createStyledButton("Cancelar");
        cancelButton.addActionListener(e -> { accepted[0] = false; dialog.dispose(); });
        buttonPanel.add(acceptButton);
        buttonPanel.add(cancelButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return accepted[0];
    }

    private void markNumber() {
        if (tables.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No hay tablas cargadas.\nCree o importe al menos una tabla antes de marcar números.",
                "Sin tablas", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String input = numberInputField.getText().trim();
        if (input.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Ingrese un número antes de marcar.");
            return;
        }
        try {
            int calledNumber = Integer.parseInt(input);

            if (calledNumbers.contains(calledNumber)) {
                JOptionPane.showMessageDialog(this,
                    "El número " + calledNumber + " ya fue marcado anteriormente.",
                    "Número repetido", JOptionPane.WARNING_MESSAGE);
                numberInputField.setText("");
                return;
            }

            calledNumbers.add(calledNumber);
            boolean found = false;

            for (JTextField[][] table : tables) {
                for (int row = 0; row < SIZE; row++) {
                    for (int col = 0; col < SIZE; col++) {
                        if (isPartOfCompletedPattern(table, row, col) && table[row][col].getBackground().equals(new Color(46, 204, 113))) {
                            table[row][col].setBackground(ModernUIUtils.MARKED_COLOR);
                        }
                    }
                }
            }

            for (JTextField[][] table : tables) {
                for (int row = 0; row < SIZE; row++) {
                    for (int col = 0; col < SIZE; col++) {
                        if (table[row][col].getText().equals(String.valueOf(calledNumber))) {
                            table[row][col].setBackground(ModernUIUtils.MARKED_COLOR);
                            found = true;
                        }
                    }
                }
            }

            if (!found) JOptionPane.showMessageDialog(this, "El número no está en ninguna tabla.");
            else markedNumbersHistory.push(calledNumber);

            numberInputField.setText("");
            checkWinCondition();
            checkFullTable();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Ingrese un número válido.");
        }
    }

    private boolean isPartOfCompletedPattern(JTextField[][] table, int row, int col) {
        List<boolean[][]> completed = completedPatternsByTable.get(table);
        if (completed == null) return false;
        for (boolean[][] pattern : completed) {
            if (pattern[row][col]) return true;
        }
        return false;
    }

    /**
     * Si la casilla (row, col) de esta tabla formaba parte de algún patrón ya
     * marcado como completado, "deshace" ese logro: lo saca de la lista de
     * patrones completados y devuelve el resto de sus casillas de verde
     * (ganado) a naranja (marcado). Se usa al editar el número de una casilla
     * a mitad de partida, para que el estado del juego no quede desincronizado
     * con lo que realmente hay dibujado en la tabla.
     */
    private void revertCompletedPatternsAffectingCell(JTextField[][] table, int row, int col) {
        List<boolean[][]> completedForTable = completedPatternsByTable.get(table);
        if (completedForTable == null || completedForTable.isEmpty()) return;

        Color wonGreen = new Color(46, 204, 113);
        List<boolean[][]> affected = new ArrayList<>();
        for (boolean[][] pattern : completedForTable) {
            if (pattern[row][col]) affected.add(pattern);
        }
        if (affected.isEmpty()) return;

        for (boolean[][] pattern : affected) {
            completedForTable.remove(pattern);
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    if (pattern[r][c] && table[r][c].getBackground().equals(wonGreen)) {
                        table[r][c].setBackground(ModernUIUtils.MARKED_COLOR);
                    }
                }
            }
        }
    }

    /**
     * Si esta tabla es Especial y todavía le quedan casillas en el verde de
     * "tabla llena ganada" a pesar de que la tabla ya no está completa (por
     * ejemplo, porque se editó una casilla), las regresa a naranja (marcado).
     * Evita que una tabla quede "ganada a medias" visualmente para siempre.
     */
    private void revertStaleFullTableWin(JTextField[][] table, boolean isSpecial) {
        if (!isSpecial) return;
        Color wonGreen = new Color(46, 204, 113);
        boolean stillFull = true;
        for (int r = 0; r < SIZE && stillFull; r++) {
            for (int c = 0; c < SIZE; c++) {
                boolean isBlank = table[r][c].getText().trim().isEmpty();
                if (!isBlank && table[r][c].getBackground() != ModernUIUtils.MARKED_COLOR
                        && !table[r][c].getBackground().equals(wonGreen)) {
                    stillFull = false; break;
                }
            }
        }
        if (stillFull) return; // sigue completa (o no tenía nada verde), no hay nada que corregir

        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (table[r][c].getBackground().equals(wonGreen)) {
                    table[r][c].setBackground(ModernUIUtils.MARKED_COLOR);
                }
            }
        }
    }

    private void checkWinCondition() {
        for (JTextField[][] table : tables) {
            // Las tablas especiales no siguen los patrones dibujados: se juegan
            // llenando todos los números que el usuario colocó (ver checkFullTable).
            if (Boolean.TRUE.equals(specialTables.get(table))) continue;

            List<boolean[][]> completedForTable = completedPatternsByTable.computeIfAbsent(table, k -> new ArrayList<>());
            for (int p = 0; p < winPatterns.size(); p++) {
                boolean[][] pattern = winPatterns.get(p);
                if (completedForTable.contains(pattern)) continue;

                boolean hasMarkedCell = false;
                for (int row = 0; row < SIZE; row++) {
                    for (int col = 0; col < SIZE; col++) {
                        if (pattern[row][col]) { hasMarkedCell = true; break; }
                    }
                    if (hasMarkedCell) break;
                }
                if (!hasMarkedCell) continue;

                boolean matches = true;
                for (int row = 0; row < SIZE; row++) {
                    for (int col = 0; col < SIZE; col++) {
                        if (pattern[row][col] && !table[row][col].getBackground().equals(ModernUIUtils.MARKED_COLOR)) {
                            matches = false; break;
                        }
                    }
                    if (!matches) break;
                }

                if (matches) {
                    completedForTable.add(pattern);
                    List<JTextField> winningCells = new ArrayList<>();
                    for (int row = 0; row < SIZE; row++) {
                        for (int col = 0; col < SIZE; col++) {
                            if (pattern[row][col]) {
                                winningCells.add(table[row][col]);
                            }
                        }
                    }
                    String tableCode = tableCodes.get(table);
                    String patternCode = getPatternCodeByIndex(p);
                    if (patternCode == null) patternCode = String.valueOf(p + 1);
                    javax.swing.Timer pulse = pulseWinningCells(winningCells);
                    showWinMessage(tableCode, patternCode, pulse, winningCells);
                }
            }
        }
    }

    /**
     * Pone en pulso continuo (verde esmeralda ↔ verde neón, cada 50ms) las
     * celdas ganadoras, y les aplica un borde dorado grueso para que no se
     * vean "planas". El Timer devuelto debe detenerse cuando se cierre el
     * diálogo de victoria (ver showWinMessage), de lo contrario seguiría
     * corriendo indefinidamente en segundo plano.
     */
    private javax.swing.Timer pulseWinningCells(List<JTextField> cells) {
        Color emerald = new Color(0x10, 0xB9, 0x81);
        Color neon    = new Color(0x34, 0xD3, 0x99);
        javax.swing.border.Border goldBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xF5, 0x9E, 0x0B), 3),
            BorderFactory.createEmptyBorder(2, 2, 2, 2));
        for (JTextField cell : cells) cell.setBorder(goldBorder);

        javax.swing.Timer timer = new javax.swing.Timer(50, null);
        final boolean[] toggle = {false};
        timer.addActionListener(e -> {
            toggle[0] = !toggle[0];
            Color c = toggle[0] ? neon : emerald;
            for (JTextField cell : cells) cell.setBackground(c);
        });
        timer.start();
        return timer;
    }

    private void showWinMessage(String tableCode, String patternCode,
                                 javax.swing.Timer pulseTimer, List<JTextField> winningCells) {
        Color solidWinGreen = new Color(46, 204, 113); // color final estable, usado también
                                                        // por la lógica de "isPartOfCompletedPattern"
        JDialog winDialog = new JDialog(this, "¡BINGO!", true);
        winDialog.setUndecorated(true);
        winDialog.setSize(520, 400);
        winDialog.setLocationRelativeTo(this);
        winDialog.setBackground(new Color(0, 0, 0, 0));

        // Panel principal con pintura personalizada (confetti animado + fondo degradado).
        // El propio Timer de la animación vive dentro del panel para poder llamar
        // directamente a tick() sin necesidad de reflexión.
        class ConfettiPanel extends JPanel {
            private final java.util.Random rnd = new java.util.Random(42);
            private final Color[] confettiColors = {
                new Color(255,215,0), new Color(255,80,80), new Color(80,200,120),
                new Color(80,160,255), new Color(255,140,0), new Color(200,80,255)
            };
            private final int N = 50;
            private final float[] cx = new float[N], cy = new float[N], cspeed = new float[N];
            private final int[] cw = new int[N], ch = new int[N], ci = new int[N];
            private final javax.swing.Timer fallTimer;
            ConfettiPanel() {
                super(new BorderLayout(10, 15));
                for (int k = 0; k < N; k++) {
                    cx[k] = rnd.nextInt(500);
                    cy[k] = rnd.nextInt(400) - 400; // arrancan arriba, fuera de vista
                    cw[k] = 6 + rnd.nextInt(10);
                    ch[k] = 6 + rnd.nextInt(10);
                    ci[k] = rnd.nextInt(confettiColors.length);
                    cspeed[k] = 1.5f + rnd.nextFloat() * 3f;
                }
                fallTimer = new javax.swing.Timer(30, e -> tick());
            }
            void startAnim() { fallTimer.start(); }
            void stopAnim()  { fallTimer.stop(); }
            private void tick() {
                for (int k = 0; k < N; k++) {
                    cy[k] += cspeed[k];
                    if (cy[k] > getHeight()) cy[k] = -20;
                }
                repaint();
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Fondo degradado dorado/oscuro
                GradientPaint gp = new GradientPaint(0,0,new Color(50,35,5),0,getHeight(),new Color(15,20,50));
                g2.setPaint(gp);
                g2.fillRoundRect(0,0,getWidth(),getHeight(),30,30);
                // Borde dorado
                g2.setColor(new Color(255,215,0));
                g2.setStroke(new BasicStroke(3f));
                g2.drawRoundRect(2,2,getWidth()-4,getHeight()-4,28,28);
                // Confetti cayendo
                for(int k=0;k<N;k++){
                    g2.setColor(confettiColors[ci[k]]);
                    g2.fillRoundRect((int)cx[k],(int)cy[k],cw[k],ch[k],3,3);
                }
                g2.dispose();
            }
        }
        ConfettiPanel panel = new ConfettiPanel();
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(30, 30, 25, 30));

        // Título grande: "¡TABLA GANADORA!" con pulso dorado/blanco
        JLabel title = new JLabel("🏆 ¡TABLA GANADORA! 🏆", JLabel.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 34));
        title.setForeground(new Color(255, 215, 0));
        javax.swing.Timer titlePulse = new javax.swing.Timer(600, null);
        titlePulse.addActionListener(pe -> {
            Color current = title.getForeground();
            title.setForeground(current.equals(new Color(255,215,0))
                ? new Color(255,255,255) : new Color(255,215,0));
        });
        titlePulse.start();

        // Subtítulo: patrón completado (o "tabla llena" si patternCode es null)
        JLabel patLabel = new JLabel(
            patternCode != null
                ? "<html><div style='text-align:center; font-size:13px;'>"
                  + "✨ Se completó el patrón <b style='color:#FFD700;'>" + patternCode + "</b></div></html>"
                : "<html><div style='text-align:center; font-size:13px;'>"
                  + "✨ ¡Tabla completa! Todos los números jugados</div></html>",
            JLabel.CENTER);
        patLabel.setFont(new Font("Segoe UI", Font.BOLD, 17));
        patLabel.setForeground(new Color(200, 220, 255));

        // Código de tabla (bien legible, fuente más pequeña que el título)
        JLabel codeLabel = new JLabel(
            "<html><div style='text-align:center;'>"
            + "📋 Código: <b style='color:#FFD700; font-size:16px;'>" + tableCode + "</b></div></html>",
            JLabel.CENTER);
        codeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        codeLabel.setForeground(new Color(180, 200, 255));

        JPanel infoPanel = new JPanel(new GridLayout(2, 1, 5, 8));
        infoPanel.setOpaque(false);
        infoPanel.add(patLabel);
        infoPanel.add(codeLabel);

        // Botón estilizado, con efecto "hover" (crece un poco al pasar el mouse)
        final float[] scale = {1f};
        JButton okButton = new JButton("🎊  ¡Continuar jugando!") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D)g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                double sw = w * scale[0], sh = h * scale[0];
                g2.translate((w - sw) / 2.0, (h - sh) / 2.0);
                g2.scale(scale[0], scale[0]);
                GradientPaint gp = new GradientPaint(0,0,new Color(255,180,0),0,h,new Color(200,80,0));
                g2.setPaint(gp);
                g2.fillRoundRect(0,0,w,h,22,22);
                g2.setColor(new Color(255,255,255,60));
                g2.fillRoundRect(0,0,w,h/2,22,22);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        okButton.setFont(new Font("Segoe UI", Font.BOLD, 16));
        okButton.setForeground(Color.WHITE);
        okButton.setPreferredSize(new Dimension(240, 46));
        okButton.setContentAreaFilled(false);
        okButton.setBorderPainted(false);
        okButton.setFocusPainted(false);
        okButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        okButton.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { scale[0] = 1.08f; okButton.repaint(); }
            @Override public void mouseExited(java.awt.event.MouseEvent e)  { scale[0] = 1f;    okButton.repaint(); }
        });
        okButton.addActionListener(e -> {
            titlePulse.stop();
            panel.stopAnim();
            pulseTimer.stop();
            // Deja las celdas ganadoras en el verde final estable (usado también
            // por la lógica de "recordar" cuáles cayeron en un patrón ganado).
            for (JTextField cell : winningCells) cell.setBackground(solidWinGreen);
            winDialog.dispose();
        });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 5));
        btnPanel.setOpaque(false);
        btnPanel.add(okButton);

        panel.add(title, BorderLayout.NORTH);
        panel.add(infoPanel, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);

        winDialog.setContentPane(panel);
        winDialog.getRootPane().setBorder(BorderFactory.createEmptyBorder());
        panel.startAnim();
        winDialog.setVisible(true);
    }

    private void checkFullTable() {
        for (JTextField[][] table : tables) {
            boolean isFull = true;
            boolean hasContent = false;
            List<JTextField> filledCells = new ArrayList<>();
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    // Una casilla en blanco (sin número, incluida la casilla libre central
                    // de las tablas normales) no impide considerar la tabla como completa.
                    boolean isBlank = table[row][col].getText().trim().isEmpty();
                    if (!isBlank) { hasContent = true; filledCells.add(table[row][col]); }
                    if (table[row][col].getBackground() != ModernUIUtils.MARKED_COLOR && !isBlank) {
                        isFull = false; break;
                    }
                }
                if (!isFull) break;
            }
            isFull = isFull && hasContent;
            if (isFull) {
                String tableCode = tableCodes.get(table);
                javax.swing.Timer pulse = pulseWinningCells(filledCells);
                showWinMessage(tableCode, null, pulse, filledCells);
            }
        }
    }

    private void editTable() {
        String code = JOptionPane.showInputDialog(this, "Ingrese el código de la tabla que desea editar:");
        if (code == null || code.trim().isEmpty()) return;

        JTextField[][] tableToEdit = findTableByCode(code);
        if (tableToEdit == null) {
            JOptionPane.showMessageDialog(this, "No se encontró una tabla con ese código.");
            return;
        }
        boolean isSpecial = Boolean.TRUE.equals(specialTables.get(tableToEdit));

        JDialog editDialog = new JDialog(this, "Editar Tabla", true);
        editDialog.setLayout(new BorderLayout());
        editDialog.setSize(400, 400);
        editDialog.getContentPane().setBackground(BG_DARK);

        JPanel tablePanel = new JPanel(new GridLayout(SIZE, SIZE, 5, 5));
        tablePanel.setBackground(BG_DARK);
        tablePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                JTextField cell = tableToEdit[row][col];
                JButton cellButton = new JButton(cell.getText());
                cellButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
                cellButton.setBackground(cell.getBackground());
                cellButton.setForeground(Color.BLACK);
                cellButton.setBorder(BorderFactory.createLineBorder(BG_DARK, 2));
                if (row == 2 && col == 2) {
                    // El centro nunca se juega en el bingo (ni en tablas normales ni especiales).
                    cellButton.setEnabled(false);
                    cellButton.setBackground(new Color(200, 200, 200));
                } else {
                    final int r = row, c = col;
                    cellButton.addActionListener(e -> {
                        String prompt = isSpecial
                            ? "Ingrese el nuevo número para la casilla (" + (r + 1) + ", " + (c + 1) + "),\n"
                              + "o déjelo vacío y presione Aceptar para dejarla en blanco:"
                            : "Ingrese el nuevo número para la casilla (" + (r + 1) + ", " + (c + 1) + "):";
                        String newNumberInput = JOptionPane.showInputDialog(editDialog, prompt, cell.getText().trim());
                        if (newNumberInput == null) return; // canceló, no cambia nada

                        if (newNumberInput.trim().isEmpty()) {
                            if (isSpecial) {
                                // Solo las tablas especiales permiten dejar una casilla en blanco.
                                revertCompletedPatternsAffectingCell(tableToEdit, r, c);
                                cell.setText("");
                                cellButton.setText("");
                                cell.setBackground(Color.WHITE);
                                revertStaleFullTableWin(tableToEdit, isSpecial);
                                JOptionPane.showMessageDialog(editDialog, "Casilla dejada en blanco.");
                            } else {
                                JOptionPane.showMessageDialog(editDialog, "Las tablas normales no permiten casillas en blanco.");
                            }
                            return;
                        }
                        try {
                            int newNumber = Integer.parseInt(newNumberInput.trim());
                            if (isNumberInTable(tableToEdit, newNumber)) {
                                JOptionPane.showMessageDialog(editDialog, "El número ya está en la tabla.");
                                return;
                            }
                            int[] columnRange = COLUMN_RANGES[c];
                            if (newNumber < columnRange[0] || newNumber > columnRange[1]) {
                                JOptionPane.showMessageDialog(editDialog, "El número está fuera del rango permitido para esta columna.");
                                return;
                            }
                            revertCompletedPatternsAffectingCell(tableToEdit, r, c);
                            cell.setText(String.valueOf(newNumber));
                            cellButton.setText(String.valueOf(newNumber));
                            cell.setBackground(Color.WHITE);
                            revertStaleFullTableWin(tableToEdit, isSpecial);
                            JOptionPane.showMessageDialog(editDialog, "Casilla actualizada correctamente.");
                        } catch (NumberFormatException ex) {
                            JOptionPane.showMessageDialog(editDialog, "Ingrese un número válido.");
                        }
                    });
                }
                tablePanel.add(cellButton);
            }
        }

        JButton closeButton = createStyledButton("Cerrar");
        closeButton.addActionListener(e -> editDialog.dispose());
        JPanel closePanel = new JPanel();
        closePanel.setBackground(BG_DARK);
        closePanel.add(closeButton);

        editDialog.add(tablePanel, BorderLayout.CENTER);
        editDialog.add(closePanel, BorderLayout.SOUTH);
        editDialog.setLocationRelativeTo(this);
        editDialog.setVisible(true);
    }

    private JTextField[][] findTableByCode(String code) {
        for (JTextField[][] table : tables) {
            if (tableCodes.get(table).equals(code)) return table;
        }
        return null;
    }

    private boolean isNumberInTable(JTextField[][] table, int number) {
        for (int row = 0; row < SIZE; row++)
            for (int col = 0; col < SIZE; col++)
                if (table[row][col].getText().equals(String.valueOf(number))) return true;
        return false;
    }

    private void deleteTable() {
        String code = JOptionPane.showInputDialog(this, "Ingrese el código de la tabla que desea eliminar:");
        if (code == null || code.trim().isEmpty()) return;

        int confirm = JOptionPane.showConfirmDialog(this, "¿Estás seguro de que deseas eliminar esta tabla?",
            "Confirmar eliminación", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        JTextField[][] tableToRemove = null;
        for (JTextField[][] table : tables) {
            if (tableCodes.get(table).equals(code)) { tableToRemove = table; break; }
        }

        if (tableToRemove != null) {
            JPanel panelToRemove = tableContainers.get(tableToRemove);
            tables.remove(tableToRemove);
            tableCodes.remove(tableToRemove);
            specialTables.remove(tableToRemove);
            tableContainers.remove(tableToRemove);
            completedPatternsByTable.remove(tableToRemove);
            if (panelToRemove != null) {
                savedTablesPanel.remove(panelToRemove);
            }
            savedTablesPanel.revalidate();
            savedTablesPanel.repaint();
            JOptionPane.showMessageDialog(this, "Tabla eliminada correctamente.");
        } else {
            JOptionPane.showMessageDialog(this, "No se encontró una tabla con ese código.");
        }
    }

    private void deletePattern() {
        String patternCode = JOptionPane.showInputDialog(this, "Ingrese el número del patrón que desea eliminar:");
        if (patternCode == null || patternCode.trim().isEmpty()) return;
        if (!patternCodes.containsKey(patternCode)) {
            JOptionPane.showMessageDialog(this, "No se encontró un patrón con ese código.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this, "¿Estás seguro de que deseas eliminar este patrón?",
            "Confirmar eliminación", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            boolean[][] patternToRemove = patternCodes.get(patternCode);
            JButton[][] buttonsToRemove = patternButtonCodes.get(patternCode);
            winPatterns.remove(patternToRemove);
            patternButtons.remove(buttonsToRemove);
            patternCodes.remove(patternCode);
            patternButtonCodes.remove(patternCode);
            String expectedLabel = "Código: " + patternCode;
            for (Component comp : savedPatternsPanel.getComponents()) {
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;
                    for (Component label : panel.getComponents()) {
                        if (label instanceof JLabel) {
                            JLabel codeLabel = (JLabel) label;
                            if (codeLabel.getText().equals(expectedLabel)) {
                                savedPatternsPanel.remove(panel);
                                break;
                            }
                        }
                    }
                }
            }
            savedPatternsPanel.revalidate();
            savedPatternsPanel.repaint();
            JOptionPane.showMessageDialog(this, "Patrón eliminado correctamente.");
        }
    }

    private void resetGame() {
        int confirm = JOptionPane.showConfirmDialog(this, "¿Estás seguro de que deseas reiniciar el juego?",
            "Confirmar reinicio", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            for (JTextField[][] table : tables)
                for (int row = 0; row < SIZE; row++)
                    for (int col = 0; col < SIZE; col++)
                        table[row][col].setBackground(Color.WHITE);
            savedTablesPanel.removeAll();
            savedPatternsPanel.removeAll();
            tables.clear();
            winPatterns.clear();
            patternButtons.clear();
            calledNumbers.clear();
            completedPatternsByTable.clear();
            tableCodes.clear();
            specialTables.clear();
            tableContainers.clear();
            patternCodes.clear();
            patternButtonCodes.clear();
            markedNumbersHistory.clear();
            patternCounter = 1;
            savedTablesPanel.revalidate();
            savedTablesPanel.repaint();
            savedPatternsPanel.revalidate();
            savedPatternsPanel.repaint();
            JOptionPane.showMessageDialog(this, "Juego reiniciado correctamente.");
        }
    }

    private void showStats() {
        int totalTables = tables.size();
        int totalPatterns = winPatterns.size();
        int totalCalledNumbers = calledNumbers.size();
        int completedPatternsCount = completedPatternsByTable.values().stream().mapToInt(List::size).sum();
        double percentageCalled = (double) totalCalledNumbers / 75 * 100;
        String stats = "Estadísticas del juego:\n" +
            "Tablas creadas: " + totalTables + "\n" +
            "Patrones creados: " + totalPatterns + "\n" +
            "Patrones completados: " + completedPatternsCount + "\n" +
            "Números cantados: " + totalCalledNumbers + "\n" +
            "Porcentaje de números cantados: " + String.format("%.2f", percentageCalled) + "%";
        JOptionPane.showMessageDialog(this, stats);
    }

    private void openPatternWindow() {
        if (patternCodes.isEmpty()) {
            patternCounter = 1;
        } else {
            int maxCode = patternCodes.keySet().stream().mapToInt(Integer::parseInt).max().orElse(0);
            patternCounter = maxCode + 1;
        }

        JPanel patternContainer = new JPanel(new BorderLayout());
        patternContainer.setBackground(ModernUIUtils.PATTERN_BG);
        patternContainer.setBorder(BorderFactory.createLineBorder(new Color(60, 68, 100), 1, true));
        JPanel patternPanel = new JPanel(new GridLayout(SIZE, SIZE, 3, 3));
        patternPanel.setBackground(ModernUIUtils.PATTERN_BG);
        patternPanel.setPreferredSize(new Dimension(155, 155));
        JButton[][] patternCells = new JButton[SIZE][SIZE];
        boolean[][] newPattern = new boolean[SIZE][SIZE];

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                JButton cell = new JButton();
                cell.setPreferredSize(new Dimension(40, 40));
                cell.setBackground(ModernUIUtils.PATTERN_BG);
                cell.setOpaque(true);
                cell.setBorder(BorderFactory.createLineBorder(BG_DARK, 2));
                final int r = row, c = col;
                if (row == 2 && col == 2) {
                    cell.setEnabled(false);
                    cell.setBackground(new Color(60, 68, 100));
                } else {
                    cell.addActionListener(e -> {
                        newPattern[r][c] = !newPattern[r][c];
                        boolean nowActive = newPattern[r][c];
                        cell.setBackground(nowActive ? ModernUIUtils.PATTERN_NEON : ModernUIUtils.PATTERN_BG);
                        cell.setBorder(nowActive ? ModernUIUtils.glowBorder(ModernUIUtils.PATTERN_NEON)
                                                  : BorderFactory.createLineBorder(BG_DARK, 2));
                    });
                }
                patternCells[row][col] = cell;
                patternPanel.add(cell);
            }
        }

        String patternCode = String.valueOf(patternCounter++);
        patternContainer.add(patternPanel, BorderLayout.CENTER);
        JLabel codeLabel = new JLabel("Código: " + patternCode, JLabel.CENTER);
        codeLabel.setForeground(TEXT_MAIN);
        codeLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
        codeLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        patternContainer.add(codeLabel, BorderLayout.SOUTH);

        savedPatternsPanel.add(patternContainer);
        savedPatternsPanel.revalidate();
        savedPatternsPanel.repaint();

        winPatterns.add(newPattern);
        patternButtons.add(patternCells);
        patternCodes.put(patternCode, newPattern);
        patternButtonCodes.put(patternCode, patternCells);

        disablePatternEditing(patternCells);
    }

    private void disablePatternEditing(JButton[][] patternCells) {
        for (int row = 0; row < SIZE; row++)
            for (int col = 0; col < SIZE; col++)
                patternCells[row][col].setEnabled(false);
    }

    private void enablePatternEditing(JButton[][] patternCells) {
        for (int row = 0; row < SIZE; row++)
            for (int col = 0; col < SIZE; col++)
                if (!(row == 2 && col == 2))
                    patternCells[row][col].setEnabled(true);
    }

    private void editPattern() {
        String patternCode = JOptionPane.showInputDialog(this, "Ingrese el número del patrón que desea editar:");
        if (patternCode == null || patternCode.trim().isEmpty()) return;
        if (!patternCodes.containsKey(patternCode)) {
            JOptionPane.showMessageDialog(this, "No se encontró un patrón con ese código.");
            return;
        }
        if (currentEditingPatternIndex != -1) {
            String currentPatternCode = getPatternCodeByIndex(currentEditingPatternIndex);
            JButton[][] currentPatternCells = patternButtonCodes.get(currentPatternCode);
            if (currentPatternCells != null) disablePatternEditing(currentPatternCells);
        }
        JButton[][] patternCells = patternButtonCodes.get(patternCode);
        if (patternCells == null) {
            JOptionPane.showMessageDialog(this, "No se encontraron botones asociados a este patrón.");
            return;
        }
        enablePatternEditing(patternCells);
        finishEditButton.setVisible(true);
        currentEditingPatternIndex = winPatterns.indexOf(patternCodes.get(patternCode));
        JOptionPane.showMessageDialog(this, "Ahora puedes editar el patrón " + patternCode);
    }

    private void finishEditing() {
        if (currentEditingPatternIndex != -1) {
            String patternCode = getPatternCodeByIndex(currentEditingPatternIndex);
            if (patternCode != null) {
                JButton[][] patternCells = patternButtonCodes.get(patternCode);
                if (patternCells != null) disablePatternEditing(patternCells);
            }
            currentEditingPatternIndex = -1;
        }
        finishEditButton.setVisible(false);
        JOptionPane.showMessageDialog(this, "Edición finalizada. El patrón ya no es editable.");
    }

    private String getPatternCodeByIndex(int index) {
        for (Map.Entry<String, boolean[][]> entry : patternCodes.entrySet()) {
            if (winPatterns.indexOf(entry.getValue()) == index) return entry.getKey();
        }
        return null;
    }

    private void undoLastMove() {
        if (markedNumbersHistory.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No hay jugadas para retroceder.");
            return;
        }
        int lastMarkedNumber = markedNumbersHistory.pop();
        Color wonGreen = new Color(46, 204, 113);

        for (JTextField[][] table : tables) {
            boolean tableAffected = false;
            for (int row = 0; row < SIZE; row++) {
                for (int col = 0; col < SIZE; col++) {
                    if (table[row][col].getText().equals(String.valueOf(lastMarkedNumber))) {
                        table[row][col].setBackground(Color.WHITE);
                        tableAffected = true;
                    }
                }
            }

            // Si esta tabla es ESPECIAL y le acabamos de quitar un número, ya no
            // puede seguir "llena/ganada" — cualquier otra casilla que le haya
            // quedado en verde (del festejo de "tabla llena") vuelve a naranja
            // (marcada), igual que ya se hacía para los patrones de las Normales.
            if (tableAffected && Boolean.TRUE.equals(specialTables.get(table))) {
                for (int row = 0; row < SIZE; row++) {
                    for (int col = 0; col < SIZE; col++) {
                        if (table[row][col].getBackground().equals(wonGreen)) {
                            table[row][col].setBackground(ModernUIUtils.MARKED_COLOR);
                        }
                    }
                }
            }
        }

        calledNumbers.remove(lastMarkedNumber);

        for (JTextField[][] table : tables) {
            List<boolean[][]> completedForTable = completedPatternsByTable.get(table);
            if (completedForTable == null) continue;
            for (int p = 0; p < winPatterns.size(); p++) {
                boolean[][] pattern = winPatterns.get(p);
                if (completedForTable.contains(pattern)) {
                    boolean wasAffected = false;
                    for (int row = 0; row < SIZE; row++) {
                        for (int col = 0; col < SIZE; col++) {
                            if (pattern[row][col] && table[row][col].getText().equals(String.valueOf(lastMarkedNumber))) {
                                wasAffected = true; break;
                            }
                        }
                        if (wasAffected) break;
                    }
                    if (wasAffected) {
                        completedForTable.remove(pattern);
                        for (int row = 0; row < SIZE; row++)
                            for (int col = 0; col < SIZE; col++)
                                if (pattern[row][col]) {
                                    if (table[row][col].getBackground().equals(new Color(46, 204, 113)))
                                        table[row][col].setBackground(ModernUIUtils.MARKED_COLOR);
                                    else
                                        table[row][col].setBackground(Color.WHITE);
                                }
                    }
                }
            }
        }
        JOptionPane.showMessageDialog(this, "Se ha desmarcado el número: " + lastMarkedNumber);
    }

    private javax.swing.border.Border titledPanelBorder(String title) {
        javax.swing.border.Border line = BorderFactory.createLineBorder(ACCENT_2, 1, true);
        javax.swing.border.TitledBorder titled = BorderFactory.createTitledBorder(line, title);
        titled.setTitleFont(new Font("Segoe UI", Font.BOLD, 13));
        titled.setTitleColor(ACCENT);
        return BorderFactory.createCompoundBorder(titled, BorderFactory.createEmptyBorder(8, 8, 8, 8));
    }

    private JButton createStyledButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // "accentColor": botón con color de acento propio (ej. Marcar = azul).
                // "outlined": botón sin relleno, solo contorno (ej. Retroceder Jugada).
                Color accent = (Color) getClientProperty("accentColor");
                boolean outlined = Boolean.TRUE.equals(getClientProperty("outlined"));
                if (outlined) {
                    g2.setColor(getModel().isRollover() ? new Color(60, 68, 100) : BG_FIELD);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.setColor(getModel().isRollover() ? ACCENT_2 : new Color(90, 98, 120));
                    g2.setStroke(new BasicStroke(1.4f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                } else {
                    Color base = accent != null ? accent : new Color(40, 46, 76);
                    Color hover = accent != null ? accent.brighter() : ACCENT_2;
                    g2.setColor(getModel().isRollover() ? hover : base);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                    g2.setColor(getModel().isRollover() ? hover : new Color(60, 68, 100));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setForeground(TEXT_MAIN);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return button;
    }

    private void applyModernColors() {
        UIManager.put("Panel.background", BG_DARK);
        UIManager.put("Button.background", BG_FIELD);
        UIManager.put("Button.foreground", TEXT_MAIN);
        UIManager.put("TextField.background", BG_FIELD);
        UIManager.put("TextField.foreground", TEXT_MAIN);
        UIManager.put("TextField.caretForeground", ACCENT);
        UIManager.put("TextArea.background", BG_FIELD);
        UIManager.put("TextArea.foreground", TEXT_MAIN);
        UIManager.put("TextArea.caretForeground", ACCENT);
        UIManager.put("Label.foreground", TEXT_MAIN);
        UIManager.put("ScrollPane.background", BG_DARK);
        UIManager.put("Viewport.background", BG_DARK);
        UIManager.put("OptionPane.background", BG_PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT_MAIN);
        UIManager.put("TitledBorder.titleColor", ACCENT);
    }

    private void setUIFont(javax.swing.plaf.FontUIResource f) {
        java.util.Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof javax.swing.plaf.FontUIResource) UIManager.put(key, f);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new BingoGUI());
    }
}
