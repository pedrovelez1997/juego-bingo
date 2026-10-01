package com.tuproyecto;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * BOTÓN DE PRUEBA — Importa tablas de bingo usando la IA de Google (Gemini),
 * pensado para PDFs escaneados con mucha marca de agua que PDFTableLoader
 * (basado en texto real del PDF) no puede leer.
 *
 * No reemplaza ni modifica PDFTableLoader.java: es un camino totalmente
 * aparte. Convierte cada página del PDF en una imagen y le pide a Gemini
 * que devuelva directamente la grilla 5x5 de cada tabla en formato JSON,
 * evitando así tener que "adivinar" la posición de cada número como hace
 * un OCR clásico.
 *
 * Requiere una API key GRATUITA de Google AI Studio (aistudio.google.com),
 * que el usuario pega una sola vez y queda guardada en este equipo.
 */
public class GeminiPDFImporter {

    private static final String KEY_FILE = "gemini_api_key.dat";
    private static final String MODEL = "gemini-3.6-flash"; // modelo Flash vigente, cubierto por el nivel gratuito
    private static final String ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=%s";

    private static final String PROMPT =
        "Analiza esta imagen de una o más tablas de BINGO (cuadrícula 5x5, columnas B-I-N-G-O). " +
        "Cada tabla tiene un código/número de identificación (por ejemplo: 'TABLA No. 0117521', " +
        "'#0117521', 'CÓDIGO: 0117521'). " +
        "Devuelve EXCLUSIVAMENTE un JSON válido, sin texto adicional, sin explicación, sin backticks " +
        "de markdown, con este formato exacto:\n" +
        "[{\"code\":\"0117521\",\"numbers\":[[0,0,41,0,0],[0,29,42,0,0],[10,0,0,0,0],[0,0,39,0,0],[7,16,35,52,70]]}]\n" +
        "Reglas estrictas:\n" +
        "- \"numbers\" es SIEMPRE una matriz de 5 filas x 5 columnas, en el orden de columnas B, I, N, G, O.\n" +
        "- Una casilla vacía, con guion, con la palabra FREE/LIBRE, con un logo, o con cualquier cosa que " +
        "no sea un número, se representa con 0 (cero).\n" +
        "- \"code\" son solo los dígitos del código de la tabla (sin '#', sin 'TABLA No.', sin espacios).\n" +
        "- Incluye TODAS las tablas que veas en la imagen, cada una como un objeto distinto del arreglo.\n" +
        "- No inventes ni adivines números: si una celda no se lee con certeza, ponla en 0.";

    // ===================== MANEJO DE LA API KEY =====================

