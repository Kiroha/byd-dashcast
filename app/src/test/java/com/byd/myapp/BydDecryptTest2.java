package com.byd.myapp;

import org.junit.Test;
import static org.junit.Assert.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class BydDecryptTest2 {

    @Test
    public void testDecryption() throws Exception {
        String s = "\u001c\u0007\u2007\u2063 \u001d\u0006\u200a\u0012\u200d\u0014\u2066\u2002\u000f\u000e \u2007\u2001\u0003\u0002\u202c \u001e\u200a\u2006\u0016\u0019\u0002\u2008\u0012\u200e\u0018\u2060\u0019\u001d\u0017\u001b\u0010\u0013\u0018\u0019\u206a\u2009\u2001\u2067\u206a\u2065\u001f\u001d\u200c\u205f\u2002\u2065\u202f\u200c\u2004\u0019\u2007\u202c \u001e\u200a\u202a\u0011\u0019\u0002\u206c\u0003\u2008\u0001\u2006\u000f\u0003\u0001\u2006\u200c\u2001\u200d\u0017\u200f\u0018\u001b\u206a\u0004\u001a\u2065\u0018\u0006\u001e\u2065\u2066\u0002\u001b\u0006\u206e\u001b\u0017\u001b\u0010\u0013\u0018\u001c\u202f\u202c \u001e\u200c\u2003\u0011\u0017";
        
        try {
            SecretKeySpec key = new SecretKeySpec("decrypt".getBytes(StandardCharsets.UTF_8), "DES");
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decrypted = cipher.doFinal(s.getBytes(StandardCharsets.UTF_8));
            System.out.println("H1 (Pure UTF-8 Bytes with key 'decrypt'): " + new String(decrypted, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("Error 1: " + e);
        }
    }
}
