package com.share.ops.file.service;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.share.common.api.ErrorCode;
import com.share.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 远端文件客户端（FR-09）：SFTP / FTP / FTPS 上传与下载。
 * <p>用于文件收发任务在 LOCAL 与远端之间搬运文件；连接参数由任务配置提供，
 * 口令在库中 AES-GCM 加密，传输层建议同时启用 FTPS 或 SFTP。</p>
 */
@Slf4j
@Service
public class RemoteFileService {

    /** 远端连接配置。 */
    public record RemoteConfig(String type, String host, int port, String username, String password) {
    }

    public boolean isRemote(String type) {
        if (type == null) {
            return false;
        }
        String t = type.trim().toUpperCase(Locale.ROOT);
        return "SFTP".equals(t) || "FTP".equals(t) || "FTPS".equals(t);
    }

    public void upload(RemoteConfig config, Path localFile, String remotePath) {
        String type = normalize(config.type());
        if ("SFTP".equals(type)) {
            sftp(config, sftp -> {
                mkdirsSftp(sftp, remotePath);
                try (InputStream in = Files.newInputStream(localFile)) {
                    sftp.put(in, remotePath, ChannelSftp.OVERWRITE);
                }
            });
        } else {
            ftp(config, "FTPS".equals(type), client -> {
                mkdirsFtp(client, remotePath);
                try (InputStream in = Files.newInputStream(localFile)) {
                    if (!client.storeFile(remotePath, in)) {
                        throw new BizException(ErrorCode.BUSINESS,
                                "FTP 上传失败: " + client.getReplyString());
                    }
                }
            });
        }
    }

    public void download(RemoteConfig config, String remotePath, Path localFile) {
        String type = normalize(config.type());
        try {
            if (localFile.getParent() != null) {
                Files.createDirectories(localFile.getParent());
            }
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "创建本地目录失败: " + e.getMessage());
        }
        if ("SFTP".equals(type)) {
            sftp(config, sftp -> {
                try (OutputStream out = Files.newOutputStream(localFile)) {
                    sftp.get(remotePath, out);
                }
            });
        } else {
            ftp(config, "FTPS".equals(type), client -> {
                try (OutputStream out = Files.newOutputStream(localFile)) {
                    if (!client.retrieveFile(remotePath, out)) {
                        throw new BizException(ErrorCode.BUSINESS,
                                "FTP 下载失败: " + client.getReplyString());
                    }
                }
            });
        }
    }

    /** 远端文件大小（用于传输后校验）；取不到时返回 -1。 */
    public long size(RemoteConfig config, String remotePath) {
        String type = normalize(config.type());
        try {
            if ("SFTP".equals(type)) {
                long[] result = {-1};
                sftp(config, sftp -> result[0] = sftp.lstat(remotePath).getSize());
                return result[0];
            }
            long[] result = {-1};
            ftp(config, "FTPS".equals(type), client -> {
                String size = client.getSize(remotePath);
                if (size != null && !size.isBlank()) {
                    result[0] = Long.parseLong(size.trim());
                }
            });
            return result[0];
        } catch (Exception e) {
            log.warn("获取远端文件大小失败 {}:{} err={}", config.host(), remotePath, e.getMessage());
            return -1;
        }
    }

    private String normalize(String type) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!"SFTP".equals(t) && !"FTP".equals(t) && !"FTPS".equals(t)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的远端文件协议: " + type);
        }
        return t;
    }

    private void sftp(RemoteConfig config, SftpAction action) {
        Session session = null;
        ChannelSftp sftp = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(config.username(), config.host(), config.port());
            session.setPassword(config.password());
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(30000);
            session.connect(15000);
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10000);
            action.run(sftp);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS,
                    "SFTP 连接/传输失败 " + config.host() + ":" + config.port() + " - " + e.getMessage());
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private void ftp(RemoteConfig config, boolean ftps, FtpAction action) {
        FTPClient client = ftps ? new FTPSClient("TLS", false) : new FTPClient();
        client.setConnectTimeout(15000);
        client.setDefaultTimeout(30000);
        try {
            client.connect(config.host(), config.port());
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new BizException(ErrorCode.BUSINESS,
                        "FTP 连接被拒绝: " + client.getReplyString());
            }
            if (!client.login(config.username(), config.password())) {
                throw new BizException(ErrorCode.BUSINESS, "FTP 登录失败: " + client.getReplyString());
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            if (ftps) {
                FTPSClient tls = (FTPSClient) client;
                tls.execPBSZ(0);
                tls.execPROT("P");
            }
            action.run(client);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BUSINESS,
                    "FTP 连接/传输失败 " + config.host() + ":" + config.port() + " - " + e.getMessage());
        } finally {
            try {
                if (client.isConnected()) {
                    client.logout();
                    client.disconnect();
                }
            } catch (Exception ignore) {
                // 关闭失败不影响结果
            }
        }
    }

    private void mkdirsSftp(ChannelSftp sftp, String filePath) throws SftpException {
        String dir = parentOf(filePath);
        if (dir.isEmpty()) {
            return;
        }
        StringBuilder current = new StringBuilder(dir.startsWith("/") ? "/" : "");
        for (String part : dir.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.charAt(current.length() - 1) != '/') {
                current.append('/');
            }
            current.append(part);
            try {
                sftp.stat(current.toString());
            } catch (SftpException e) {
                sftp.mkdir(current.toString());
            }
        }
    }

    private void mkdirsFtp(FTPClient client, String filePath) throws Exception {
        String dir = parentOf(filePath);
        if (dir.isEmpty()) {
            return;
        }
        StringBuilder current = new StringBuilder(dir.startsWith("/") ? "/" : "");
        for (String part : dir.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.charAt(current.length() - 1) != '/') {
                current.append('/');
            }
            current.append(part);
            client.makeDirectory(current.toString());
        }
    }

    private String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            return "";
        }
        return idx == 0 ? "/" : path.substring(0, idx);
    }

    @FunctionalInterface
    private interface SftpAction {
        void run(ChannelSftp sftp) throws Exception;
    }

    @FunctionalInterface
    private interface FtpAction {
        void run(FTPClient client) throws Exception;
    }
}
