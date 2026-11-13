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
import java.util.Objects;
import java.util.UUID;

/**
 * Resolve a Huawei AppGallery privacy-policy URL from an exact app name.
 *
 * <pre>
 * javac HuaweiAppGalleryPrivacy.java
 * java HuaweiAppGalleryPrivacy 快手
 * </pre>
 */
public final class HuaweiAppGalleryPrivacy {

  static final String DEFAULT_BASE = "https://web-drcn.hispace.dbankcloud.cn/edge";
  static final String DEFAULT_ZONE = "CN";
  static final String DEFAULT_LOCALE = "zh_CN";
  static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
          + "AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/120.0.0.0 Safari/537.36";
  static final String ORIGIN = "https://appgallery.huawei.com";
  static final String REFERER = "https://appgallery.huawei.com/";

  public static final class AppGalleryException extends RuntimeException {
    public AppGalleryException(String message) {
      super(message);
    }
  }

  public static final class LookupResult {
    public final String appName;
    public final String appId;
    public final String packageName;
    public final String detailUrl;
    public final String privacyUrl;

    public LookupResult(
        String appName, String appId, String packageName, String detailUrl, String privacyUrl) {
      this.appName = appName;
      this.appId = appId;
      this.packageName = packageName;
      this.detailUrl = detailUrl;
      this.privacyUrl = privacyUrl;
    }
  }

  public static final class AppGalleryClient {
    private final String baseUrl;
    private final String zone;
    private final String locale;
    private final String identityId = UUID.randomUUID().toString();
    private final HttpClient http;
    private String interfaceCode;

    public AppGalleryClient(String baseUrl, String zone, String locale) {
      this.baseUrl = trimTrailingSlash(baseUrl);
      this.zone = zone;
      this.locale = locale;
      this.http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(20))
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build();
    }

