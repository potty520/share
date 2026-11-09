package com.share.ops.notify.service;

import com.share.common.util.JsonUtil;
import com.share.ops.notify.entity.NotifyChannel;
import com.share.ops.support.OpsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知通道发送器（FR-14）：EMAIL / SMS / WEBHOOK 真实投递。
 * <p>尽力发送：连接/读写超时控制在 2~3 秒，失败只记录日志与发送状态，绝不阻塞业务线程。</p>
 */
@Slf4j
@Component
public class ChannelSender {

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;

    /** 发送结果 */
    public record SendResult(boolean success, String status, String error) {

        public static SendResult sent() {
            return new SendResult(true, OpsConstants.SEND_SENT, null);
        }

        public static SendResult skipped(String reason) {
            return new SendResult(false, OpsConstants.SEND_SKIPPED, reason);
        }

        public static SendResult failed(String error) {
            return new SendResult(false, OpsConstants.SEND_FAILED, error);
        }
    }

    /**
     * 按通道类型投递消息。
     *
     * @param channel  通道配置（含 configJson）
     * @param title    标题
     * @param content  正文
     * @param receiver 接收人标识（手机号/邮箱/用户 ID，可为空）
     */
    public SendResult send(NotifyChannel channel, String title, String content, String receiver) {
        if (channel == null) {
            return SendResult.skipped("通道不存在");
        }
        if (!Boolean.TRUE.equals(channel.getEnabled())) {
            return SendResult.skipped("通道已停用");
        }
        Map<String, Object> config = parseConfig(channel.getConfigJson());
        try {
            switch (channel.getChannelType() == null ? "" : channel.getChannelType().toUpperCase()) {
                case OpsConstants.CHANNEL_TYPE_WEBHOOK:
                    return sendWebhook(config, title, content, receiver);
                case OpsConstants.CHANNEL_TYPE_SMS:
                    return sendSms(config, title, content, receiver);
                case OpsConstants.CHANNEL_TYPE_EMAIL:
                    return sendEmail(config, title, content, receiver);
                default:
                    return SendResult.skipped("不支持的通道类型: " + channel.getChannelType());
            }
        } catch (Exception e) {
            log.warn("通知通道 {} 发送失败: {}", channel.getChannelCode(), e.getMessage());
            return SendResult.failed(e.getMessage());
        }
    }

    // ================= Webhook =================

    private SendResult sendWebhook(Map<String, Object> config, String title, String content, String receiver) {
        String url = str(config.get("url"));
        if (url == null || url.isBlank()) {
            return SendResult.skipped("Webhook 未配置 url");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("content", content);
        payload.put("receiver", receiver);
        payload.put("source", "share-exchange");
        payload.put("timestamp", LocalDateTime.now().toString());
        return postJson(url, str(config.get("token")), JsonUtil.toJson(payload));
    }

    // ================= SMS（短信网关 HTTP 接口） =================

    private SendResult sendSms(Map<String, Object> config, String title, String content, String receiver) {
        String endpoint = str(config.get("endpoint"));
        if (endpoint == null || endpoint.isBlank()) {
            return SendResult.skipped("短信网关未配置 endpoint");
        }
        String phone = receiver;
        if (phone == null || phone.isBlank()) {
            phone = str(config.get("defaultPhone"));
        }
        if (phone == null || phone.isBlank()) {
            return SendResult.skipped("缺少接收手机号");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appKey", str(config.get("appKey")));
        payload.put("appSecret", str(config.get("appSecret")));
        payload.put("sign", str(config.get("sign")));
        payload.put("phone", phone);
        payload.put("content", "【" + (config.get("sign") == null ? "共享交换平台" : config.get("sign")) + "】"
                + title + "：" + content);
        return postJson(endpoint, str(config.get("token")), JsonUtil.toJson(payload));
    }

    // ================= Email（最小 SMTP 客户端） =================

    private SendResult sendEmail(Map<String, Object> config, String title, String content, String receiver) {
        String host = str(config.get("host"));
        int port = intVal(config.get("port"), 25);
        String from = str(config.get("from"));
        String to = receiver != null && receiver.contains("@") ? receiver : str(config.get("defaultTo"));
        if (host == null || host.isBlank()) {
            return SendResult.skipped("邮件通道未配置 smtp host");
        }
        if (from == null || to == null) {
            return SendResult.skipped("邮件通道缺少发件人/收件人");
        }
        boolean ssl = Boolean.parseBoolean(String.valueOf(config.getOrDefault("ssl", "false")));
        try (Socket socket = ssl
                ? SSLSocketFactory.getDefault().createSocket()
                : new Socket()) {
            if (ssl) {
                ((SSLSocket) socket).connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } else {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            }
            socket.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            readResponse(reader);
            command(writer, reader, "EHLO share-exchange");
            String username = str(config.get("username"));
            String password = str(config.get("password"));
            if (username != null && password != null) {
                command(writer, reader, "AUTH LOGIN");
                command(writer, reader, java.util.Base64.getEncoder()
                        .encodeToString(username.getBytes(StandardCharsets.UTF_8)));
                command(writer, reader, java.util.Base64.getEncoder()
                        .encodeToString(password.getBytes(StandardCharsets.UTF_8)));
            }
            command(writer, reader, "MAIL FROM:<" + from + ">");
            command(writer, reader, "RCPT TO:<" + to + ">");
            command(writer, reader, "DATA");
            writer.write("Subject: " + encodeSubject(title) + "\r\n");
            writer.write("From: " + from + "\r\n");
            writer.write("To: " + to + "\r\n");
            writer.write("Content-Type: text/plain; charset=UTF-8\r\n");
            writer.write("\r\n");
            writer.write(content == null ? "" : content);
            writer.write("\r\n.\r\n");
            writer.flush();
            readResponse(reader);
            command(writer, reader, "QUIT");
            return SendResult.sent();
        } catch (Exception e) {
            log.warn("SMTP 邮件发送失败 host={}:{}: {}", host, port, e.getMessage());
            return SendResult.failed(e.getMessage());
        }
    }

    private String encodeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        return "=?UTF-8?B?" + java.util.Base64.getEncoder()
                .encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private String command(BufferedWriter writer, BufferedReader reader, String command) throws Exception {
        writer.write(command + "\r\n");
        writer.flush();
        return readResponse(reader);
    }

    private String readResponse(BufferedReader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                break;
            }
            if (line.length() < 4) {
                break;
            }
        }
        return sb.toString();
    }

    // ================= 公共 HTTP POST =================

    private SendResult postJson(String url, String token, String body) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            if (token != null && !token.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                return SendResult.sent();
            }
            return SendResult.failed("HTTP " + code);
        } catch (Exception e) {
            return SendResult.failed(e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ================= 配置解析 =================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JsonUtil.fromJson(configJson, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.warn("通知通道配置 JSON 解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intVal(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }
}
