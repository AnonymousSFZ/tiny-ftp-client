package edu.whu.cs.ftp.client;

import edu.whu.cs.ftp.downloader.DirSeparator;
import edu.whu.cs.ftp.downloader.DirSeparatorModes;
import edu.whu.cs.ftp.downloader.DownloadException;
import edu.whu.cs.ftp.downloader.Downloader;
import edu.whu.cs.ftp.uploader.UpLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;

/**
 * FTP client implementation for modern FTP servers. Implementations
 * on both RFC 959 and RFC 3659 are required on the server side.
 * Note that no exception will get actually thrown. Most methods
 * will return {@code null} if any exception occurred.
 *
 * @see SimpleFTPClientHandler
 */
public class FTPClientImpl implements FTPClient, StreamLogging {
    @SuppressWarnings("FieldCanBeLocal")
    private String username;
    @SuppressWarnings("FieldCanBeLocal")
    private String password;
    private String remoteDir = "/";
    private ControlSocket controlSocket;

    /**
     * Connect to FTP server. Invoking this method directly is
     * not permitted, since it may throw {@link IOException}
     *
     * @param addr FTP server address
     * @param port FTP server port
     * @throws IOException exception thrown by Socket connection
     */
    private FTPClientImpl(String addr, int port) throws IOException {
        controlSocket = new ControlSocket(addr, port);
    }

    private FTPClientImpl() {}

    /**
     * Login for ftp client. For anonymous login, try
     * <pre>{@code
     * ftp.login("anonymous", "");
     * }</pre>
     *
     * @param user username for ftp account
     * @param pass password for ftp account
     * @return {@code true} if access granted.
     */
    @Override
    public Boolean login(String user, String pass) throws IOException {
        controlSocket.execute("USER " + user);
        controlSocket.execute("PASS " + pass);
        username = user;
        password = pass;
        return controlSocket.getStatusCode() == 230;
    }

    /**
     * Simply quit and close underlying
     * {@link #controlSocket}. After this operation,
     * {@link #controlSocket} will no longer be valid.
     *
     * @throws IOException .
     */
    @Override
    public Boolean quit() throws IOException {
        controlSocket.execute("QUIT");
        if (controlSocket.getStatusCode() != 221)
            return false;
        controlSocket.close();
        return true;
    }

    @Override
    public int getStatusCode() {
        return controlSocket.getStatusCode();
    }

    @Override
    public String getMessage() {
        return controlSocket.getMessage();
    }

    /**
     * Raw response of LIST command. Calling this method
     * directly is strongly discouraged, though some FTP
     * server only support this command, rather than MLSD.
     *
     * @param dir remote directory to list.
     * @return raw response.
     * @throws IOException .
     */
    @Override
    public String[] rawList(String dir) throws IOException {
        DataSocket dataSocket =
                controlSocket.execute("LIST " + dir, 150);
        if (dataSocket == null) return null;
        String[] ret = dataSocket.getTextResponse();
        return (controlSocket.getStatusCode() / 100 != 2) ? null : ret;
    }

    /**
     * Current directory listing with LIST and MLSD.
     *
     * @return Array of {@link FTPPath}, or {@code null}
     * if the server side happen to not support MLSD.
     * @throws IOException .
     * @see FTPPath
     * @see #list(String dir)
     */
    @Override
    public FTPPath[] list() throws IOException {
        return list(remoteDir);
    }

    /**
     * Directory listing with LIST and MLSD. Note that some
     * ancient FTP server may not support MLSD, so good luck.
     *
     * @param dir Remote directory.
     * @return Array of {@link FTPPath}, or {@code null}
     * if the server side happen to not support MLSD.
     * @throws IOException .
     * @see FTPPath
     */
    @Override
    public FTPPath[] list(String dir) throws IOException {
        DataSocket dataSocket =
                controlSocket.execute("MLSD " + dir, 150);
        if (dataSocket == null)
            return null;
        FTPPath[] paths = FTPPath.parseFromMLSD(
                dir, dataSocket.getTextResponse());
        if (controlSocket.getStatusCode() != 226)
            return null;
        String[] res = rawList(dir);
        if (res == null)
            return null;
        for (int i = 0; i < res.length; i++)
            paths[i].addPermission(res[i]);
        return paths;
    }

    /**
     * Change current directory. Syntax like "..",
     * relative path and absolute path are supported as well.
     *
     * @param dir Remote directory.
     * @return {@code false} if directory not found else {@code true}
     */
    @Override
    public Boolean changeWorkingDirectory(String dir) throws IOException {
        controlSocket.execute("CWD " + dir);
        boolean ret = controlSocket.getStatusCode() == 250;
        return getWorkingDirectory() == null ? null : ret;
    }

    @Override
    public String getWorkingDirectory() throws IOException {
        controlSocket.execute("PWD");
        return controlSocket.getStatusCode() == 257
                ? (remoteDir = controlSocket.getMessage().split("\"")[1])
                : null;
    }

    /**
     * Rename file or directory.
     *
     * @param oldName Current name.
     * @param newName New name.
     * @return {@code false} if directory not found, permission denied,
     * or other shits happened else {@code true}.
     */
    @Override
    public Boolean rename(String oldName, String newName) throws IOException {
        controlSocket.execute("RNFR " + oldName);
        if (controlSocket.getStatusCode() != 350)
            return false;
        controlSocket.execute("RNTO " + newName);
        return controlSocket.getStatusCode() == 250;
    }

    @Override
    public Boolean deleteFile(String path) throws IOException {
        logger.info("Deleting " + path);
        controlSocket.execute("DELE " + path);
        return controlSocket.getStatusCode() == 250;
    }

