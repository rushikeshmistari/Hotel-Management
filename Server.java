import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verdure Hotel backend.
 * Pure JDK (Java 17+): com.sun.net.httpserver for HTTP, a tiny JSON codec, and a JSON file for storage.
 *
 * Run:   java Server.java
 * Open:  http://localhost:8080
 *
 * Optional env vars: PORT (default 8080), ADMIN_PASSWORD (default verdure123)
 */
public class Server {

    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "verdure123");
    static final Path DATA_FILE = Paths.get("data.json");
    static final Path PUBLIC_DIR = Paths.get("public").toAbsolutePath().normalize();
    static final SecureRandom RNG = new SecureRandom();

    // ---- in-memory state, persisted to data.json ----
    static final Object LOCK = new Object();
    static List<Object> rooms = new ArrayList<>();
    static List<Object> bookings = new ArrayList<>();
    static List<Object> customers = new ArrayList<>();
    static List<Object> messages = new ArrayList<>();

    record Session(String role, String customerId) {}
    static final Map<String, Session> sessions = new ConcurrentHashMap<>();

    static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    // =====================================================================
    //  Main
    // =====================================================================
    public static void main(String[] args) throws Exception {
        load();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", Server::handle);
        server.setExecutor(null);
        server.start();
        System.out.println("Verdure running at http://localhost:" + PORT);
        System.out.println("Admin password: " + ADMIN_PASSWORD + "  (override with ADMIN_PASSWORD env var)");
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                synchronized (LOCK) { api(ex, path); }
            } else {
                serveStatic(ex, path);
            }
        } catch (ApiException e) {
            sendJson(ex, e.status, map("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, map("error", "Something went wrong on the server."));
        } finally {
            ex.close();
        }
    }

    // =====================================================================
    //  API routing
    // =====================================================================
    static void api(HttpExchange ex, String path) throws IOException {
        String method = ex.getRequestMethod();
        String[] p = path.substring("/api/".length()).split("/");
        String r0 = p[0];

        // ---- public ----
        if (r0.equals("rooms") && p.length == 1 && method.equals("GET")) {
            sendJson(ex, 200, rooms);
            return;
        }
        if (r0.equals("signup") && method.equals("POST")) { signup(ex); return; }
        if (r0.equals("login") && method.equals("POST")) { login(ex); return; }
        if (r0.equals("contact") && method.equals("POST")) { contact(ex); return; }

        // ---- customer ----
        if (r0.equals("me") && method.equals("GET")) {
            Map<String, Object> c = requireCustomer(ex);
            sendJson(ex, 200, publicCustomer(c));
            return;
        }
        if (r0.equals("logout") && method.equals("POST")) {
            String t = token(ex);
            if (t != null) sessions.remove(t);
            sendJson(ex, 200, map("ok", true));
            return;
        }
        if (r0.equals("bookings")) {
            if (p.length == 1 && method.equals("POST")) { createBooking(ex); return; }
            if (p.length == 2 && p[1].equals("mine") && method.equals("GET")) { myBookings(ex); return; }
            if (p.length == 3 && p[2].equals("cancel") && method.equals("POST")) { cancelBooking(ex, p[1]); return; }
        }

        // ---- admin ----
        if (r0.equals("admin")) {
            if (p.length == 2 && p[1].equals("login") && method.equals("POST")) { adminLogin(ex); return; }
            requireAdmin(ex);
            if (p.length == 2 && p[1].equals("bookings") && method.equals("GET")) { sendJson(ex, 200, bookings); return; }
            if (p.length == 3 && p[1].equals("bookings")) {
                if (method.equals("PATCH")) { adminSetStatus(ex, p[2]); return; }
                if (method.equals("DELETE")) { adminDeleteBooking(ex, p[2]); return; }
            }
            if (p.length == 2 && p[1].equals("rooms") && method.equals("POST")) { adminAddRoom(ex); return; }
            if (p.length == 3 && p[1].equals("rooms")) {
                if (method.equals("PATCH")) { adminToggleRoom(ex, p[2]); return; }
                if (method.equals("DELETE")) { adminDeleteRoom(ex, p[2]); return; }
            }
            if (p.length == 2 && p[1].equals("messages") && method.equals("GET")) { sendJson(ex, 200, messages); return; }
        }

        throw new ApiException(404, "No such endpoint.");
    }

    // =====================================================================
    //  Auth handlers
    // =====================================================================
    static void signup(HttpExchange ex) throws IOException {
        Map<String, Object> b = body(ex);
        String name = str(b, "name"), email = str(b, "email").toLowerCase(), phone = str(b, "phone"), pw = raw(b, "password");

        if (name.length() < 2 || name.length() > 80) throw new ApiException(400, "Please enter your full name.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new ApiException(400, "Please enter a valid email.");
        if (pw.length() < 6) throw new ApiException(400, "Password must be at least 6 characters.");
        if (findCustomerByEmail(email) != null) throw new ApiException(409, "An account with that email already exists - try logging in.");

        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", newId("cust"));
        c.put("name", name);
        c.put("email", email);
        c.put("phone", phone);
        c.put("salt", Base64.getEncoder().encodeToString(salt));
        c.put("hash", hashPassword(pw, salt));
        c.put("createdAt", java.time.Instant.now().toString());
        customers.add(c);
        save();

        String t = newSession(new Session("customer", (String) c.get("id")));
        sendJson(ex, 201, map("token", t, "customer", publicCustomer(c)));
    }

    static void login(HttpExchange ex) throws IOException {
        Map<String, Object> b = body(ex);
        String email = str(b, "email").toLowerCase(), pw = raw(b, "password");
        Map<String, Object> c = findCustomerByEmail(email);
        if (c == null || !verifyPassword(pw, c)) throw new ApiException(401, "Email or password is incorrect.");
        String t = newSession(new Session("customer", (String) c.get("id")));
        sendJson(ex, 200, map("token", t, "customer", publicCustomer(c)));
    }

    static void adminLogin(HttpExchange ex) throws IOException {
        Map<String, Object> b = body(ex);
        byte[] given = raw(b, "password").getBytes(StandardCharsets.UTF_8);
        byte[] real = ADMIN_PASSWORD.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, real)) throw new ApiException(401, "Incorrect password.");
        String t = newSession(new Session("admin", null));
        sendJson(ex, 200, map("token", t));
    }

    // =====================================================================
    //  Booking handlers
    // =====================================================================
    @SuppressWarnings("unchecked")
    static void createBooking(HttpExchange ex) throws IOException {
        Map<String, Object> customer = requireCustomer(ex);
        Map<String, Object> b = body(ex);

        Map<String, Object> room = findById(rooms, str(b, "roomId"));
        if (room == null) throw new ApiException(400, "Please choose a room.");
        if (!Boolean.TRUE.equals(room.get("available"))) throw new ApiException(409, "That room isn't available right now.");

        LocalDate in, out;
        try {
            in = LocalDate.parse(str(b, "checkin"));
            out = LocalDate.parse(str(b, "checkout"));
        } catch (Exception e) {
            throw new ApiException(400, "Please pick valid check-in and check-out dates.");
        }
        if (in.isBefore(LocalDate.now())) throw new ApiException(400, "Check-in can't be in the past.");
        long nights = ChronoUnit.DAYS.between(in, out);
        if (nights <= 0) throw new ApiException(400, "Check-out must be after check-in.");
        if (nights > 30) throw new ApiException(400, "Stays are limited to 30 nights per booking.");

        long capacity = ((Number) room.get("capacity")).longValue();
        long guests;
        try { guests = Long.parseLong(str(b, "guests")); } catch (Exception e) { guests = -1; }
        if (guests < 1 || guests > capacity)
            throw new ApiException(400, room.get("name") + " sleeps up to " + capacity + " guests.");

        String phone = str(b, "phone");
        if (phone.length() < 6) throw new ApiException(400, "Please add a phone number we can reach you on.");

        // prevent double-booking the same room on overlapping dates
        for (Object o : bookings) {
            Map<String, Object> other = (Map<String, Object>) o;
            if (!room.get("id").equals(other.get("roomId"))) continue;
            if ("cancelled".equals(other.get("status"))) continue;
            LocalDate oIn = LocalDate.parse((String) other.get("checkin"));
            LocalDate oOut = LocalDate.parse((String) other.get("checkout"));
            if (in.isBefore(oOut) && out.isAfter(oIn))
                throw new ApiException(409, "That room is already booked for some of those dates.");
        }

        long price = ((Number) room.get("price")).longValue();
        Map<String, Object> bk = new LinkedHashMap<>();
        bk.put("id", newId("bk"));
        bk.put("customerId", customer.get("id"));
        bk.put("name", customer.get("name"));
        bk.put("email", customer.get("email"));
        bk.put("phone", phone);
        bk.put("roomId", room.get("id"));
        bk.put("roomName", room.get("name"));
        bk.put("checkin", in.toString());
        bk.put("checkout", out.toString());
        bk.put("guests", guests);
        bk.put("notes", str(b, "notes"));
        bk.put("nights", nights);
        bk.put("total", price * nights); // always computed server-side
        bk.put("status", "pending");
        bk.put("createdAt", java.time.Instant.now().toString());
        bookings.add(bk);
        save();
        sendJson(ex, 201, bk);
    }

    @SuppressWarnings("unchecked")
    static void myBookings(HttpExchange ex) throws IOException {
        Map<String, Object> customer = requireCustomer(ex);
        List<Object> mine = new ArrayList<>();
        for (Object o : bookings) {
            Map<String, Object> bk = (Map<String, Object>) o;
            if (customer.get("id").equals(bk.get("customerId"))) mine.add(bk);
        }
        sendJson(ex, 200, mine);
    }

    static void cancelBooking(HttpExchange ex, String id) throws IOException {
        Map<String, Object> customer = requireCustomer(ex);
        Map<String, Object> bk = findById(bookings, id);
        if (bk == null || !customer.get("id").equals(bk.get("customerId")))
            throw new ApiException(404, "Booking not found.");
        if (!"pending".equals(bk.get("status")))
            throw new ApiException(409, "Only pending bookings can be cancelled here - please contact the front desk.");
        bk.put("status", "cancelled");
        save();
        sendJson(ex, 200, bk);
    }

    static void adminSetStatus(HttpExchange ex, String id) throws IOException {
        Map<String, Object> bk = findById(bookings, id);
        if (bk == null) throw new ApiException(404, "Booking not found.");
        String status = str(body(ex), "status");
        if (!List.of("pending", "confirmed", "cancelled").contains(status))
            throw new ApiException(400, "Invalid status.");
        bk.put("status", status);
        save();
        sendJson(ex, 200, bk);
    }

    static void adminDeleteBooking(HttpExchange ex, String id) throws IOException {
        if (!bookings.removeIf(o -> id.equals(((Map<?, ?>) o).get("id")))) throw new ApiException(404, "Booking not found.");
        save();
        sendJson(ex, 200, map("ok", true));
    }

    // =====================================================================
    //  Room handlers (admin)
    // =====================================================================
    static void adminAddRoom(HttpExchange ex) throws IOException {
        Map<String, Object> b = body(ex);
        String name = str(b, "name");
        long price, capacity;
        try {
            price = Long.parseLong(str(b, "price"));
            capacity = Long.parseLong(str(b, "capacity"));
        } catch (Exception e) {
            throw new ApiException(400, "Price and capacity must be numbers.");
        }
        if (name.isEmpty() || name.length() > 60) throw new ApiException(400, "Room name is required.");
        if (price < 0 || capacity < 1 || capacity > 10) throw new ApiException(400, "Check the price and capacity values.");

        Map<String, Object> room = new LinkedHashMap<>();
        room.put("id", newId("r"));
        room.put("name", name);
        room.put("price", price);
        room.put("capacity", capacity);
        room.put("tag", str(b, "tag"));
        room.put("available", true);
        rooms.add(room);
        save();
        sendJson(ex, 201, room);
    }

    static void adminToggleRoom(HttpExchange ex, String id) throws IOException {
        Map<String, Object> room = findById(rooms, id);
        if (room == null) throw new ApiException(404, "Room not found.");
        room.put("available", !Boolean.TRUE.equals(room.get("available")));
        save();
        sendJson(ex, 200, room);
    }

    static void adminDeleteRoom(HttpExchange ex, String id) throws IOException {
        if (!rooms.removeIf(o -> id.equals(((Map<?, ?>) o).get("id")))) throw new ApiException(404, "Room not found.");
        save();
        sendJson(ex, 200, map("ok", true));
    }

    static void contact(HttpExchange ex) throws IOException {
        Map<String, Object> b = body(ex);
        String name = str(b, "name"), email = str(b, "email"), msg = str(b, "message");
        if (name.isEmpty() || email.isEmpty() || msg.isEmpty()) throw new ApiException(400, "Please fill in every field.");
        if (msg.length() > 2000) throw new ApiException(400, "Message is too long.");
        messages.add(map("id", newId("msg"), "name", name, "email", email, "message", msg,
                "createdAt", java.time.Instant.now().toString()));
        save();
        sendJson(ex, 201, map("ok", true));
    }

    // =====================================================================
    //  Auth helpers
    // =====================================================================
    static String token(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    static Session session(HttpExchange ex) {
        String t = token(ex);
        return t == null ? null : sessions.get(t);
    }

    static Map<String, Object> requireCustomer(HttpExchange ex) {
        Session s = session(ex);
        if (s == null || !s.role().equals("customer")) throw new ApiException(401, "Please log in first.");
        Map<String, Object> c = findById(customers, s.customerId());
        if (c == null) throw new ApiException(401, "Please log in first.");
        return c;
    }

    static void requireAdmin(HttpExchange ex) {
        Session s = session(ex);
        if (s == null || !s.role().equals("admin")) throw new ApiException(401, "Admin login required.");
    }

    static String newSession(Session s) {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(t, s);
        return t;
    }

    static String hashPassword(String pw, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 65536, 256);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static boolean verifyPassword(String pw, Map<String, Object> customer) {
        byte[] salt = Base64.getDecoder().decode((String) customer.get("salt"));
        String expected = (String) customer.get("hash");
        return MessageDigest.isEqual(hashPassword(pw, salt).getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> publicCustomer(Map<String, Object> c) {
        return map("id", c.get("id"), "name", c.get("name"), "email", c.get("email"), "phone", c.get("phone"));
    }

    // =====================================================================
    //  Data helpers
    // =====================================================================
    @SuppressWarnings("unchecked")
    static Map<String, Object> findById(List<Object> list, String id) {
        if (id == null) return null;
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (id.equals(m.get("id"))) return m;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> findCustomerByEmail(String email) {
        for (Object o : customers) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (email.equals(m.get("email"))) return m;
        }
        return null;
    }

    static String newId(String prefix) {
        return prefix + "_" + Long.toString(Math.abs(RNG.nextLong()), 36).substring(0, 6);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Trimmed string value (numbers are stringified). */
    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "";
        if (v instanceof Number n) return (n.doubleValue() == n.longValue()) ? String.valueOf(n.longValue()) : n.toString();
        return v.toString().trim();
    }

    /** Untrimmed string value (for passwords). */
    static String raw(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(100_001);
        if (bytes.length > 100_000) throw new ApiException(413, "Request too large.");
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new LinkedHashMap<>();
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map) return (Map<String, Object>) parsed;
        } catch (Exception ignored) { }
        throw new ApiException(400, "Invalid JSON body.");
    }

    // =====================================================================
    //  Persistence
    // =====================================================================
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        if (Files.exists(DATA_FILE)) {
            Map<String, Object> d = (Map<String, Object>) Json.parse(Files.readString(DATA_FILE));
            rooms = new ArrayList<>((List<Object>) d.getOrDefault("rooms", List.of()));
            bookings = new ArrayList<>((List<Object>) d.getOrDefault("bookings", List.of()));
            customers = new ArrayList<>((List<Object>) d.getOrDefault("customers", List.of()));
            messages = new ArrayList<>((List<Object>) d.getOrDefault("messages", List.of()));
        } else {
            seedRooms();
            save();
        }
    }

    static void save() {
        try {
            Map<String, Object> d = map("rooms", rooms, "bookings", bookings, "customers", customers, "messages", messages);
            Path tmp = Paths.get("data.json.tmp");
            Files.writeString(tmp, Json.write(d));
            Files.move(tmp, DATA_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Could not save data", e);
        }
    }

    static void seedRooms() {
        rooms.add(map("id", "r1", "name", "Fern Room", "price", 4200L, "capacity", 2L, "tag", "Garden view", "available", true));
        rooms.add(map("id", "r2", "name", "Moss Suite", "price", 6800L, "capacity", 3L, "tag", "Courtyard", "available", true));
        rooms.add(map("id", "r3", "name", "Canopy Room", "price", 5200L, "capacity", 2L, "tag", "Balcony", "available", true));
        rooms.add(map("id", "r4", "name", "Willow Suite", "price", 7600L, "capacity", 4L, "tag", "Two rooms", "available", true));
        rooms.add(map("id", "r5", "name", "Clover Room", "price", 3900L, "capacity", 2L, "tag", "Quiet wing", "available", true));
        rooms.add(map("id", "r6", "name", "Palm Room", "price", 5600L, "capacity", 3L, "tag", "Pool access", "available", false));
    }

    // =====================================================================
    //  HTTP output + static files
    // =====================================================================
    static void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] out = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
    }

    static void serveStatic(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
            sendJson(ex, 405, map("error", "Method not allowed."));
            return;
        }
        if (path.equals("/")) path = "/verdure.html";
        Path file = PUBLIC_DIR.resolve(path.substring(1)).normalize();
        if (!file.startsWith(PUBLIC_DIR) || !Files.isRegularFile(file)) {
            byte[] nf = "404 - not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, nf.length);
            ex.getResponseBody().write(nf);
            return;
        }
        String name = file.getFileName().toString().toLowerCase();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    // =====================================================================
    //  Minimal JSON codec (objects, arrays, strings, numbers, booleans, null)
    // =====================================================================
    static final class Json {
        private final String s;
        private int i = 0;

        private Json(String s) { this.s = s; }

        static Object parse(String text) {
            Json j = new Json(text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw new IllegalArgumentException("Trailing data");
            return v;
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        private Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else sb.append(c);
            }
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty()) throw new IllegalArgumentException("Bad JSON at " + start);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            i++;
        }

        // ---- writer ----
        static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(o, sb);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(Object o, StringBuilder sb) {
            if (o == null) sb.append("null");
            else if (o instanceof String str) quote(str, sb);
            else if (o instanceof Number || o instanceof Boolean) sb.append(o);
            else if (o instanceof Map<?, ?> m) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), sb);
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            } else if (o instanceof List<?> l) {
                sb.append('[');
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) sb.append(',');
                    write(l.get(k), sb);
                }
                sb.append(']');
            } else quote(o.toString(), sb);
        }

        private static void quote(String str, StringBuilder sb) {
            sb.append('"');
            for (char c : str.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }
    }
}
