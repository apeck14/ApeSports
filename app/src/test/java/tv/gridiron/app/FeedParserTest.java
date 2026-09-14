package tv.gridiron.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class FeedParserTest {
    @Test public void acceptsBomAndCommasInMetadataAndGameTitle(){
        var feeds=FeedParser.parseM3u("\uFEFF#EXTM3U\n#EXTINF:-1 group-title=\"NFL, US\",Chiefs at Bills, English\nhttps://example.com/nfl","https://example.com/");
        assertEquals(1,feeds.size());assertEquals("Chiefs at Bills, English",feeds.get(0).title);
    }
    @Test(expected=IllegalArgumentException.class) public void limitsParsedEntries(){
        StringBuilder input=new StringBuilder("#EXTM3U\n");for(int i=0;i<10001;i++)input.append("#EXTINF:-1,NFL\nhttps://example.com/"+i+"\n");FeedParser.parseM3u(input.toString(),"https://example.com/");
    }
    @Test public void filtersSportsAndResolvesRelativeUrls() {
        var feeds = FeedParser.parseM3u("#EXTM3U\n#EXTINF:-1 group-title=\"NFL\",Game 1\n../game.m3u8\n#EXTINF:-1,NBA basketball\nhttps://example.com/nba\n#EXTINF:-1,Chiefs at Bills\nhttps://example.com/nfl", "https://example.com/catalog/list.m3u");
        assertEquals(2,feeds.size()); assertEquals("https://example.com/game.m3u8",feeds.get(0).url);
    }
    @Test(expected=IllegalArgumentException.class) public void refusesHlsSegmentsAsChannels() { FeedParser.parseM3u("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts", "https://example.com/"); }
    @Test public void rejectsUnsafeAndMalformedUrls() { assertFalse(FeedParser.validUrl("file:///etc/passwd")); assertFalse(FeedParser.validUrl("https://user:pass@example.com")); assertFalse(FeedParser.validUrl("junk")); assertTrue(FeedParser.validUrl("https://example.com/live?token=123")); }
    @Test public void skipsNonHttpEntries() { assertEquals(0, FeedParser.parseM3u("#EXTM3U\n#EXTINF:-1,NFL\nfile:///bad", "https://example.com/").size()); }
}