    public void refreshInterfaceCode() throws IOException, InterruptedException {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/webedge/getInterfaceCode"))
              .timeout(Duration.ofSeconds(45))
              .header("User-Agent", UA)
              .header("Accept", "application/json, text/plain, */*")
              .header("Origin", ORIGIN)
              .header("Referer", REFERER)
              .header("Identity-Id", identityId)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new AppGalleryException(
            "getInterfaceCode failed HTTP "
                + response.statusCode()
                + ": "
                + abbreviate(response.body()));
      }
      Object parsed = Json.parse(response.body());
      if (!(parsed instanceof String) || ((String) parsed).isEmpty()) {
        throw new AppGalleryException("getInterfaceCode returned empty token");
      }
      this.interfaceCode = (String) parsed;
    }

    public Map<String, Object> uowap(Map<String, String> params)
        throws IOException, InterruptedException {
      if (interfaceCode == null) {
        refreshInterfaceCode();
      }
      String url = baseUrl + "/uowap/index?" + encodeQuery(params);
      HttpResponse<String> response = sendAuthedGet(url);
      if (response.statusCode() == 403 && response.body() != null && response.body().contains("InterfaceCode")) {
        refreshInterfaceCode();
        response = sendAuthedGet(url);
      }
      if (response.statusCode() != 200) {
        throw new AppGalleryException(
            "uowap HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
      }
      Object parsed = Json.parse(response.body());
      if (!(parsed instanceof Map)) {
        throw new AppGalleryException("uowap non-object JSON: " + abbreviate(response.body()));
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = (Map<String, Object>) parsed;
      Object rtn = payload.get("rtnCode");
      if (rtn != null && !(Objects.equals(rtn, 0) || Objects.equals(rtn, 0L) || Objects.equals(rtn, "0"))) {
        throw new AppGalleryException(
            "uowap rtnCode=" + rtn + " rtnDesc=" + payload.get("rtnDesc"));
      }
      return payload;
    }

    private HttpResponse<String> sendAuthedGet(String url)
        throws IOException, InterruptedException {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(45))
              .header("User-Agent", UA)
              .header("Accept", "application/json, text/plain, */*")
              .header("Origin", ORIGIN)
              .header("Referer", REFERER)
              .header("Identity-Id", identityId)
              .header("Interface-Code", interfaceCode + "_" + System.currentTimeMillis())
              .GET()
              .build();
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public List<Map<String, Object>> searchApps(String appName)
        throws IOException, InterruptedException {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("method", "internal.getTabDetail");
      params.put("serviceType", "20");
      params.put("reqPageNum", "1");
      params.put("maxResults", "25");
      params.put("uri", "searchApp|" + appName);
      params.put("zone", zone);
      params.put("locale", locale);
      Map<String, Object> payload = uowap(params);
      List<Map<String, Object>> apps = new ArrayList<>();
      Object layoutData = payload.get("layoutData");
      if (!(layoutData instanceof List)) {
        return apps;
      }
      for (Object blockObj : (List<?>) layoutData) {
        if (!(blockObj instanceof Map)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) blockObj;
        Object dataList = block.get("dataList");
        if (!(dataList instanceof List)) {
          continue;
        }
        for (Object itemObj : (List<?>) dataList) {
          if (!(itemObj instanceof Map)) {
            continue;
          }
          @SuppressWarnings("unchecked")
          Map<String, Object> item = (Map<String, Object>) itemObj;
          Object name = item.get("name");
          Object appId = item.get("appid");
          if (name instanceof String && appId != null && !String.valueOf(appId).isEmpty()) {
            apps.add(item);
          }
        }
      }
      return apps;
    }

    public Map<String, Object> appDetail(String appId) throws IOException, InterruptedException {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("method", "internal.getTabDetail");
      params.put("serviceType", "20");
      params.put("reqPageNum", "1");
      params.put("maxResults", "25");
      params.put("uri", "app|" + appId);
      params.put("appid", appId);
      params.put("zone", zone);
      params.put("locale", locale);
      return uowap(params);
    }
  }

  /** First search hit only if its displayed name exactly equals the query. */
  static Map<String, Object> firstExactMatch(List<Map<String, Object>> apps, String appName) {
    if (apps.isEmpty()) {
      throw new AppGalleryException("no search results for '" + appName + "'");
    }
    Map<String, Object> first = apps.get(0);
    Object name = first.get("name");
    if (!(name instanceof String) || !appName.equals(name)) {
      throw new AppGalleryException(
          "first result name does not exactly match query: query='"
              + appName
              + "' first='"
              + name
              + "' appid='"
              + first.get("appid")
              + "'");
    }
    return first;
  }

  static String extractPrivacyUrl(Map<String, Object> detail) {
    Object layoutData = detail.get("layoutData");
    if (!(layoutData instanceof List)) {
      throw new AppGalleryException("privacy policy URL missing (no layoutData)");
    }
    for (Object blockObj : (List<?>) layoutData) {
      if (!(blockObj instanceof Map)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> block = (Map<String, Object>) blockObj;
      Object dataList = block.get("dataList");
      if (!(dataList instanceof List)) {
        continue;
      }
      for (Object itemObj : (List<?>) dataList) {
        if (!(itemObj instanceof Map)) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) itemObj;
        Object conceal = item.get("conceal");
        if (conceal instanceof Map) {
          Object text = ((Map<?, ?>) conceal).get("text");
          if (text instanceof String && !((String) text).trim().isEmpty()) {
            return ((String) text).trim();
          }
        }
      }
    }
    throw new AppGalleryException("privacy policy URL missing (no conceal.text in detail)");
  }

  public static LookupResult lookupPrivacyUrl(
      String appName, String baseUrl, String zone, String locale)
      throws IOException, InterruptedException {
    AppGalleryClient client = new AppGalleryClient(baseUrl, zone, locale);
    List<Map<String, Object>> apps = client.searchApps(appName);
    Map<String, Object> match = firstExactMatch(apps, appName);
    String appId = String.valueOf(match.get("appid"));
    Map<String, Object> detail = client.appDetail(appId);
    String privacyUrl = extractPrivacyUrl(detail);
    Object pkg = match.get("package");
    return new LookupResult(
        String.valueOf(match.get("name")),
        appId,
        pkg == null ? "" : String.valueOf(pkg),
        "https://appgallery.huawei.com/app/" + appId,
        privacyUrl);
  }

  public static void main(String[] args) {
    Args parsed;
    try {
      parsed = Args.parse(args);
    } catch (IllegalArgumentException ex) {
      System.err.println("error: " + ex.getMessage());
      System.err.println(
          "usage: java HuaweiAppGalleryPrivacy <app_name> [--json] [--zone CN] [--locale zh_CN] [--base-url URL]");
      System.exit(2);
      return;
    }

    try {
      LookupResult result =
          lookupPrivacyUrl(parsed.appName, parsed.baseUrl, parsed.zone, parsed.locale);
      if (parsed.json) {
        System.out.println(
            "{\n"
                + "  \"app_name\": "
                + Json.quote(result.appName)
                + ",\n"
                + "  \"app_id\": "
                + Json.quote(result.appId)
                + ",\n"
                + "  \"package\": "
                + Json.quote(result.packageName)
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
    } catch (AppGalleryException ex) {
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
    final String baseUrl;
    final String zone;
    final String locale;

    Args(String appName, boolean json, String baseUrl, String zone, String locale) {
      this.appName = appName;
      this.json = json;
      this.baseUrl = baseUrl;
      this.zone = zone;
      this.locale = locale;
    }

    static Args parse(String[] args) {
      if (args.length == 0) {
        throw new IllegalArgumentException("app_name required");
      }
      String appName = null;
      boolean json = false;
      String baseUrl = DEFAULT_BASE;
      String zone = DEFAULT_ZONE;
      String locale = DEFAULT_LOCALE;
      for (int i = 0; i < args.length; i++) {
        String a = args[i];
        switch (a) {
          case "--json":
            json = true;
            break;
          case "--base-url":
            baseUrl = requireValue(args, ++i, "--base-url");
            break;
          case "--zone":
            zone = requireValue(args, ++i, "--zone");
            break;
          case "--locale":
            locale = requireValue(args, ++i, "--locale");
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
      return new Args(appName, json, baseUrl, zone, locale);
    }

    private static String requireValue(String[] args, int index, String flag) {
      if (index >= args.length) {
        throw new IllegalArgumentException(flag + " requires a value");
      }
      return args[index];
    }
  }

  private static String encodeQuery(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (sb.length() > 0) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
      sb.append('=');
      sb.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  private static String trimTrailingSlash(String s) {
    if (s.endsWith("/")) {
      return s.substring(0, s.length() - 1);
    }
    return s;
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
        throw new AppGalleryException("invalid JSON: " + ex.getMessage());
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
        int c = peek();
        if (c == '-') {
          sb.append((char) read());
        }
        readDigits(sb);
        c = peek();
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
