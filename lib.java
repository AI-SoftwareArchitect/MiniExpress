import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

public class MiniExpress {

    public static class Request {
        public final HttpExchange exchange;
        public final Map<String, String> params;
        public final Map<String, String> query;
        public final String body;

        public Request(HttpExchange exchange, Map<String, String> params) throws IOException {
            this.exchange = exchange;
            this.params = params;
            this.query = parseQuery(exchange.getRequestURI().getRawQuery());
            this.body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        private Map<String, String> parseQuery(String rawQuery) {
            Map<String, String> map = new HashMap<>();
            if (rawQuery == null) return map;
            for (String p : rawQuery.split("&")) {
                String[] kv = p.split("=");
                if (kv.length == 2) map.put(kv[0], kv[1]);
            }
            return map;
        }

        public String getHeader(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }
    }

    public static class Response {
        private final HttpExchange exchange;
        private boolean sent = false;

        public Response(HttpExchange exchange) {
            this.exchange = exchange;
        }

        public void send(String text) throws IOException {
            if (sent) return;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            sent = true;
        }

        public void json(String json) throws IOException {
            if (sent) return;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            sent = true;
        }

        public void status(int code, String msg) throws IOException {
            if (sent) return;
            byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
            sent = true;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(Request req, Response res) throws IOException;
    }

    @FunctionalInterface
    public interface Middleware {
        void apply(Request req, Response res, Runnable next) throws IOException;
    }

    private static class Route {
        final String method;
        final String path;
        final Handler handler;

        Route(String method, String path, Handler handler) {
            this.method = method;
            this.path = path;
            this.handler = handler;
        }
    }

    private final List<Route> routes = new ArrayList<>();
    private final List<Middleware> middlewares = new ArrayList<>();

    public void get(String path, Handler handler) {
        routes.add(new Route("GET", path, handler));
    }

    public void post(String path, Handler handler) {
        routes.add(new Route("POST", path, handler));
    }

    public void use(Middleware m) {
        middlewares.add(m);
    }

    public void listen(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", exchange -> {
            String reqPath = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            for (Route r : routes) {
                Map<String, String> params = match(r.path, reqPath);
                if (r.method.equals(method) && params != null) {
                    Request req = new Request(exchange, params);
                    Response res = new Response(exchange);
                    runMiddleware(0, req, res, () -> {
                        try {
                            r.handler.handle(req, res);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return;
                }
            }

            byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, notFound.length);
            exchange.getResponseBody().write(notFound);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        System.out.println("MiniExpress running at http://localhost:" + port);
    }

    private Map<String, String> match(String route, String path) {
        String[] rParts = route.split("/");
        String[] pParts = path.split("/");
        if (rParts.length != pParts.length) return null;

        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < rParts.length; i++) {
            if (rParts[i].startsWith(":")) {
                params.put(rParts[i].substring(1), pParts[i]);
            } else if (!rParts[i].equals(pParts[i])) {
                return null;
            }
        }
        return params;
    }

    private void runMiddleware(int index, Request req, Response res, Runnable next) throws IOException {
        if (index < middlewares.size()) {
            middlewares.get(index).apply(req, res, () -> {
                try {
                    runMiddleware(index + 1, req, res, next);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            next.run();
        }
    }

    // === Example Usage ===
    public static void main(String[] args) throws IOException {
        MiniExpress api = new MiniExpress();

        // Global Middleware
        api.use((req, res, next) -> {
            System.out.println("Incoming " + req.exchange.getRequestMethod() + " " + req.exchange.getRequestURI());
            next.run();
        });

        // GET /
        api.get("/", (req, res) -> res.send("Hello from Java MiniExpress!"));

        // GET /user/:id
        api.get("/user/:id", (req, res) -> {
            String id = req.params.get("id");
            res.json("{\"user_id\": \"" + id + "\"}");
        });

        // POST /echo
        api.post("/echo", (req, res) -> {
            res.json("{\"you_sent\": \"" + req.body.replace("\"", "\\\"") + "\"}");
        });

        // Start server
        api.listen(3000);
    }
}
