package com.example.mapaiserver.mcp.qweather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.GZIPInputStream;

@Service
public class QWeatherMcpService {

    private final HttpClient httpClient = HttpClient.newBuilder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNode geoCityLookup(Map<String, String> params) throws Exception {
        return callQWeather("/geo/v2/city/lookup", params);
    }

    public JsonNode geoPoiRange(Map<String, String> params) throws Exception {
        return callQWeather("/geo/v2/poi/range", params);
    }

    public JsonNode weatherDaily(String days, Map<String, String> params) throws Exception {
        String safeDays = normalizeDays(days, "7");
        return callQWeather("/v7/weather/" + safeDays + "d", params);
    }

    public JsonNode weatherIndices(String days, Map<String, String> params) throws Exception {
        String safeDays = normalizeDays(days, "1");
        return callQWeather("/v7/indices/" + safeDays + "d", params);
    }

    public JsonNode weatherWarningNow(Map<String, String> params) throws Exception {
        return callQWeather("/v7/warning/now", params);
    }

    private JsonNode callQWeather(String path, Map<String, String> inputParams) throws Exception {
        String host = getenv("HEFENG_HOST");
        if (host == null || host.isBlank()) {
            return error("HEFENG_HOST 未配置");
        }

        Map<String, String> params = new LinkedHashMap<>(inputParams);
        params.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isBlank());

        String jwt = buildJwtIfPossible();
        boolean useJwt = jwt != null && !jwt.isBlank();
        if (!useJwt) {
            String key = getenv("HEFENG_KEY");
            if (key == null || key.isBlank()) {
                return error("未找到可用鉴权：请配置 JWT(HEFENG_JWT_KEY+HEFENG_PROJECT_ID+HEFENG_KEY_ID) 或 HEFENG_KEY");
            }
            params.put("key", key);
        }

        String url = "https://" + host + path + "?" + toQueryString(params);
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip")
                .GET();
        if (useJwt) {
            reqBuilder.header("Authorization", "Bearer " + jwt);
        }

        HttpResponse<byte[]> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        String body = decodeResponseBody(resp);
        JsonNode upstream;
        try {
            upstream = objectMapper.readTree(body);
        } catch (Exception ignored) {
            return error("上游返回非 JSON: HTTP " + resp.statusCode() + " body=" + body);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("httpStatus", resp.statusCode());
        out.put("host", host);
        out.put("path", path);
        out.put("authMode", useJwt ? "jwt" : "key");
        out.put("upstream", upstream);
        return objectMapper.valueToTree(out);
    }

    private String normalizeDays(String days, String fallback) {
        if (days == null || days.isBlank()) return fallback;
        switch (days.trim()) {
            case "1":
            case "3":
            case "7":
            case "10":
            case "15":
            case "30":
                return days.trim();
            default:
                return fallback;
        }
    }

    private String toQueryString(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((k, v) -> joiner.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" +
                        URLEncoder.encode(v, StandardCharsets.UTF_8)
        ));
        return joiner.toString();
    }

    private String decodeResponseBody(HttpResponse<byte[]> resp) throws Exception {
        String encoding = resp.headers().firstValue("content-encoding").orElse("");
        byte[] bytes = resp.body();
        if ("gzip".equalsIgnoreCase(encoding)) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = gis.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                return baos.toString(StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String buildJwtIfPossible() {
        try {
            String rawPk = getenv("HEFENG_JWT_KEY");
            String projectId = getenv("HEFENG_PROJECT_ID");
            String keyId = getenv("HEFENG_KEY_ID");
            if (rawPk == null || rawPk.isBlank() || projectId == null || projectId.isBlank() || keyId == null || keyId.isBlank()) {
                return null;
            }
            PrivateKey privateKey = loadPrivateKey(rawPk);
            return createJwt(keyId, projectId, privateKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private PrivateKey loadPrivateKey(String raw) throws Exception {
        String normalized = raw.replace("\\n", "\n").trim();
        String base64 = normalized
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(keySpec);
        } catch (Exception e) {
            return KeyFactory.getInstance("EdDSA").generatePrivate(keySpec);
        }
    }

    private String createJwt(String kid, String projectId, PrivateKey privateKey) throws Exception {
        String headerJson = "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}";
        long iat = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond() - 30;
        long exp = iat + 900;
        String payloadJson = "{\"sub\":\"" + projectId + "\",\"iat\":" + iat + ",\"exp\":" + exp + "}";
        String data = base64Url(headerJson.getBytes(StandardCharsets.UTF_8)) + "." +
                base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));

        Signature signer;
        try {
            signer = Signature.getInstance("Ed25519");
        } catch (Exception e) {
            signer = Signature.getInstance("EdDSA");
        }
        signer.initSign(privateKey);
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        return data + "." + base64Url(signer.sign());
    }

    private String base64Url(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }

    private JsonNode error(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("httpStatus", 500);
        out.put("message", msg);
        return objectMapper.valueToTree(out);
    }

    private String getenv(String key) {
        return System.getenv(key);
    }
}

