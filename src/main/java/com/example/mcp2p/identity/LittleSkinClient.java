package com.example.mcp2p.identity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * The account service calls MC P2P needs, all built on one base URL
 * ({@code https://littleskin.cn/api/yggdrasil} by default):
 *
 * <ul>
 *   <li>{@code POST /authserver/authenticate} — name and password in, token pair out
 *   <li>{@code POST /authserver/validate} — is a token still good
 *   <li>{@code POST /authserver/refresh} — fresh token pair plus the selected profile
 *   <li>{@code POST /sessionserver/session/minecraft/join} — a joiner announces itself to the session
 *       server with a random id
 *   <li>{@code GET /sessionserver/session/minecraft/hasJoined} — the host asks whether an account
 *       really joined with that id
 *   <li>{@code POST /api/profiles/minecraft} — name to profile lookup
 * </ul>
 *
 * <p>{@code join} plus {@code hasJoined} is exactly how a Minecraft server authenticates a player: only
 * the owner of the account can answer the join call, because it takes the secret token. That is what
 * makes it a proof of ownership instead of a claim.
 */
public final class LittleSkinClient {
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final String baseUrl;

    public LittleSkinClient(final String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** @return the base URL every call is built on */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * POST {@code /authserver/validate}.
     *
     * @return {@code true} when the session server still accepts the token (it answers 204)
     */
    public boolean validate(final String accessToken, final String clientToken)
            throws IOException, InterruptedException {
        final JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        if (clientToken != null && !clientToken.isBlank()) {
            body.addProperty("clientToken", clientToken);
        }

        final HttpResponse<String> response = send(jsonRequest("/authserver/validate", body));
        if (response.statusCode() == 204) {
            return true;
        }
        if (response.statusCode() == 403) {
            return false;
        }
        throw failure(response);
    }

    /**
     * POST {@code /authserver/refresh}: trades the token pair for a fresh one and reports the profile
     * the account has selected.
     */
    public TokenPair refresh(final String accessToken, final String clientToken)
            throws IOException, InterruptedException {
        final JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("clientToken", clientToken == null ? "" : clientToken);
        body.addProperty("requestUser", false);

        final HttpResponse<String> response = send(jsonRequest("/authserver/refresh", body));
        if (response.statusCode() != 200) {
            throw failure(response);
        }
        return parseTokenPair(response.body());
    }

    /** POST {@code /authserver/authenticate}, the call behind the {@code /mcp2p login} command. */
    public TokenPair authenticate(
            final String username, final String password, final String clientToken)
            throws IOException, InterruptedException {
        final JsonObject body = new JsonObject();
        final JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);
        body.add("agent", agent);
        body.addProperty("username", username);
        body.addProperty("password", password);
        body.addProperty(
                "clientToken",
                clientToken == null || clientToken.isBlank()
                        ? java.util.UUID.randomUUID().toString()
                        : clientToken);
        body.addProperty("requestUser", false);

        final HttpResponse<String> response = send(jsonRequest("/authserver/authenticate", body));
        if (response.statusCode() != 200) {
            throw failure(response);
        }
        return parseTokenPair(response.body());
    }

    /**
     * POST {@code /sessionserver/session/minecraft/join}: tells the session server that this account
     * wants to enter the server identified by {@code serverId}. It answers 204 on success.
     */
    public void join(final String accessToken, final String uuid, final String serverId)
            throws IOException, InterruptedException {
        final JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("selectedProfile", uuid.replace("-", ""));
        body.addProperty("serverId", serverId);

        final HttpResponse<String> response =
                send(jsonRequest("/sessionserver/session/minecraft/join", body));
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw failure(response);
        }
    }

    /**
     * GET {@code /sessionserver/session/minecraft/hasJoined}.
     *
     * @return the profile when the account really joined with that id, otherwise empty
     */
    public Optional<PlayerIdentity> hasJoined(final String username, final String serverId)
            throws IOException, InterruptedException {
        final String path =
                "/sessionserver/session/minecraft/hasJoined?username="
                        + encode(username)
                        + "&serverId="
                        + encode(serverId);

        final HttpResponse<String> response = send(getRequest(path));
        if (response.statusCode() == 204 || response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw failure(response);
        }
        return Optional.of(identityOf(GSON.fromJson(response.body(), JsonObject.class)));
    }

    /** POST {@code /api/profiles/minecraft}, the name to profile lookup. */
    public Optional<PlayerIdentity> profileByName(final String name)
            throws IOException, InterruptedException {
        final JsonArray names = new JsonArray();
        names.add(name);

        final HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/profiles/minecraft"))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        GSON.toJson(names), StandardCharsets.UTF_8))
                        .build();

        final HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw failure(response);
        }

        final JsonArray profiles = GSON.fromJson(response.body(), JsonArray.class);
        if (profiles == null || profiles.isEmpty()) {
            return Optional.empty();
        }
        final JsonElement first = profiles.get(0);
        return first.isJsonObject()
                ? Optional.of(identityOf(first.getAsJsonObject()))
                : Optional.empty();
    }

    /** A fresh token pair plus the profile it belongs to. */
    public record TokenPair(String accessToken, String clientToken, PlayerIdentity identity) {}

    /** A failed call, carrying the Yggdrasil error code and message. */
    public static final class LittleSkinException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String code;
        private final int status;

        LittleSkinException(final String code, final String message, final int status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }

        /** @return {@code true} for the error LittleSkin uses when a token is no longer valid */
        public boolean isInvalidToken() {
            return "ForbiddenOperationException".equals(code);
        }
    }

    private HttpRequest jsonRequest(final String path, final JsonObject body) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest getRequest(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private HttpResponse<String> send(final HttpRequest request)
            throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static TokenPair parseTokenPair(final String json) {
        final JsonObject root = GSON.fromJson(json, JsonObject.class);
        final String accessToken = root.has("accessToken") ? root.get("accessToken").getAsString() : null;
        final String clientToken = root.has("clientToken") ? root.get("clientToken").getAsString() : null;

        PlayerIdentity identity = null;
        if (root.has("selectedProfile") && root.get("selectedProfile").isJsonObject()) {
            identity = identityOf(root.getAsJsonObject("selectedProfile"));
        } else if (root.has("availableProfiles")
                && !root.getAsJsonArray("availableProfiles").isEmpty()) {
            identity = identityOf(root.getAsJsonArray("availableProfiles").get(0).getAsJsonObject());
        }
        return new TokenPair(accessToken, clientToken, identity);
    }

    private static PlayerIdentity identityOf(final JsonObject profile) {
        return new PlayerIdentity(profile.get("id").getAsString(), profile.get("name").getAsString());
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static LittleSkinException failure(final HttpResponse<String> response) {
        String message = "HTTP " + response.statusCode();
        String code = "unknown";
        try {
            final JsonObject error = GSON.fromJson(response.body(), JsonObject.class);
            if (error != null) {
                if (error.has("errorMessage")) {
                    message = error.get("errorMessage").getAsString();
                } else if (error.has("message")) {
                    message = error.get("message").getAsString();
                }
                if (error.has("error")) {
                    code = error.get("error").getAsString();
                }
            }
        } catch (JsonSyntaxException ignored) {
            // Not a JSON error body, the status line has to do.
        }
        return new LittleSkinException(code, message, response.statusCode());
    }
}
