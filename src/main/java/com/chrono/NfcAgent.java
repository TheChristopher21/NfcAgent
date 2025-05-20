package com.chrono;

import javax.smartcardio.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import org.json.JSONObject;
import javazoom.jl.player.Player;  // JLayer zum Abspielen von MP3-Dateien

/**
 * ExternalNfcAgent – Ein NFC-Agent, der im Stamp Mode (Stempeln) und im Program Mode (Programmieren) arbeitet.
 *
 * Der Agent läuft standardmäßig im Stamp Mode und wechselt nur dann in den Programmiermodus,
 * wenn über das Frontend ein neuer Programmierbefehl (Typ "PROGRAM") ausgelöst wurde.
 *
 * Nach einem erfolgreichen Stempel- oder Programmiervorgang wird ein Sound abgespielt.
 *
 * Der Agent-Token wird als System-Property gelesen (über JVM-Option -Dnfc.agent.token).
 */
public class NfcAgent {

    private static final String AGENT_TOKEN = System.getProperty("nfc.agent.token", "SUPER-SECRET-AGENT-TOKEN");
    private static final String NFC_COMMAND_ENDPOINT = "https://api.chrono-logisch.ch/api/nfc/command";
    private static final String TIME_PUNCH_ENDPOINT = "https://api.chrono-logisch.ch/api/timetracking/punch";
    private static final long POLL_INTERVAL_MS = 1000;
    private static boolean cardProcessed = false;
    private static final long STAMP_COOLDOWN_MS = 60000;
    private static final long PROGRAM_LOCK_MS = 10000;
    private static Map<String, Long> lastStampLockUntil = new HashMap<>();
    private static Map<String, Long> lastStampTimes = new HashMap<>();

