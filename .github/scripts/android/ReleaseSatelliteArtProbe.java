import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Looper;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Collections;
import javax.net.ssl.SSLContext;

/** Loads the actual minified APK on ART; never installs it or starts an Android service. */
public final class ReleaseSatelliteArtProbe {
    private static final String TOKEN = "local-art-regression-placeholder";

    public static void main(String[] args) {
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
            SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                    loader, new Class<?>[] {SharedPreferences.class}, (proxy, method, values) -> {
                        if ("getBoolean".equals(method.getName()))
                            return "enabled".equals(values[0]);
                        if ("getString".equals(method.getName())) return TOKEN;
                        if ("getAll".equals(method.getName())) return Collections.emptyMap();
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });
            Context context = new ContextWrapper(null) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return preferences;
                }
            };
            SSLContext tls = SSLContext.getInstance("TLSv1.2");
            tls.init(null, null, null);
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
                events = eventConstructor.newInstance(serviceConstructor.newInstance(), 0L);
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
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
