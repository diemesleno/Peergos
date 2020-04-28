package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

/** Presign requests to Amazon S3 or compatible
 *
 * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
public class S3Request {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    public final String verb, host;
    public final String key;
    public final String contentSha256;
    public final Optional<Integer> expiresSeconds;
    public final boolean allowPublicReads;
    public final boolean useAuthHeader;
    public final String accessKeyId;
    public final String region;
    public final Map<String, String> extraQueryParameters;
    public final Map<String, String> extraHeaders;
    public final Instant date;

    public S3Request(String verb,
                     String host,
                     String key,
                     String contentSha256,
                     Optional<Integer> expiresSeconds,
                     boolean allowPublicReads,
                     boolean useAuthHeader,
                     Map<String, String> extraQueryParameters,
                     Map<String, String> extraHeaders,
                     String accessKeyId,
                     String region,
                     ZonedDateTime timestamp) {
        this.verb = verb;
        this.host = host;
        this.key = key;
        this.contentSha256 = contentSha256;
        this.expiresSeconds = expiresSeconds;
        this.allowPublicReads = allowPublicReads;
        this.useAuthHeader = useAuthHeader;
        this.extraQueryParameters = extraQueryParameters;
        this.extraHeaders = extraHeaders;
        this.accessKeyId = accessKeyId;
        this.region = region;
        this.date = timestamp.withNano(0).withZoneSameInstant(ZoneId.of("UTC")).toInstant();
    }

    public static PresignedUrl preSignPut(String key,
                                          int size,
                                          String contentSha256,
                                          boolean allowPublicReads,
                                          ZonedDateTime now,
                                          String host,
                                          Map<String, String> extraHeaders,
                                          String region,
                                          String accessKeyId,
                                          String s3SecretKey) {
        extraHeaders.put("Content-Length", "" + size);
        S3Request policy = new S3Request("PUT", host, key, contentSha256, Optional.empty(), allowPublicReads, true,
                Collections.emptyMap(), extraHeaders, accessKeyId, region, now);
        return preSignRequest(policy, key, host, s3SecretKey);
    }

    public static PresignedUrl preSignGet(String key,
                                          Optional<Integer> expirySeconds,
                                          ZonedDateTime now,
                                          String host,
                                          String region,
                                          String accessKeyId,
                                          String s3SecretKey) {
        return preSignNulliPotent("GET", key, expirySeconds, now, host, region, accessKeyId, s3SecretKey);
    }

    public static PresignedUrl preSignDelete(String key,
                                             ZonedDateTime now,
                                             String host,
                                             String region,
                                             String accessKeyId,
                                             String s3SecretKey) {
        S3Request policy = new S3Request("DELETE", host, key, UNSIGNED, Optional.empty(), false, true,
                Collections.emptyMap(), Collections.emptyMap(), accessKeyId, region, now);
        return preSignRequest(policy, key, host, s3SecretKey);
    }

    public static PresignedUrl preSignHead(String key,
                                           Optional<Integer> expirySeconds,
                                           ZonedDateTime now,
                                           String host,
                                           String region,
                                           String accessKeyId,
                                           String s3SecretKey) {
        return preSignNulliPotent("HEAD", key, expirySeconds, now, host, region, accessKeyId, s3SecretKey);
    }

    public static PresignedUrl preSignList(String prefix,
                                           int maxKeys,
                                           Optional<String> continuationToken,
                                           ZonedDateTime now,
                                           String host,
                                           String region,
                                           String accessKeyId,
                                           String s3SecretKey) {
        Map<String, String> extraQueryParameters = new LinkedHashMap<>();
        extraQueryParameters.put("list-type", "2");
        extraQueryParameters.put("max-keys", "" + maxKeys);
        extraQueryParameters.put("fetch-owner", "false");
        extraQueryParameters.put("prefix", prefix);
        continuationToken.ifPresent(t -> extraQueryParameters.put("continuation-token", t));

        S3Request policy = new S3Request("GET", host, "", UNSIGNED, Optional.empty(), false, true,
                extraQueryParameters, Collections.emptyMap(), accessKeyId, region, now);
        return preSignRequest(policy, "", host, s3SecretKey);
    }

    private static PresignedUrl preSignNulliPotent(String verb,
                                                   String key,
                                                   Optional<Integer> expiresSeconds,
                                                   ZonedDateTime now,
                                                   String host,
                                                   String region,
                                                   String accessKeyId,
                                                   String s3SecretKey) {
        S3Request policy = new S3Request(verb, host, key, UNSIGNED, expiresSeconds, false, false,
                Collections.emptyMap(), Collections.emptyMap(), accessKeyId, region, now);
        return preSignRequest(policy, key, host, s3SecretKey);
    }

    private static PresignedUrl preSignRequest(S3Request req,
                                               String key,
                                               String host,
                                               String s3SecretKey) {
        String signature = computeSignature(req, s3SecretKey);

        String query = req.getQueryString(signature);
        return new PresignedUrl("https://" + host + "/" + key + query, req.getHeaders(signature));
    }

    private static byte[] hmacSha256(byte[] secretKeyBytes, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HMACSHA256");
            SecretKey secretKey = new SecretKeySpec(secretKeyBytes, "HMACSHA256");
            mac.init(secretKey);
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] hmacSha256(String secretKey, byte[] message) {
        return hmacSha256(secretKey.getBytes(), message);
    }

    /**
     * Method for generating policy signature V4 for direct browser upload.
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
     */
    public static String computeSignature(S3Request policy,
                                          String s3SecretKey) {
        String stringToSign = policy.stringToSign();
        String shortDate = S3Request.asAwsShortDate(policy.date);

        byte[] dateKey = hmacSha256("AWS4" + s3SecretKey, shortDate.getBytes());
        byte[] dateRegionKey = hmacSha256(dateKey, policy.region.getBytes());
        byte[] dateRegionServiceKey = hmacSha256(dateRegionKey, "s3".getBytes());
        byte[] signingKey = hmacSha256(dateRegionServiceKey, "aws4_request".getBytes());

        return ArrayOps.bytesToHex(hmacSha256(signingKey, stringToSign.getBytes()));
    }

    public String stringToSign() {
        StringBuilder res = new StringBuilder();
        res.append(ALGORITHM + "\n");
        res.append(asAwsDate(date) + "\n");
        res.append(scope() + "\n");
        res.append(ArrayOps.bytesToHex(Hash.sha256(toCanonicalRequest().getBytes())));
        return res.toString();
    }

    private static String urlEncode(String in) {
        try {
            return URLEncoder.encode(in, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public String toCanonicalRequest() {
        StringBuilder res = new StringBuilder();
        res.append(verb + "\n");
        res.append("/" + key + "\n");

        res.append(getQueryParameters().entrySet()
                .stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&")));
        res.append("\n");

        Map<String, String> headers = getCanonicalHeaders();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            res.append(e.getKey().toLowerCase() + ":" + e.getValue() + "\n");
        }
        res.append("\n");

        res.append(headersToSign() + "\n");
        res.append(contentSha256);
        return res.toString();
    }

    private Map<String, String> getHeaders(String signature) {
        Map<String, String> headers = getOriginalHeaders();
        if (! useAuthHeader)
            return headers;
        headers.put("Authorization", ALGORITHM + " Credential=" + credential()
                + ",SignedHeaders=" + headersToSign() + ",Signature=" + signature);
        return headers;
    }

    private Map<String, String> getOriginalHeaders() {
        Map<String, String> res = new LinkedHashMap<>();
        res.put("Host", host);
        if (! useAuthHeader)
            return res;
        res.put("x-amz-date", asAwsDate(date));
        res.put("x-amz-content-sha256", contentSha256);
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            res.put(e.getKey(), e.getValue());
        }
        if (allowPublicReads)
            res.put("x-amz-acl", "public-read");
        return res;
    }

    private String getQueryString(String signature) {
        Map<String, String> res = getQueryParameters();
        if (! useAuthHeader)
            res.put("X-Amz-Signature", signature);
        return "?" + res.entrySet()
                .stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private Map<String, String> getQueryParameters() {
        Map<String, String> res = new TreeMap<>();
        res.putAll(extraQueryParameters);
        if (! useAuthHeader) {
            res.put("X-Amz-Algorithm", ALGORITHM);
            res.put("X-Amz-Credential", credential());
            res.put("X-Amz-Date", asAwsDate(date));
            expiresSeconds.ifPresent(seconds -> res.put("X-Amz-Expires", "" + seconds));
            res.put("X-Amz-SignedHeaders", "host");
        }
        return res;
    }

    private SortedMap<String, String> getCanonicalHeaders() {
        SortedMap<String, String> res = new TreeMap<>();
        Map<String, String> originalHeaders = getOriginalHeaders();
        for (Map.Entry<String, String> e : originalHeaders.entrySet()) {
            res.put(e.getKey().toLowerCase(), e.getValue());
        }
        return res;
    }

    private String headersToSign() {
        return getCanonicalHeaders().keySet()
                .stream()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private String scope() {
        return String.format(
                "%s/%s/%s/%s",
                asAwsShortDate(date),
                region,
                "s3",
                "aws4_request");
    }

    private String credential() {
        return String.format(
                "%s/%s/%s/%s/%s",
                accessKeyId,
                asAwsShortDate(date),
                region,
                "s3",
                "aws4_request"
        );
    }

    public boolean isGet() {
        return "GET".equals(verb);
    }

    public boolean isHead() {
        return "HEAD".equals(verb);
    }

    private static String asAwsDate(Instant instant) {
        return instant.toString()
                .replaceAll("[:\\-]|\\.\\d{3}", "");
    }

    private static String asAwsShortDate(Instant instant) {
        return asAwsDate(instant).substring(0, 8);
    }
}

