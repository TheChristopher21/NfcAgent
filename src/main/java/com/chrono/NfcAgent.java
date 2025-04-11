package com.chrono;

import javax.smartcardio.*;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/**
 * ExternalNfcAgent – eigenständiger NFC Agent zum Auslesen, Beschreiben und Übermitteln der Kartendaten.
 *
 * Es gibt zwei Modi:
 *
 * 1) Stamp Mode (Standard):
 *    - Überwacht kontinuierlich den NFC-Leser.
 *    - Liest in Block 1 den gespeicherten Benutzernamen (als Hex-String, in ASCII umgerechnet).
 *    - Sendet per HTTP an das TimeTracking-API einen Punch-Request, der den Ein-/Ausstempelvorgang auslöst.
 *    - Bestimmt anhand der Antwort den neuen Status ("I" für hereinkommen, "O" für rausgehen) und schreibt
 *      diese Information (zusammen mit dem Benutzernamen, auf 16 Zeichen formatiert) zurück in Block 1.
 *
 * 2) Program Mode:
 *    - Wird mit dem Parameter "program" gestartet.
 *    - Dann wird auf eine Karteinlage gewartet und der angegebene Text (oder per Konsole eingegebene) auf die Karte geschrieben.
 */
public class NfcAgent {

    // API-Endpunkt zum Stempeln (Punch-Request)
    private static final String TIME_PUNCH_ENDPOINT = "https://api.chrono-logisch.ch/api/timetracking/punch";

    // Polling-Intervall (ms) für den NFC-Leser
    private static final long POLL_INTERVAL_MS = 1000;
    // Sperrzeit (pro Benutzer) – z.B. 60 Sekunden
    private static final long STAMP_LOCK_MS = 60000;

