package UpLoader;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.NumberFormat;

import UpLoader.Controller.State;

public class UpLoader implements StreamLogging{
    private ControlSocket controlSocket;
    private DataSocket dataSocket;
    private FTPClient ftpClient;
    ConnectMySQL db = new ConnectMySQL();
    NumberFormat nt = NumberFormat.getPercentInstance();

    public UpLoader(FTPClient ftpClient, ControlSocket controlSocket) throws IOException, SQLException {
        this.controlSocket = controlSocket;
        this.ftpClient = ftpClient;
    }

    /*
    上传文件
     */
    public UploadStatus UpLoadFile(Path local_path, FTPPath server_path) throws IOException, SQLException {
        UploadStatus result;
        File localFile = local_path.toFile();
        String serverFileName = localFile.getName();

        //处理远程目录
        if(server_path.isDirectory())
        {
            if(!ftpClient.changeWorkingDirectory(server_path.getPath())) {
                logger.info("Wrong Server Path");
                return UploadStatus.SeverPathWrong;
            }
        }

        //检查远程是否存在文件
        FTPPath[] files = ftpClient.list(serverFileName);
        if(files == null)
        {
            db.add(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length()));
            result = Start(serverFileName, localFile, ftpClient);
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
                result = Continue(serverFileName, localFile, ftpClient, serverFileSize);

                //断点续传失败，重新上传
                if(result == UploadStatus.UploadFromBreakFail)
                {
                    if(!ftpClient.deleteFile(serverFileName))
                    {
                        result = UploadStatus.DeleteServerFail;
                        logger.info("DeleteServerFail");
                        return result;
                    }
                    db.delete(localFile.getPath(), server_path.getPath(), String.valueOf(localFile.length()));
                    result = Start(serverFileName, localFile, ftpClient);
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

        if(fs.isFile())
        {
           return UpLoadFile(local_path, server_path);
        }
        else
        {
            server_path = new FTPPath(server_path.getPath(), fs.getName());
//            ftpClient.makeDirectory(server_path.getPath());
            if(!ftpClient.makeDirectory(server_path.getPath())) {
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
    断点续传
     */
    private UploadStatus Continue(String serverFile, File localFile, FTPClient ftpClient, long serverSize) throws IOException {
        UploadStatus status;

        float step = localFile.length();
        float process = 0;
        float localRead = 0;

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
                new State(localFile.getName(), "==>", serverFile, String.valueOf(serverSize), "1", String.valueOf(process*100) + "%");
                nt.setMaximumFractionDigits(2);
                logger.info("上传进度:" + nt.format(process));
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
        }


        status = result ? UploadStatus.UploadFromBreakSuccess : UploadStatus.UploadFromBreakFail;

        logger.info(status.toString());
        return status;
    }


    /*
    首次上传
     */
    private UploadStatus Start(String serverFile, File localFile, FTPClient ftpClient) throws IOException {
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
                    new State(localFile.getName(), "==>", serverFile, String.valueOf(localFile.length()), "normal", String.valueOf(process*100) + '%');
                    nt.setMaximumFractionDigits(2);
                    logger.info("上传进度:" + nt.format(process));
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
            logger.info("上传进度:" + nt.format(process));
        }

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
        }

        status = result ? UploadStatus.UploadNewFileSuccess : UploadStatus.UploadNewFileFail;

        logger.info(status.toString());
        return status;
    }
}
