package org.fuin.porelo.mcp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The forms porelo-mcp and the planner sign under the planner's token, as the window signs them: a
 * request, an answer and a proof, each behind a label of its own, so one never stands for another.
 * A request's and an answer's fields are each prefixed by their length as four bytes, big endian.
 */
final class PlannerSignature {

    /** The header a signed request carries instead of the token. */
    static final String HEADER = "Porelo-Signature";

    /** The header an answer to a signed request carries. */
    static final String ANSWER_HEADER = "Porelo-Answer";

    private static final String REQUEST = "porelo-planner-request-1";

    private static final String ANSWER = "porelo-planner-answer-1";

    private static final String PROOF = "porelo-planner-proof-1";

    private PlannerSignature() {}

    /**
     * The header of a signed request.
     *
     * @param token The planner's token.
     * @param method The method.
     * @param target The raw request target as sent.
     * @param host The {@code Host} header.
     * @param contentType The {@code Content-Type} header, empty when absent.
     * @param body The body's bytes, empty when absent.
     * @param time The UTC time in seconds.
     * @param nonce The 16 bytes of the nonce.
     * @return The header's value.
     */
    static String request(
            String token,
            String method,
            String target,
            String host,
            String contentType,
            byte[] body,
            long time,
            byte[] nonce) {
        final ByteArrayOutputStream form = labeled(REQUEST);
        field(form, method.getBytes(StandardCharsets.US_ASCII));
        field(form, target.getBytes(StandardCharsets.UTF_8));
        field(form, host.getBytes(StandardCharsets.UTF_8));
        field(form, contentType.getBytes(StandardCharsets.UTF_8));
        field(form, sha256(body));
        field(form, Long.toString(time).getBytes(StandardCharsets.US_ASCII));
        field(form, nonce);
        return REQUEST
                + ";t="
                + time
                + ";n="
                + base64(nonce)
                + ";s="
                + base64(mac(token, form.toByteArray()));
    }

    /**
     * The header the planner's answer to a request must carry.
     *
     * @param token The planner's token.
     * @param nonce The request's nonce.
     * @param status The answer's status.
     * @param body The answer's body as received.
     * @return The header's value.
     */
    static String answer(String token, byte[] nonce, int status, byte[] body) {
        final ByteArrayOutputStream form = labeled(ANSWER);
        field(form, nonce);
        field(form, Integer.toString(status).getBytes(StandardCharsets.US_ASCII));
        field(form, sha256(body));
        return ANSWER + ";s=" + base64(mac(token, form.toByteArray()));
    }

    /**
     * The proof the planner on a port must answer for a challenge.
     *
     * @param token The planner's token.
     * @param port The port the proof is asked on.
     * @param challenge The 32 bytes of the challenge.
     * @return The proof in base64url without padding.
     */
    static String proof(String token, int port, byte[] challenge) {
        final ByteArrayOutputStream form = labeled(PROOF);
        form.write(port >> 8 & 0xff);
        form.write(port & 0xff);
        form.writeBytes(challenge);
        return base64(mac(token, form.toByteArray()));
    }

    /**
     * Whether a text received equals the one expected, in a time that does not depend on where they
     * differ.
     *
     * @param given The text received, or {@code null} when none was.
     * @param expected The text expected.
     * @return Whether they are equal.
     */
    static boolean same(@Nullable String given, String expected) {
        return given != null
                && MessageDigest.isEqual(
                        given.getBytes(StandardCharsets.UTF_8),
                        expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64url without padding.
     *
     * @param bytes The bytes.
     * @return Their text.
     */
    static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ByteArrayOutputStream labeled(String label) {
        final ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        form.write(0);
        return form;
    }

    private static void field(ByteArrayOutputStream form, byte[] bytes) {
        final int length = bytes.length;
        form.write(length >>> 24 & 0xff);
        form.write(length >>> 16 & 0xff);
        form.write(length >>> 8 & 0xff);
        form.write(length & 0xff);
        form.writeBytes(bytes);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is missing from this runtime", ex);
        }
    }

    private static byte[] mac(String token, byte[] message) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is missing from this runtime", ex);
        }
    }
}
