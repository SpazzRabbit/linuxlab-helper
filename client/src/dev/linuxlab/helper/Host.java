package dev.linuxlab.helper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * TCP link to llandroid, the daemon of the GNOME session that takes what only Android has:
 * the charge of the battery and the text typed on the Android keyboard. Lines of text both
 * ways; the daemon describes them (gnome/llandroid.py). A desktop without the daemon simply
 * never answers.
 */
final class Host {
    interface Listener {
        void onHostState(boolean connected);

        /** The desktop asks for the Android keyboard: 0 hide, 1 show, 2 toggle. */
        void onKeyboardWanted(int state);

        /** Which screen keyboard is chosen in Linux Lab's settings: Android's or Linux's. */
        void onKeyboardChosen(boolean android);
    }

    static final int PORT = 54399;
    private static final String GREETING = "LLANDROID";
    // told apart from real lines by identity
    private static final String POISON = new String("");

    private final Listener listener;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private volatile boolean wanted;
    private volatile boolean connected;
    private volatile boolean stopped;

    Host(Listener listener) {
        this.listener = listener;
        Thread thread = new Thread(this::run, "llandroid-link");
        thread.setDaemon(true);
        thread.start();
    }

    /** Keep trying to connect only while the Linux Lab window is on screen. */
    void setWanted(boolean value) {
        if (wanted != value) {
            wanted = value;
            queue.offer(POISON);
        }
    }

    boolean isConnected() {
        return connected;
    }

    void send(String line) {
        if (connected) {
            queue.offer(line);
        }
    }

    void stop() {
        stopped = true;
        queue.offer(POISON);
    }

    private void run() {
        while (!stopped) {
            if (!wanted) {
                take(1000);
                continue;
            }
            try (Socket socket = new Socket()) {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                socket.setSoTimeout(2000);
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String hello = in.readLine();
                if (hello == null || !hello.startsWith(GREETING)) {
                    throw new IOException("bad hello");
                }
                socket.setSoTimeout(0);
                OutputStream out = socket.getOutputStream();
                queue.clear();
                Thread reader = new Thread(() -> read(in, socket), "llandroid-reader");
                reader.setDaemon(true);
                reader.start();
                setConnected(true);
                while (!stopped && wanted && !socket.isClosed()) {
                    String line = take(3000);
                    if (line == POISON) {
                        continue;
                    }
                    // an idle link is probed with a line the daemon ignores, so a dead one is noticed
                    out.write(((line == null ? "ping" : line) + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException e) {
                // no daemon, or it is gone: retry below
            }
            setConnected(false);
            if (!stopped && wanted) {
                take(2000);
            }
        }
    }

    private void read(BufferedReader in, Socket socket) {
        try {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.startsWith("keyboard ")) {
                    try {
                        listener.onKeyboardWanted(Integer.parseInt(line.substring(9).trim()));
                    } catch (NumberFormatException e) {
                        // not for us
                    }
                } else if (line.startsWith("ime ")) {
                    listener.onKeyboardChosen(line.substring(4).trim().equals("1"));
                }
            }
        } catch (IOException e) {
            // the writer finds out below
        }
        try {
            socket.close();
        } catch (IOException e) {
            // closed already
        }
        queue.offer(POISON);
    }

    private String take(long millis) {
        try {
            return queue.poll(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return null;
        }
    }

    private void setConnected(boolean value) {
        if (connected != value) {
            connected = value;
            listener.onHostState(value);
        }
    }
}
