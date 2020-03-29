package edu.ftp.client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Control Socket for FTP Client.
 */
public class ControlSocket implements StreamLogging {
    private Socket controlSocket;
    private String remoteAddr;
    private BufferedReader reader;
    private BufferedWriter writer;
    private int statusCode;
    private String message;
    private DataSocket dataSocket;

    /**
     * Connect to control port of FTP server. Note that {@link #reader}
     * and {@link #writer} are initialized as well.
     *
     * @param addr FTP server ip address.
     * @param port FTP server control port.
     * @throws IOException .
     */
    public ControlSocket(String addr, int port) throws IOException {
        controlSocket = new Socket(addr, port);
        reader = new BufferedReader(new InputStreamReader(
                controlSocket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(
                controlSocket.getOutputStream(), StandardCharsets.UTF_8));
        remoteAddr = controlSocket.
                getRemoteSocketAddress().toString().split("[/|:]")[1];
        parseResponse("CONN");
    }

    /**
     * Send FTP command and parse response. To get status code
     * and response, try {@link #getStatusCode()} and
     * {@link #getMessage()}.
     *
     * @param command FTP command which doesn't need data socket
     * @throws IOException .
     */
    public void execute(String command) throws IOException {
        execute(command, null);
    }

    /**
     * Send FTP command and parse response. To get status code
     * and response, try {@link #getStatusCode()} and
     * {@link #getMessage()}.
     *
     * @param command FTP command which needs data socket
     * @param dataSocket {@link DataSocket} used for transfer
     * @throws IOException .
     */
    public void execute(String command, DataSocket dataSocket) throws IOException {
        waitForDataSocketClosure();
        writer.write(command);
        writer.write("\r\n");
        writer.flush();
        parseResponse(command);
        if (dataSocket != null) {
            this.dataSocket = dataSocket;
            new Thread(() -> parseAfterTransfer(command)).start();
        }
    }

    private void parseAfterTransfer(String command) {
        while (true) {
            synchronized (this) {
                if (this.dataSocket.isClosed()) {
                    try {
                        parseResponse(command);
                    } catch (IOException e) {
                        logger.severe(e.getMessage());
                    } finally {
                        this.dataSocket = null;
                    }
                    break;
                }
            }
        }
    }

    private void checkDataSocketState() {
        while (true)
            synchronized (this) {
                if (dataSocket == null)
                    break;
                else if (!dataSocket.isClosed())
                    break;
            }
    }

    private void waitForDataSocketClosure() {
        while (true)
            synchronized (this) {
                if (dataSocket == null) break;
            }
    }

    /**
     * Parse response far control socket.
     *
     * @param command FTP command.
     * @throws IOException .
     */
    private void parseResponse(String command) throws IOException {
        StringBuilder messageBuilder = new StringBuilder();
        String ret = reader.readLine();
        logger.info(String.format("[%-4s] %s", command.split(" ")[0], ret));
        messageBuilder.append(ret).append('\n');
        statusCode = Integer.parseInt(ret.substring(0, 3));
        if (ret.charAt(3) == '-')
            do {
                ret = reader.readLine();
                logger.info(String.format("[%-4s] %s", command.split(" ")[0], ret));
                messageBuilder.append(ret).append('\n');
            } while (!ret.startsWith(statusCode + " "));
        message = messageBuilder.toString();
    }

    public String getLocalAddr() {
        return controlSocket.getLocalAddress().getHostAddress();
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public int getLocalPort() {
        return controlSocket.getLocalPort();
    }

    public int getStatusCode() {
        checkDataSocketState();
        return statusCode;
    }

    public String getMessage() {
        checkDataSocketState();
        return message;
    }

    void close() throws IOException {
        controlSocket.close();
    }
}