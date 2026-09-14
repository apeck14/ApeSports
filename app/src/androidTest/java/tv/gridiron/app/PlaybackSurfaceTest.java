package tv.gridiron.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.ui.PlayerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Offline device tests: actual codecs and SurfaceHolder callbacks, not mocked players. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class PlaybackSurfaceTest {
    private ActivityScenario<MainActivity> scenario;
    private SharedPreferences prefs;
    private Map<String, ?> savedPrefs;
    private final PlayerView[] views = new PlayerView[4];
    private final Player[] original = new Player[4];
    private final AtomicInteger[] created = counters(), destroyed = counters(), first = counters();
    private final AtomicInteger[] decoderStarts = counters();
    private String wide, standard;

    private static AtomicInteger[] counters() {
        return new AtomicInteger[]{new AtomicInteger(),new AtomicInteger(),new AtomicInteger(),new AtomicInteger()};
    }
    private String fixture(Context target, String name) throws Exception {
        File out = new File(target.getCacheDir(), "surface-test-" + name);
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
             FileOutputStream stream = new FileOutputStream(out)) {
            byte[] bytes = new byte[4096]; int n;
            while ((n=in.read(bytes))!=-1) stream.write(bytes,0,n);
        }
        return out.toURI().toString();
    }
    @Before public void setup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        savedPrefs = prefs.getAll();
        assertTrue(prefs.edit().clear().commit());
        wide=fixture(context,"wide.mp4"); standard=fixture(context,"standard.mp4");
        scenario=ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(a->{
            a.setCount(4);
            for(int i=0;i<4;i++) {
                final int slot=i;
                FrameLayout frame=a.findViewById(android.R.id.content).findViewWithTag("slot"+i);
                views[i]=(PlayerView)frame.getChildAt(0);
                assertTrue(views[i].getVideoSurfaceView() instanceof SurfaceView);
                ((SurfaceView)views[i].getVideoSurfaceView()).getHolder().addCallback(new SurfaceHolder.Callback(){
                    public void surfaceCreated(SurfaceHolder h){created[slot].incrementAndGet();}
                    public void surfaceDestroyed(SurfaceHolder h){destroyed[slot].incrementAndGet();}
                    public void surfaceChanged(SurfaceHolder h,int f,int w,int height){}
                });
                a.assign(i,new FeedParser.Feed("TEST "+i,wide));
                original[i]=views[i].getPlayer();
                original[i].setRepeatMode(Player.REPEAT_MODE_ONE);
                original[i].addListener(new Player.Listener(){
                    @Override public void onRenderedFirstFrame(){first[slot].incrementAndGet();}
                });
                ((ExoPlayer)original[i]).addAnalyticsListener(new AnalyticsListener(){
                    @Override public void onVideoDecoderInitialized(EventTime event,String name,long time,long duration){decoderStarts[slot].incrementAndGet();}
                });
            }
        });
        await(()->{
            for(int i=0;i<4;i++)if(first[i].get()==0)return false;
            return true;
        });
        settle();
        resetEvents();
    }
    @After public void teardown() {
        if(scenario!=null)scenario.close();
        if(savedPrefs!=null) {
            SharedPreferences.Editor edit=prefs.edit().clear();
            for(Map.Entry<String,?> e:savedPrefs.entrySet()) {
                if(e.getValue() instanceof String)edit.putString(e.getKey(),(String)e.getValue());
                else if(e.getValue() instanceof Integer)edit.putInt(e.getKey(),(Integer)e.getValue());
                else if(e.getValue() instanceof Boolean)edit.putBoolean(e.getKey(),(Boolean)e.getValue());
                else if(e.getValue() instanceof Long)edit.putLong(e.getKey(),(Long)e.getValue());
                else if(e.getValue() instanceof Float)edit.putFloat(e.getKey(),(Float)e.getValue());
            }
            assertTrue(edit.commit());
        }
    }
    interface Condition {boolean ready();}
    private void await(Condition condition) {
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end) {
            if(condition.ready())return;
            SystemClock.sleep(50);
        }
        fail("Playback did not reach the expected state within 20 seconds");
    }
    private void settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);
    }
    private void resetEvents() {
        for(int i=0;i<4;i++){created[i].set(0);destroyed[i].set(0);first[i].set(0);decoderStarts[i].set(0);}
    }
    private void assertUndisturbed(int excluded) {
        // Object identity alone can pass with a frozen picture. Require new decoded output.
        int[] rendered=new int[4];
        scenario.onActivity(a->{for(int i=0;i<4;i++)if(i!=excluded){
            var counters=((ExoPlayer)original[i]).getVideoDecoderCounters();
            assertNotNull(counters);counters.ensureUpdated();rendered[i]=counters.renderedOutputBufferCount;
        }});
        AtomicBoolean advancing=new AtomicBoolean();
        await(()->{scenario.onActivity(a->{boolean all=true;for(int i=0;i<4;i++)if(i!=excluded){
            var counters=((ExoPlayer)original[i]).getVideoDecoderCounters();
            if(counters!=null)counters.ensureUpdated();
            all&=original[i].isPlaying()&&counters!=null&&counters.renderedOutputBufferCount>=rendered[i]+3;
        }advancing.set(all);});return advancing.get();});
        scenario.onActivity(a->{
            for(int i=0;i<4;i++)if(i!=excluded) {
                FrameLayout frame=a.findViewById(android.R.id.content).findViewWithTag("slot"+i);
                assertSame("View changed for slot "+i,views[i],frame.getChildAt(0));
                assertSame("Player changed for slot "+i,original[i],views[i].getPlayer());
                assertEquals("Surface created for slot "+i,0,created[i].get());
                assertEquals("Surface destroyed for slot "+i,0,destroyed[i].get());
                assertEquals("First frame reset for slot "+i,0,first[i].get());
                assertEquals("Decoder restarted for slot "+i,0,decoderStarts[i].get());
                assertNull(original[i].getPlayerError());
            }
        });
    }
    @Test public void repeatedSameLayoutAndCatalogChangesDoNotResetOutputs() {
        scenario.onActivity(a->{
            for(int i=0;i<5;i++)a.setCount(4);
            a.addCatalogFeed(new FeedParser.Feed("NFL test","https://example.com/test.m3u8"));
            a.removeCatalogFeed(0);
            a.refreshCatalog(); // Same isolated update used after a catalog import.
        });
        settle();SystemClock.sleep(1200);assertUndisturbed(-1); // Include the periodic quality refresh.
    }
    @Test public void activeBudgetIgnoresEmptyAndHiddenSlots() {
        scenario.onActivity(a->{
            assertEquals(4,a.activeGameCount());
            assertEquals(a.bandwidthShare(0),a.bandwidthShare(3));
            a.assign(1,null);a.assign(2,null);a.assign(3,null);
            assertEquals(1,a.activeGameCount());
            assertSame(original[0],views[0].getPlayer());
            VideoBudget.Profile expected=a.videoProfile();
            assertEquals(expected.height,views[0].getPlayer().getTrackSelectionParameters().maxVideoHeight);
            a.assign(2,new FeedParser.Feed("Second",wide));assertEquals(2,a.activeGameCount());
            a.expandGame(2);assertEquals(1,a.activeGameCount());assertNull(views[0].getPlayer());assertEquals(0,a.bandwidthShare(0));assertTrue(a.bandwidthShare(2)>0);
            a.expandGame(-1);assertEquals(2,a.activeGameCount());assertNotNull(views[0].getPlayer());
        });
    }
    @Test public void manualRetryKeepsPlayerAndEverySurface() {
        scenario.onActivity(a->a.retryPlayback(2));awaitAllReady();settle();assertUndisturbed(2);
        scenario.onActivity(a->{assertSame(original[2],views[2].getPlayer());assertEquals(0,created[2].get());assertEquals(0,destroyed[2].get());});
    }
    private void awaitAudioOwner(int selected){
        AtomicBoolean ready=new AtomicBoolean();
        await(()->{scenario.onActivity(a->{
            boolean valid=a.audioDecoderCount()==1;
            for(int i=0;i<4;i++)if(views[i].getPlayer()!=null){
                Player p=views[i].getPlayer();boolean audio=false;
                for(androidx.media3.common.Tracks.Group group:p.getCurrentTracks().getGroups())if(group.getType()==androidx.media3.common.C.TRACK_TYPE_AUDIO&&group.isSelected())audio=true;
                valid&=audio==(i==selected);
                valid&=p.getVolume()==(i==selected?1f:0f);
                if(i==selected){
                    var output=((ExoPlayer)p).getAudioDecoderCounters();
                    if(output!=null)output.ensureUpdated();
                    valid&=output!=null&&output.renderedOutputBufferCount>0;
                }else valid&=a.audioReservation((ExoPlayer)p)==0;
            }
            ready.set(valid);
        });return ready.get();});
    }
    @Test public void onlySelectedAudioDecodesAndSwitchingPreservesAllVideoOutputs(){
        awaitAudioOwner(0);
        for(int chosen:new int[]{1,3,2,0}){
            scenario.onActivity(a->a.selectAudio(chosen));awaitAudioOwner(chosen);settle();assertUndisturbed(-1);
        }
    }
    @Test public void rapidAudioChangesGrantOnlyLatestChoice(){
        awaitAudioOwner(0);
        scenario.onActivity(a->{a.selectAudio(1);a.selectAudio(2);a.selectAudio(3);a.selectAudio(1);});
        awaitAudioOwner(1);settle();assertUndisturbed(-1);
        scenario.onActivity(a->{assertEquals(1,a.audioDecoderCount());for(int i=0;i<4;i++)assertNull(a.suspensionReason(i));});
    }
    @Test public void pausingCancelsRecoveryWithoutResumingTheGame() {
        scenario.onActivity(a->{
            a.handlePlaybackError(0,(ExoPlayer)original[0],new androidx.media3.common.PlaybackException("injected timeout",null,androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT));
            original[0].pause();
        });
        settle();scenario.onActivity(a->{assertFalse(a.recoveryPending(0));assertFalse(original[0].getPlayWhenReady());assertSame(original[0],views[0].getPlayer());assertEquals(0,a.bandwidthShare(0));assertEquals(3,a.activeGameCount());});
    }
    @Test public void actualHttpOutageRecoversSamePlayerAfterMedia3Retries() throws Exception {
        java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        try(java.io.InputStream in=new java.io.FileInputStream(new File(new java.net.URI(wide)))){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
        }
        byte[] clip=bytes.toByteArray();
        AtomicInteger requests=new AtomicInteger(),errors=new AtomicInteger();
        java.util.List<Long> requestTimes=new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread serving=new Thread(()->{
            while(!server.isClosed())try(java.net.Socket socket=server.accept()){
                socket.setSoTimeout(3000);
                java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                requestTimes.add(SystemClock.elapsedRealtime());boolean fail=requests.incrementAndGet()<=4;
                byte[] body=fail?new byte[0]:clip;
                String header="HTTP/1.1 "+(fail?"503 Service Unavailable":"200 OK")+(fail?"\r\nRetry-After: 1":"")+"\r\nContent-Type: video/mp4\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            }catch(java.io.IOException ignored){}
        });
        serving.start();
        Player[] recovering=new Player[1];
        try {
            scenario.onActivity(a->{
                a.assign(2,new FeedParser.Feed("Local outage fixture","http://127.0.0.1:"+server.getLocalPort()+"/game.mp4"));
                recovering[0]=views[2].getPlayer();recovering[0].addListener(new Player.Listener(){
                    @Override public void onPlayerError(androidx.media3.common.PlaybackException error){errors.incrementAndGet();}
                });
            });
            AtomicBoolean ready=new AtomicBoolean();
            await(()->{scenario.onActivity(a->ready.set(errors.get()>0&&views[2].getPlayer()!=null&&views[2].getPlayer().getPlaybackState()==Player.STATE_READY));return ready.get();});
            scenario.onActivity(a->{assertSame(recovering[0],views[2].getPlayer());assertFalse(a.recoveryPending(2));});
            assertTrue(requests.get()>=5);assertEquals(0,created[2].get());assertEquals(0,destroyed[2].get());assertUndisturbed(2);
            for(int i=1;i<5;i++)assertTrue("Retry-After respected at both retry layers",requestTimes.get(i)-requestTimes.get(i-1)>=950);
        } finally {server.close();serving.join(4000);}
    }
    @Test public void serverWaitCanBeCancelledAndExcessiveWaitDoesNotAutoRetry(){
        scenario.onActivity(a->{
            var spec=new androidx.media3.datasource.DataSpec.Builder().setUri("https://example.com/live").build();
            var error=new androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(503,"test",null,
                java.util.Collections.singletonMap("Retry-After",java.util.Collections.singletonList("30")),spec,new byte[0]);
            a.handlePlaybackError(1,(ExoPlayer)original[1],new androidx.media3.common.PlaybackException("test",error,androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
            assertTrue(a.recoveryPending(1));original[1].pause();assertFalse(a.recoveryPending(1));original[1].play();
            var longWait=new androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(429,"test",null,
                java.util.Collections.singletonMap("Retry-After",java.util.Collections.singletonList("301")),spec,new byte[0]);
            a.handlePlaybackError(1,(ExoPlayer)original[1],new androidx.media3.common.PlaybackException("test",longWait,androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS));
            assertFalse(a.recoveryPending(1));assertTrue(a.bufferDetails(1).contains("Buffered ahead:"));assertTrue(a.bufferDetails(1).contains("Live offset: unavailable"));
        });
        settle();assertUndisturbed(-1);
    }
    @Test public void bufferingSpinnerIsCenteredOnlyOnItsGameInEveryLayout() throws Exception {
        java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        var gate=new java.util.concurrent.CountDownLatch(1);
        var bytes=new java.io.ByteArrayOutputStream();try(var in=new java.io.FileInputStream(new File(new java.net.URI(wide)))){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}byte[] clip=bytes.toByteArray();
        Thread serving=new Thread(()->{
            try(java.net.Socket socket=server.accept()){
                socket.setSoTimeout(3000);var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                var out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "+clip.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                out.flush();
                if(gate.await(20,java.util.concurrent.TimeUnit.SECONDS)){out.write(clip);out.flush();}
            }catch(java.io.IOException ignored){}catch(InterruptedException e){Thread.currentThread().interrupt();}
        });serving.start();
        try{
            scenario.onActivity(a->{original[0].setMediaItem(androidx.media3.common.MediaItem.fromUri("http://127.0.0.1:"+server.getLocalPort()+"/game.mp4"));original[0].prepare();});
            AtomicBoolean buffering=new AtomicBoolean();await(()->{scenario.onActivity(a->buffering.set(views[0].getPlayer().getPlaybackState()==Player.STATE_BUFFERING));return buffering.get();});
            assertUndisturbed(0);
            scenario.onActivity(a->a.expandGame(0));settle();
            long[] initialShare=new long[1];int[] initialHeight=new int[1];
            scenario.onActivity(a->{initialShare[0]=a.bandwidthShare(0);initialHeight[0]=a.videoProfile().height;});
            AtomicBoolean reduced=new AtomicBoolean();await(()->{scenario.onActivity(a->reduced.set(a.bandwidthShare(0)<initialShare[0]));return reduced.get();});
            scenario.onActivity(a->assertEquals("Network adaptation must not also penalize decoder resolution",initialHeight[0],a.videoProfile().height));
            for(int layout:new int[]{4,2,1,4}){
                scenario.onActivity(a->a.setCount(layout));settle();
                assertSpinnerCentered();
            }
            scenario.onActivity(a->a.expandGame(0));settle();assertSpinnerCentered();
            scenario.onActivity(a->{
                views[0].getPlayer().pause();assertEquals(View.GONE,views[0].findViewById(androidx.media3.ui.R.id.exo_buffering).getVisibility());
                views[0].getPlayer().play();assertEquals(View.VISIBLE,views[0].findViewById(androidx.media3.ui.R.id.exo_buffering).getVisibility());
            });
            gate.countDown();
            AtomicBoolean playing=new AtomicBoolean();
            await(()->{scenario.onActivity(a->playing.set(views[0].getPlayer().isPlaying()));return playing.get();});
            scenario.onActivity(a->assertEquals(View.GONE,views[0].findViewById(androidx.media3.ui.R.id.exo_buffering).getVisibility()));
        }finally{gate.countDown();server.close();serving.join(4000);}
    }
    private void assertSpinnerCentered(){
        AtomicBoolean othersReady=new AtomicBoolean();await(()->{scenario.onActivity(a->{boolean ready=true;for(int i=1;i<4;i++){Player player=views[i].getPlayer();ready&=player==null||player.isPlaying();}othersReady.set(ready);});return othersReady.get();});
        scenario.onActivity(a->{
            View spinner=views[0].findViewById(androidx.media3.ui.R.id.exo_buffering);
            assertEquals(View.VISIBLE,spinner.getVisibility());
            int[] indicator=new int[2],tile=new int[2];spinner.getLocationOnScreen(indicator);views[0].getLocationOnScreen(tile);
            assertEquals(tile[0]+views[0].getWidth()/2f,indicator[0]+spinner.getWidth()/2f,1f);
            assertEquals(tile[1]+views[0].getHeight()/2f,indicator[1]+spinner.getHeight()/2f,1f);
            for(int i=1;i<4;i++)assertEquals(View.GONE,views[i].findViewById(androidx.media3.ui.R.id.exo_buffering).getVisibility());
        });
    }
    @Test public void changingGameSourcePlaysVideoAndPreservesOtherGames() throws Exception {
        java.net.ServerSocket server=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        var bytes=new java.io.ByteArrayOutputStream();try(var in=new java.io.FileInputStream(new File(new java.net.URI(standard)))){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}byte[] clip=bytes.toByteArray();
        Thread serving=new Thread(()->{
            while(!server.isClosed())try(java.net.Socket socket=server.accept()){
                socket.setSoTimeout(3000);var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                String header="HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: "+clip.length+"\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(clip);
            }catch(java.io.IOException ignored){}
        });serving.start();
        String event="switch-test-"+java.util.UUID.randomUUID();
        FeedParser.Feed backup=new FeedParser.Feed("Backup","http://127.0.0.1:"+server.getLocalPort()+"/game.mp4",event);
        try{
            scenario.onActivity(a->{a.assign(2,new FeedParser.Feed("Fixture game",wide,event));
                new GameSources(a).select(event,backup);
                var picker=GameSources.show(a,event,"Fixture game",wide,source->a.switchSource(2,source));
                picker.getListView().performItemClick(null,0,0);picker.dismiss();
            });
            awaitAllReady();settle();assertUndisturbed(2);
            scenario.onActivity(a->{
                Player switched=views[2].getPlayer();assertNotSame(original[2],switched);
                assertEquals(backup.url,switched.getCurrentMediaItem().localConfiguration.uri.toString());
                a.switchSource(2,backup);assertSame(switched,views[2].getPlayer());
                assertEquals(backup.url,new GameSources(a).selected(event));
                try{var persisted=a.feed(new org.json.JSONArray(a.getPreferences(0).getString("slots","[]")).getJSONObject(2));
                    assertEquals(event,persisted.eventId);assertEquals("Fixture game",persisted.title);
                }catch(org.json.JSONException error){throw new AssertionError(error);}
            });
            assertEquals(0,created[2].get());assertEquals(0,destroyed[2].get());
        }finally{
            server.close();serving.join(4000);
            InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("HomeActivity",0).edit().remove("source."+event).remove("sources."+event).commit();
        }
    }
    @Test public void permanentFailureReturnsBandwidthAndRetryRestoresItsReservation() {
        scenario.onActivity(a->{
            long before=a.bandwidthShare(0);
            a.handlePlaybackError(2,(ExoPlayer)original[2],new androidx.media3.common.PlaybackException("unsupported feed",null,androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED));
            assertFalse(a.recoveryPending(2));assertEquals(0,a.bandwidthShare(2));
            assertEquals(3,a.activeGameCount());assertTrue(a.bandwidthShare(0)>before);
            assertSame(original[2],views[2].getPlayer());
            a.retryPlayback(2);
            assertEquals(4,a.activeGameCount());assertEquals(a.bandwidthShare(0),a.bandwidthShare(2));
            assertTrue(a.bandwidthShare(2)>0);assertSame(original[2],views[2].getPlayer());
        });
        awaitAllReady();settle();assertUndisturbed(2);
        assertEquals(0,created[2].get());assertEquals(0,destroyed[2].get());
    }
    @Test public void transientRetriesReserveBandwidthUntilExhaustedAndReplacementRestoresIt() {
        scenario.onActivity(a->{
            var timeout=new androidx.media3.common.PlaybackException("timeout",null,androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT);
            for(int attempt=0;attempt<4;attempt++){
                a.handlePlaybackError(2,(ExoPlayer)original[2],timeout);
                assertTrue(a.recoveryPending(2));assertTrue(a.bandwidthShare(2)>0);assertEquals(4,a.activeGameCount());
            }
            a.handlePlaybackError(2,(ExoPlayer)original[2],timeout);
            assertFalse(a.recoveryPending(2));assertEquals(0,a.bandwidthShare(2));assertEquals(3,a.activeGameCount());
            a.assign(2,new FeedParser.Feed("Replacement",standard));
            assertEquals(4,a.activeGameCount());assertTrue(a.bandwidthShare(2)>0);
        });
        awaitAllReady();settle();assertUndisturbed(2);
    }
    @Test public void transientRecoveryReusesPlayerAndReplacementCancelsPendingRetry() {
        scenario.onActivity(a->{
            a.handlePlaybackError(2,(ExoPlayer)original[2],new androidx.media3.common.PlaybackException("injected timeout",null,androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT));
            assertTrue(a.recoveryPending(2));
        });
        AtomicBoolean done=new AtomicBoolean();await(()->{scenario.onActivity(a->done.set(!a.recoveryPending(2)));return done.get();});
        assertUndisturbed(-1);
        scenario.onActivity(a->{
            a.handlePlaybackError(2,(ExoPlayer)original[2],new androidx.media3.common.PlaybackException("injected timeout",null,androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT));
            a.assign(2,new FeedParser.Feed("Replacement",standard));assertFalse(a.recoveryPending(2));
        });
        awaitAllReady();assertUndisturbed(2);
    }
    @Test public void liveWindowRecoveryAndBackgroundCancellationAreIsolated() {
        scenario.onActivity(a->{a.handlePlaybackError(1,(ExoPlayer)original[1],new androidx.media3.common.PlaybackException("injected live window",null,androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW));assertTrue(a.recoveryPending(1));});
        AtomicBoolean done=new AtomicBoolean();await(()->{scenario.onActivity(a->done.set(!a.recoveryPending(1)));return done.get();});
        settle();assertUndisturbed(1);scenario.onActivity(a->assertSame(original[1],views[1].getPlayer()));
        scenario.onActivity(a->a.handlePlaybackError(1,(ExoPlayer)original[1],new androidx.media3.common.PlaybackException("injected timeout",null,androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)));
        scenario.moveToState(Lifecycle.State.CREATED);scenario.onActivity(a->{assertFalse(a.recoveryPending(1));assertNull(views[1].getPlayer());});
    }
    @Test public void criticalMemoryPressureReleasesExtraGameWithoutRestartLoop() {
        scenario.onActivity(a->{
            assertEquals(4,a.admittedDecoderCount());
            a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
            assertEquals(3,a.activeGameCount());assertEquals(3,a.admittedDecoderCount());
            assertSame(original[0],views[0].getPlayer());assertNull(views[3].getPlayer());
            assertNotNull(a.suspensionReason(3));assertEquals(0,a.bandwidthShare(3));
            for(int n=0;n<4;n++)a.startPlayers();
            assertNull(views[3].getPlayer());assertEquals(3,a.admittedDecoderCount());
        });
        settle();assertUndisturbed(3);
    }
    @Test public void admissionDenialIsBoundedAndFullScreenCanRecoverAssignment() {
        scenario.onActivity(a->{
            a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
            a.assign(3,new FeedParser.Feed("Retry retained feed",wide)); // Session limit now three.
        });
        AtomicBoolean paused=new AtomicBoolean();
        await(()->{scenario.onActivity(a->paused.set(a.suspensionReason(3)!=null&&views[3].getPlayer()==null));return paused.get();});
        settle();assertUndisturbed(3);
        scenario.onActivity(a->{
            assertEquals(3,a.admittedDecoderCount());assertEquals(0,a.bandwidthShare(3));
            assertTrue(a.suspensionReason(3).contains("limited to 3"));
            a.expandGame(3);
        });
        AtomicBoolean ready=new AtomicBoolean();
        await(()->{scenario.onActivity(a->ready.set(views[3].getPlayer()!=null&&views[3].getPlayer().getPlaybackState()==Player.STATE_READY));return ready.get();});
        scenario.onActivity(a->{assertEquals(1,a.admittedDecoderCount());assertNull(a.suspensionReason(3));
            for(int i=0;i<3;i++)assertNull(views[i].getPlayer());
        });
    }
    @Test public void lowMemoryOnlyLowersQualityAndBackgroundReleasesAllReservations() {
        scenario.onActivity(a->{a.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);assertEquals(4,a.admittedDecoderCount());});
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.onActivity(a->assertEquals(0,a.admittedDecoderCount()));
        scenario.moveToState(Lifecycle.State.RESUMED);awaitAllReady();
        scenario.onActivity(a->assertEquals(4,a.admittedDecoderCount()));
    }
    private void awaitAllReady() {
        AtomicBoolean ready=new AtomicBoolean();
        await(()->{scenario.onActivity(a->{boolean all=true;for(PlayerView view:views)all&=view.getPlayer()!=null&&view.getPlayer().getPlaybackState()==Player.STATE_READY;ready.set(all);});return ready.get();});
    }
    private void assertQuality(Player player,boolean single) {
        // Hardware/output-aware profiles supersede step 2's fixed caps.
        assertTrue(player.getTrackSelectionParameters().maxVideoWidth>0);
        assertTrue(player.getTrackSelectionParameters().maxVideoHeight<=720); // 720p emulator output
        assertTrue(player.getTrackSelectionParameters().maxVideoBitrate>=0);
        assertTrue(player.getTrackSelectionParameters().maxVideoBitrate<Integer.MAX_VALUE);
        assertEquals(0,player.getTrackSelectionParameters().minVideoFrameRate);
    }
    @Test public void expandReleasesHiddenPlayersAndReturnPreservesVisibleOutput() {
        for(int slot=0;slot<4;slot++) {
            final int chosen=slot;
            Player[] before=new Player[4];
            AtomicInteger selectedFrames=new AtomicInteger(),selectedDecoders=new AtomicInteger();
            scenario.onActivity(a->{for(int i=0;i<4;i++)before[i]=views[i].getPlayer();
                before[chosen].addListener(new Player.Listener(){@Override public void onRenderedFirstFrame(){selectedFrames.incrementAndGet();}});
                ((ExoPlayer)before[chosen]).addAnalyticsListener(new AnalyticsListener(){@Override public void onVideoDecoderInitialized(EventTime event,String name,long time,long duration){selectedDecoders.incrementAndGet();}});
                a.expandGame(chosen);
            });settle();
            scenario.onActivity(a->{
                PlaybackWall wall=a.findViewById(android.R.id.content).findViewWithTag("playbackWall");
                for(int i=0;i<4;i++) {
                    View child=wall.getChildAt(i);
                    assertEquals(View.VISIBLE,child.getVisibility());
                    assertEquals(i==chosen,child.isFocusable());
                    if(i==chosen){assertSame(before[i],views[i].getPlayer());assertQuality(views[i].getPlayer(),true);assertEquals(wall.getWidth(),child.getWidth());}
                    else {assertNull(views[i].getPlayer());assertTrue(((ExoPlayer)before[i]).isReleased());assertTrue(child.getTranslationX()>wall.getWidth());}
                    assertEquals(0,created[i].get());assertEquals(0,destroyed[i].get());
                }
                a.expandGame(chosen); // Repeated expansion must not recreate the visible player.
                assertSame(before[chosen],views[chosen].getPlayer());
                a.onBackPressed();
            });
            awaitAllReady();settle();
            scenario.onActivity(a->{for(int i=0;i<4;i++){
                assertQuality(views[i].getPlayer(),false);
                if(i==chosen)assertSame(before[i],views[i].getPlayer());else assertNotSame(before[i],views[i].getPlayer());
                assertEquals(0,created[i].get());assertEquals(0,destroyed[i].get());
            }});
            assertEquals(0,selectedFrames.get());assertEquals(0,selectedDecoders.get());
        }
        assertEquals(0,first[0].get());assertEquals(0,decoderStarts[0].get());
    }
    @Test public void sameCountWhileExpandedReturnsToGrid() {
        scenario.onActivity(a->a.expandGame(3));settle();
        scenario.onActivity(a->a.setCount(4));awaitAllReady();settle();
        scenario.onActivity(a->{
            assertSame(original[3],views[3].getPlayer());assertQuality(views[3].getPlayer(),false);
            PlaybackWall wall=a.findViewById(android.R.id.content).findViewWithTag("playbackWall");
            assertEquals(wall.getWidth()/2,wall.getChildAt(0).getWidth());
            assertEquals(wall.getHeight()/2,wall.getChildAt(0).getHeight());
        });
        assertEquals(0,first[3].get());assertEquals(0,decoderStarts[3].get());
    }
    @Test public void expandedRetryBackgroundAndRemovalRespectVisibility() {
        scenario.onActivity(a->{a.expandGame(2);a.assign(2,new FeedParser.Feed("Retry",wide));
            assertNotSame(original[2],views[2].getPlayer());assertQuality(views[2].getPlayer(),true);
            for(int i=0;i<4;i++)if(i!=2)assertNull(views[i].getPlayer());
        });
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.onActivity(a->{for(PlayerView view:views)assertNull(view.getPlayer());});
        scenario.moveToState(Lifecycle.State.RESUMED);
        scenario.onActivity(a->{assertNotNull(views[2].getPlayer());assertQuality(views[2].getPlayer(),true);
            for(int i=0;i<4;i++)if(i!=2)assertNull(views[i].getPlayer());
            a.assign(2,null);
            assertNull(views[2].getPlayer());
            for(int i=0;i<4;i++)if(i!=2){assertNotNull(views[i].getPlayer());assertQuality(views[i].getPlayer(),false);}
        });
    }
    @Test public void expandedLayoutChangeDoesNotRestoreOutOfRangePlayers() {
        scenario.onActivity(a->{a.expandGame(3);a.setCount(2);
            for(int i=0;i<2;i++){assertNotNull(views[i].getPlayer());assertQuality(views[i].getPlayer(),false);}
            assertNull(views[2].getPlayer());assertNull(views[3].getPlayer());
            a.expandGame(1);assertQuality(views[1].getPlayer(),true);assertNull(views[0].getPlayer());
            a.setCount(1);assertQuality(views[0].getPlayer(),true);assertNull(views[1].getPlayer());
        });
    }
    @Test public void replaceAndRetryOnlyAffectTheirSlot() {
        for(int attempt=0;attempt<2;attempt++) {
            scenario.onActivity(a->a.assign(2,new FeedParser.Feed("Replacement",standard)));
            AtomicBoolean ready=new AtomicBoolean();
            await(()->{scenario.onActivity(a->ready.set(views[2].getPlayer().getPlaybackState()==Player.STATE_READY));return ready.get();});
            settle();assertUndisturbed(2);
            scenario.onActivity(a->{
                assertNotSame(original[2],views[2].getPlayer());
                assertEquals(0,destroyed[2].get());
                assertEquals(0,created[2].get());
                View content=views[2].findViewById(androidx.media3.ui.R.id.exo_content_frame);
                assertEquals(4.0/3,content.getWidth()/(double)content.getHeight(),0.02);
            });
        }
    }
    @Test public void removeThenRestoreKeepsSlotViewAndOtherGames() {
        scenario.onActivity(a->a.assign(1,null));settle();assertUndisturbed(1);
        scenario.onActivity(a->{assertNull(views[1].getPlayer());a.assign(1,new FeedParser.Feed("Restored",wide));});
        settle();assertUndisturbed(1);
        assertEquals(0,destroyed[1].get());assertEquals(0,created[1].get());
    }
    @Test public void oneTwoFourGeometryAndRetainedPlayers() {
        scenario.onActivity(a->a.setCount(2));settle();
        scenario.onActivity(a->{
            PlaybackWall wall=a.findViewById(android.R.id.content).findViewWithTag("playbackWall");
            assertSame(original[0],views[0].getPlayer());assertSame(original[1],views[1].getPlayer());
            assertNull(views[2].getPlayer());assertNull(views[3].getPlayer());
            assertEquals(wall.getWidth(),wall.getChildAt(0).getWidth());
            assertEquals(wall.getHeight()/2,wall.getChildAt(0).getHeight());
            assertEquals(wall.getHeight()/2,wall.getChildAt(1).getTranslationY(),0.01);
            View content=views[0].findViewById(androidx.media3.ui.R.id.exo_content_frame);
            assertEquals(16.0/9,content.getWidth()/(double)content.getHeight(),0.02);
        });
        scenario.onActivity(a->a.setCount(1));settle();
        scenario.onActivity(a->{
            PlaybackWall wall=a.findViewById(android.R.id.content).findViewWithTag("playbackWall");
            assertSame(original[0],views[0].getPlayer());assertNull(views[1].getPlayer());
            assertEquals(wall.getWidth(),wall.getChildAt(0).getWidth());assertEquals(wall.getHeight(),wall.getChildAt(0).getHeight());
        });
        scenario.onActivity(a->a.setCount(4));settle();
        scenario.onActivity(a->assertSame(original[0],views[0].getPlayer()));
        assertEquals(0,created[0].get());assertEquals(0,destroyed[0].get());assertEquals(0,first[0].get());
    }
    @Test public void backgroundReleasesPlayersAndResumeReusesViews() {
        scenario.moveToState(Lifecycle.State.CREATED);
        scenario.onActivity(a->{for(PlayerView view:views)assertNull(view.getPlayer());});
        scenario.moveToState(Lifecycle.State.RESUMED);
        AtomicBoolean ready=new AtomicBoolean();
        await(()->{scenario.onActivity(a->{boolean all=true;for(PlayerView view:views)all&=view.getPlayer()!=null&&view.getPlayer().getPlaybackState()==Player.STATE_READY;ready.set(all);});return ready.get();});
        scenario.onActivity(a->{
            for(int i=0;i<4;i++) {
                FrameLayout frame=a.findViewById(android.R.id.content).findViewWithTag("slot"+i);
                assertSame(views[i],frame.getChildAt(0));assertNotSame(original[i],views[i].getPlayer());
            }
        });
    }
    @Test public void oddPixelDimensionsHaveNoGaps() {
        scenario.onActivity(a->{
            PlaybackWall wall=new PlaybackWall(a);
            for(int i=0;i<4;i++)wall.addView(new View(a));
            wall.measure(View.MeasureSpec.makeMeasureSpec(1281,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(721,View.MeasureSpec.EXACTLY));
            wall.layout(0,0,1281,721);
            assertEquals(1281,wall.getChildAt(0).getWidth()+wall.getChildAt(1).getWidth());
            assertEquals(721,wall.getChildAt(0).getHeight()+wall.getChildAt(2).getHeight());
            assertEquals(wall.getChildAt(0).getWidth(),wall.getChildAt(1).getTranslationX(),0);
            assertEquals(wall.getChildAt(0).getHeight(),wall.getChildAt(2).getTranslationY(),0);
        });
    }
}
