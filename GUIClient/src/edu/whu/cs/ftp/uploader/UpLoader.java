package edu.whu.cs.ftp.uploader;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.NumberFormat;

import edu.whu.cs.ftp.client.*;

public class UpLoader implements StreamLogging {
    private ControlSocket controlSocket;
    private DataSocket dataSocket;
    private FTPClient ftpClient;
    public ConnectMySQL db = new ConnectMySQL();
    private int id;
    private StatusPublisher publisher;
    private String remoteDir = "/";
    NumberFormat nt = NumberFormat.getPercentInstance();

    public UpLoader(FTPClient ftpClient, ControlSocket controlSocket, StatusPublisher publisher) throws IOException, SQLException {
        this.controlSocket = controlSocket;
        this.ftpClient = ftpClient;
        this.publisher = publisher;
    }

    /*
    上传文件
     */
    public UploadStatus UpLoadFile(Path local_path, FTPPath server_path) throws IOException, SQLException {

        UploadStatus result;
        File localFile = local_path.toFile();
        String serverFileName = localFile.getName();


        logger.info("UploadNewFileStart:" + localFile.getPath() + "-->" + server_path.getPath());

        //处理远程目录
        if(server_path.isDirectory())
        {
            if(!changeWorkingDirectory(server_path.getPath())) {
                logger.info("Wrong Server Path");
                return UploadStatus.SeverPathWrong;
            }
        }

        //检查远程是否存在文件
        FTPPath[] files = list(serverFileName);
        if(files == null)
        {
            db.add(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length()));

            //初始化状态信息
            id = publisher.initialize(localFile.getPath(), server_path.getPath(), StatusPublisher.DIRECTION.UPLOAD, String.valueOf(localFile.length()) + "B");
            result = Start(serverFileName, localFile, ftpClient, server_path);
            if(result == UploadStatus.UploadNewFileSuccess)
            {
                db.delete(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length()));
            }
        }
        else
        {
            long serverFileSize = files[0].getSize();
            long localFileSize = localFile.length();
            //以前不存在上传记录，说明存在同名文件
            if(!db.check(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length())))
            {

                result = UploadStatus.FileExits;
                logger.info("FileExits");
                return result;
            }
            else
            {
                //移动文件内读取指针，实现断点续传
                id = publisher.initialize(localFile.getPath(), server_path.getPath(), StatusPublisher.DIRECTION.UPLOAD, String.valueOf(localFile.length()));
                result = Continue(serverFileName, localFile, ftpClient, serverFileSize);

                //断点续传失败，重新上传
                if(result == UploadStatus.UploadFromBreakFail)
                {
                    logger.info("UploadFromBreakFail,TryAgain");
                    if(!deleteFile(serverFileName))
                    {
                        result = UploadStatus.DeleteServerFail;
                        logger.info("DeleteServerFail");
                        return result;
                    }

                    db.delete(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length()));

                    id = publisher.initialize(localFile.getPath(), server_path.getPath(), StatusPublisher.DIRECTION.UPLOAD, getSize(localFile.length()));

                    result = Start(serverFileName, localFile, ftpClient, server_path);
                }
                else{
                    db.delete(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length()));

                }
            }

        }

        return result;
    }


    /*
    上传整个目录
    */
    public UploadStatus UpLoadDirectory(Path local_path, FTPPath server_path) throws IOException, SQLException {
        File fs = local_path.toFile();

        logger.info("UpLoadDirectory:" + fs.getPath() + "-->" + server_path.getPath());

        if(fs.isFile())
        {
           return UpLoadFile(local_path, server_path);
        }
        else
        {
            server_path = new FTPPath(server_path.getPath(), fs.getName());
//            ftpClient.makeDirectory(server_path.getPath());
            if(!makeDirectory(server_path.getPath())) {
                logger.info("CreateDirectoryFail");
                return UploadStatus.CreateDirectoryFail;
            }

            File[] fi = fs.listFiles();
            for(File f : fi)
            {
                if(f.isFile())
                {
                    UpLoadFile(Paths.get(local_path.toString(), "/" , f.getName()), server_path);
                }
                else
                {
                    UpLoadDirectory(Paths.get(local_path.toString(), "/" , f.getName()), server_path);
                }

            }

            logger.info("UpLoadDirectoryFinish");
            return UploadStatus.UpLoadDirectoryFinish;
        }
    }

    /*
    首次上传
     */
    private UploadStatus Start(String serverFile, File localFile, FTPClient ftpClient, FTPPath server_path) throws IOException {
        UploadStatus status;

        float step = localFile.length();
        float process = 0;
        float localRead = 0;

        dataSocket = controlSocket.execute("STOR " + localFile.getName(), 150);

        RandomAccessFile raf = new RandomAccessFile(localFile, "r");
        BufferedOutputStream out = new BufferedOutputStream(dataSocket.getDataSocket().getOutputStream());

        byte[] buffer = new byte[1024];
        int bytesRead;
        if(step != 0)
        {
            while ((bytesRead = raf.read(buffer)) != -1)
            {
                out.write(buffer, 0, bytesRead);
                localRead += bytesRead;
                if(localRead / step != process)
                {
                    process = localRead / step;

                    nt.setMaximumFractionDigits(2);

                    publisher.publish(id, nt.format(process));

//                    logger.info("UpLoadStatus:" + nt.format(process));
                }

            }
            out.flush();
            raf.close();
            out.close();
        }
        else
        {
            process = 1;
            nt.setMaximumFractionDigits(2);
            publisher.publish(id, nt.format(process));
            logger.info("UpLoadStatus:" + nt.format(process));
        }

        logger.info("UpLoadStatus:" + nt.format(process));

        if(!dataSocket.isClosed()) {
            dataSocket.close();
        }

        boolean result;
        if(process < 1)
        {
            result = false;
        }
        else
        {
            result = true;
            publisher.publish(id, "完成");
        }

        status = result ? UploadStatus.UploadNewFileSuccess : UploadStatus.UploadNewFileFail;

        logger.info(status.toString());
        return status;
    }

    /*
    断点续传
     */
    private UploadStatus Continue(String serverFile, File localFile, FTPClient ftpClient, long serverSize) throws IOException {
        UploadStatus status;

        float step = localFile.length();
        float process = 0;
        float localRead = 0;


        logger.info("UploadFromBreakStart:" + localFile.getPath());

        dataSocket = controlSocket.execute("APPE " + localFile.getName(), 150);

        RandomAccessFile raf = new RandomAccessFile(localFile, "r");
        BufferedOutputStream out = new BufferedOutputStream(dataSocket.getDataSocket().getOutputStream());

        if(serverSize > 0)
        {
            process = serverSize / step;
            raf.seek(serverSize);
            localRead = serverSize;
        }

        if(serverSize == step)
        {
            return UploadStatus.UploadFromBreakSuccess;
        }

        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = raf.read(buffer)) != -1)
        {
            out.write(buffer, 0, bytesRead);
            localRead += bytesRead;
            if(localRead / step != process)
            {
                process = localRead / step;

                nt.setMaximumFractionDigits(2);

                publisher.publish(id, nt.format(process));

//                logger.info("UpLoadStatus:" + nt.format(process));
            }
        }
        out.flush();
        raf.close();
        out.close();

        if(!dataSocket.isClosed()) {
            dataSocket.close();
        }

        boolean result;
        if(process < 1)
        {
            result = false;
        }
        else
        {
            result = true;
            publisher.publish(id, "完成");
        }

        status = result ? UploadStatus.UploadFromBreakSuccess : UploadStatus.UploadFromBreakFail;

        logger.info(status.toString());

        return status;
    }


    public static String getSize(long size) {

        //B为单位
        if (size < 1024) {
            return String.valueOf(size) + "B";
        } else {
            size = size / 1024;
        }

        //以KB为单位
        if (size < 1024) {
            return String.valueOf(size) + "KB";
        } else {
            size = size / 1024;
        }

        if (size < 1024) {
            //以MB为单位
            size = size * 100;
            return String.valueOf((size / 100)) + "."
                    + String.valueOf((size % 100)) + "MB";
        } else {
            //以GB为单位
            size = size * 100 / 1024;
            return String.valueOf((size / 100)) + "."
                    + String.valueOf((size % 100)) + "GB";
        }
    }


    public String[] rawList(String dir) throws IOException {
        DataSocket dataSocket =
                controlSocket.execute("LIST " + dir, 150);
        if (dataSocket == null) return null;
        String[] ret = dataSocket.getTextResponse();
        return (controlSocket.getStatusCode() / 100 != 2) ? null : ret;
    }

    public FTPPath[] list() throws IOException {
        return list(remoteDir);
    }

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

    public Boolean changeWorkingDirectory(String dir) throws IOException {
        controlSocket.execute("CWD " + dir);
        boolean ret = controlSocket.getStatusCode() == 250;
        return getWorkingDirectory() == null ? null : ret;
    }

    public String getWorkingDirectory() throws IOException {
        controlSocket.execute("PWD");
        return controlSocket.getStatusCode() == 257
                ? (remoteDir = controlSocket.getMessage().split("\"")[1])
                : null;
    }

    public Boolean rename(String oldName, String newName) throws IOException {
        controlSocket.execute("RNFR " + oldName);
        if (controlSocket.getStatusCode() != 350)
            return false;
        controlSocket.execute("RNTO " + newName);
        return controlSocket.getStatusCode() == 250;
    }

    public Boolean deleteFile(String path) throws IOException {
        logger.info("Deleting " + path);
        controlSocket.execute("DELE " + path);
        return controlSocket.getStatusCode() == 250;
    }

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

    public Boolean makeDirectory(String path) throws IOException {
        controlSocket.execute("MKD " + path);
        return controlSocket.getStatusCode() == 257;
    }

}
