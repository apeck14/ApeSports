package tv.gridiron.app;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.app.Instrumentation;
import android.content.Intent;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class NflHomeTest {
    static JSONObject event(String id,String state)throws Exception {
        JSONArray competitors=new JSONArray();
        competitors.put(new JSONObject().put("homeAway","home").put("score","21").put("team",new JSONObject().put("displayName","Detroit Lions").put("abbreviation","DET")));
        competitors.put(new JSONObject().put("homeAway","away").put("score","14").put("team",new JSONObject().put("displayName","New Orleans Saints").put("abbreviation","NO")));
        return new JSONObject().put("id",id).put("name","New Orleans Saints at Detroit Lions").put("date","2026-09-13T17:00Z")
            .put("competitions",new JSONArray().put(new JSONObject().put("competitors",competitors)))
            .put("status",new JSONObject().put("type",new JSONObject().put("state",state).put("shortDetail","7:32 - 3rd")));
    }
    static String board(JSONObject... events)throws Exception {
        JSONArray list=new JSONArray();for(JSONObject e:events)list.put(e);
        return new JSONObject().put("leagues",new JSONArray().put(new JSONObject().put("slug","nfl"))).put("events",list).toString();
    }
    @Test public void sortsLiveFirstAndMapsTeamsByHomeAway()throws Exception {
        var games=NflScoreboard.parse(board(event("final","post"),event("soon","pre"),event("live","in")));
        assertEquals("live",games.get(0).id);assertEquals("soon",games.get(1).id);assertEquals("final",games.get(2).id);
        assertEquals("NO",games.get(0).away.abbreviation);assertEquals("DET",games.get(0).home.abbreviation);
    }
    @Test public void rejectsWrongLeagueAndIncompleteSnapshots()throws Exception {
        try{NflScoreboard.parse(board(event("1","in")).replace("\"nfl\"","\"nba\""));fail();}catch(JSONException expected){}
        JSONObject broken=event("1","in");broken.getJSONArray("competitions").getJSONObject(0).put("competitors",new JSONArray());
        try{NflScoreboard.parse(board(broken));fail();}catch(JSONException expected){}
        assertEquals(1,NflScoreboard.parse(board(broken,event("2","in"),event("2","in"))).size());
    }
    @Test public void completionOverridesLiveStateAndEmptyScheduleIsValid()throws Exception {
        JSONObject e=event("1","in");e.getJSONObject("status").getJSONObject("type").put("completed",true);
        assertFalse(NflScoreboard.parse(board(e)).get(0).live());assertTrue(NflScoreboard.parse(board()).isEmpty());
    }
    @Test public void freshnessRejectsExpiredAndFutureData(){
        assertTrue(NflScoreboard.fresh(1000,121000));assertFalse(NflScoreboard.fresh(1000,121001));
        assertFalse(NflScoreboard.fresh(1001,1000));assertFalse(NflScoreboard.fresh(0,1000));
        assertTrue(NflScoreboard.url(NflScoreboard.parseDate("2026-09-13T17:00Z")).contains("dates=20260912-20260914"));
    }
    @Test public void selectionSurvivesRefreshButDropsFinishedGames()throws Exception {
        var games=NflScoreboard.parse(board(event("1","in"),event("2","in"),event("3","in"),event("4","in")));
        var finished=NflScoreboard.parse(board(event("1","post"),event("2","in"),event("3","in"),event("4","in")));
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{a.cancelRefresh();a.applySnapshot(games,System.currentTimeMillis(),"");
                for(var game:games)a.toggle(game);assertEquals(4,a.selectedCount());
                a.applySnapshot(games,System.currentTimeMillis(),"");assertEquals(4,a.selectedCount());
                a.applySnapshot(finished,System.currentTimeMillis(),"");assertEquals(3,a.selectedCount());
                a.toggle(games.get(1));assertEquals(2,a.selectedCount());});
        }
    }
    @Test public void fifthSelectionIsRejectedButStaleGamesRemainSelectable()throws Exception {
        var games=NflScoreboard.parse(board(event("1","in"),event("2","in"),event("3","in"),event("4","in"),event("5","in")));
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{a.cancelRefresh();a.applySnapshot(games,System.currentTimeMillis(),"");
                for(var game:games)a.toggle(game);assertEquals(4,a.selectedCount());
                a.applySnapshot(Collections.emptyList(),System.currentTimeMillis(),"");assertEquals(0,a.selectedCount());
                a.applySnapshot(games,System.currentTimeMillis()-NflScoreboard.FRESH_MS-1,"");
                a.toggle(games.get(0));assertEquals(1,a.selectedCount());
                a.applySnapshot(games,System.currentTimeMillis(),"Live data unavailable");
                a.toggle(games.get(1));assertEquals(2,a.selectedCount());});
        }
    }
    @Test public void scoreboardFailureDoesNotBlockWatchingLinkedGame()throws Exception {
        var games=NflScoreboard.parse(board(event("offline-test","in")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Intent[] launched=new Intent[1];
        var monitor=new Instrumentation.ActivityMonitor(){
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
                if(intent.getComponent()!=null&&intent.getComponent().getClassName().equals(MainActivity.class.getName())){
                    launched[0]=intent;return new Instrumentation.ActivityResult(0,null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{
                a.cancelRefresh();a.applySnapshot(games,System.currentTimeMillis()-NflScoreboard.FRESH_MS-1,"Live data unavailable");
                new GameSources(a).select("offline-test",new FeedParser.Feed("Saved game","https://example.com/live.m3u8"));
                a.toggle(games.get(0));a.watchSelected();
                a.getPreferences(0).edit().remove("source.offline-test").remove("sources.offline-test").commit();
            });
            assertNotNull("A scoreboard outage must not block playback",launched[0]);
            JSONArray feeds=new JSONArray(launched[0].getStringExtra("selectedFeeds"));
            assertEquals(1,feeds.length());assertEquals("offline-test",feeds.getJSONObject(0).getString("eventId"));
            assertEquals("https://example.com/live.m3u8",feeds.getJSONObject(0).getString("url"));
        }finally{instrumentation.removeMonitor(monitor);}
    }
    @Test public void homeShowsTeamsAndLiveStatusWithoutScoresOrGameClock()throws Exception {
        var games=NflScoreboard.parse(board(event("simple-live","in"),event("simple-final","post")));
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{a.cancelRefresh();a.applySnapshot(games,System.currentTimeMillis(),"");
                android.widget.LinearLayout card=a.findViewById(android.R.id.content).findViewWithTag("simple-live");
                assertEquals("Live",((android.widget.TextView)((android.widget.LinearLayout)card.getChildAt(0)).getChildAt(0)).getText().toString());
                for(int i=1;i<=2;i++){
                    var row=(android.widget.LinearLayout)card.getChildAt(i);
                    assertEquals("Only a logo and team name belong in each row",2,row.getChildCount());
                    assertEquals(i==1?"New Orleans Saints":"Detroit Lions",((android.widget.TextView)row.getChildAt(1)).getText().toString());
                }
                assertNull(a.findViewById(android.R.id.content).findViewWithTag("simple-final"));
                a.toggle(games.get(0));assertEquals(1,a.selectedCount());
            });
        }
    }

}