    public static String loadApiKey() {
        File f = new File(KEY_FILE);
        if (!f.exists()) return null;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            return (String) ois.readObject();
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveApiKey(String key) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(KEY_FILE))) {
            oos.writeObject(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===================== IMPORTACIÓN PRINCIPAL =====================

    /** Se ejecuta en un hilo de fondo (SwingWorker) — hace llamadas de red, no tocar desde el EDT. */
    public static List<PDFTableLoader.BingoTableData> importWithGemini(File pdfFile, String apiKey)
            throws IOException, InterruptedException {
        List<PDFTableLoader.BingoTableData> allTables = new ArrayList<>();
        for (BufferedImage page : renderPdfPages(pdfFile)) {
            String base64Png = encodePng(page);
            String rawResponse = callGemini(base64Png, apiKey);
            allTables.addAll(parseGeminiResponse(rawResponse));
        }
        return allTables;
    }

    private static List<BufferedImage> renderPdfPages(File pdfFile) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                // 200 DPI: buen balance entre legibilidad para la IA y tamaño de la petición
                images.add(renderer.renderImageWithDPI(i, 200));
            }
        }
        return images;
    }

    private static String encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String callGemini(String base64Png, String apiKey) throws IOException, InterruptedException {
        String url = String.format(ENDPOINT, apiKey);
        String body = "{"
            + "\"contents\":[{\"parts\":["
            + "{\"text\":" + MiniJson.quote(PROMPT) + "},"
            + "{\"inline_data\":{\"mime_type\":\"image/png\",\"data\":\"" + base64Png + "\"}}"
            + "]}],"
            + "\"generationConfig\":{\"temperature\":0}"
            + "}";

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(describeHttpError(response.statusCode(), response.body()));
        }
        return response.body();
    }

    private static String describeHttpError(int status, String body) {
        String hint;
        switch (status) {
            case 400: hint = "Solicitud inválida (revise que la clave esté bien copiada)."; break;
            case 403: hint = "La clave de API no es válida o no tiene permiso para este modelo."; break;
            case 429: hint = "Se alcanzó el límite gratuito de solicitudes por minuto/día. Espere un momento e intente de nuevo."; break;
            default:  hint = "Error inesperado del servidor de Gemini.";
        }
        return "Gemini API devolvió el error HTTP " + status + ".\n" + hint
            + "\n\nDetalle técnico:\n" + (body.length() > 400 ? body.substring(0, 400) + "..." : body);
    }

    // ===================== PARSEO DE LA RESPUESTA =====================

    @SuppressWarnings("unchecked")
    private static List<PDFTableLoader.BingoTableData> parseGeminiResponse(String fullResponseJson) throws IOException {
        Object root = MiniJson.parse(fullResponseJson);
        String generatedText;
        try {
            Map<String, Object> rootMap = (Map<String, Object>) root;
            List<Object> candidates = (List<Object>) rootMap.get("candidates");
            Map<String, Object> firstCandidate = (Map<String, Object>) candidates.get(0);
            Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
            List<Object> parts = (List<Object>) content.get("parts");
            Map<String, Object> firstPart = (Map<String, Object>) parts.get(0);
            generatedText = (String) firstPart.get("text");
        } catch (Exception e) {
            throw new IOException("No se pudo interpretar la respuesta de Gemini (formato inesperado).\n" +
                "Respuesta cruda:\n" + fullResponseJson);
        }

        String cleanJson = stripMarkdownFences(generatedText);

        Object parsedTables;
        try {
            parsedTables = MiniJson.parse(cleanJson);
        } catch (Exception e) {
            throw new IOException("Gemini no devolvió un JSON válido de tablas.\nRespuesta:\n" + cleanJson);
        }

        List<PDFTableLoader.BingoTableData> result = new ArrayList<>();
        List<Object> tableList = (List<Object>) parsedTables;
        for (Object tableObj : tableList) {
            Map<String, Object> tableMap = (Map<String, Object>) tableObj;
            String code = String.valueOf(tableMap.get("code")).replaceAll("[^0-9]", "");
            List<Object> rows = (List<Object>) tableMap.get("numbers");

            int[][] numbers = new int[5][5];
            int blanks = 0;
            for (int r = 0; r < 5 && r < rows.size(); r++) {
                List<Object> row = (List<Object>) rows.get(r);
                for (int c = 0; c < 5 && c < row.size(); c++) {
                    int val = ((Number) row.get(c)).intValue();
                    // Validar contra el rango real de esa columna (bingo de 75 bolas:
                    // B 1-15, I 16-30, N 31-45, G 46-60, O 61-75). Si la IA devolvió
                    // un número fuera de rango para esa columna, se descarta (queda
                    // en blanco) en vez de confiar ciegamente en lo que interpretó.
                    int[] range = BingoGUI.COLUMN_RANGES[c];
                    if (val < range[0] || val > range[1]) val = 0;
                    numbers[r][c] = val;
                    if (val == 0) blanks++;
                }
            }
            boolean special = blanks > 1;
            result.add(new PDFTableLoader.BingoTableData(code, numbers, special));
        }
        return result;
    }

    private static String stripMarkdownFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline != -1) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    // ===================== MINI-PARSER JSON (sin dependencias externas) =====================

    /**
     * Parser JSON mínimo y autocontenido — evita depender de Gson/Jackson,
     * que quizás no estén garantizados en el classpath del proyecto.
     * Soporta objetos, arreglos, strings (con escapes), números, booleanos y null.
     */
    private static final class MiniJson {
        private final String s;
        private int i = 0;

        private MiniJson(String s) { this.s = s; }

        static Object parse(String json) {
            MiniJson p = new MiniJson(json);
            p.skipWs();
            Object v = p.parseValue();
            return v;
        }

        static String quote(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append("\"");
            return sb.toString();
        }

        private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object parseValue() {
            skipWs();
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't') { i += 4; return Boolean.TRUE; }
            if (c == 'f') { i += 5; return Boolean.FALSE; }
            if (c == 'n') { i += 4; return null; }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            i++; skipWs();
            if (s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                i++; // ':'
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (s.charAt(i) == ',') { i++; continue; }
                if (s.charAt(i) == '}') { i++; break; }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; skipWs();
            if (s.charAt(i) == ']') { i++; return list; }
            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWs();
                if (s.charAt(i) == ',') { i++; continue; }
                if (s.charAt(i) == ']') { i++; break; }
            }
            return list;
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            i++; // '"'
            while (s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                    char esc = s.charAt(i);
                    switch (esc) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            String hex = s.substring(i + 1, i + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default: sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
                i++;
            }
            i++; // '"'
            return sb.toString();
        }

        private Object parseNumber() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || "+-.eE".indexOf(s.charAt(i)) != -1)) i++;
            String numStr = s.substring(start, i);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }
    }
}
