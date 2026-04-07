package com.monkey.proudAuth.common.util;

import org.mindrot.jbcrypt.BCrypt;

public final class HashUtil {

    private HashUtil() {
    }

    public static String hash(String plainText) {
        return BCrypt.hashpw(plainText, BCrypt.gensalt(12));
    }

    public static boolean matches(String plainText, String hashed) {
        return hashed != null && BCrypt.checkpw(plainText, hashed);
    }
}
