package com.example.bilibilimusic.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class HashUtil {

    private HashUtil() {}

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((input != null ? input : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input);
        }
    }
}

