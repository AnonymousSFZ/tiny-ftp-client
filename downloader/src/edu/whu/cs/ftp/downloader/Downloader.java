package edu.whu.cs.ftp.downloader;

import edu.whu.cs.ftp.client.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Downloader implements StreamLogging {
    private final ControlSocket controlSocket;
    private final FTPClient ftpClient;
    private DownloadExpectedStatusCodes expectedStatusCodes;
//    private DownloadingStates downloadingStates; // TODO: thread to refresh

    public Downloader(ControlSocket controlSocket, FTPClient ftpClient) {
        this.controlSocket = controlSocket;
        this.ftpClient = ftpClient;
        this.expectedStatusCodes = new DownloadExpectedStatusCodes();
    }

    // assume in passive mode (PASV):
    // client:N & N+1 (N > 1024) -> client:N--control socket--server:21
    // -> client send PASV -> server:P (P>1024)
    // -> client get "227 entering passive mode (h1,h2,h3,h4,p1,p2)" -> h1.h2.h3.h4:p1*256+p2
    // -> client:N+1--data socket--server:P (already in this step when get DataSocket)
    // CWD -> SIZE -> REST -> RETR
    public void downloadFile(FTPPath downloadFrom, String saveTo) throws DownloadException, IOException {
        FileInfo fileInfo = new FileInfo();
        checkRemoteFile(downloadFrom, fileInfo);
        checkLocalPath(saveTo, fileInfo);

        DataSocket ftpDataSocket;
        if (fileInfo.downloadedByteNum > 0) {
            // REST must be executed right before RETR
            ftpDataSocket = execFTPCommand("RETR", fileInfo.serverFileName,
                    "REST", String.valueOf(fileInfo.downloadedByteNum));
        } else {
            ftpDataSocket = (DataSocket) execFTPCommand("RETR", fileInfo.serverFileName, true);
        }

        Socket dataSocket = ftpDataSocket.getDataSocket();

        File tempFilePath = new File(fileInfo.localFilePath + ".ftpdownloading");
        FileOutputStream tempFile;
        if (fileInfo.downloadedByteNum > 0) {
            tempFile = new FileOutputStream(tempFilePath, true);
        } else {
            tempFile = new FileOutputStream(tempFilePath);
        }

        InputStream readFromServer = dataSocket.getInputStream();
        BufferedOutputStream tempFileBufferedStream = new BufferedOutputStream(tempFile);
        readFromServer.transferTo(tempFileBufferedStream);
        tempFileBufferedStream.flush();

        tempFileBufferedStream.close(); // as well as underlying FileOutputStream tempFile
        readFromServer.close();
        ftpDataSocket.close();

        Files.move(tempFilePath.toPath(), Path.of(saveTo));
    }

    public void downloadDirectory(FTPPath serverPath, FTPPath savePath) throws DownloadException {
    }

    private static String parseDirFromString(String fullPath, dirSeparator separator) {
        String[] parts = fullPath.split(String.valueOf(separator.getCodingSeparator()));
        StringBuilder dir = new StringBuilder();
        int len = parts.length;
        for (int i = 0; i + 1 < len; ++i) {
            dir.append(parts[i]);
            dir.append(separator);
        }

        return dir.toString();
    }

    private static String parseNameFromString(String fullPath, dirSeparator separator) {
        String[] parts = fullPath.split(separator.getCodingSeparator());

        return parts[parts.length - 1];
    }

    // DataSocket / String message, depending on parameter getDataSocket
    private Object execFTPCommand(String cmd, String arg, boolean getDataSocket) throws DownloadException, IOException {
        String command = cmd + ' ' + arg;
        if (getDataSocket) {
            DataSocket dataSocket = controlSocket.execute(command, expectedStatusCodes.getStatusCode(cmd));
            if (dataSocket == null) {
                throw new FTPCommandFailedException(cmd, arg, null);
            }
            return dataSocket;
        } else {
            controlSocket.execute(command);
            String statusMessage = controlSocket.getMessage();
            int statusCode = Integer.parseInt(statusMessage.substring(0, 3));
            if (statusCode != expectedStatusCodes.getStatusCode(cmd)) {
                throw new FTPCommandFailedException(cmd, arg, statusMessage);
            }
            return statusMessage;
        }
    }

    // REST must be executed right before RETR, without PASV in between
    private DataSocket execFTPCommand(String cmd, String arg, String preSimpleCmd, String preSimpleArg)
            throws DownloadException, IOException {
        String preSimpleCommand = preSimpleCmd + ' ' + preSimpleArg;
        String command = cmd + ' ' + arg;
        DataSocket dataSocket = controlSocket.execute(command, expectedStatusCodes.getStatusCode(cmd), preSimpleCommand);
        if (dataSocket == null) {
            throw new FTPCommandFailedException(cmd, arg, null);
        }

        return dataSocket;
    }

    private String execFTPCommand(String cmd, String arg, String groupedRegex, int regexGroupIndex)
            throws DownloadException, IOException {
        String statusMessage = (String) execFTPCommand(cmd, arg, false);
        Pattern pattern = Pattern.compile(groupedRegex);
        Matcher matcher = pattern.matcher(statusMessage);
        if (matcher.matches()) {
            return matcher.group(regexGroupIndex);
        } else {
            throw new ParseStatusMessageFailed(statusMessage, groupedRegex, regexGroupIndex);
        }
    }

    private long getServerFileSize(String serverFileName, FTPPath serverPath) throws DownloadException, IOException {
        long serverFileByteNum = 0;

        String ret = execFTPCommand("SIZE", serverFileName, "(\\d+)(\\s)(\\d+)(\\s+)", 3);
        serverFileByteNum = ret.equals("0") ? -1 : Long.parseLong(ret);
        if (serverFileByteNum == -1) {
            throw new ServerFileNotExistsException(serverPath);
        }

        return serverFileByteNum;
    }

    // check existence and get size of remote file
    private void checkRemoteFile(FTPPath remotePath, FileInfo fileInfo)
            throws DownloadException, IOException {
        // change working dir
        if (remotePath.getName().contains(" ")) {
            fileInfo.serverFileName = "\"" + remotePath.getName() + "\"";
        } else {
            fileInfo.serverFileName = remotePath.getName();
        }
        fileInfo.serverFileDir = parseDirFromString(remotePath.getPath(), new dirSeparator(dirSeparatorModes.FTP));
        if (!ftpClient.getWorkingDirectory().equals(fileInfo.serverFileDir)) {
            ftpClient.changeWorkingDirectory(fileInfo.serverFileDir);
        }

        // check if remote file exists and get SIZE
        fileInfo.serverFileByteNum = getServerFileSize(fileInfo.serverFileName, remotePath);
    }

    // check if local path available to save and collect related info
    private void checkLocalPath(String localPath, FileInfo fileInfo) throws DownloadException {
        // check if local path is already occupied
        File pathOccupied = new File(localPath);
        if (pathOccupied.exists()) {
            throw new LocalPathOccupiedException(localPath);
        }

        // collect path info and check dir
        fileInfo.localFileName = parseNameFromString(localPath, new dirSeparator(dirSeparatorModes.LocalMachine));
        fileInfo.localFileDir = parseDirFromString(localPath, new dirSeparator(dirSeparatorModes.LocalMachine));
        fileInfo.localFilePath = fileInfo.localFileDir + fileInfo.localFileName;
        if (!new File(fileInfo.localFileDir).exists()) {
            throw new SaveDirNotExistsException(fileInfo.localFileDir);
        }

        // check if enough local space and downloaded before
        File downloadedFile = new File(localPath + ".ftpdownloading");
        fileInfo.downloadedByteNum = 0;
        if (downloadedFile.exists()) {
            fileInfo.downloadedByteNum = downloadedFile.length();
        }
        long restByteNum = fileInfo.serverFileByteNum - fileInfo.downloadedByteNum;

        // check if have enough space
        long availableByteNum = new File(fileInfo.localFileDir).getUsableSpace();
        if (restByteNum >= availableByteNum) {
            throw new NoEnoughSpaceException(localPath, restByteNum, availableByteNum);
        }
    }

    public static void main(String[] args) {
        System.out.println("Unit testing: the downloading module of FTP client");
    }
}

class FileInfo {
    public String serverFileName;
    public String serverFileDir;
    public String localFileName;
    public String localFileDir;
    public String localFilePath;
    public long serverFileByteNum;
    public long downloadedByteNum;
}

enum dirSeparatorModes {
    FTP,
    Unix,
    Windows,
    LocalMachine
}

class dirSeparator {
    private String separator;

    public dirSeparator(dirSeparatorModes mode) {
        switch (mode) {
            case FTP, Unix -> separator = "/";
            case Windows -> separator = "\\\\"; // code-level representation
            case LocalMachine -> separator = File.separator.equals("\\") ? "\\\\" : File.separator;
        }
    }

    public String getCodingSeparator() {
        return separator;
    }

    @Override
    public String toString() { // real representation
        return separator.equals("\\\\") ? "\\" : separator;
    }
}
