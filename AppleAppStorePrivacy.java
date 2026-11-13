import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolve an Apple App Store privacy-policy URL from an exact app name.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>iTunes Search API → first hit only if {@code trackName} equals the query
 *   <li>Fetch App Store product page HTML
 *   <li>Read 「开发者隐私政策」 / Developer Privacy Policy href (SSR, not amp-api)
 * </ol>
 *
 * <pre>
 * javac AppleAppStorePrivacy.java
 * java AppleAppStorePrivacy 微信
 * </pre>
 */
public final class AppleAppStorePrivacy {

  static final String DEFAULT_COUNTRY = "cn";
  static final String DEFAULT_LANG = "zh_CN";
  static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";

  private static final Pattern ARIA_PRIVACY_HREF =
      Pattern.compile(
          "aria-label=\"(开发者隐私政策|Developer Privacy Policy)\"[^>]*href=\"([^\"]+)\"",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern LINKED_SUBSTRINGS_URL =
      Pattern.compile(
          "\"linkedSubstrings\"\\s*:\\s*\\{\\s*\"(开发者隐私政策|Developer Privacy Policy)\"\\s*:\\{.*?\"url\"\\s*:\\s*\"(http[^\"]+)\"",
          Pattern.DOTALL);

  public static final class AppStoreException extends RuntimeException {
    public AppStoreException(String message) {
      super(message);
    }
  }

  public static final class LookupResult {
    public final String appName;
    public final long trackId;
    public final String bundleId;
    public final String detailUrl;
    public final String privacyUrl;

    public LookupResult(
        String appName, long trackId, String bundleId, String detailUrl, String privacyUrl) {
      this.appName = appName;
      this.trackId = trackId;
      this.bundleId = bundleId;
      this.detailUrl = detailUrl;
      this.privacyUrl = privacyUrl;
    }
  }

  public static final class AppStoreClient {
    private final String country;
    private final String lang;
    private final HttpClient http;

    public AppStoreClient(String country, String lang) {
      this.country = country;
      this.lang = lang;
      this.http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(20))
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build();
    }

    public List<Map<String, Object>> searchApps(String appName)
        throws IOException, InterruptedException {
      String qs =
          "term="
              + enc(appName)
              + "&country="
              + enc(country)
              + "&entity=software"
              + "&limit=25"
              + "&lang="
              + enc(lang);
      String body = get("https://itunes.apple.com/search?" + qs);
      Object parsed = Json.parse(body);
      if (!(parsed instanceof Map)) {
        throw new AppStoreException("search non-object JSON");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = (Map<String, Object>) parsed;
      Object results = payload.get("results");
      List<Map<String, Object>> apps = new ArrayList<>();
      if (!(results instanceof List)) {
        return apps;
      }
      for (Object itemObj : (List<?>) results) {
        if (!(itemObj instanceof Map)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) itemObj;
        if (item.get("trackName") instanceof String && item.get("trackId") != null) {
          apps.add(item);
        }
      }
      return apps;
    }

    public String fetchProductPageHtml(long trackId) throws IOException, InterruptedException {
      // Stable product URL form; slug is optional for Apple.
      String url = "https://apps.apple.com/" + country + "/app/id" + trackId;
      return get(url);
    }

    private String get(String url) throws IOException, InterruptedException {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(45))
              .header("User-Agent", UA)
              .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
              .header("Accept-Language", lang.replace('_', '-') + ",en;q=0.8")
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new AppStoreException(
            "HTTP " + response.statusCode() + " for " + url + ": " + abbreviate(response.body()));
      }
      return response.body();
    }
  }

  /** First search hit only if its trackName exactly equals the query. */
  static Map<String, Object> firstExactMatch(List<Map<String, Object>> apps, String appName) {
    if (apps.isEmpty()) {
      throw new AppStoreException("no search results for '" + appName + "'");
    }
    Map<String, Object> first = apps.get(0);
    Object name = first.get("trackName");
    if (!(name instanceof String) || !appName.equals(name)) {
      throw new AppStoreException(
          "first result name does not exactly match query: query='"
              + appName
              + "' first='"
              + name
              + "' trackId='"
              + first.get("trackId")
              + "'");
    }
    return first;
  }

  static String extractPrivacyUrl(String html) {
    Matcher aria = ARIA_PRIVACY_HREF.matcher(html);
    if (aria.find()) {
      return normalizeUrl(htmlUnescape(aria.group(2)));
    }
    Matcher linked = LINKED_SUBSTRINGS_URL.matcher(html);
    if (linked.find()) {
      return normalizeUrl(htmlUnescape(linked.group(2)));
    }
    throw new AppStoreException(
        "privacy policy URL missing (no 开发者隐私政策 / Developer Privacy Policy link)");
  }

  static String htmlUnescape(String s) {
    return s.replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">");
  }

  /** Prefer https; Apple SSR often emits http:// for external privacy links. */
  static String normalizeUrl(String url) {
    String u = url.trim();
    if (u.startsWith("http://")) {
      return "https://" + u.substring("http://".length());
    }
    return u;
  }

  static long asLong(Object n) {
    if (n instanceof Number) {
      return ((Number) n).longValue();
    }
    return Long.parseLong(String.valueOf(n));
  }

  public static LookupResult lookupPrivacyUrl(String appName, String country, String lang)
      throws IOException, InterruptedException {
    AppStoreClient client = new AppStoreClient(country, lang);
    List<Map<String, Object>> apps = client.searchApps(appName);
    Map<String, Object> match = firstExactMatch(apps, appName);
    long trackId = asLong(match.get("trackId"));
    Object bundle = match.get("bundleId");
    String detailUrl =
        match.get("trackViewUrl") instanceof String
            ? ((String) match.get("trackViewUrl")).split("\\?")[0]
            : "https://apps.apple.com/" + country + "/app/id" + trackId;
    String html = client.fetchProductPageHtml(trackId);
    String privacyUrl = extractPrivacyUrl(html);
    return new LookupResult(
        String.valueOf(match.get("trackName")),
        trackId,
        bundle == null ? "" : String.valueOf(bundle),
        detailUrl,
        privacyUrl);
  }

  public static void main(String[] args) {
    Args parsed;
    try {
      parsed = Args.parse(args);
    } catch (IllegalArgumentException ex) {
      System.err.println("error: " + ex.getMessage());
      System.err.println(
          "usage: java AppleAppStorePrivacy <app_name> [--json] [--country cn] [--lang zh_CN]");
      System.exit(2);
      return;
    }

    try {
      LookupResult result = lookupPrivacyUrl(parsed.appName, parsed.country, parsed.lang);
      if (parsed.json) {
        System.out.println(
            "{\n"
                + "  \"app_name\": "
                + Json.quote(result.appName)
                + ",\n"
                + "  \"track_id\": "
                + result.trackId
                + ",\n"
                + "  \"bundle_id\": "
                + Json.quote(result.bundleId)
                + ",\n"
                + "  \"detail_url\": "
                + Json.quote(result.detailUrl)
                + ",\n"
                + "  \"privacy_url\": "
                + Json.quote(result.privacyUrl)
                + "\n}");
      } else {
        System.out.println(result.privacyUrl);
      }
    } catch (AppStoreException ex) {
      System.err.println("error: " + ex.getMessage());
      System.exit(1);
    } catch (Exception ex) {
      System.err.println("error: unexpected failure: " + ex.getMessage());
      System.exit(2);
    }
  }

  private static final class Args {
    final String appName;
    final boolean json;
    final String country;
    final String lang;

    Args(String appName, boolean json, String country, String lang) {
      this.appName = appName;
      this.json = json;
      this.country = country;
      this.lang = lang;
    }

    static Args parse(String[] args) {
      if (args.length == 0) {
        throw new IllegalArgumentException("app_name required");
      }
      String appName = null;
      boolean json = false;
      String country = DEFAULT_COUNTRY;
      String lang = DEFAULT_LANG;
      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        switch (a) {
          case "--json":
            json = true;
            break;
          case "--country":
            country = requireValue(args, ++i, "--country");
            break;
          case "--lang":
            lang = requireValue(args, ++i, "--lang");
            break;
          default:
            if (a.startsWith("-")) {
              throw new IllegalArgumentException("unknown option: " + a);
            }
            if (appName != null) {
              throw new IllegalArgumentException("unexpected argument: " + a);
            }
            appName = a;
            break;
        }
      }
      if (appName == null || appName.isEmpty()) {
        throw new IllegalArgumentException("app_name required");
      }
      return new Args(appName, json, country, lang);
    }

    private static String requireValue(String[] args, int index, String flag) {
      if (index >= args.length) {
        throw new IllegalArgumentException(flag + " requires a value");
      }
      return args[index];
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static String abbreviate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200);
  }

  /** Minimal JSON parser (objects/arrays/strings/numbers/booleans/null). */
  static final class Json {
    private Json() {}

    static Object parse(String text) {
      try {
        return new Parser(new StringReader(text)).parseValue();
      } catch (IOException ex) {
        throw new AppStoreException("invalid JSON: " + ex.getMessage());
      }
    }

    static String quote(String s) {
      StringBuilder sb = new StringBuilder("\"");
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"':
          case '\\':
            sb.append('\\').append(c);
            break;
          case '\b':
            sb.append("\\b");
            break;
          case '\f':
            sb.append("\\f");
            break;
          case '\n':
            sb.append("\\n");
            break;
          case '\r':
            sb.append("\\r");
            break;
          case '\t':
            sb.append("\\t");
            break;
          default:
            if (c < 0x20) {
              sb.append(String.format("\\u%04x", (int) c));
            } else {
              sb.append(c);
            }
        }
      }
      sb.append('"');
      return sb.toString();
    }

    private static final class Parser {
      private final Reader in;
      private int next = -2;

      Parser(Reader in) {
        this.in = in;
      }

      Object parseValue() throws IOException {
        skipWs();
        int c = peek();
        if (c == '"') {
          return parseString();
        }
        if (c == '{') {
          return parseObject();
        }
        if (c == '[') {
          return parseArray();
        }
        if (c == 't') {
          expect("true");
          return Boolean.TRUE;
        }
        if (c == 'f') {
          expect("false");
          return Boolean.FALSE;
        }
        if (c == 'n') {
          expect("null");
          return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
          return parseNumber();
        }
        throw new IOException("unexpected char: " + (char) c);
      }

      private Map<String, Object> parseObject() throws IOException {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
          read();
          return map;
        }
        while (true) {
          skipWs();
          String key = parseString();
          skipWs();
          expect(':');
          Object value = parseValue();
          map.put(key, value);
          skipWs();
          int c = read();
          if (c == '}') {
            return map;
          }
          if (c != ',') {
            throw new IOException("expected ',' or '}'");
          }
        }
      }

      private List<Object> parseArray() throws IOException {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
          read();
          return list;
        }
        while (true) {
          list.add(parseValue());
          skipWs();
          int c = read();
          if (c == ']') {
            return list;
          }
          if (c != ',') {
            throw new IOException("expected ',' or ']'");
          }
        }
      }

      private String parseString() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
          int c = read();
          if (c < 0) {
            throw new IOException("unterminated string");
          }
          if (c == '"') {
            return sb.toString();
          }
          if (c == '\\') {
            int e = read();
            switch (e) {
              case '"':
              case '\\':
              case '/':
                sb.append((char) e);
                break;
              case 'b':
                sb.append('\b');
                break;
              case 'f':
                sb.append('\f');
                break;
              case 'n':
                sb.append('\n');
                break;
              case 'r':
                sb.append('\r');
                break;
              case 't':
                sb.append('\t');
                break;
              case 'u':
                int code = 0;
                for (int i = 0; i < 4; i++) {
                  int h = read();
                  code = (code << 4) + hex(h);
                }
                sb.append((char) code);
                break;
              default:
                throw new IOException("bad escape");
            }
          } else {
            sb.append((char) c);
          }
        }
      }

      private Number parseNumber() throws IOException {
        StringBuilder sb = new StringBuilder();
        if (peek() == '-') {
          sb.append((char) read());
        }
        readDigits(sb);
        int c = peek();
        if (c == '.') {
          sb.append((char) read());
          readDigits(sb);
        }
        c = peek();
        if (c == 'e' || c == 'E') {
          sb.append((char) read());
          c = peek();
          if (c == '+' || c == '-') {
            sb.append((char) read());
          }
          readDigits(sb);
          return Double.valueOf(sb.toString());
        }
        if (sb.indexOf(".") >= 0) {
          return Double.valueOf(sb.toString());
        }
        try {
          return Long.valueOf(sb.toString());
        } catch (NumberFormatException ex) {
          return Double.valueOf(sb.toString());
        }
      }

      private void readDigits(StringBuilder sb) throws IOException {
        int c = peek();
        if (c < '0' || c > '9') {
          throw new IOException("expected digit");
        }
        while (true) {
          c = peek();
          if (c < '0' || c > '9') {
            return;
          }
          sb.append((char) read());
        }
      }

      private void expect(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
          expect(s.charAt(i));
        }
      }

      private void expect(char expected) throws IOException {
        int c = read();
        if (c != expected) {
          throw new IOException("expected '" + expected + "' got '" + (char) c + "'");
        }
      }

      private void skipWs() throws IOException {
        while (true) {
          int c = peek();
          if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
            return;
          }
          read();
        }
      }

      private int peek() throws IOException {
        if (next == -2) {
          next = in.read();
        }
        return next;
      }

      private int read() throws IOException {
        if (next != -2) {
          int c = next;
          next = -2;
          return c;
        }
        return in.read();
      }

      private static int hex(int c) throws IOException {
        if (c >= '0' && c <= '9') {
          return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
          return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
          return c - 'A' + 10;
        }
        throw new IOException("bad hex");
      }
    }
  }
}
