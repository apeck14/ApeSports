package tv.gridiron.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** M3U catalog parsing only; HLS media playlists are not channel catalogs. */
public final class FeedParser {
    public static final class Feed {
        public final String title, url, eventId;
        public Feed(String title, String url) { this(title,url,""); }
        public Feed(String title, String url,String eventId) { this.title = title; this.url = url; this.eventId=eventId; }
    }
    private static final Pattern NFL = Pattern.compile("\\b(nfl|cardinals|falcons|ravens|bills|panthers|bears|bengals|browns|cowboys|broncos|lions|packers|texans|colts|jaguars|chiefs|raiders|chargers|rams|dolphins|vikings|patriots|saints|giants|jets|eagles|steelers|49ers|seahawks|buccaneers|titans|commanders)\\b", Pattern.CASE_INSENSITIVE);
    public static boolean validUrl(String url) {
        try { URI u = new URI(url); return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme())) && u.getHost() != null && u.getUserInfo() == null; }
        catch (Exception e) { return false; }
    }
    public static List<Feed> parseM3u(String content, String base) {
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        if (content.contains("#EXT-X-")) throw new IllegalArgumentException("This is a video playlist. Add it as a single stream instead.");
        if (!content.trim().startsWith("#EXTM3U")) throw new IllegalArgumentException("Expected an M3U catalog beginning with #EXTM3U.");
        List<Feed> feeds = new ArrayList<>();
        String title = null, metadata = "";
        for (String raw : content.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                metadata = line;
                int comma = separator(line);
                title = comma >= 0 ? line.substring(comma + 1).trim() : "NFL stream";
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                if (title != null && NFL.matcher(metadata).find()) {
                    String url;
                    try { url = URI.create(base).resolve(line).toString(); } catch (Exception e) { title = null; continue; }
                    if (validUrl(url)) {
                        feeds.add(new Feed(title, url));
                        if(feeds.size()>10000)throw new IllegalArgumentException("Playlist exceeds 10,000 channels");
                    }
                }
                title = null;
            }
        }
        return feeds;
    }
    private static int separator(String line){char quote=0;for(int i=0;i<line.length();i++){char c=line.charAt(i);if(quote!=0){if(c==quote)quote=0;}else if(c=='\"'||c=='\'')quote=c;else if(c==',')return i;}return -1;}
}
