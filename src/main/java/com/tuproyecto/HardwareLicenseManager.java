package com.tuproyecto;

import java.io.*;
import java.net.NetworkInterface;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Set;

public class HardwareLicenseManager {

    private static final String LICENSE_FILE = "bingo_license.dat";
    private static final String USED_LICENSES_FILE = "bingo_used_licenses.dat";

    /**
     * Clave PÚBLICA RSA (2048 bits), en formato X.509/DER codificado en Base64.
     * Esto SÍ es seguro de distribuir dentro del programa: solo sirve para
     * VERIFICAR firmas, no para crearlas. Las licencias se generan aparte,
     * con la clave PRIVADA correspondiente, usando la herramienta separada
     * LicenseGenerator (que NUNCA debe incluirse en el .jar que se reparte).
     *
     * Si algún día regenera el par de claves, reemplace este valor por la
     * nueva clave pública Y regenere licencias nuevas para sus clientes
     * (las licencias firmadas con la clave privada anterior dejarán de
     * validar con una clave pública distinta).
     */
    private static final String PUBLIC_KEY_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtL+U6/OCKXp74a8I4bgr" +
        "YJpO+8GznJpEACBLrV2bidng/r7I+DoHuJeR/xLNqMoHGegXHECfmopj5V+PF3be" +
        "fxW08oulCsGWWzYW0i6y11FnNZkchhDixGEKLPr/fEveAgY/eqbz+WPIfXhetKDi" +
        "6WOKzTHrkrXQ9THLLNKnFP6LCcKOOE0t+ltF0qPpoYXE+K4kpvqvoWsn4fFLbqgM" +
        "BJWsZDbnBtP+K+y5/lDVjn/osEgL0qPGTL/8tshyKnRlmXhtiOUMRpRnTZJtkaHc" +
        "0nu6oPqgj167ujQ7s2kETA5lgalh9/xGSv3+pRdEkvtTOkAbSqbfPpDgDyGO7SLi" +
        "kQIDAQAB";

    private static PublicKey loadPublicKey() throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(PUBLIC_KEY_B64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    /** Verifica la firma RSA de "hardwareCode|expirationStr" contra la clave pública embebida. */
    private static boolean verifyRsaSignature(String hardwareCode, String expirationStr, String signatureB64) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(signatureB64);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(loadPublicKey());
            sig.update((hardwareCode + "|" + expirationStr).getBytes());
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    // Nombres/keywords típicos de adaptadores de red VIRTUALES (VPNs, máquinas
    // virtuales, contenedores, Bluetooth, túneles) que NO deben usarse para el
    // fingerprint, porque aparecen/desaparecen o cambian de MAC sin que el
    // cliente haga nada raro, invalidando licencias válidas por accidente.
    private static final String[] VIRTUAL_ADAPTER_KEYWORDS = {
        "vmware", "virtualbox", "vbox", "docker", "veth", "hyper-v", "vethernet",
        "tap", "tun", "vpn", "bluetooth", "teredo", "isatap", "ppp", "loopback",
        "virtual", "npcap", "wsl", "hamachi", "zerotier", "tailscale", "wireguard"
    };

    private static boolean isVirtualAdapter(NetworkInterface network) {
        try {
            if (network.isVirtual() || network.isLoopback() || network.isPointToPoint()) return true;
            String name = (network.getName() + " " + network.getDisplayName()).toLowerCase();
            for (String keyword : VIRTUAL_ADAPTER_KEYWORDS) {
                if (name.contains(keyword)) return true;
            }
            return false;
        } catch (Exception e) {
            return true; // ante la duda, no la usamos para el fingerprint
        }
    }

    // Obtener código único del hardware de esta computadora
    public static String getHardwareCode() {
        try {
            StringBuilder hardwareInfo = new StringBuilder();

            // Recolectar solo tarjetas de red FÍSICAS reales (se descartan VPNs,
            // máquinas virtuales, Bluetooth, Docker, etc. — ver isVirtualAdapter).
            // Se ordenan por nombre para que el resultado sea siempre el mismo
            // sin importar el orden en que el sistema operativo las reporte.
            java.util.List<NetworkInterface> physicalAdapters = new java.util.ArrayList<>();
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                byte[] mac = network.getHardwareAddress();
                if (mac != null && !isVirtualAdapter(network)) {
                    physicalAdapters.add(network);
                }
            }
            physicalAdapters.sort(java.util.Comparator.comparing(NetworkInterface::getName));

            // Se usa solo la PRIMERA tarjeta física (no todas): así, conectar o
            // desconectar un segundo adaptador (ej. un dock USB-Ethernet) no
            // cambia el código si la tarjeta principal sigue siendo la misma.
            if (!physicalAdapters.isEmpty()) {
                byte[] mac = physicalAdapters.get(0).getHardwareAddress();
                for (byte b : mac) {
                    hardwareInfo.append(String.format("%02X", b));
                }
            }

            // Información del sistema (solo el sistema operativo; el usuario de
            // Windows NO se incluye: no es un dato de hardware y cambiaría el
            // código si el cliente crea otra cuenta de usuario en la misma PC).
            hardwareInfo.append(System.getProperty("os.name"));

            // Generar un código único
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(hardwareInfo.toString().getBytes());
            
            return Base64.getEncoder().encodeToString(hash).substring(0, 32);
            
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR-" + System.currentTimeMillis();
        }
    }
    
