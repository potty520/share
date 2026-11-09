package com.share.app;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 独立 mTLS 连接器（架构演进：服务间双向 TLS）。
 * <p>业务 HTTP 端口保持不变，另开 8443 端口要求客户端证书；用于服务间调用、
 * 前置 Agent 上报等可信通道，不影响门户与开放 API 的既有访问方式。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "share.mtls.enabled", havingValue = "true")
public class MtlsConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> mtlsConnectorCustomizer(
            @Value("${share.mtls.port:8443}") int port,
            @Value("${share.mtls.keystore:}") String keystore,
            @Value("${share.mtls.keystore-password:changeit}") String keystorePassword,
            @Value("${share.mtls.truststore:}") String truststore,
            @Value("${share.mtls.truststore-password:changeit}") String truststorePassword) {
        return factory -> {
            if (keystore == null || keystore.isBlank() || !Files.exists(Paths.get(keystore))) {
                log.error("mTLS 已启用但服务端 keystore 不存在: {}，跳过 8443 连接器", keystore);
                return;
            }
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(port);
            connector.setSecure(true);
            connector.setScheme("https");
            connector.setProperty("SSLEnabled", "true");

            SSLHostConfig sslHostConfig = new SSLHostConfig();
            sslHostConfig.setHostName("_default_");
            sslHostConfig.setProtocols("TLSv1.2,TLSv1.3");
            sslHostConfig.setCertificateVerification("required");
            SSLHostConfigCertificate certificate =
                    new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.UNDEFINED);
            certificate.setCertificateKeystoreFile(keystore);
            certificate.setCertificateKeystorePassword(keystorePassword);
            certificate.setCertificateKeystoreType("PKCS12");
            sslHostConfig.addCertificate(certificate);
            if (truststore != null && !truststore.isBlank() && Files.exists(Paths.get(truststore))) {
                sslHostConfig.setTruststoreFile(truststore);
                sslHostConfig.setTruststorePassword(truststorePassword);
                sslHostConfig.setTruststoreType("PKCS12");
            }
            connector.addSslHostConfig(sslHostConfig);
            factory.addAdditionalTomcatConnectors(connector);
            log.info("mTLS 连接器已启用 port={} keystore={}", port, keystore);
        };
    }
}
