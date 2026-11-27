package org.fuin.porelo.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * porelo-mcp signs as the planner checks: held to the test vector the planner's own test holds its
 * CallSignature to (porelo-planner, handed over in s360-for-mcp.md at 2994ec3c).
 */
class PlannerSignatureTest {

    private static final String TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    // Harm: a request the planner refuses, or an answer from a stranger taken for the planner's.
    @Test
    void theRequestAnswerAndProofEqualThePlannersTestVector() {
        final byte[] nonce = bytes(100, 16);

        assertThat(
                        PlannerSignature.request(
                                TOKEN,
                                "POST",
                                "/projects/p/moves?x=1",
                                "127.0.0.1:41234",
                                "application/json",
                                "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                                1764000000L,
                                nonce))
                .as("the request's header")
                .isEqualTo(
                        "porelo-planner-request-1;t=1764000000;n=ZGVmZ2hpamtsbW5vcHFycw"
                                + ";s=mzk3aNeJvNxWu2bLGrFJnBzwo-0Hizqcanwo41mhO_c");
        assertThat(
                        PlannerSignature.answer(
                                TOKEN,
                                nonce,
                                200,
                                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)))
                .as("the answer's header")
                .isEqualTo("porelo-planner-answer-1;s=C4oEI-9hOCKwI6pKOnvr0qt-xkx0KVZg0J0M6_yIcU8");
        assertThat(PlannerSignature.proof(TOKEN, 41234, bytes(200, 32)))
                .as("the proof")
                .isEqualTo("i_GGT6A769tJy81GkQoj2SAFmcJafS8Wu8ik66-BsOA");
    }

    private static byte[] bytes(int first, int count) {
        final byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = (byte) (first + i);
        }
        return bytes;
    }
}