    /**
     * Remove directory recursively.
     *
     * @param path directory path.
     * @return whether successful
     * @throws IOException .
     */
    @Override
    public Boolean removeDirectory(String path) throws IOException {
        FTPPath[] paths = list(path);
        if (paths == null) return false;
        if (paths.length != 0) {
            for (FTPPath ftpPath : paths) {
                String absolutePath = ftpPath.getPath();
                if (ftpPath.isDirectory()
                        ? !removeDirectory(absolutePath)
                        : !deleteFile(absolutePath))
                    logger.warning("Failed to delete " + absolutePath);
            }
        }
        controlSocket.execute("RMD " + path);
        return controlSocket.getStatusCode() == 250;
    }

    @Override
    public Boolean makeDirectory(String path) throws IOException {
        controlSocket.execute("MKD " + path);
        return controlSocket.getStatusCode() == 257;
    }

    @Override
    public void downloadFile(String remotePath, String localPath, StatusPublisher publisher)
            throws IOException, DownloadException {
        String ftpDir = Downloader.parseDirFromString(remotePath, new DirSeparator(DirSeparatorModes.FTP));
        String ftpName = Downloader.parseNameFromString(remotePath, new DirSeparator(DirSeparatorModes.FTP));
        FTPPath remoteFTPPath = new FTPPath(ftpDir, ftpName, 1);

        logger.info("-------StartDownloading-------");

        Downloader downloader = new Downloader(controlSocket, this, publisher);
        downloader.downloadFileOrDirectory(remoteFTPPath, localPath + (localPath.endsWith(File.separator) ?
                "" : File.separator) + ftpName);

        logger.info("-------SuccessfullyDownloaded-------");
    }

    @Override
    public void downloadDirectory(String remotePath, String localPath, StatusPublisher publisher)
            throws IOException, DownloadException {
        String ftpDir = Downloader.parseDirFromString(remotePath, new DirSeparator(DirSeparatorModes.FTP));
        String ftpName = Downloader.parseNameFromString(remotePath, new DirSeparator(DirSeparatorModes.FTP));
        FTPPath remoteFTPPath = new FTPPath(ftpDir, ftpName);

        logger.info("-------StartDownloading-------");

        Downloader downloader = new Downloader(controlSocket, this, publisher);
        downloader.downloadFileOrDirectory(remoteFTPPath, localPath + (localPath.endsWith(File.separator) ?
                "" : File.separator) + ftpName);

        logger.info("-------SuccessfullyDownloaded-------");
    }

    @Override
    public void uploadFile(String localPath, String remotePath, StatusPublisher publisher)
            throws IOException, SQLException {
        UpLoader uploader = new UpLoader(this, controlSocket, publisher);
        Path local_path = Paths.get(localPath);
        File localFile = local_path.toFile();
        String serverFileName = remotePath.trim();
        serverFileName = serverFileName.substring(serverFileName.lastIndexOf("/") + 1);
        FTPPath server_path = new FTPPath(remotePath.substring(0, remotePath.lastIndexOf("/")), "");
        uploader.UpLoadFile(local_path, server_path, serverFileName);
        uploader.db.close();
    }

    @Override
    public void uploadDirectory(String localPath, String remotePath, StatusPublisher publisher)
            throws IOException, SQLException {
        UpLoader uploader = new UpLoader(this, controlSocket, publisher);
        Path local_path = Paths.get(localPath);
        String serverDirectoryName = remotePath.trim();
        serverDirectoryName = serverDirectoryName.substring(serverDirectoryName.lastIndexOf("/") + 1);
        FTPPath server_path = new FTPPath(remotePath.substring(0, remotePath.lastIndexOf("/")), "");
        uploader.UpLoadDirectory(local_path, server_path, serverDirectoryName);
        uploader.db.close();
    }

    @Override
    public void help() throws IOException {
        controlSocket.execute("HELP");
    }

    public static void main(String[] args) throws Throwable {
        final String FTP_SERVER_ADDR = "localhost";
        final String FTP_USERNAME = "anonymous";

        // log to console
        StreamLogging.addLogPublisher(System.out::println);
        // this is not a singleton
        FTPClient ftp = FTPClientFactory
                .newMultiThreadFTPClient(FTP_SERVER_ADDR, 21);
        ftp.login(FTP_USERNAME, "");

//        ftp.getWorkingDirectory();
//        ftp.rename("a", "abcd");
//        System.out.println(Arrays.toString(ftp.list("a.txt")));
//        ftp.removeDirectory("abs");
//        ftp.changeWorkingDirectory("b");

        // downloading demo
        // ONLY for testing: delete downloaded files before
//        File testSavePath = new File("C:\\Users\\zjz42\\Desktop\\plain\\");
//        if (testSavePath.exists()) {
//            String[] entries = testSavePath.list();
//            for (String s : entries) {
//                File currentFile = new File(testSavePath.getPath(), s);
//                currentFile.delete();
//            }
//        }
        // start downloading
        StatusPublisher dullStatusPublisher = new StatusPublisher() {
            @Override
            public int initialize(String localPath, String remotePath, DIRECTION direction, String size) {
                return 0;
            }

            @Override
            public void publish(int id, String status) {
            }
        };
        for (int i = 0; i < 10; i++) {
            ftp.downloadDirectory(
                    "/a",
                    "downloads", dullStatusPublisher);
        }
//        ftp.uploadFile("downloads/a.txt", "/", dullStatusPublisher);
//        Thread.sleep(60000);
        ftp.quit();
    }
}