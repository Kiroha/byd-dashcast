import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Looper;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLContext;
import javax.security.auth.x500.X500Principal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;

/** Loads the actual minified APK on ART; never installs it or starts an Android service. */
public final class ReleaseSatelliteArtProbe {
    private static final String TOKEN = "local-art-regression-placeholder";
    private static final String LEGACY = "dashcast_satellite_tls_v1";
    private static final String CURRENT = "dashcast_satellite_tls_v2";
    private static int exitCode;

    public static void main(String[] args) {
        KeyStore store = null;
        boolean ownsProbeKeys = false;
        try {
            ClassLoader loader = new DexClassLoader(args[0], args[2], null,
                    ReleaseSatelliteArtProbe.class.getClassLoader());
            Class<?> serverClass = Class.forName(args[1], true, loader);
            if (!Runnable.class.isAssignableFrom(serverClass))
                throw new AssertionError("Mapped class is not the server Runnable");
            System.out.println("ART_SERVER_CLASS_OK");
            if ("load".equals(args[3])) return;

            // Standalone Service constructors contain a main-thread Handler. No lifecycle
            // method is called: the probe does not enable projection or vehicle outputs.
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            Map<String, Object> settings = new HashMap<>();
            settings.put("enabled", true);
            settings.put("token", TOKEN);
            Object editor = Proxy.newProxyInstance(loader, new Class<?>[] {SharedPreferences.Editor.class},
                    (proxy, method, values) -> {
                        if (method.getName().startsWith("put")) settings.put((String) values[0], values[1]);
                        if ("remove".equals(method.getName())) settings.remove(values[0]);
                        if ("commit".equals(method.getName())) return true;
                        if ("apply".equals(method.getName())) return null;
                        return proxy;
                    });
            SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                    loader, new Class<?>[] {SharedPreferences.class}, (proxy, method, values) -> {
                        if ("getBoolean".equals(method.getName()))
                            return settings.containsKey(values[0]) ? settings.get(values[0]) : values[1];
                        if ("getString".equals(method.getName()))
                            return settings.containsKey(values[0]) ? settings.get(values[0]) : values[1];
                        if ("getAll".equals(method.getName())) return settings;
                        if ("edit".equals(method.getName())) return editor;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });
            Context context = new ContextWrapper(null) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return preferences;
                }
                @Override public Context getApplicationContext() { return this; }
            };
            boolean realTls = "tls".equals(args[3]);
            SSLContext tls;
            String pin = null;
            String legacyPin = null;
            if (realTls) {
                // Real KeyStore also consults ActivityThread.currentApplication().
                // Bootstrap the emulator's system context, never DashCast's Application.
                Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
                // ActivityThread normally installs this platform provider when
                // binding an app. app_process has no app lifecycle to do so.
                Class.forName("android.security.keystore.AndroidKeyStoreProvider")
                        .getMethod("install").invoke(null);
                // These aliases belong to the shell UID of a dedicated emulator, not
                // an installed app. Refuse to overwrite any pre-existing identity.
                store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (store.containsAlias(LEGACY) || store.containsAlias(CURRENT))
                    throw new AssertionError("Probe requires unused shell-UID TLS aliases");
                ownsProbeKeys = true;
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(LEGACY,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(new X500Principal("CN=Probe legacy identity"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(new Date(0)).setCertificateNotAfter(new Date(4102444800000L))
                        .build());
                generator.generateKeyPair();
                StringBuilder legacyHash = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(store.getCertificate(LEGACY).getEncoded()))
                    legacyHash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                legacyPin = legacyHash.toString();
                boolean rejected = false;
                try {
                    Cipher raw = Cipher.getInstance("RSA/ECB/NoPadding");
                    raw.init(Cipher.ENCRYPT_MODE, store.getKey(LEGACY, null));
                    raw.doFinal(new byte[256]);
                } catch (java.security.GeneralSecurityException | java.security.ProviderException expected) {
                    rejected = true;
                }
                if (!rejected) throw new AssertionError("Legacy identity unexpectedly permits raw TLS signing");
                System.out.println("ART_LEGACY_RAW_SIGN_REJECTED");
                Class<?> tlsClass = Class.forName(args[5], true, loader);
                Class<?> singletonClass = Class.forName(args[8], true, loader);
                Field singleton = singletonClass.getDeclaredField(args[9]);
                singleton.setAccessible(true);
                Object tlsIdentity = singleton.get(null);
                tls = (SSLContext) callSingleton(tlsClass, args[6], tlsIdentity);
                pin = (String) callSingleton(tlsClass, args[7], tlsIdentity);
                if (!store.containsAlias(LEGACY)) throw new AssertionError("Migration deleted legacy identity");
                if (!pin.equals(callSingleton(tlsClass, args[7], tlsIdentity)))
                    throw new AssertionError("Identity rotated during profile preparation");
                callSingleton(tlsClass, args[6], tlsIdentity);
                System.out.println("ART_KEYSTORE_MIGRATION_STABLE");
            } else {
                tls = SSLContext.getInstance("TLSv1.2");
                tls.init(null, null, null);
            }
            Constructor<?> constructor = null;
            for (Constructor<?> candidate : serverClass.getDeclaredConstructors()) {
                Class<?>[] types = candidate.getParameterTypes();
                if (types.length == 4 && types[0] == Context.class &&
                        types[1] == SSLContext.class && types[2] == String.class) {
                    constructor = candidate;
                    break;
                }
            }
            if (constructor == null) throw new AssertionError("Server constructor unavailable");
            Class<?> eventType = constructor.getParameterTypes()[3];
            Object events;
            if (eventType.isInterface()) {
                events = Proxy.newProxyInstance(loader, new Class<?>[] {eventType},
                        (proxy, method, values) -> null);
            } else {
                // R8 can specialize the Events interface to its sole concrete implementation.
                Constructor<?> eventConstructor = null;
                for (Constructor<?> candidate : eventType.getDeclaredConstructors()) {
                    Class<?>[] types = candidate.getParameterTypes();
                    if (types.length == 2 && android.app.Service.class.isAssignableFrom(types[0])
                            && types[1] == long.class) {
                        eventConstructor = candidate;
                        break;
                    }
                }
                if (eventConstructor == null) throw new AssertionError("Event constructor unavailable");
                eventConstructor.setAccessible(true);
                Constructor<?> serviceConstructor = eventConstructor.getParameterTypes()[0]
                        .getDeclaredConstructor();
                serviceConstructor.setAccessible(true);
                Object service = serviceConstructor.newInstance();
                Field base = ContextWrapper.class.getDeclaredField("mBase");
                base.setAccessible(true);
                base.set(service, context);
                events = eventConstructor.newInstance(service, 0L);
            }
            constructor.setAccessible(true);
            Object server = constructor.newInstance(context, tls, TOKEN, events);
            System.out.println("ART_SERVER_CONSTRUCTOR_OK");
            Thread selectorThread = new Thread((Runnable) server, "release-art-selector");
            selectorThread.setDaemon(true);
            selectorThread.start();
            int port = Integer.parseInt(args[4]);
            boolean connected = false;
            ServerSocketChannel ownedChannel = null;
            long deadline = System.nanoTime() + 15_000_000_000L;
            try {
                while (!connected && System.nanoTime() < deadline) {
                    boolean ownedListener = false;
                    for (Class<?> type = serverClass; type != null; type = type.getSuperclass()) {
                        for (Field field : type.getDeclaredFields()) {
                            if (ServerSocketChannel.class.isAssignableFrom(field.getType())) {
                                field.setAccessible(true);
                                ServerSocketChannel channel = (ServerSocketChannel) field.get(server);
                                ownedListener |= channel != null && channel.isOpen()
                                        && channel.socket().isBound()
                                        && channel.socket().getLocalPort() == port;
                                if (ownedListener) ownedChannel = channel;
                            }
                        }
                    }
                    if (!ownedListener) {
                        if (!selectorThread.isAlive()) throw new AssertionError("Selector exited before binding");
                        Thread.sleep(100);
                        continue;
                    }
                    if (!selectorThread.isAlive()) throw new AssertionError("Selector exited after binding");
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                        connected = true;
                    } catch (java.io.IOException retry) {
                        Thread.sleep(100);
                    }
                }
                if (!connected) throw new AssertionError("Control listener did not bind");
                System.out.println("ART_CONTROL_LISTENER_BOUND");
                if (realTls) {
                    checkWrongPin(port, pin);
                    checkRejectedPin(port, legacyPin);
                    System.out.println("ART_LEGACY_PIN_REJECTED");
                    checkWebSocket(port, pin, "invalid-test-token", false);
                    System.out.println("ART_WRONG_TOKEN_REJECTED");
                    checkWebSocket(port, pin, TOKEN, true);
                    System.out.println("ART_PINNED_WSS_HELLO_WELCOME");
                    Thread.sleep(500);
                    checkWebSocket(port, pin, TOKEN, true);
                    System.out.println("ART_PINNED_WSS_RECONNECT_OK");
                }
            } finally {
                selectorThread.interrupt();
                for (Class<?> type = serverClass; type != null; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (Selector.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            Selector selector = (Selector) field.get(server);
                            if (selector != null && selector.isOpen()) selector.wakeup();
                        }
                    }
                }
                selectorThread.join(5_000);
            }
            if (selectorThread.isAlive()) throw new AssertionError("Selector did not stop");
            if (ownedChannel == null || ownedChannel.isOpen())
                throw new AssertionError("Control listener channel was not closed");
            System.out.println("ART_CONTROL_LISTENER_STOPPED");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            if (ownsProbeKeys && store != null) {
                try { store.deleteEntry(CURRENT); store.deleteEntry(LEGACY); }
                catch (Exception cleanup) { cleanup.printStackTrace(System.err); exitCode = 1; }
            }
        }
        System.exit(exitCode);
    }

    private static Object callSingleton(Class<?> owner, String name, Object singleton) throws Exception {
        Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        if (Modifier.isStatic(method.getModifiers())) return method.invoke(null);
        return method.invoke(singleton);
    }

    private static SSLContext pinned(String pin) throws Exception {
        X509TrustManager manager = new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not used");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    byte[] expected = new byte[32];
                    for (int i = 0; i < expected.length; i++)
                        expected[i] = (byte) Integer.parseInt(pin.substring(i * 2, i * 2 + 2), 16);
                    if (!MessageDigest.isEqual(expected, hash)) throw new CertificateException("Pin mismatch");
                } catch (CertificateException error) { throw error; }
                catch (Exception error) { throw new CertificateException(error); }
            }
        };
        SSLContext result = SSLContext.getInstance("TLSv1.2");
        result.init(null, new javax.net.ssl.TrustManager[] {manager}, null);
        return result;
    }

    private static SSLSocket socket(int port, String pin) throws Exception {
        SSLSocket socket = (SSLSocket) pinned(pin).getSocketFactory().createSocket();
        socket.setEnabledProtocols(new String[] {"TLSv1.2"});
        socket.setSoTimeout(10000);
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        return socket;
    }

    private static void checkWrongPin(int port, String pin) throws Exception {
        String incorrect = (pin.startsWith("0") ? "1" : "0") + pin.substring(1);
        checkRejectedPin(port, incorrect);
        System.out.println("ART_WRONG_PIN_REJECTED");
    }

    private static void checkRejectedPin(int port, String incorrect) throws Exception {
        boolean rejected = false;
        try (SSLSocket socket = socket(port, incorrect)) {
            socket.startHandshake();
        } catch (javax.net.ssl.SSLException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Wrong certificate pin was accepted");
    }

    private static void checkWebSocket(int port, String pin, String token, boolean expectedWelcome) throws Exception {
        try (SSLSocket socket = socket(port, pin)) {
            socket.startHandshake();
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();
            output.write(("GET /satellite/v1 HTTP/1.1\r\nHost: 127.0.0.1:" + port +
                    "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            StringBuilder header = new StringBuilder();
            while (header.length() < 8192 && !header.toString().endsWith("\r\n\r\n")) {
                int value = input.read();
                if (value < 0) throw new AssertionError("Upgrade closed before response");
                header.append((char) value);
            }
            if (!header.toString().startsWith("HTTP/1.1 101 "))
                throw new AssertionError("WebSocket upgrade rejected");
            sendFrame(output, 1, new JSONObject().put("type", "hello").put("version", 1)
                    .put("token", token).toString().getBytes(StandardCharsets.UTF_8));
            int opcode = input.read() & 15;
            int length = input.read();
            if (length < 0 || (length & 128) != 0) throw new AssertionError("Invalid server frame");
            if (length == 126) length = (input.read() << 8) | input.read();
            if (length > 16384 || length == 127) throw new AssertionError("Unexpected frame size");
            byte[] payload = new byte[length];
            for (int i = 0; i < length; i++) {
                int value = input.read();
                if (value < 0) throw new AssertionError("Truncated server frame");
                payload[i] = (byte) value;
            }
            if (expectedWelcome) {
                JSONObject welcome = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                if (opcode != 1 || !"welcome".equals(welcome.optString("type")))
                    throw new AssertionError("Authenticated hello did not get welcome");
                for (String capability : new String[] {"videoDiagnostics", "videoDiagnosticsDetails", "videoDiagnosticsH264"})
                    if (!(welcome.get(capability) instanceof Integer) || welcome.getInt(capability) != 1)
                        throw new AssertionError("Missing exact diagnostics capability");
                checkH264Diagnostics(input, output);
                sendFrame(output, 8, new byte[] {3, (byte) 232});
                // Complete the close handshake before proving a replacement connection.
                // Sending bytes individually creates one TLS record per byte and can
                // trip liveness deadlines on a software-emulated device.
                while (input.read() != -1) { /* bounded by the socket read timeout */ }
            } else if (opcode != 8 || length < 2 || (((payload[0] & 255) << 8) | (payload[1] & 255)) != 1008) {
                throw new AssertionError("Wrong token did not close with authentication failure");
            }
        }
    }

    /** Exercises strict schema 2 in the exact minified APK, then proves rejection preserves control. */
    private static void checkH264Diagnostics(InputStream input, OutputStream output) throws Exception {
        JSONObject observation = new JSONObject().put("ageMs", 500).put("declaredKey", true)
                .put("width", 960).put("height", 540).put("framing", "annex_b").put("inspection", "complete")
                .put("sps", true).put("pps", true).put("idr", true)
                .put("profileIdc", 66).put("constraintFlags", 224).put("levelIdc", 31);
        JSONObject attempt = new JSONObject().put("attempt", 1).put("share", 1).put("ageMs", 1000)
                .put("sampleAgeMs", JSONObject.NULL).put("ended", true).put("codec", JSONObject.NULL)
                .put("h264", new org.json.JSONArray().put(observation));
        for (String key : new String[] {"bytesSent", "packetsSent", "framesEncoded", "keyFramesEncoded", "pliCount", "nackCount", "firCount"})
            attempt.put(key, JSONObject.NULL);
        JSONObject diagnostic = new JSONObject().put("type", "diagnostics.video").put("schema", 1).put("request", 1)
                .put("snapshotAgeMs", 0).put("appVersionCode", 16).put("state", "capture_paused")
                .put("captureBusy", true).put("share", 1).put("counts", new JSONObject()).put("recent", new org.json.JSONArray())
                .put("details", new JSONObject().put("schema", 2).put("attempts", new org.json.JSONArray().put(attempt)));
        sendFrame(output, 1, diagnostic.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject accepted = readJson(input);
        if (!"diagnostics.video.ack".equals(accepted.optString("type")) || accepted.getInt("schema") != 1 ||
                accepted.getInt("request") != 1 || !"accepted".equals(accepted.optString("status")))
            throw new AssertionError("Valid H264 diagnostics not accepted");
        diagnostic.put("request", 2); observation.put("inspection", "truncated").put("pps", false);
        sendFrame(output, 1, diagnostic.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject rejected = readJson(input);
        if (!"diagnostics.video.ack".equals(rejected.optString("type")) || rejected.getInt("request") != 2 ||
                !"rejected".equals(rejected.optString("status")))
            throw new AssertionError("Malformed H264 diagnostics not rejected independently");
        sendFrame(output, 1, new JSONObject().put("type", "ping").toString().getBytes(StandardCharsets.UTF_8));
        if (!"pong".equals(readJson(input).optString("type")))
            throw new AssertionError("Diagnostics rejection interrupted authenticated control");
        System.out.println("ART_H264_DIAGNOSTICS_ACCEPT_REJECT_CONTROL_OK");
    }

    private static JSONObject readJson(InputStream input) throws Exception {
        int first = input.read(); int length = input.read();
        if (first < 0 || (first & 15) != 1 || length < 0 || (length & 128) != 0)
            throw new AssertionError("Expected unmasked text frame");
        if (length == 126) length = (input.read() << 8) | input.read();
        else if (length == 127) throw new AssertionError("Unexpected large frame");
        if (length > 16384) throw new AssertionError("Response budget exceeded");
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            int value = input.read(); if (value < 0) throw new AssertionError("Truncated response"); payload[i] = (byte) value;
        }
        return new JSONObject(new String(payload, StandardCharsets.UTF_8));
    }

    private static void sendFrame(OutputStream output, int opcode, byte[] payload) throws Exception {
        if (payload.length > 16384) throw new AssertionError("Probe frame budget exceeded");
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(128 | opcode);
        if (payload.length < 126) frame.write(128 | payload.length);
        else { frame.write(128 | 126); frame.write(payload.length >> 8); frame.write(payload.length & 255); }
        byte[] mask = new byte[] {17, 34, 51, 68};
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i % 4]);
        output.write(frame.toByteArray());
        output.flush();
    }
}