    // Verificar si una licencia es válida
    public static boolean verifyLicense(String licenseKey) {
        try {
            if (licenseKey == null || licenseKey.isEmpty()) {
                return false;
            }
            
            // Decodificar la licencia
            String decodedLicense = new String(Base64.getDecoder().decode(licenseKey));
            String[] parts = decodedLicense.split("\\|");
            
            if (parts.length != 3) {
                return false;
            }
            
            String hardwareCode = parts[0];
            String expirationStr = parts[1];
            String providedSignature = parts[2];
            
            // Verificar que el hardware coincide
            String currentHardwareCode = getHardwareCode();
            if (!hardwareCode.equals(currentHardwareCode)) {
                return false;
            }
            
            // Verificar que no haya expirado
            LocalDateTime expiration = LocalDateTime.parse(expirationStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (LocalDateTime.now().isAfter(expiration)) {
                return false;
            }
            
            // Verificar la firma RSA (solo la clave PRIVADA, que nunca viaja con el
            // programa, pudo haber generado una firma válida para este contenido)
            return verifyRsaSignature(hardwareCode, expirationStr, providedSignature);
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Verifica que la firma de la licencia sea válida y corresponda a este equipo,
     * SIN comprobar la fecha de expiración. Útil para distinguir "expirada" de "inválida".
     */
    public static boolean isLicenseSignatureValid(String licenseKey) {
        try {
            if (licenseKey == null || licenseKey.isEmpty()) {
                return false;
            }

            String decodedLicense = new String(Base64.getDecoder().decode(licenseKey));
            String[] parts = decodedLicense.split("\\|");

            if (parts.length != 3) {
                return false;
            }

            String hardwareCode = parts[0];
            String expirationStr = parts[1];
            String providedSignature = parts[2];

            String currentHardwareCode = getHardwareCode();
            if (!hardwareCode.equals(currentHardwareCode)) {
                return false;
            }

            return verifyRsaSignature(hardwareCode, expirationStr, providedSignature);
        } catch (Exception e) {
            return false;
        }
    }

    /** Devuelve la fecha/hora de expiración codificada en la licencia, o null si no se puede leer. */
    public static LocalDateTime getExpiration(String licenseKey) {
        try {
            if (licenseKey == null || licenseKey.isEmpty()) {
                return null;
            }
            String decodedLicense = new String(Base64.getDecoder().decode(licenseKey));
            String[] parts = decodedLicense.split("\\|");
            if (parts.length != 3) {
                return null;
            }
            return LocalDateTime.parse(parts[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** Indica si la licencia ya expiró (independientemente de si la firma es válida). */
    public static boolean isExpired(String licenseKey) {
        LocalDateTime exp = getExpiration(licenseKey);
        if (exp == null) return false;
        return LocalDateTime.now().isAfter(exp);
    }

    /**
     * Verifica si una licencia (por su cadena exacta) ya fue usada y marcada como
     * "consumida" anteriormente, para que no pueda activarse de nuevo una vez expirada.
     */
    @SuppressWarnings("unchecked")
    public static boolean isLicenseUsed(String licenseKey) {
        Set<String> used = loadUsedLicenses();
        return used.contains(licenseKey);
    }

    /** Marca una licencia como usada/consumida para que no se pueda volver a activar. */
    public static void markLicenseUsed(String licenseKey) {
        Set<String> used = loadUsedLicenses();
        used.add(licenseKey);
        try (FileOutputStream fos = new FileOutputStream(USED_LICENSES_FILE);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(used);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadUsedLicenses() {
        File file = new File(USED_LICENSES_FILE);
        if (!file.exists()) {
            return new java.util.HashSet<>();
        }
        try (FileInputStream fis = new FileInputStream(USED_LICENSES_FILE);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (Set<String>) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return new java.util.HashSet<>();
        }
    }
    
    // Guardar la licencia en el disco
    public static void saveLicense(String licenseKey) {
        try (FileOutputStream fos = new FileOutputStream(LICENSE_FILE);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(licenseKey);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    // Cargar la licencia guardada
    public static String loadLicense() {
        File file = new File(LICENSE_FILE);
        if (!file.exists()) {
            return null;
        }
        
        try (FileInputStream fis = new FileInputStream(LICENSE_FILE);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            return (String) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
}