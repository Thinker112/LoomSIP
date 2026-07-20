package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.KeyManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Creates disposable JDK-keytool material for TLS integration tests. */
final class TestTlsMaterial implements AutoCloseable {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private final Path directory;
    private final Path keyStorePath;
    private final X509Certificate certificate;

    private TestTlsMaterial(String hostName) throws Exception {
        directory = Files.createTempDirectory("loomsip-tls-test-");
        keyStorePath = directory.resolve("identity.p12");
        String keytool = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "keytool.exe"
                        : "keytool"
        ).toString();
        Process process = new ProcessBuilder(List.of(
                keytool,
                "-genkeypair",
                "-alias", "loomsip",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", new String(PASSWORD),
                "-keypass", new String(PASSWORD),
                "-dname", "CN=" + hostName,
                "-ext", "SAN=dns:" + hostName,
                "-noprompt"
        )).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("JDK keytool failed to generate TLS test material");
        }
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, PASSWORD);
            certificate = (X509Certificate) keyStore.getCertificate("loomsip");
        }
    }

    /** Generates a short-lived certificate whose DNS SAN is {@code hostName}. */
    static TestTlsMaterial create(String hostName) throws Exception {
        return new TestTlsMaterial(hostName);
    }

    /** Returns a server-mode context backed by the generated key pair. */
    SslContext serverContext() throws Exception {
        KeyStore keyStore = loadKeyStore();
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
        );
        keyManagers.init(keyStore, PASSWORD);
        return SslContextBuilder.forServer(keyManagers).build();
    }

    /** Returns a client context trusting only this material's certificate. */
    SslContext trustedClientContext() throws Exception {
        return SslContextBuilder.forClient().trustManager(certificate).build();
    }

    /** Returns the generated certificate for assertions or trust configuration. */
    X509Certificate certificate() {
        return certificate;
    }

    private KeyStore loadKeyStore() throws Exception {
        try (InputStream input = Files.newInputStream(keyStorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, PASSWORD);
            return keyStore;
        }
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(keyStorePath);
        Files.deleteIfExists(directory);
    }
}
