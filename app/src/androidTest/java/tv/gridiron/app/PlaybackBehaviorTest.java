package tv.gridiron.app;

import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real HTTP video, codec output, Activity recreation, and remote events. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class PlaybackBehaviorTest {
    private ActivityScenario<MainActivity> scenario;
    private SharedPreferences prefs;
    private Map<String,?> saved;
    private ServerSocket server;
    private Thread serving;
    private String base;
    private volatile boolean unavailable;
    private final java.util.concurrent.CountDownLatch catalogRequested=new java.util.concurrent.CountDownLatch(1),catalogGate=new java.util.concurrent.CountDownLatch(1);
    private final List<String> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
    private byte[] asset(String name)throws IOException {
        try(var in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
            var out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
        }
    }
    @Before public void setup()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=context.getSharedPreferences("MainActivity",0);saved=prefs.getAll();assertTrue(prefs.edit().clear().commit());
        byte[] wide=asset("wide.mp4"),standard=asset("standard.mp4");
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort();
        serving=new Thread(()->{
            while(!server.isClosed())try(Socket socket=server.accept()){
                socket.setSoTimeout(3000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                String request=reader.readLine();if(request==null)continue;
                String path=request.split(" ")[1];requests.add(path);
                String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                boolean fail=unavailable&&path.equals("/outage.mp4");
                byte[] body=fail?new byte[0]:path.equals("/backup.mp4")?standard:wide;
                String contentType="video/mp4";
                if(path.equals("/website.m3u8")){contentType="text/html; charset=utf-8";body="<html>Sign in</html>".getBytes(StandardCharsets.UTF_8);}
                if(path.equals("/api.m3u8")){contentType="application/json";body="{\"error\":\"session required\"}".getBytes(StandardCharsets.UTF_8);}
                if(path.equals("/generic.mp4"))contentType="text/plain";
                if(path.endsWith("catalog.m3u")){
                    boolean slow=path.equals("/slow-catalog.m3u");
                    if(slow){catalogRequested.countDown();try{if(!catalogGate.await(10,java.util.concurrent.TimeUnit.SECONDS))continue;}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}
                    body=("#EXTM3U\n#EXTINF:-1,NFL fixture\n"+base+(slow?"/stale.mp4":"/fresh.mp4")+"\n").getBytes(StandardCharsets.UTF_8);
                }
                var out=socket.getOutputStream();
                out.write(("HTTP/1.1 "+(fail?"503 Service Unavailable\r\nRetry-After: 30":"200 OK")+"\r\nContent-Type: "+contentType+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);
            }catch(IOException ignored){}
        },"playback-behavior-fixture");serving.start();
        JSONArray feeds=new JSONArray();for(int i=0;i<2;i++)feeds.put(new JSONObject().put("title","Game "+i).put("eventId","behavior-"+i).put("url",base+"/original"+i+".mp4"));
        scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).putExtra("selectedFeeds",feeds.toString()));
        awaitPlaying();
    }
    @After public void cleanup()throws Exception {
        if(scenario!=null)scenario.close();
        catalogGate.countDown();if(server!=null)server.close();if(serving!=null)serving.join(4000);
        if(saved!=null){var edit=prefs.edit().clear();for(var e:saved.entrySet()){
            Object v=e.getValue();if(v instanceof String)edit.putString(e.getKey(),(String)v);
            else if(v instanceof Integer)edit.putInt(e.getKey(),(Integer)v);
            else if(v instanceof Boolean)edit.putBoolean(e.getKey(),(Boolean)v);
            else if(v instanceof Long)edit.putLong(e.getKey(),(Long)v);
            else if(v instanceof Float)edit.putFloat(e.getKey(),(Float)v);
        }assertTrue(edit.commit());}
    }
    private PlayerView view(MainActivity a,int slot){FrameLayout tile=a.findViewById(android.R.id.content).findViewWithTag("slot"+slot);return (PlayerView)tile.getChildAt(0);}
    @Test public void webAndApiResponsesStopOnlyTheirSlotWithoutRetrying(){
        ExoPlayer[] healthy=new ExoPlayer[1];View[] surface=new View[1];
        scenario.onActivity(a->{healthy[0]=player(a,1);surface[0]=view(a,1).getVideoSurfaceView();});
        for(String path:new String[]{"/website.m3u8","/api.m3u8"}){
            scenario.onActivity(a->a.assign(0,new FeedParser.Feed("Invalid source",base+path)));
            await(a->player(a,0).getPlayerError()!=null);
            scenario.onActivity(a->{
                assertEquals(androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,player(a,0).getPlayerError().errorCode);
                assertFalse(a.recoveryPending(0));assertEquals(0,a.bandwidthShare(0));
                assertSame(healthy[0],player(a,1));assertSame(surface[0],view(a,1).getVideoSurfaceView());
            });
            SystemClock.sleep(1200);
            assertEquals("Do not reload a web/API response",1,Collections.frequency(requests,path));
        }
        scenario.onActivity(a->a.assign(0,new FeedParser.Feed("Generic MIME video",base+"/generic.mp4")));
        awaitPlaying();
        scenario.onActivity(a->{assertSame(healthy[0],player(a,1));assertSame(surface[0],view(a,1).getVideoSurfaceView());});
    }
    @Test public void repeatedBackExitsPlaybackAndReleasesDecoders(){
        MainActivity[] activity=new MainActivity[1];
        scenario.onActivity(a->{activity[0]=a;a.expandGame(0);a.onBackPressed();a.onBackPressed();a.onBackPressed();assertTrue("Back must not loop between controls and video",a.isFinishing());});
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(scenario.getState()!=androidx.lifecycle.Lifecycle.State.DESTROYED&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.getState());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{assertEquals(0,activity[0].audioDecoderCount());for(int i=0;i<2;i++)assertNull(player(activity[0],i));});
    }
    @Test public void dedicatedRemotePauseAndPlayKeysControlAllGames(){
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE);
        await(a->!player(a,0).getPlayWhenReady()&&!player(a,1).getPlayWhenReady());
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY);
        awaitPlaying();
    }
    @Test public void retryOfSuspendedPausedSlotActuallyResumesPlayback(){
        scenario.onActivity(a->{player(a,0).pause();a.pauseForDecoder(0,"Test suspension");});
        scenario.onActivity(a->a.retryPlayback(0));
        awaitPlaying();
        scenario.recreate();awaitPlaying();
    }
    @Test public void realPlaybackAndHttpFailureProduceUsefulPrivateDiagnostics()throws Exception {
        unavailable=true;
        scenario.onActivity(a->a.assign(0,new FeedParser.Feed("Secret title",base+"/outage.mp4","")));
        String report="";long deadline=SystemClock.elapsedRealtime()+10000;
        do{
            ByteArrayOutputStream out=new ByteArrayOutputStream();Diagnostics.export(InstrumentationRegistry.getInstrumentation().getTargetContext(),out);report=out.toString("UTF-8");
            if(report.contains("load_error")&&report.contains("http=503")&&report.contains("first_frame")&&report.contains("final=true"))break;
            SystemClock.sleep(50);
        }while(SystemClock.elapsedRealtime()<deadline);
        assertTrue(report.contains("source_start"));assertTrue(report.contains("video_format"));assertTrue(report.contains("playback_state"));
        assertTrue("Rendered video must produce startup telemetry",report.contains("first_frame"));
        assertTrue("Replacing a player must finalize its metrics",report.contains("final=true"));
        assertTrue(report.contains("playingMs="));assertTrue(report.contains("rebufferMs="));
        assertTrue("Actual HTTP response must reach the report",report.contains("http=503"));
        assertFalse(report.contains(base));assertFalse(report.contains("Secret title"));
    }
    private ExoPlayer player(MainActivity a,int slot){return (ExoPlayer)view(a,slot).getPlayer();}
    private void await(java.util.function.Predicate<MainActivity> condition){
        long end=SystemClock.elapsedRealtime()+15000;AtomicBoolean ready=new AtomicBoolean();
        while(SystemClock.elapsedRealtime()<end){scenario.onActivity(a->ready.set(condition.test(a)));if(ready.get())return;SystemClock.sleep(50);}
        fail("Expected playback behavior did not occur within 15 seconds");
    }
    private void awaitPlaying(){await(a->{for(int i=0;i<2;i++){
        ExoPlayer p=player(a,i);if(p==null||!p.isPlaying()||p.getVideoDecoderCounters()==null||p.getVideoDecoderCounters().renderedOutputBufferCount==0)return false;
    }return true;});}
    private void pressPlayPause(){InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    @Test public void recreationKeepsChangedSourceLayoutAndAudioAndDecodesBackup(){
        scenario.onActivity(a->{a.switchSource(0,new FeedParser.Feed("Backup",base+"/backup.mp4","behavior-0"));a.selectAudio(1);a.setCount(4);});
        await(a->player(a,0).isPlaying()&&player(a,0).getVideoFormat()!=null&&player(a,0).getVideoFormat().width==240);
        scenario.recreate();
        awaitPlaying();
        scenario.onActivity(a->{
            assertEquals("Source replacement must survive recreation",base+"/backup.mp4",player(a,0).getCurrentMediaItem().localConfiguration.uri.toString());
            assertEquals("Backup footage must actually decode",240,player(a,0).getVideoFormat().width);
            PlaybackWall wall=a.findViewById(android.R.id.content).findViewWithTag("playbackWall");
            assertEquals("User's four-view layout must survive",wall.getWidth()/2,wall.getChildAt(0).getWidth());
            assertEquals(1f,player(a,1).getVolume(),0);assertEquals(0f,player(a,0).getVolume(),0);
        });
        assertTrue(requests.contains("/backup.mp4"));
        long[] position=new long[1];scenario.onActivity(a->position[0]=player(a,0).getCurrentPosition());
        await(a->player(a,0).getCurrentPosition()>position[0]+200);
    }
    @Test public void remotePauseStopsBothTimelinesAndShowsPausedThenResumes(){
        pressPlayPause();await(a->!player(a,0).isPlaying()&&!player(a,1).isPlaying());
        // The app-thread pause flag precedes playback-thread acknowledgement.
        SystemClock.sleep(400);
        long[] positions=new long[2];scenario.onActivity(a->{a.setControlsVisible(true);for(int i=0;i<2;i++)positions[i]=player(a,i).getCurrentPosition();});
        SystemClock.sleep(400);
        scenario.onActivity(a->{for(int i=0;i<2;i++){
            assertEquals("Paused timeline must stop",positions[i],player(a,i).getCurrentPosition());
            FrameLayout tile=a.findViewById(android.R.id.content).findViewWithTag("slot"+i);
            assertTrue("Visible status must say Paused",((TextView)tile.getChildAt(1)).getText().toString().contains("Paused"));
        }});
        pressPlayPause();await(a->player(a,0).getCurrentPosition()>positions[0]+200&&player(a,1).getCurrentPosition()>positions[1]+200);
        scenario.onActivity(a->{for(int i=0;i<2;i++){
            FrameLayout tile=a.findViewById(android.R.id.content).findViewWithTag("slot"+i);
            assertTrue(((TextView)tile.getChildAt(1)).getText().toString().contains("Playing"));
        }});
    }
    @Test public void holdingPlayPauseDoesNotToggleAgainOnKeyRepeat(){
        var instrumentation=InstrumentationRegistry.getInstrumentation();long down=SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));
        instrumentation.waitForIdleSync();await(a->!player(a,0).getPlayWhenReady()&&!player(a,1).getPlayWhenReady());
        instrumentation.sendKeySync(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,1));
        instrumentation.sendKeySync(new KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));
        instrumentation.waitForIdleSync();
        scenario.onActivity(a->{for(int i=0;i<2;i++)assertFalse("A held key must not resume paused games",player(a,i).getPlayWhenReady());});
    }
    @Test public void pausedGamesStayPausedAcrossBackgroundAndRecreation(){
        pressPlayPause();
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        scenario.onActivity(a->{assertNull(player(a,0));assertNull(player(a,1));assertEquals(0,a.audioDecoderCount());});
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        await(a->player(a,0)!=null&&player(a,1)!=null);
        scenario.onActivity(a->{for(int i=0;i<2;i++)assertFalse("Returning must respect pause",player(a,i).getPlayWhenReady());});
        scenario.recreate();
        scenario.onActivity(a->{for(int i=0;i<2;i++)assertFalse("Recreation must respect pause",player(a,i).getPlayWhenReady());});
        pressPlayPause();awaitPlaying();
    }
    @Test public void pausedHiddenGameDoesNotAutoplayWhenRestored(){
        scenario.onActivity(a->{player(a,1).pause();a.expandGame(0);assertNull(player(a,1));a.expandGame(-1);});
        scenario.onActivity(a->assertFalse("Returning to multiview must not resume a paused game",player(a,1).getPlayWhenReady()));
    }
    @Test public void pausedGamesAllowTvIdleSleep(){
        pressPlayPause();
        await(a->(a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0);
        pressPlayPause();awaitPlaying();
        await(a->(a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0);
    }
    @Test public void corruptCatalogEntryDoesNotEraseGoodFeedsOrPlaybackSlots()throws Exception {
        scenario.close();
        JSONArray feeds=new JSONArray().put(new JSONObject().put("title","Broken").put("url","invalid"))
            .put(new JSONObject().put("title","Good").put("url",base+"/original0.mp4"));
        prefs.edit().putString("feeds",feeds.toString()).commit();
        scenario=ActivityScenario.launch(MainActivity.class);awaitPlaying();
        scenario.onActivity(a->{a.save();
            try{JSONArray savedFeeds=new JSONArray(prefs.getString("feeds","[]"));
                assertEquals(1,savedFeeds.length());assertEquals("Good",savedFeeds.getJSONObject(0).getString("title"));
            }catch(JSONException error){throw new AssertionError(error);}
        });
    }
    private void shell(String command)throws IOException {
        try(var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))){
            byte[] bytes=new byte[1024];while(input.read(bytes)!=-1){}
        }
    }
    @Test public void tvSleepReleasesPlayersAndCancelsNetworkWorkThenWakeRecovers()throws Exception {
        unavailable=true;
        scenario.onActivity(a->a.assign(0,new FeedParser.Feed("Outage",base+"/outage.mp4","behavior-0")));
        long end=SystemClock.elapsedRealtime()+10000;
        while(!requests.contains("/outage.mp4")&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);
        assertTrue("Server must receive the failing request",requests.contains("/outage.mp4"));
        try{
            shell("input keyevent 223");
            await(a->player(a,0)==null&&player(a,1)==null&&a.audioDecoderCount()==0&&a.admittedDecoderCount()==0);
            scenario.onActivity(a->{assertFalse(a.recoveryPending(0));assertEquals(0,a.bandwidthShare(0));});
            int requestsAtSleep=requests.size();SystemClock.sleep(1500);
            assertEquals("Sleeping TV must not request more media",requestsAtSleep,requests.size());
            unavailable=false;
        }finally{shell("input keyevent 224");shell("wm dismiss-keyguard");}
        awaitPlaying();
    }

    @Test public void leavingDuringCatalogImportDiscardsLateResultsAndAllowsANewImport()throws Exception {
        scenario.onActivity(a->a.startCatalogImport(base+"/slow-catalog.m3u"));
        assertTrue(catalogRequested.await(5,java.util.concurrent.TimeUnit.SECONDS));
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        scenario.onActivity(a->a.startCatalogImport(base+"/catalog.m3u"));
        catalogGate.countDown();
        await(a->prefs.getString("feeds","[]").contains("fresh.mp4"));
        JSONArray feeds=new JSONArray(prefs.getString("feeds","[]"));
        assertEquals("Cancelled import must not add a stale source",1,feeds.length());
        assertEquals(base+"/fresh.mp4",feeds.getJSONObject(0).getString("url"));
        awaitPlaying();
    }
    @Test public void expandedGameSurvivesRecreationWithoutStartingHiddenGames(){
        scenario.onActivity(a->a.expandGame(1));scenario.recreate();
        await(a->player(a,1)!=null&&player(a,1).isPlaying());
        scenario.onActivity(a->{
            assertNull(player(a,0));assertEquals(0,a.bandwidthShare(0));
            PlaybackWall wall=a.findViewById(android.R.id.content).findViewWithTag("playbackWall");
            assertEquals(wall.getWidth(),view(a,1).getWidth());assertEquals(wall.getHeight(),view(a,1).getHeight());
            a.expandGame(-1);
        });awaitPlaying();
    }
    @Test public void corruptSlotDoesNotPreventAnotherGameFromDecoding()throws Exception {
        scenario.close();
        JSONArray slots=new JSONArray(prefs.getString("slots","[]"));slots.put(0,new JSONObject().put("title","Broken").put("url","invalid"));
        prefs.edit().putString("slots",slots.toString()).commit();
        scenario=ActivityScenario.launch(MainActivity.class);
        await(a->player(a,1)!=null&&player(a,1).isPlaying()&&player(a,1).getVideoDecoderCounters()!=null&&player(a,1).getVideoDecoderCounters().renderedOutputBufferCount>0);
        scenario.onActivity(a->{assertNull(player(a,0));assertEquals(1,a.activeGameCount());});
    }

}
