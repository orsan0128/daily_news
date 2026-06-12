package com.newsapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String TAG = "NewsApp";

    private WebView webView;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // MSN API endpoint
    private static final String MSN_API_URL =
            "https://assets.msn.com/service/news/feed?query=topstories&market=zh-tw&$top=200"
            + "&apikey=0QfOX3Vn51YCzitbLaRkTTBadtWpgTN8NZLW0C1SEM&contentType=article";

    // 快科技 (MyDrivers) HTML scraping URL
    private static final String KUAIKEJI_URL = "https://news.mydrivers.com/";

    // RSS feed definitions: url -> {source, category}
    private static final Map<String, String[]> RSS_FEEDS = new HashMap<>();
    static {
        // ETtoday
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/realtime", new String[]{"ETtoday", "realtime"});
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/news",    new String[]{"ETtoday", "politics"});
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/finance", new String[]{"ETtoday", "finance"});
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/society", new String[]{"ETtoday", "society"});
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/sport",   new String[]{"ETtoday", "sports"});
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/star",    new String[]{"ETtoday", "entertainment"});
        RSS_FEEDS.put("https://feeds.feedburner.com/ettoday/global",  new String[]{"ETtoday", "global"});
        // Yahoo
        RSS_FEEDS.put("https://tw.news.yahoo.com/rss",    new String[]{"Yahoo", "realtime"});
        RSS_FEEDS.put("https://tw.finance.yahoo.com/rss", new String[]{"Yahoo", "finance"});
        RSS_FEEDS.put("https://tw.stock.yahoo.com/rss",   new String[]{"Yahoo", "finance"});
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        webView = new WebView(this);
        setContentView(webView);

        // WebView settings
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    Intent intent = new Intent(MainActivity.this, ArticleActivity.class);
                    intent.putExtra("url", url);
                    startActivity(intent);
                    return true;
                }
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // Add JavaScript interface for Android <-> WebView communication
        webView.addJavascriptInterface(new WebBridge(), "Android");

        // Load HTML from assets
        try {
            InputStream is = getAssets().open("index.html");
            String html = readStream(is);
            is.close();
            webView.loadDataWithBaseURL("http://127.0.0.1/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load HTML", e);
        }

        // Start fetching all feeds
        fetchAllFeeds();
    }

    // ===== JavaScript Bridge =====
    class WebBridge {
        @JavascriptInterface
        public void refreshFeeds() {
            fetchAllFeeds();
        }

        @JavascriptInterface
        public void openArticle(String url) {
            Intent intent = new Intent(MainActivity.this, ArticleActivity.class);
            intent.putExtra("url", url);
            startActivity(intent);
        }
    }

    // ===== Feed Fetching =====
    private void fetchAllFeeds() {
        List<String> urls = new ArrayList<>(RSS_FEEDS.keySet());
        // +1 for MSN API, +1 for 快科技 HTML scraping
        int totalTasks = urls.size() + 2;
        final AtomicInteger completed = new AtomicInteger(0);
        final CopyOnWriteArrayList<JSONObject> allArticles = new CopyOnWriteArrayList<>();

        // Fetch each RSS feed on a thread
        for (String rssUrl : urls) {
            executor.execute(() -> {
                try {
                    String[] meta = RSS_FEEDS.get(rssUrl);
                    String source = meta[0];
                    String category = meta[1];

                    String xml = fetchUrl(rssUrl);
                    if (xml != null) {
                        List<JSONObject> articles = parseRss(xml, source, category);
                        allArticles.addAll(articles);
                        Log.i(TAG, "Fetched " + articles.size() + " from " + source + "/" + category);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching " + rssUrl, e);
                }
                checkAllDone(completed, totalTasks, allArticles);
            });
        }

        // Fetch MSN API
        executor.execute(() -> {
            try {
                String json = fetchUrl(MSN_API_URL);
                if (json != null) {
                    List<JSONObject> articles = parseMsnApi(json);
                    allArticles.addAll(articles);
                    Log.i(TAG, "Fetched " + articles.size() + " from MSN");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching MSN API", e);
            }
            checkAllDone(completed, totalTasks, allArticles);
        });

        // Fetch 快科技 (MyDrivers) via HTML scraping
        executor.execute(() -> {
            try {
                String html = fetchUrl(KUAIKEJI_URL);
                if (html != null) {
                    List<JSONObject> articles = parseKuaikeji(html);
                    allArticles.addAll(articles);
                    Log.i(TAG, "Fetched " + articles.size() + " from 快科技");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error fetching 快科技", e);
            }
            checkAllDone(completed, totalTasks, allArticles);
        });
    }

    private void checkAllDone(AtomicInteger completed, int total, CopyOnWriteArrayList<JSONObject> allArticles) {
        if (completed.incrementAndGet() == total) {
            try {
                JSONArray jsonArray = new JSONArray();
                for (JSONObject article : allArticles) {
                    jsonArray.put(article);
                }
                final String jsonStr = jsonArray.toString();
                runOnUiThread(() -> loadJsonToWebView(jsonStr));
            } catch (Exception e) {
                Log.e(TAG, "JSON build error", e);
            }
        }
    }

    /**
     * Injects the JSON article array into the WebView via JavaScript.
     * Performs basic string escaping to prevent JS injection issues.
     */
    private void loadJsonToWebView(String json) {
        // Use evaluateJavascript for reliable large data injection
        // Split into chunks if too large (evaluateJavascript limit ~1MB)
        final String js = "loadNews('" + json
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", " ")
                .replace("\r", "") + "')";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    // ===== MSN API Parsing =====
    private List<JSONObject> parseMsnApi(String json) {
        List<JSONObject> articles = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray values = root.optJSONArray("value");
            if (values == null) return articles;

            for (int i = 0; i < values.length(); i++) {
                JSONObject group = values.getJSONObject(i);
                JSONArray cards = group.optJSONArray("subCards");
                if (cards == null) continue;

                for (int j = 0; j < cards.length(); j++) {
                    try {
                        JSONObject card = cards.getJSONObject(j);
                        String title = card.optString("title", "");
                        String url = card.optString("url", "");
                        String snippet = card.optString("abstract", "");

                        String provider = "";
                        JSONObject providerObj = card.optJSONObject("provider");
                        if (providerObj != null) {
                            provider = providerObj.optString("name", "MSN");
                        }

                        String pubDate = card.optString("publishedDateTime", "");

                        // Extract image
                        String imageUrl = "";
                        JSONArray images = card.optJSONArray("images");
                        if (images != null && images.length() > 0) {
                            imageUrl = images.getJSONObject(0).optString("url", "");
                        }

                        // Map category from URL
                        String category = "realtime";
                        if (url.contains("/sports/")) category = "sports";
                        else if (url.contains("/finance/")) category = "finance";
                        else if (url.contains("/entertainment/")) category = "entertainment";
                        else if (url.contains("/scienceandtechnology/")) category = "global";
                        else if (url.contains("/lifestyle/")) category = "society";

                        String timeAgo = formatTimeToMsn(pubDate);

                        if (!title.isEmpty() && !url.isEmpty()) {
                            JSONObject article = new JSONObject();
                            article.put("source", "MSN");
                            article.put("category", category);
                            article.put("title", title.trim());
                            article.put("link", url.trim());
                            article.put("snippet", snippet.length() > 150
                                    ? snippet.substring(0, 150) + "..." : snippet.trim());
                            article.put("image", imageUrl);
                            article.put("time", timeAgo);
                            articles.add(article);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "MSN card parse error", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "MSN API parse error", e);
        }
        return articles;
    }

    // ===== 快科技 (MyDrivers) HTML Scraping =====
    private List<JSONObject> parseKuaikeji(String html) {
        List<JSONObject> articles = new ArrayList<>();
        try {
            // Split HTML into <li> blocks within the news list
            // Each article: <li> ... <h3><a href="URL">TITLE</a></h3> ... <img data-original="IMG" alt="TITLE"> ... <span class="time">TIME</span> ... </li>
            String[] blocks = html.split("<li>");
            for (String block : blocks) {
                try {
                    // Extract URL from h3 > a
                    Pattern urlPattern = Pattern.compile(
                        "<h3>\\s*<a\\s+href=\"(https://news\\.mydrivers\\.com/[^\"]+)\"",
                        Pattern.DOTALL);
                    Matcher urlMatcher = urlPattern.matcher(block);
                    if (!urlMatcher.find()) continue;
                    String url = urlMatcher.group(1);

                    // Extract title from the same <a> tag's text content
                    Pattern titlePattern = Pattern.compile(
                        "<h3>\\s*<a\\s+href=\"[^\"]+\"[^>]*>([^<]+)</a>",
                        Pattern.DOTALL);
                    Matcher titleMatcher = titlePattern.matcher(block);
                    if (!titleMatcher.find()) continue;
                    String title = titleMatcher.group(1).trim();
                    // Decode common HTML entities
                    title = title.replace("&amp;", "&").replace("&lt;", "<")
                                 .replace("&gt;", ">").replace("&quot;", "\"")
                                 .replace("&#39;", "'").replace("&hellip;", "…")
                                 .replace("&mdash;", "—").replace("&nbsp;", " ");

                    // Extract image from data-original
                    String imageUrl = "";
                    Pattern imgPattern = Pattern.compile(
                        "data-original=\"([^\"]+)\"",
                        Pattern.DOTALL);
                    Matcher imgMatcher = imgPattern.matcher(block);
                    if (imgMatcher.find()) {
                        imageUrl = imgMatcher.group(1);
                        if (!imageUrl.startsWith("http")) {
                            imageUrl = "https://news.mydrivers.com" + imageUrl;
                        }
                    }

                    // Extract snippet from <p><a>...</a></p>
                    String snippet = "";
                    Pattern snippetPattern = Pattern.compile(
                        "<p>\\s*<a[^>]*>([^<]+)</a>",
                        Pattern.DOTALL);
                    Matcher snippetMatcher = snippetPattern.matcher(block);
                    if (snippetMatcher.find()) {
                        snippet = snippetMatcher.group(1).trim();
                        snippet = snippet.replace("&amp;", "&").replace("&hellip;", "…")
                                         .replace("&mdash;", "—").replace("&nbsp;", " ");
                        if (snippet.length() > 150) {
                            snippet = snippet.substring(0, 150) + "...";
                        }
                    }

                    // Extract time from <span class="time">
                    String timeStr = "";
                    Pattern timePattern = Pattern.compile(
                        "<span class=\"time\">\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s*</span>",
                        Pattern.DOTALL);
                    Matcher timeMatcher = timePattern.matcher(block);
                    if (timeMatcher.find()) {
                        timeStr = formatTimeFromDateTime(timeMatcher.group(1));
                    }

                    if (!title.isEmpty() && !url.isEmpty()) {
                        JSONObject article = new JSONObject();
                        article.put("source", "快科技");
                        article.put("category", "tech");
                        article.put("title", title);
                        article.put("link", url);
                        article.put("snippet", snippet);
                        article.put("image", imageUrl);
                        article.put("time", timeStr);
                        articles.add(article);
                    }
                } catch (Exception e) {
                    // Skip malformed blocks
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "快科技 parse error", e);
        }
        return articles;
    }

    /**
     * Format "2026-06-11 15:57" to relative time string.
     */
    private String formatTimeFromDateTime(String dateTime) {
        if (dateTime == null || dateTime.isEmpty()) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
            Date date = sdf.parse(dateTime);
            if (date == null) return dateTime;

            long diff = System.currentTimeMillis() - date.getTime();
            long minutes = diff / 60000;
            long hours = minutes / 60;
            long days = hours / 24;

            if (minutes < 1) return "剛剛";
            if (minutes < 60) return minutes + " 分鐘前";
            if (hours < 24) return hours + " 小時前";
            if (days < 7) return days + " 天前";

            SimpleDateFormat display = new SimpleDateFormat("MM/dd", Locale.TAIWAN);
            return display.format(date);
        } catch (Exception e) {
            return dateTime;
        }
    }

    // ===== RSS XML Parsing =====
    private List<JSONObject> parseRss(String xml, String source, String category) {
        List<JSONObject> articles = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new InputStreamReader(
                    new ByteArrayInputStream(xml.getBytes("UTF-8"))));

            boolean inItem = false;
            String title = "", link = "", description = "", pubDate = "", imageUrl = "";

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();

                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("item".equalsIgnoreCase(tagName)) {
                            inItem = true;
                            title = "";
                            link = "";
                            description = "";
                            pubDate = "";
                            imageUrl = "";
                        } else if (inItem) {
                            if ("title".equalsIgnoreCase(tagName)) {
                                title = getParserText(parser);
                            } else if ("link".equalsIgnoreCase(tagName)) {
                                link = getParserText(parser);
                            } else if ("description".equalsIgnoreCase(tagName)) {
                                description = getParserText(parser);
                            } else if ("pubDate".equalsIgnoreCase(tagName)) {
                                pubDate = getParserText(parser);
                            } else if ("enclosure".equalsIgnoreCase(tagName)) {
                                String encUrl = parser.getAttributeValue(null, "url");
                                if (encUrl != null && !encUrl.isEmpty()) {
                                    imageUrl = encUrl;
                                }
                            } else if ("content:encoded".equalsIgnoreCase(tagName)
                                    || "content".equalsIgnoreCase(tagName)) {
                                String content = getParserText(parser);
                                if (imageUrl.isEmpty() && content != null && !content.isEmpty()) {
                                    imageUrl = content.trim();
                                }
                            }
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if ("item".equalsIgnoreCase(tagName) && inItem) {
                            inItem = false;

                            // Extract image from description if not from enclosure
                            if (imageUrl.isEmpty()) {
                                imageUrl = extractImageFromHtml(description);
                            }

                            // Clean description (remove HTML tags)
                            String snippet = stripHtml(description);
                            if (snippet.length() > 150) {
                                snippet = snippet.substring(0, 150) + "...";
                            }

                            // Format time
                            String timeAgo = formatTimeAgo(pubDate);

                            // Fix relative links
                            if (link != null && !link.startsWith("http")) {
                                if ("ETtoday".equals(source)) {
                                    link = "https://www.ettoday.net" + link;
                                }
                            }

                            if (title != null && !title.isEmpty()
                                    && link != null && !link.isEmpty()) {
                                JSONObject article = new JSONObject();
                                article.put("source", source);
                                article.put("category", category);
                                article.put("title", title.trim());
                                article.put("link", link.trim());
                                article.put("snippet", snippet.trim());
                                article.put("image", imageUrl);
                                article.put("time", timeAgo);
                                articles.add(article);
                            }
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "RSS parse error: " + source, e);
        }
        return articles;
    }

    private String getParserText(XmlPullParser parser) {
        try {
            return parser.nextText();
        } catch (Exception e) {
            return "";
        }
    }

    // ===== Time Formatting =====

    /**
     * Format RSS pubDate (RFC 2822) to relative time string.
     * e.g. "Wed, 11 Jun 2025 08:30:00 +0800" -> "3 小時前"
     */
    private String formatTimeAgo(String pubDate) {
        if (pubDate == null || pubDate.isEmpty()) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
            Date date = sdf.parse(pubDate);
            if (date == null) return pubDate;

            long diff = System.currentTimeMillis() - date.getTime();
            long minutes = diff / 60000;
            long hours = minutes / 60;
            long days = hours / 24;

            if (minutes < 1) return "剛剛";
            if (minutes < 60) return minutes + " 分鐘前";
            if (hours < 24) return hours + " 小時前";
            if (days < 7) return days + " 天前";

            SimpleDateFormat display = new SimpleDateFormat("MM/dd", Locale.TAIWAN);
            return display.format(date);
        } catch (Exception e) {
            return pubDate;
        }
    }

    /**
     * Format MSN ISO 8601 date to relative time string.
     * e.g. "2025-06-11T08:30:00Z" -> "2 小時前"
     */
    private String formatTimeToMsn(String isoDate) {
        if (isoDate == null || isoDate.isEmpty()) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            Date date = sdf.parse(isoDate);
            if (date == null) return "";

            long diff = System.currentTimeMillis() - date.getTime();
            long minutes = diff / 60000;
            long hours = minutes / 60;
            long days = hours / 24;

            if (minutes < 1) return "剛剛";
            if (minutes < 60) return minutes + " 分鐘前";
            if (hours < 24) return hours + " 小時前";
            if (days < 7) return days + " 天前";

            SimpleDateFormat display = new SimpleDateFormat("MM/dd", Locale.TAIWAN);
            return display.format(date);
        } catch (Exception e) {
            return isoDate;
        }
    }

    // ===== HTML / String Utilities =====

    /** Extract first image URL from HTML content. */
    private String extractImageFromHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        Pattern pattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            String img = matcher.group(1);
            img = img.replace("&amp;", "&");
            return img;
        }
        return "";
    }

    /** Strip HTML tags and decode common entities. */
    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&nbsp;", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    // ===== Network Utility =====
    private String fetchUrl(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setInstanceFollowRedirects(true);

        int code = conn.getResponseCode();
        if (code >= 200 && code < 400) {
            String result = readStream(conn.getInputStream());
            conn.disconnect();
            return result;
        }
        conn.disconnect();
        return null;
    }

    private String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toString("UTF-8");
    }

    // ===== Lifecycle =====
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
