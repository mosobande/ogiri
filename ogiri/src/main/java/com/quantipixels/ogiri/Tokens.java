// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

final class Tokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private Tokens() {}

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "og1_" + ENCODER.encodeToString(bytes);
    }

    static byte[] digest(String token) {
        if (token == null || token.length() != 47 || !token.startsWith("og1_")) return null;
        String encoded = token.substring(4);
        final byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(encoded); }
        catch (IllegalArgumentException malformed) { return null; }
        if (bytes.length != 32 || !ENCODER.encodeToString(bytes).equals(encoded)) return null;
        return sha256().digest(token.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] lockKey(Subject subject) {
        MessageDigest hash = sha256();
        hash.update("ogiri-session-admission-v1".getBytes(StandardCharsets.US_ASCII));
        for (String part : new String[]{subject.realm(), subject.tenantId(), subject.subjectId()}) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            hash.update(bytes);
        }
        return hash.digest();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
