import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public class Sign {
    public static void main(String[] a) throws Exception {
        // a: in.apk out.apk keystore.jks passfile
        String pass = new String(java.nio.file.Files.readAllBytes(new File(a[3]).toPath())).trim();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(a[2])) { ks.load(f, pass.toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey("q07", pass.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("q07");
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("q07", key, List.of(cert)).build();
        new ApkSigner.Builder(List.of(sc))
            .setInputApk(new File(a[0])).setOutputApk(new File(a[1]))
            .setMinSdkVersion(30).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build().sign();
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[1])).build().verify();
        System.out.println("verified=" + r.isVerified() + " v2=" + r.isVerifiedUsingV2Scheme() + " v3=" + r.isVerifiedUsingV3Scheme());
        for (X509Certificate c : r.getSignerCertificates()) {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X:", b));
            System.out.println("SHA256 " + sb.substring(0, sb.length() - 1));
        }
        for (ApkVerifier.IssueWithParams e : r.getErrors()) System.out.println("ERR " + e);
    }
}
