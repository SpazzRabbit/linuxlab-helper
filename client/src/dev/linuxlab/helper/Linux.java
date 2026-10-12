package dev.linuxlab.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** What can be learned about Linux Lab from outside, and what can be asked of it. */
final class Linux {
    static final String PACKAGE = "com.hihonor.pcengine";
    /** lldesktop.py inside Linux Lab: says which desktop runs and changes it */
    static final int DESKTOP_PORT = 54397;

    private Linux() {
    }

    /** Linux Lab is installed and switched on in the developer options. */
    static boolean installed(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(PACKAGE, 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void open(Activity activity) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch != null) {
            activity.startActivity(launch);
        }
    }

    /**
     * Size of the Linux screen when the touch module answers, else null. The module serves one
     * client; while the service is connected its word is taken, otherwise a short connection
     * of our own disturbs nothing.
     */
    static int[] module() {
        int[] size = TouchService.connectedScreen();
        if (size != null) {
            return size;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", Link.PORT), 400);
            socket.setSoTimeout(800);
            String[] parts = words(socket);
            if (parts.length == 3 && "LLTOUCH".equals(parts[0])) {
                return new int[] {Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            }
        } catch (IOException | NumberFormatException e) {
            // nobody there
        }
        return null;
    }

    /**
     * What runs while the switch of the Ubuntu desktop answers, else null: {"gnome" or "lxqt",
     * "proot" or "chroot", the release serial of the installer that set it up}. The second is null when
     * the switch is older than the chroot mode, the third is "0" when it is older than the serials.
     */
    static String[] desktop() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", DESKTOP_PORT), 400);
            socket.setSoTimeout(800);
            String[] parts = words(socket);
            if (parts.length >= 3 && "LLDESKTOP".equals(parts[0])) {
                return new String[] {parts[2], parts.length >= 4 ? parts[3] : null, parts.length >= 6 ? parts[5] : "0"};
            }
        } catch (IOException e) {
            // nobody there
        }
        return null;
    }

    /** Asks for the other desktop. Returns null when it is on its way, else what went wrong. */
    static String switchDesktop(String name) {
        return ask("desktop " + name);
    }

    /** Asks for the desktop to run under PRoot or in a chroot ("proot", "chroot"). Like switchDesktop. */
    static String switchEngine(String name) {
        return ask("engine " + name);
    }

    private static String ask(String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", DESKTOP_PORT), 500);
            socket.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String hello = in.readLine();
            if (hello == null || !hello.startsWith("LLDESKTOP ")) {
                return "no answer";
            }
            socket.getOutputStream().write((command + "\n").getBytes(StandardCharsets.US_ASCII));
            String reply = in.readLine();
            return "ok".equals(reply) ? null : String.valueOf(reply);
        } catch (IOException e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static String[] words(Socket socket) throws IOException {
        String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        return line == null ? new String[0] : line.trim().split(" ");
    }
}
