package com.rocom.nearby.net;

import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class GameCrypto {

    private byte[] key;

    void setKey(byte[] newKey) {
        key = newKey != null && newKey.length == 16 ? Arrays.copyOf(newKey, 16) : null;
    }

    void reset() {
        key = null;
    }

    byte[] decrypt(byte[] data, int offset, int length) {
        if (key == null || data == null || length <= 0)
            return null;
        byte[] iv;
        int cipherOffset;
        int cipherLength;
        if (length >= 32 && (length - 16) % 16 == 0) {
            iv = Arrays.copyOfRange(data, offset, offset + 16);
            cipherOffset = offset + 16;
            cipherLength = length - 16;
        } else if (length % 16 == 0) {
            iv = new byte[16];
            cipherOffset = offset;
            cipherLength = length;
        } else return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data, cipherOffset, cipherLength);
        } catch (Throwable e) {
            return null;
        }
    }
}
