package com.tuproyecto;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Validar la licencia (basada en huella de hardware, ver HardwareLicenseManager)
            LoginDialog loginDialog = new LoginDialog(null);

            if (!loginDialog.isLoginSuccessful()) {
                System.out.println("Licencia no válida. El programa se cerrará.");
                System.exit(0);
            }

            // Iniciar el Bingo
            BingoGUI bingoApp = new BingoGUI();
            bingoApp.setVisible(true);
        });
    }
}