    private static boolean programCommandActive = false;
    private static String pendingProgramData = "";
    private static long pendingCommandId = -1;
    private static long lastProcessedCommandId = -1;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("AGENT_TOKEN = " + AGENT_TOKEN);
        if (args.length > 0 && args[0].equalsIgnoreCase("program")) {
            runProgramModeForCommand((args.length > 1) ? args[1] : "", -1);
        } else {
            runStampMode();
        }
    }

    private static void runStampMode() throws InterruptedException {
        System.out.println("Starte NFC Stamp Mode ...");
        while (true) {
            try {
                if (!programCommandActive) {
                    JSONObject command = fetchPendingCommand();
                    if (command != null && "PROGRAM".equalsIgnoreCase(command.optString("type"))) {
                        long commandId = command.optLong("id", -1);
                        if (commandId != lastProcessedCommandId) {
                            pendingProgramData = command.optString("data", "");
                            pendingCommandId = commandId;
                            programCommandActive = true;
                            System.out.println("Neuer Programmierbefehl empfangen: Daten = '" + pendingProgramData + "', ID = " + pendingCommandId);
                        }
                    }
                }
                List<CardTerminal> terminals = TerminalFactory.getDefault().terminals().list();
                if (terminals.isEmpty()) {
                    System.out.println("Kein NFC-Leser gefunden. Warte 3 Sekunden...");
                    Thread.sleep(3000);
                    continue;
                }
                CardTerminal terminal = terminals.get(0);
                if (programCommandActive) {
                    if (terminal.isCardPresent()) {
                        System.out.println("Bitte entfernen Sie die Karte, um in den Programmiermodus zu wechseln.");
                        Thread.sleep(POLL_INTERVAL_MS);
                        continue;
                    }
                    System.out.println("Warte auf Karteinlage im Programmiermodus...");
                    runProgramModeForCommand(pendingProgramData, pendingCommandId);
                    lastProcessedCommandId = pendingCommandId;
                    // Setze Sperrphase für den programmierten Benutzer
                    String userForLock = pendingProgramData.trim();
                    lastStampLockUntil.put(userForLock, System.currentTimeMillis() + PROGRAM_LOCK_MS);
                    programCommandActive = false;
                    pendingProgramData = "";
                    pendingCommandId = -1;
                    continue;
                }
                if (terminal.isCardPresent()) {
                    if (!cardProcessed) {
                        System.out.println("Karte erkannt. Starte Stempelvorgang ...");
                        String cardHexData = readBlock(1);
                        String cardData = hexToAscii(cardHexData).trim();
                        String username = cardData.split(" ")[0];
                        System.out.println("Aus Karte gelesener Username: '" + username + "'");
                        if (!username.isEmpty()) {
                            long currentTime = System.currentTimeMillis();
                            if (lastStampLockUntil.containsKey(username)) {
                                long lockUntil = lastStampLockUntil.get(username);
                                if (currentTime < lockUntil) {
                                    long remainingSec = (lockUntil - currentTime) / 1000;
                                    System.out.println("Stempeln von '" + username + "' ist gesperrt. Bitte warten Sie " + remainingSec + " Sekunden.");
                                    Thread.sleep(POLL_INTERVAL_MS);
                                    continue;
                                }
                            }
                            if (lastStampTimes.containsKey(username)) {
                                long lastTime = lastStampTimes.get(username);
                                if (currentTime - lastTime < STAMP_COOLDOWN_MS) {
                                    long remainingSec = (STAMP_COOLDOWN_MS - (currentTime - lastTime)) / 1000;
                                    System.out.println("Stempeln von '" + username + "' ist noch gesperrt. Bitte warten Sie " + remainingSec + " Sekunden.");
                                    Thread.sleep(POLL_INTERVAL_MS);
                                    continue;
                                }
                            }
                            lastStampTimes.put(username, currentTime);
                            String punchResponse = sendPunch(username);
                            System.out.println("Punch API Antwort: " + punchResponse);
                            String newStatus = punchResponse.contains("Work Start") ? "I" : punchResponse.contains("Work End") ? "O" : "X";
                            String newCardDataAscii = formatCardData(username, newStatus);
                            String newCardDataHex = asciiToHex(newCardDataAscii);
                            System.out.println("Neue Kartendaten: " + newCardDataAscii + " (Hex: " + newCardDataHex + ")");
                            String writeResult = writeSector0Block1(newCardDataHex);
                            System.out.println("Ergebnis des Schreibvorgangs: " + writeResult);
                            // Setze den Cooldown für den Stempelvorgang
                            lastStampLockUntil.put(username, System.currentTimeMillis() + STAMP_COOLDOWN_MS);
                            playSound();
                        } else {
                            System.out.println("Kein Benutzername in der Karte gefunden. Vorgang überspringen.");
                        }
                        cardProcessed = true;
                    } else {
                        System.out.println("Karte wurde bereits verarbeitet. Warte auf Entfernung.");
                    }
                } else {
                    if (cardProcessed) {
                        System.out.println("Karte entfernt. Bereit für neuen Vorgang.");
                        cardProcessed = false;
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (CardException ce) {
                System.err.println("Fehler beim NFC-Leser: " + ce.getMessage());
                ce.printStackTrace();
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                System.err.println("Agent unterbrochen. Beende...");
                break;
            } catch (Exception e) {
                System.err.println("Fehler im Stamp Mode: " + e.getMessage());
                e.printStackTrace();
                Thread.sleep(3000);
            }
        }
    }

    private static void runProgramModeForCommand(String dataToWrite, long commandId) throws InterruptedException {
        System.out.println("Program Mode: Zu schreibende Daten: '" + dataToWrite + "'");
        String formattedData = formatCardData(dataToWrite, "");
        String hexData = asciiToHex(formattedData);
        System.out.println("Konvertierte Daten (Hex): " + hexData);

        boolean cardProgrammed = false;
        long startTime = System.currentTimeMillis();
        while (!cardProgrammed) {
            try {
                if (System.currentTimeMillis() - startTime > 10000) {
                    System.out.println("Timeout: Keine Karte innerhalb von 10 Sekunden erkannt. Markiere Befehl als done und beende den Programmiermodus.");
                    if (commandId != -1) {
                        updateCommandStatus(commandId, "done");
                    }
                    break;
                }
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
                    if (commandId != -1) {
                        updateCommandStatus(commandId, "done");
                    }
                    playSound();
                } else {
                    System.out.println("Warte auf Karteinlage im Program Mode...");
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (CardException ce) {
                System.err.println("Fehler beim NFC-Leser (Program Mode): " + ce.getMessage());
                ce.printStackTrace();
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                System.err.println("Agent unterbrochen im Program Mode. Beende...");
                break;
            } catch (Exception e) {
                System.err.println("Fehler im Program Mode: " + e.getMessage());
                e.printStackTrace();
                Thread.sleep(3000);
            }
        }
        System.out.println("Program Mode abgeschlossen. Wechsel zurück in den Stamp Mode.");
    }

    // Spielt den Sound aus der eingebetteten Ressource ab.
    private static void playSound() {
        new Thread(() -> {
            try (BufferedInputStream buffer = new BufferedInputStream(
                    NfcAgent.class.getResourceAsStream("/com/chrono/sounds/stamp.mp3"))) {
                if (buffer == null) {
                    System.err.println("Sound-Datei '/com/chrono/sounds/stamp.mp3' nicht gefunden!");
                    return;
                }
                Player player = new Player(buffer);
                player.play();
            } catch (Exception e) {
                System.err.println("Fehler beim Abspielen des Sounds: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private static String sendPunch(String username) {
        try {
            username = username.trim();
            String encodedUsername = URLEncoder.encode(username, StandardCharsets.UTF_8.name());
            URL url = new URL(TIME_PUNCH_ENDPOINT + "?username=" + encodedUsername);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(false);
            int responseCode = conn.getResponseCode();
            System.out.println("Punch API Response Code: " + responseCode);
            return getResponseString(conn);
        } catch (Exception e) {
            System.err.println("Fehler beim Punch-Request: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    private static JSONObject fetchPendingCommand() {
        try {
            URL url = new URL(NFC_COMMAND_ENDPOINT);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(3000); // max 3 Sekunden warten
            con.setReadTimeout(3000);

            int status = con.getResponseCode();
            if (status == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                String inputLine;
                StringBuilder content = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    content.append(inputLine);
                }
                in.close();
                con.disconnect();
                return new JSONObject(content.toString());
            } else {
                con.disconnect();
                return null;
            }
        } catch (ConnectException | SocketTimeoutException ce) {
            System.out.println("⚠️ Backend nicht erreichbar. Versuche erneut...");
        } catch (Exception e) {
            System.out.println("❌ Allgemeiner Fehler bei der Verbindung: " + e.getMessage());
        }
        return null;
    }

    private static void updateCommandStatus(long id, String status) {
        try {
            URL url = new URL(NFC_COMMAND_ENDPOINT + "/" + id + "?status=" + status);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            System.out.println("Sende X-Agent-Token: " + AGENT_TOKEN);
            conn.setRequestProperty("X-Agent-Token", AGENT_TOKEN);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int responseCode = conn.getResponseCode();
            System.out.println("Update Command Response Code: " + responseCode);
            String response = getResponseString(conn);
            System.out.println("Update Command Response: " + response);
        } catch (Exception e) {
            System.err.println("Fehler beim Aktualisieren des Befehls: " + e.getMessage());
            e.printStackTrace();
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

    private static String readBlock(int blockNumber) throws Exception {
        TerminalFactory factory = TerminalFactory.getDefault();
        List<CardTerminal> terminals = factory.terminals().list();
        if (terminals.isEmpty()) {
            System.out.println("Kein NFC-Leser gefunden. Verwende Simulationswert.");
            return "0102030405060708090A0B0C0D0E0F10";
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
            String result = writeBlockWithKey(terminal, blockNumber, hexData, keyA, 0x60);
            return result;
        } catch (Exception e) {
            // Falls Key A nicht funktioniert, versuche Key B
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

    private static String hexToAscii(String hex) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            String str = hex.substring(i, i + 2);
            output.append((char) Integer.parseInt(str, 16));
        }
        return output.toString();
    }

    private static String asciiToHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

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
