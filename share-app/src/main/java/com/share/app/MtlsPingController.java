package com.share.app;

import com.share.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** mTLS 连通性探针：仅通过 8443 双向 TLS 连接器访问，返回客户端证书主体。 */
@RestController
@RequestMapping("/api/mtls")
public class MtlsPingController {

    @GetMapping("/ping")
    public Result<Map<String, Object>> ping(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "share-exchange");
        data.put("secure", request.isSecure());
        data.put("scheme", request.getScheme());
        data.put("cipherSuite", request.getAttribute("javax.servlet.request.cipher_suite"));
        data.put("time", LocalDateTime.now());
        Object certs = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs instanceof X509Certificate[] array && array.length > 0) {
            X509Certificate cert = array[0];
            data.put("clientSubject", cert.getSubjectX500Principal().getName());
            data.put("clientIssuer", cert.getIssuerX500Principal().getName());
            data.put("clientNotAfter", cert.getNotAfter().toInstant().toString());
        }
        return Result.ok(data);
    }
}