    // Statt eines globalen Flags und Zeitstempels: Mapping von Benutzername -> letzter Stempelzeitpunkt (in Millisekunden)
    private static Map<String, Long> userLastStampMap = new HashMap<>();

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("program")) {
            runProgramMode(args);
        } else {
            runStampMode();
        }
    }

    /* ---------------- Stamp Mode (Standard) ---------------- */
    private static void runStampMode() {
        System.out.println("Starte NFC Stamp Mode ...");

        while (true) {
            try {
                List<CardTerminal> terminals = TerminalFactory.getDefault().terminals().list();
                if (terminals.isEmpty()) {
                    System.out.println("Kein NFC-Leser gefunden. Warte 3 Sekunden...");
                    Thread.sleep(3000);
                    continue;
                }
                CardTerminal terminal = terminals.get(0);
                if (terminal.isCardPresent()) {
                    // Lese Daten von der Karte
                    String cardHexData = readBlock(1);
                    String username = hexToAscii(cardHexData).trim();
                    if (username.isEmpty()) {
                        System.out.println("Kein Benutzername in der Karte gefunden. Vorgang überspringen.");
                    } else {
                        long now = System.currentTimeMillis();
                        // Hole den letzten Stempelzeitpunkt für diesen Benutzer (falls vorhanden)
                        Long lastStamp = userLastStampMap.get(username);
                        if (lastStamp != null && (now - lastStamp) < STAMP_LOCK_MS) {
                            System.out.println("Benutzer '" + username + "' hat innerhalb der Sperrzeit bereits gestempelt. Vorgang überspringen.");
                        } else {
                            System.out.println("Benutzer '" + username + "' wird gestempelt.");
                            String punchResponse = sendPunch(username);
                            System.out.println("Punch API Antwort: " + punchResponse);
                            String newStatus;
                            if (punchResponse.contains("Work Start")) {
                                newStatus = "I";  // hereinkommen
                            } else if (punchResponse.contains("Work End")) {
                                newStatus = "O";  // rausgehen
                            } else {
                                newStatus = "X";  // unbekannter Status
                            }
                            String newCardDataAscii = formatCardData(username, newStatus);
                            String newCardDataHex = asciiToHex(newCardDataAscii);
                            System.out.println("Neue Kartendaten: " + newCardDataAscii + " (Hex: " + newCardDataHex + ")");
                            String writeResult = writeSector0Block1(newCardDataHex);
                            System.out.println("Ergebnis des Schreibvorgangs: " + writeResult);
                            // Aktualisiere die letzte Stempelzeit für diesen Benutzer
                            userLastStampMap.put(username, now);
                        }
                    }
                } else {
                    // Optional: Entferne Benutzer aus der Map, wenn die Karte entfernt wird
                    // (Das ist optional – eventuell möchtest Du den Sperrzeitraum auch über das Entfernen hinaus gelten lassen)
                    System.out.println("Keine Karte vorhanden.");
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (CardException ce) {
                System.err.println("Fehler beim NFC-Leser: " + ce.getMessage());
                ce.printStackTrace();
                try { Thread.sleep(3000); } catch (InterruptedException ie) {}
            } catch (InterruptedException ie) {
                System.err.println("Agent unterbrochen. Beende...");
                break;
            } catch (Exception e) {
                System.err.println("Fehler im Stamp Mode: " + e.getMessage());
                e.printStackTrace();
                try { Thread.sleep(3000); } catch (InterruptedException ie) {}
            }
        }
    }

    /* ---------------- Program Mode ---------------- */
    private static void runProgramMode(String[] args) {
        // (Program Mode bleibt unverändert – hier geht es nur um das Stempeln.)
        String dataToWrite = (args.length > 1) ? args[1] : "";
        if (dataToWrite.isEmpty()) {
            System.out.println("Kein Programmdaten-Text über Parameter. Bitte eingeben:");
            Scanner scanner = new Scanner(System.in);
            dataToWrite = scanner.nextLine();
        }
        System.out.println("Program Mode: Zu schreibende Daten: '" + dataToWrite + "'");
        String formattedData = formatCardData(dataToWrite, "");
        String hexData = asciiToHex(formattedData);
        System.out.println("Konvertierte Daten (Hex): " + hexData);

        boolean cardProgrammed = false;
        while (!cardProgrammed) {
            try {
                List<CardTerminal> terminals = TerminalFactory.getDefault().terminals().list();
                if (terminals.isEmpty()) {
                    System.out.println("Kein NFC-Leser gefunden. Warte 3 Sekunden...");
                    Thread.sleep(3000);
                    continue;
                }
                CardTerminal terminal = terminals.get(0);
                if (terminal.isCardPresent()) {
                    System.out.println("Karte erkannt. Starte Programmierung ...");
                    String writeResult = writeSector0Block1(hexData);
                    System.out.println("Ergebnis des Schreibvorgangs: " + writeResult);
                    cardProgrammed = true;
                } else {
                    System.out.println("Warte auf Karteinlage...");
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (CardException ce) {
                System.err.println("Fehler beim NFC-Leser: " + ce.getMessage());
                ce.printStackTrace();
                try { Thread.sleep(3000); } catch (InterruptedException ie) {}
            } catch (InterruptedException ie) {
                System.err.println("Agent unterbrochen. Beende...");
                break;
            } catch (Exception e) {
                System.err.println("Fehler im Program Mode: " + e.getMessage());
                e.printStackTrace();
                try { Thread.sleep(3000); } catch (InterruptedException ie) {}
            }
        }
    }

    /* ---------------- HTTP Kommunikation für Stempeln ---------------- */
    private static String sendPunch(String username) {
        try {
            URL url = new URL(TIME_PUNCH_ENDPOINT + "?username=" + username);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            int responseCode = conn.getResponseCode();
            System.out.println("Punch API Response Code: " + responseCode);
            return getResponseString(conn);
        } catch (Exception e) {
            System.err.println("Fehler beim Punch-Request: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    private static String getResponseString(HttpURLConnection conn) {
        try {
            Scanner scanner;
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                scanner = new Scanner(conn.getInputStream(), "UTF-8");
            } else {
                if (conn.getErrorStream() != null) {
                    scanner = new Scanner(conn.getErrorStream(), "UTF-8");
                } else {
                    return "";
                }
            }
            StringBuilder sb = new StringBuilder();
            while (scanner.hasNextLine()) {
                sb.append(scanner.nextLine()).append("\n");
            }
            scanner.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ---------------- NFC-Lese- und Schreibmethoden ---------------- */
    private static String readBlock(int blockNumber) throws Exception {
        TerminalFactory factory = TerminalFactory.getDefault();
        List<CardTerminal> terminals = factory.terminals().list();
        if (terminals.isEmpty()) {
            throw new Exception("Kein NFC-Leser gefunden");
        }
        CardTerminal terminal = terminals.get(0);
        Card card;
        try {
            card = terminal.connect("*");
        } catch (CardNotPresentException cne) {
            throw new Exception("Keine Karte erkannt");
        } catch (Exception e) {
            throw new Exception("Verbindungsfehler mit dem NFC-Reader: " + e.getMessage());
        }

        CardChannel channel = card.getBasicChannel();
        String[] possibleKeys = { "FFFFFFFFFFFF", "A0A1A2A3A4A5" };
        boolean authSuccess = false;
        Exception authException = null;
        for (String key : possibleKeys) {
            try {
                byte[] keyBytes = hexStringToByteArray(key);
                byte[] loadKeyCommand = new byte[] {
                        (byte)0xFF, (byte)0x82, 0x00, 0x00, 0x06,
                        keyBytes[0], keyBytes[1], keyBytes[2], keyBytes[3], keyBytes[4], keyBytes[5]
                };
                ResponseAPDU loadKeyResponse = channel.transmit(new CommandAPDU(loadKeyCommand));
                if (loadKeyResponse.getSW() != 0x9000) {
                    continue;
                }
                byte[] authCommand = new byte[] {
                        (byte)0xFF, (byte)0x86, 0x00, 0x00, 0x05,
                        0x01, 0x00, (byte) blockNumber, 0x60, 0x00
                };
                ResponseAPDU authResponse = channel.transmit(new CommandAPDU(authCommand));
                if (authResponse.getSW() == 0x9000) {
                    authSuccess = true;
                    break;
                } else {
                    authException = new Exception("Authentifizierung fehlgeschlagen mit Key: " + key + " SW: " + Integer.toHexString(authResponse.getSW()));
                }
            } catch (Exception e) {
                authException = e;
            }
        }
        if (!authSuccess) {
            card.disconnect(false);
            if (authException != null) {
                throw authException;
            } else {
                throw new Exception("Keine der Authentifizierungen war erfolgreich!");
            }
        }
        byte[] readCommand = new byte[] {
                (byte)0xFF, (byte)0xB0, 0x00, (byte) blockNumber, 0x10
        };
        ResponseAPDU response = channel.transmit(new CommandAPDU(readCommand));
        card.disconnect(false);
        if (response.getSW() == 0x9000) {
            return bytesToHex(response.getData());
        } else {
            throw new Exception("Fehler beim Lesen des Blocks!");
        }
    }

    private static String writeSector0Block1(String hexData) throws Exception {
        if (hexData.length() != 32) {
            throw new Exception("Ungültige Länge! 32 Hex-Zeichen (16 Bytes) erwartet.");
        }
        int blockNumber = 1;
        TerminalFactory factory = TerminalFactory.getDefault();
        List<CardTerminal> terminals = factory.terminals().list();
        if (terminals.isEmpty()) {
            throw new Exception("Kein NFC-Leser gefunden");
        }
        CardTerminal terminal = terminals.get(0);
        String keyA = "A0A1A2A3A4A5";
        try {
            return writeBlockWithKey(terminal, blockNumber, hexData, keyA, 0x60);
        } catch (Exception e) {
            // Falls nicht, versuche mit Key B
        }
        String keyB = "FFFFFFFFFFFF";
        return writeBlockWithKey(terminal, blockNumber, hexData, keyB, 0x61);
    }

    private static String writeBlockWithKey(CardTerminal terminal, int blockNumber, String hexData, String keyString, int keyCode) throws Exception {
        Card card;
        try {
            card = terminal.connect("*");
        } catch (CardNotPresentException cne) {
            throw new Exception("Keine Karte erkannt");
        } catch (Exception e) {
            throw new Exception("Verbindungsfehler beim Writer: " + e.getMessage());
        }
        CardChannel channel = card.getBasicChannel();
        byte[] keyBytes = hexStringToByteArray(keyString);
        byte[] loadKeyCommand = new byte[] {
                (byte)0xFF, (byte)0x82, 0x00, 0x00, 0x06,
                keyBytes[0], keyBytes[1], keyBytes[2], keyBytes[3], keyBytes[4], keyBytes[5]
        };
        ResponseAPDU loadKeyResponse = channel.transmit(new CommandAPDU(loadKeyCommand));
        if (loadKeyResponse.getSW() != 0x9000) {
            card.disconnect(false);
            throw new Exception("Laden des Schlüssels " + keyString + " fehlgeschlagen! SW: " + Integer.toHexString(loadKeyResponse.getSW()));
        }
        byte[] authCommand = new byte[] {
                (byte)0xFF, (byte)0x86, 0x00, 0x00, 0x05,
                0x01, 0x00, (byte) blockNumber, (byte) keyCode, 0x00
        };
        ResponseAPDU authResponse = channel.transmit(new CommandAPDU(authCommand));
        if (authResponse.getSW() != 0x9000) {
            card.disconnect(false);
            throw new Exception("Authentifizierung fehlgeschlagen mit Key " + keyString + "! SW: " + Integer.toHexString(authResponse.getSW()));
        }
        byte[] dataBytes = hexStringToByteArray(hexData);
        byte[] writeCommand = new byte[21];
        writeCommand[0] = (byte)0xFF;
        writeCommand[1] = (byte)0xD6;
        writeCommand[2] = 0x00;
        writeCommand[3] = (byte) blockNumber;
        writeCommand[4] = 0x10;
        System.arraycopy(dataBytes, 0, writeCommand, 5, 16);
        ResponseAPDU response = channel.transmit(new CommandAPDU(writeCommand));
        card.disconnect(false);
        if (response.getSW() == 0x9000) {
            return "Block " + blockNumber + " erfolgreich mit Key " + keyString + " beschrieben!";
        } else {
            throw new Exception("Fehler beim Schreiben des Blocks mit Key " + keyString + "! SW: " + Integer.toHexString(response.getSW()));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    /* ---------------- Hilfsfunktionen ---------------- */
    /**
     * Konvertiert einen Hex-String in einen ASCII-String.
     */
    private static String hexToAscii(String hex) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            String str = hex.substring(i, i + 2);
            output.append((char) Integer.parseInt(str, 16));
        }
        return output.toString();
    }

    /**
     * Konvertiert einen ASCII-String in einen Hex-String.
     */
    private static String asciiToHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Formatiert einen gegebenen Text (und optional einen Status) zu einem 16-Zeichen langen ASCII-String.
     * Falls der kombinierte String kürzer ist, wird er rechts aufgefüllt, andernfalls abgeschnitten.
     */
    private static String formatCardData(String data, String status) {
        String combined = data + (status.isEmpty() ? "" : " " + status);
        if (combined.length() < 16) {
            combined = String.format("%-16s", combined);
        } else if (combined.length() > 16) {
            combined = combined.substring(0, 16);
        }
        return combined;
    }
}
