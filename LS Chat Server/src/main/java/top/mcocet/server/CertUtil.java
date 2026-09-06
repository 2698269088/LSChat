package top.mcocet.server;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * 首次启动时自动生成自签名 RSA 证书并保存到 JKS 密钥库，
 * 之后复用已有证书，保证服务端重启后证书不变。
 */
public final class CertUtil {

    private static final String KEYSTORE_FILE = "keystore.jks";
    private static final String ALIAS = "lschat";
    private static final char[] PASSWORD = "lschat-keystore".toCharArray();

    private CertUtil() {
    }

    public static SSLContext buildOrCreateSslContext(Path dataDir) throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(KEYSTORE_FILE);

        KeyStore keyStore = KeyStore.getInstance("JKS");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                keyStore.load(in, PASSWORD);
            }
            System.out.println("[CertUtil] 已加载现有证书: " + file.toAbsolutePath());
        } else {
            generateSelfSigned(keyStore);
            try (OutputStream out = Files.newOutputStream(file)) {
                keyStore.store(out, PASSWORD);
            }
            System.out.println("[CertUtil] 已生成新的自签名证书: " + file.toAbsolutePath());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, PASSWORD);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    private static void generateSelfSigned(KeyStore keyStore) throws Exception {
        keyStore.load(null, null);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600 * 1000);
        Date notAfter = new Date(now + 3650L * 24 * 3600 * 1000);
        X500Name owner = new X500Name("CN=LSChat Server");
        BigInteger serial = new BigInteger(160, new SecureRandom());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                owner, serial, notBefore, notAfter, owner, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(pair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
        certificate.checkValidity(new Date());

        keyStore.setKeyEntry(ALIAS, pair.getPrivate(), PASSWORD, new Certificate[]{certificate});
    }
}
