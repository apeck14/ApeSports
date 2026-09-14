package tv.gridiron.app;

import android.content.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class GameSourcesTest {
    private Context context;
    private SharedPreferences prefs;
    private String event;
    @Before public void setup(){context=InstrumentationRegistry.getInstrumentation().getTargetContext();prefs=context.getSharedPreferences("HomeActivity",0);event="source-test-"+java.util.UUID.randomUUID();}
    @After public void cleanup(){prefs.edit().remove("source."+event).remove("sources."+event).remove("source."+event+"-other").remove("sources."+event+"-other").commit();}
    @Test public void legacyLinkSurvivesAddingAlternativesAndSelectionsPersist(){
        prefs.edit().putString("source."+event,"https://example.com/old.m3u8").commit();
        GameSources store=new GameSources(context);assertEquals(1,store.list(event).size());
        store.select(event,new FeedParser.Feed("Backup","https://example.com/backup.mpd",event));
        store=new GameSources(context);assertEquals(2,store.list(event).size());assertEquals("https://example.com/backup.mpd",store.selected(event));
        store.select(event,new FeedParser.Feed("Renamed backup","https://example.com/backup.mpd",event));
        assertEquals(2,store.list(event).size());assertEquals("Renamed backup",store.list(event).get(1).title);
        assertTrue(store.list(event+"-other").isEmpty());
    }
    @Test public void invalidUrlDoesNotOverwriteWorkingSelection(){
        GameSources store=new GameSources(context);store.select(event,new FeedParser.Feed("Original","https://example.com/a.m3u8",event));
        try{store.select(event,new FeedParser.Feed("Invalid","javascript:alert(1)",event));fail();}catch(IllegalArgumentException expected){}
        assertEquals("https://example.com/a.m3u8",store.selected(event));assertEquals(1,store.list(event).size());
    }
    @Test public void sourcePickerListsAlternativesAndSelectsTheRequestedOne(){
        GameSources store=new GameSources(context);
        store.select(event,new FeedParser.Feed("First","https://example.com/a.m3u8",event));
        store.select(event,new FeedParser.Feed("Second","https://example.com/b.mpd",event));
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{a.cancelRefresh();
                FeedParser.Feed[] chosen=new FeedParser.Feed[1];
                var dialog=GameSources.show(a,event,"NFL fixture",store.selected(event),source->chosen[0]=source);
                assertEquals(2,dialog.getListView().getAdapter().getCount());
                assertEquals("Current · Second",dialog.getListView().getAdapter().getItem(1));
                dialog.getListView().performItemClick(null,0,0);
                assertNotNull(chosen[0]);assertEquals("https://example.com/a.m3u8",chosen[0].url);
                assertEquals(chosen[0].url,new GameSources(a).selected(event));dialog.dismiss();
            });
        }
    }
}
