package com.example.phonebrowserdownload;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class FileServer {
    private static final int PORT = 8765;
    private final ContentResolver resolver;
    private final Uri tree;
    private final String password;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile boolean running;
    private ServerSocket socket;
    private Thread acceptThread;

    private static final class Entry {
        final String documentId, name, mime;
        final long size;
        Entry(String documentId, String name, String mime, long size) {
            this.documentId = documentId; this.name = name; this.mime = mime; this.size = size;
        }
        boolean directory() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); }
    }

    FileServer(ContentResolver resolver, Uri tree) {
        this.resolver = resolver;
        this.tree = tree;
        String root = DocumentsContract.getTreeDocumentId(tree);
        entries.put("root", new Entry(root, "Selected folder", DocumentsContract.Document.MIME_TYPE_DIR, -1));
        SecureRandom rng = new SecureRandom();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder pass = new StringBuilder();
        for (int i = 0; i < 14; i++) pass.append(chars.charAt(rng.nextInt(chars.length())));
        password = pass.toString();
    }

    void start() throws IOException {
        socket = new ServerSocket(PORT, 16);
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = socket.accept();
                    client.setSoTimeout(15000);
                    pool.execute(() -> handle(client));
                } catch (IOException | java.util.concurrent.RejectedExecutionException e) {
                    if (!running) break;
                }
            }
        }, "phone-files-accept");
        acceptThread.start();
    }
    int getPort() { return PORT; }
    String getPassword() { return password; }

    void close() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
        entries.clear();
    }

    private void handle(Socket client) {
        try (Socket c = client;
             InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new BufferedOutputStream(c.getOutputStream())) {
            String request = readLine(in);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 3 || !parts[0].equals("GET")) { response(out, 405, "Method Not Allowed", "Only GET is supported"); return; }
            String auth = null;
            int headerCount = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (++headerCount > 60) return;
                if (line.regionMatches(true, 0, "Authorization:", 0, 14)) auth = line.substring(14).trim();
            }
            if (!authenticated(auth)) {
                write(out, "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Phone files\"\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                out.flush(); return;
            }
            String target = parts[1];
            String path = target.split("\\?", 2)[0];
            String key = queryParameter(target, "id");
            if (path.equals("/") || path.equals("/browse")) {
                Entry dir = entries.get(key == null ? "root" : key);
                if (dir == null || !dir.directory()) { response(out, 404, "Not Found", "Folder not found"); return; }
                serveDirectory(out, dir);
            } else if (path.equals("/download")) {
                Entry file = entries.get(key);
                if (file == null || file.directory()) { response(out, 404, "Not Found", "File not found"); return; }
                serveFile(out, file);
            } else {
                response(out, 404, "Not Found", "Not found");
            }
            out.flush();
        } catch (Exception ignored) { /* broken connections should not interrupt sharing */ }
    }

    private boolean authenticated(String auth) {
        if (auth == null || !auth.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            byte[] actual = decoded.getBytes(StandardCharsets.UTF_8);
            byte[] expected = ("phone:" + password).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException e) { return false; }
    }

    private void serveDirectory(OutputStream out, Entry directory) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory.documentId);
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Phone files</title>" +
                "<style>body{font:16px system-ui,sans-serif;max-width:860px;margin:40px auto;padding:0 18px;color:#202124}" +
                "h1{font-size:25px}li{padding:12px 6px;border-bottom:1px solid #ddd;overflow-wrap:anywhere}a{color:#1256aa;text-decoration:none}a:hover{text-decoration:underline}" +
                ".hint{color:#555;font-size:14px}.folder{font-weight:600}</style></head><body><h1>Phone files</h1>");
        html.append("<p class=\"hint\">Read-only access to the folder selected on your phone. Select a file to download.</p>");
        html.append("<h2>").append(escape(directory.name)).append("</h2><ul>");
        int count = 0;
        try (Cursor c = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) throw new IOException("Cannot list folder");
            while (c.moveToNext()) {
                if (++count > 10000) { html.append("<li>Folder has too many entries to display.</li>"); break; }
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? -1 : c.getLong(3);
                if (id == null) continue;
                Entry entry = new Entry(id, name == null ? "Unnamed" : name, mime == null ? "application/octet-stream" : mime, size);
                // Only IDs discovered while browsing from the selected root are made accessible.
                if (entries.size() > 50000) entries.clear();
                entries.put("root", entries.getOrDefault("root", new Entry(DocumentsContract.getTreeDocumentId(tree), "Selected folder", DocumentsContract.Document.MIME_TYPE_DIR, -1)));
                String handle = UUID.randomUUID().toString();
                entries.put(handle, entry);
                html.append("<li><a ").append(entry.directory() ? "class=\"folder\" " : "")
                    .append("href=\"").append(entry.directory() ? "/browse?id=" : "/download?id=")
                    .append(handle).append("\">")
                    .append(entry.directory() ? "📁 " : "⬇ ")
                    .append(escape(entry.name)).append("</a>");
                if (!entry.directory() && size >= 0) html.append(" <span class=\"hint\">(").append(humanSize(size)).append(")</span>");
                html.append("</li>");
            }
        } catch (SecurityException e) { throw new IOException("Folder access has expired", e); }
        html.append("</ul><p class=\"hint\">Stop sharing on the phone when finished.</p></body></html>");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n");
        out.write(bytes);
    }

    private void serveFile(OutputStream out, Entry file) throws IOException {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId);
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) throw new IOException("Cannot open file");
            String filename = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20");
            String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"download\"; filename*=UTF-8''" + filename + "\r\n" +
                "X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n" +
                (file.size >= 0 ? "Content-Length: " + file.size + "\r\n" : "") +
                "Connection: close\r\n\r\n";
            write(out, headers);
            byte[] buffer = new byte[65536]; int n;
            while (running && (n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }

    private static String queryParameter(String target, String wanted) {
        int index = target.indexOf('?'); if (index < 0) return null;
        for (String part : target.substring(index + 1).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && wanted.equals(pair[0])) {
                try { return URLDecoder.decode(pair[1], "UTF-8"); } catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char)b);
        }
        throw new IOException("HTTP line too long");
    }
    private static void response(OutputStream out, int status, String reason, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        write(out, "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n");
        out.write(bytes);
    }
    private static void write(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    private static String humanSize(long size) {
        if (size < 1024) return size + " B";
        double n = size; String[] units = {"B", "KB", "MB", "GB", "TB"}; int i = 0;
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", n, units[i]);
    }
    static String localAddresses(int port) {
        StringBuilder result = new StringBuilder();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        result.append("http://").append(addr.getHostAddress()).append(":").append(port).append("/\n");
                    }
                }
            }
        } catch (SocketException ignored) { }
        if (result.length() == 0) return "No network address found. Connect to Wi-Fi and restart sharing.\n";
        return result.toString();
    }
}
