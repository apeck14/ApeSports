package tv.gridiron.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity {
    private static final int WHITE=Ui.TEXT, MUTED=Ui.MUTED, FOCUS=Ui.TEXT;
    private static final AudioAttributes AUDIO_ATTRIBUTES=new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build();
    private final ArrayList<FeedParser.Feed> catalog=new ArrayList<>();
    private final FeedParser.Feed[] games=new FeedParser.Feed[4];
    private static long nextMetricId;
    private final PlaybackMetrics[] metrics=new PlaybackMetrics[4];
    private final String[] metricIds=new String[4],metricSources=new String[4];
    private final ExoPlayer[] players=new ExoPlayer[4];
    private final PlayerView[] surfaces=new PlayerView[4];
    private final FrameLayout[] slots=new FrameLayout[4];
    private final LinearLayout[] emptySlots=new LinearLayout[4];
    private final TextView[] emptyHints=new TextView[4];
    private PlaybackWall wall;
    private final TextView[] labels=new TextView[4];
    private final String[] states=new String[4];
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private Future<?> catalogRequest;
    private int catalogGeneration;
    private final boolean[] resumePlayback={true,true,true,true};
    private FrameLayout root;
    private LinearLayout controls;
    private TextView help;
    private boolean controlsVisible=false;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable hideControls=()->setControlsVisible(false);
    private int count=4, audible=0, full=-1;
    private int audioOwner=-1,audioDraining=-1,audioEpoch=0;
    private boolean started=false;
    private final DecoderCapacity decoderCapacity=new DecoderCapacity();
    private final DecoderAdmission decoderAdmission=new DecoderAdmission();
    private final String[] suspended=new String[4];
    private String memoryState="Normal";
    private final RetryBudget[] retryBudgets={new RetryBudget(),new RetryBudget(),new RetryBudget(),new RetryBudget()};
    private final Runnable[] retries=new Runnable[4];
    private final boolean[] knownLive=new boolean[4];
    private final String[] decoderNames=new String[4], videoMimes=new String[4];
    private final float[] sourceFrameRates=new float[4];
    private final boolean[] hasPlayed=new boolean[4];
    private final boolean[] terminalFailure=new boolean[4];
    private final long[] bufferingSince=new long[4];
    private final VideoBudget.Health videoHealth=new VideoBudget.Health();
    private DefaultBandwidthMeter sharedMeter;
    private final BandwidthBudget bandwidthBudget=new BandwidthBudget(2000000);
    private final BandwidthMeter.EventListener bandwidthListener=(elapsed,bytes,estimate)->{
        if(!started)return;
        if(elapsed==0&&bytes==0)bandwidthBudget.reset(estimate); // Network-type estimate reset, not a measured sample.
        else bandwidthBudget.observe(estimate,android.os.SystemClock.elapsedRealtime());
    };
    private long lastDiagnosticSample;
    private final Runnable qualityTick=new Runnable(){public void run(){
        if(!started)return;
        long now=android.os.SystemClock.elapsedRealtime();boolean healthy=true,congested=false;int active=0;
        for(int i=0;i<4;i++)if(players[i]!=null&&!terminalFailure[i]){
            active++;healthy&=players[i].isPlaying();retryBudgets[i].sample(now,players[i].isPlaying());
            if(hasPlayed[i]&&players[i].getPlayWhenReady()&&players[i].getPlaybackState()==Player.STATE_BUFFERING){
                if(bufferingSince[i]==0)bufferingSince[i]=now;
                // Network stalls reduce the bitrate budget, not the decoder's resolution allowance.
                if(now-bufferingSince[i]>=1500)congested=true;
            }else bufferingSince[i]=0;
        }
        videoHealth.sample(now,active>0&&healthy);
        bandwidthBudget.sample(now,active>0&&healthy,congested);
        refreshQuality();if(now-lastDiagnosticSample>=30000){lastDiagnosticSample=now;recordPlayback();}ui.postDelayed(this,1000);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        sharedMeter=DefaultBandwidthMeter.getSingletonInstance(this);
        load();
        // A recreated Activity restores current choices, not the original launch links.
        if(state==null)acceptHomeSelection(getIntent().getStringExtra("selectedFeeds"));
        buildUi();
    }
    void acceptHomeSelection(String json){
        if(json==null)return;
        try{
            JSONArray entries=new JSONArray(json);if(entries.length()<1||entries.length()>4)return;
            FeedParser.Feed[] chosen=new FeedParser.Feed[4];for(int i=0;i<entries.length();i++)chosen[i]=feed(entries.getJSONObject(i));
            System.arraycopy(chosen,0,games,0,4);Arrays.fill(resumePlayback,true);count=entries.length()<=2?entries.length():4;full=-1;audible=0;save();
        }catch(JSONException ignored){}
    }
    int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    TextView text(String s,int size,int color) {return Ui.text(this,s,size,color);}
    GradientDrawable shape(int fill,int stroke) {return Ui.surface(this,fill,stroke);}
    Button button(String title,Runnable action) {return Ui.button(this,title,action);}
    boolean hasGames() {
        for(int i=0;i<count;i++)if(games[i]!=null)return true;
        return false;
    }
    void setControlsVisible(boolean visible) {
        controlsVisible=visible||!hasGames();
        controls.setVisibility(controlsVisible?View.VISIBLE:View.GONE);
        help.setVisibility(controlsVisible?View.VISIBLE:View.GONE);
        for(int i=0;i<4;i++) {
            if(labels[i]!=null)labels[i].setVisibility(showLabel(i)?View.VISIBLE:View.GONE);
            View tile=slots[i];
            if(tile!=null)tile.setForeground(controlsVisible&&tile.hasFocus()?shape(Color.TRANSPARENT,FOCUS):null);
        }
        ui.removeCallbacks(hideControls);
        if(controlsVisible&&hasGames())ui.postDelayed(hideControls,5000);
        if(!controlsVisible) {
            View tile=slots[full>=0?full:audible];
            if(tile!=null)tile.requestFocus();
        }
    }
    void showMenu() {
        setControlsVisible(true);
        controls.findViewWithTag("sourcesButton").requestFocus();
    }
    void buildUi() {
        root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        wall=new PlaybackWall(this);wall.setTag("playbackWall");
        root.addView(wall,new FrameLayout.LayoutParams(-1,-1));
        for(int i=0;i<4;i++)wall.addView(createSlot(i));
        wall.showLayout(count,full);
        // Controls float over playback; they never reserve space or resize video.
        controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(32),dp(12),dp(32),dp(12));controls.setBackgroundColor(Ui.OVERLAY);
        LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(dp(44),dp(44));markParams.rightMargin=dp(10);controls.addView(Ui.brandMark(this),markParams);
        TextView brand=text(getString(R.string.app_name),20,WHITE);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        controls.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        Button sourcesButton=button("Sources",this::sources);sourcesButton.setTag("sourcesButton");controls.addView(sourcesButton,new LinearLayout.LayoutParams(dp(106),dp(44)));
        for(int n:new int[]{1,2,4}) {
            Button b=button(n+(n==1?" game":" games"),()->setCount(n));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(100),dp(44));lp.leftMargin=dp(8);controls.addView(b,lp);
        }
        Button done=button("Done",()->setControlsVisible(false));LinearLayout.LayoutParams doneParams=new LinearLayout.LayoutParams(dp(85),dp(44));doneParams.leftMargin=dp(8);controls.addView(done,doneParams);
        LinearLayout.LayoutParams gamesParams=new LinearLayout.LayoutParams(dp(90),dp(44));gamesParams.leftMargin=dp(8);controls.addView(button("Games",()->{startActivity(new android.content.Intent(this,HomeActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}),gamesParams);
        root.addView(controls,new FrameLayout.LayoutParams(-1,dp(68),Gravity.TOP));
        help=text("Back  Controls     /     OK  Switch audio     /     Hold OK  Game options",13,MUTED);
        help.setGravity(Gravity.CENTER);help.setBackgroundColor(Ui.OVERLAY);
        root.addView(help,new FrameLayout.LayoutParams(-1,dp(36),Gravity.BOTTOM));
        setContentView(root);
        setControlsVisible(false);
        View tile=slots[full>=0?full:audible];
        if(tile!=null)tile.requestFocus();
    }
    View createSlot(int i) {
        FrameLayout frame=new FrameLayout(this);slots[i]=frame;frame.setTag("slot"+i);frame.setFocusable(true);frame.setBackgroundColor(Color.BLACK);
        frame.setOnFocusChangeListener((v,f)->v.setForeground(f&&controlsVisible?shape(Color.TRANSPARENT,FOCUS):null));
        {
            PlayerView pv=new PlayerView(this);surfaces[i]=pv;pv.setUseController(false);pv.setFocusable(false);pv.setFocusableInTouchMode(false);pv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);pv.setBackgroundColor(Color.BLACK);pv.setShutterBackgroundColor(Color.BLACK);
            pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
            ProgressBar spinner=pv.findViewById(androidx.media3.ui.R.id.exo_buffering);
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(WHITE));
            frame.addView(pv,new FrameLayout.LayoutParams(-1,-1));
            TextView label=text("",14,WHITE);label.setPadding(dp(12),dp(8),dp(12),dp(8));label.setBackgroundColor(Ui.OVERLAY);labels[i]=label;updateLabel(i);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(44),Gravity.BOTTOM);frame.addView(label,lp);
        }
        {
            LinearLayout empty=new LinearLayout(this);emptySlots[i]=empty;empty.setBackgroundColor(Color.BLACK);empty.setGravity(Gravity.CENTER);empty.setOrientation(LinearLayout.VERTICAL);empty.setPadding(dp(18),0,dp(18),0);
            TextView number=text(String.format(Locale.US,"%02d",i+1),24,MUTED);number.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));empty.addView(number);
            TextView title=text("Choose a game",22,WHITE);title.setPadding(0,dp(8),0,dp(6));empty.addView(title);
            emptyHints[i]=text("",13,MUTED);empty.addView(emptyHints[i]);
            for(int n=0;n<empty.getChildCount();n++)((TextView)empty.getChildAt(n)).setGravity(Gravity.CENTER);
            frame.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        }
        refreshSlot(i);
        frame.setOnClickListener(v->{if(games[i]==null)pick(i);else if(suspended[i]!=null)options(i);else{selectAudio(i);setControlsVisible(true);}});
        frame.setOnLongClickListener(v->{options(i);return true;});return frame;
    }
    void refreshSlot(int i) {
        if(surfaces[i].getPlayer()!=players[i])surfaces[i].setPlayer(players[i]);
        emptySlots[i].setVisibility(games[i]==null||suspended[i]!=null?View.VISIBLE:View.GONE);
        ((TextView)emptySlots[i].getChildAt(1)).setText(suspended[i]!=null?"Playback paused":"Choose a game");
        emptyHints[i].setText(suspended[i]!=null?suspended[i]:(catalog.isEmpty()?"Add streams from Sources":"Press OK to choose a stream"));
        slots[i].setContentDescription("Game slot "+(i+1)+(games[i]==null?", empty":", "+games[i].title));
        if(games[i]==null)labels[i].setVisibility(View.GONE);else updateLabel(i);
    }
    void refreshCatalog() {
        // Catalog updates affect placeholders only, never playback bindings or layout.
        for(int i=0;i<4;i++)emptyHints[i].setText(suspended[i]!=null?suspended[i]:(catalog.isEmpty()?"Add streams from Sources":"Press OK to choose a stream"));
    }
    void addCatalogFeed(FeedParser.Feed feed) {catalog.add(feed);save();refreshCatalog();}
    void removeCatalogFeed(int index) {catalog.remove(index);save();refreshCatalog();}
    void updateLayout() {
        wall.showLayout(count,full);
        setControlsVisible(false);
        int target=full>=0?full:audible;
        slots[target].setFocusable(true);
        slots[target].requestFocus();
    }
    void setCount(int n) {
        if(n!=1&&n!=2&&n!=4)throw new IllegalArgumentException("Expected 1, 2, or 4 games");
        if(count==n&&full<0){setControlsVisible(false);return;}
        Diagnostics.log("layout_change","count="+n);full=-1; count=n;if(audible>=count)audible=0;
        startPlayers();save();updateLayout();
    }
    void expandGame(int slot) {
        if(slot < -1 || slot >= count || (slot >= 0 && games[slot]==null))throw new IllegalArgumentException("Choose an active game");
        if(full==slot)return;
        Diagnostics.log("expand_change","slot="+slot);full=slot;
        if(slot>=0){audible=slot;suspended[slot]=null;}
        startPlayers();save();updateLayout();
    }
    private boolean showLabel(int slot){
        String state=states[slot];
        return games[slot]!=null&&(controlsVisible||(state!=null&&
            (state.startsWith("Error")||state.startsWith("Paused")||state.startsWith("Recovering"))));
    }
    void updateLabel(int i) {
        if(labels[i]!=null&&games[i]!=null){Ui.icon(labels[i],i==audible?R.drawable.ic_volume:R.drawable.ic_muted);labels[i].setText((i==audible?"Audio   ":"")+games[i].title+"   ·   "+(states[i]==null?"Connecting…":states[i])+actualVideo(i));labels[i].setContentDescription((i==audible?"Selected for audio. ":"Muted. ")+labels[i].getText());labels[i].setVisibility(showLabel(i)?View.VISIBLE:View.GONE);}
    }
    void selectAudio(int slot){if(!eligible(slot))return;audible=slot;audio();save();}
    void audio() {
        if(audioOwner>=0&&(audioOwner!=audible||!eligible(audioOwner))){
            final int oldSlot=audioOwner;final ExoPlayer old=players[oldSlot];
            audioOwner=-1;audioDraining=oldSlot;final int epoch=++audioEpoch;
            configureAudio();
            if(old!=null){
                // In pinned Media3, selector invalidation is queued before this player message.
                old.createMessage((type,payload)->ui.post(()->{
                    if(epoch!=audioEpoch||audioDraining!=oldSlot)return;
                    decoderAdmission.releaseAudio(oldSlot);audioDraining=-1;audio();
                })).send();
                return;
            }
            decoderAdmission.releaseAudio(oldSlot);audioDraining=-1;
        }
        if(audioDraining<0&&audioOwner<0&&eligible(audible)&&players[audible]!=null)audioOwner=audible;
        configureAudio();
    }
    void configureAudio(){
        VideoBudget.Profile profile=videoProfile();
        // Disable and silence nonowners before enabling the sole owner.
        for(int i=0;i<4;i++)if(players[i]!=null&&i!=audioOwner){
            players[i].setVolume(0);
            players[i].setAudioAttributes(AUDIO_ATTRIBUTES,false);
            applyVideoQuality(players[i],profile);updateLabel(i);
        }
        if(audioOwner>=0&&players[audioOwner]!=null){
            ExoPlayer owner=players[audioOwner];applyVideoQuality(owner,profile);
            owner.setAudioAttributes(AUDIO_ATTRIBUTES,true);
            owner.setVolume(1);updateLabel(audioOwner);
        }
    }
    int audioDecoderCount(){return decoderAdmission.audioCount();}
    void startPlayers() {
        if(!started)return;
        // Release hidden decoders and loaders before granting the visible game more quality.
        for(int i=0;i<4;i++)if(!eligible(i))release(i);
        updateBandwidthParticipants(); // Reserve every soon-to-start feed before any prepares.
        // Restore the grid ceiling on retained players before allocating missing decoders.
        refreshQuality();
        for(int i=0;i<count;i++) {
            if(!eligible(i))continue;
            if(players[i]==null) {
                metrics[i]=new PlaybackMetrics(android.os.SystemClock.elapsedRealtime());metricIds[i]=i+"-"+(++nextMetricId);metricSources[i]=Diagnostics.sourceId(games[i].url);
                Diagnostics.log("source_start","slot="+i+" player="+metricIds[i]+" sourceId="+metricSources[i]);
                final int slot=i;ExoPlayer player=new ExoPlayer.Builder(this,new GuardedRenderersFactory(this,i,decoderAdmission,decoderCapacity))
                    .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        new androidx.media3.datasource.DefaultDataSource.Factory(this,new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setContentTypePredicate(PlaybackRecovery::acceptsContentType))).setLoadErrorHandlingPolicy(new StreamLoadErrorPolicy()))
                    .setBandwidthMeter(sharedMeter).setTrackSelector(new DefaultTrackSelector(this,new SportsTrackSelection.Factory(bandwidthBudget,i))).build();players[i]=player;states[i]="Connecting…";
                player.addListener(new Player.Listener(){
                    @Override public void onIsPlayingChanged(boolean playing){if(players[slot]==player)sampleMetrics(slot);}
                    @Override public void onRenderedFirstFrame(){if(players[slot]==player&&metrics[slot]!=null&&metrics[slot].firstFrame(android.os.SystemClock.elapsedRealtime()))Diagnostics.log("first_frame","player="+metricIds[slot]+" startupActiveMs="+metrics[slot].startupActiveMs);}
                    @Override public void onPlaybackStateChanged(int s) {if(players[slot]!=player)return;sampleMetrics(slot);Diagnostics.log("playback_state","slot="+slot+" state="+s+" play="+player.getPlayWhenReady()+" bufferMs="+player.getTotalBufferedDuration());if(s==Player.STATE_IDLE&&player.getPlayerError()!=null)return;if(s==Player.STATE_READY)hasPlayed[slot]=true;updatePlaybackState(slot,player);}
                    @Override public void onPlayWhenReadyChanged(boolean play,int reason){
                        if(players[slot]!=player)return;
                        sampleMetrics(slot);Diagnostics.log("play_intent","slot="+slot+" play="+play+" reason="+reason);
                        if(resumePlayback[slot]!=play){resumePlayback[slot]=play;save();}
                        updateKeepScreenOn();
                        if(!play&&recoveryPending(slot)){cancelRecovery(slot);endAutomaticRecovery(slot);states[slot]="Error · recovery paused; hold OK to retry";updateLabel(slot);return;}
                        updatePlaybackState(slot,player);
                    }
                    @Override public void onTimelineChanged(Timeline timeline,int reason){if(players[slot]==player)knownLive[slot]|=player.isCurrentMediaItemLive();}
                    @Override public void onTracksChanged(Tracks tracks){if(players[slot]==player){sourceFrameRates[slot]=sourceFrameRate(tracks);refreshQuality();}}
                    @Override public void onPlayerError(PlaybackException e) {if(players[slot]!=player)return;metrics[slot].error(android.os.SystemClock.elapsedRealtime());sampleMetrics(slot);Diagnostics.log("playback_error","slot="+slot+" code="+e.errorCode+" "+Diagnostics.error(e));
                        if(e.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED||e.errorCode==PlaybackException.ERROR_CODE_DECODING_FAILED
                            ||e.errorCode==PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES||e.errorCode==PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED){
                            ui.post(()->{if(players[slot]==player)pauseForDecoder(slot,decoderErrorReason(e));});return;
                        }
                        handlePlaybackError(slot,player,e);}
                });
                player.addAnalyticsListener(new AnalyticsListener(){
                    @Override public void onLoadError(EventTime event,androidx.media3.exoplayer.source.LoadEventInfo load,androidx.media3.exoplayer.source.MediaLoadData data,java.io.IOException error,boolean cancelled){
                        if(players[slot]==player)Diagnostics.log("load_error","slot="+slot+" type="+data.dataType+" durationMs="+load.loadDurationMs+" bytes="+load.bytesLoaded+" cancelled="+cancelled+" "+Diagnostics.error(error));
                    }
                    @Override public void onVideoDecoderInitialized(EventTime event,String name,long time,long duration){
                        if(players[slot]!=player)return;decoderNames[slot]=name;Diagnostics.log("decoder","slot="+slot+" name="+name+" initMs="+duration);refreshQuality();
                    }
                    @Override public void onVideoInputFormatChanged(EventTime event,Format format,DecoderReuseEvaluation reuse){
                        if(players[slot]!=player)return;videoMimes[slot]=format.sampleMimeType;metrics[slot].format(format.width+"x"+format.height+"/"+format.frameRate+"/"+format.bitrate);Diagnostics.log("video_format","slot="+slot+" mime="+format.sampleMimeType+" size="+format.width+"x"+format.height+" fps="+format.frameRate+" bitrate="+format.bitrate);
                        DecoderAdmission.Request old=decoderAdmission.video(slot);
                        if(old!=null){String denied=decoderAdmission.reserve(slot,new DecoderAdmission.Request(old.name,true,old.hardware,old.instances,
                            GuardedRenderersFactory.workload(format),old.budget));
                            if(denied!=null)ui.post(()->{if(players[slot]==player)pauseForDecoder(slot,denied);});
                        }
                        refreshQuality();updateLabel(slot);
                    }
                    @Override public void onDroppedVideoFrames(EventTime event,int dropped,long elapsed){
                        if(players[slot]!=player||!player.isPlaying()||elapsed<500)return;
                        Format format=player.getVideoFormat();float fps=format!=null&&format.frameRate>0?format.frameRate:60;
                        long now=android.os.SystemClock.elapsedRealtime();
                        if(dropped>=10&&dropped>=fps*elapsed/1000*0.03){videoHealth.bad(now);refreshQuality();}
                        if(decoderAdmission.dropped(slot,now,elapsed,dropped,fps))ui.post(()->{if(players[slot]==player)shedDecoderLoad("Sustained dropped frames",now);});
                    }
                });
                applyVideoQuality(player);
                refreshSlot(i);
                player.setVolume(0);
                player.setPlayWhenReady(resumePlayback[i]);player.setMediaItem(MediaItem.fromUri(games[i].url));player.prepare();
            }
        }
        if(!eligible(audible))for(int i=0;i<count;i++)if(eligible(i)){audible=i;break;}
        audio();
        for(int i=0;i<4;i++)refreshSlot(i);
        updateKeepScreenOn();
    }
    void updateKeepScreenOn(){
        boolean keep=false;for(int i=0;i<4;i++)if(players[i]!=null&&!terminalFailure[i])
            keep|=players[i].getPlayWhenReady()&&players[i].getPlaybackState()!=Player.STATE_ENDED;
        int flag=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if(keep!=((getWindow().getAttributes().flags&flag)!=0)){
            if(keep)getWindow().addFlags(flag);else getWindow().clearFlags(flag);
        }
    }
    void updatePlaybackState(int slot,ExoPlayer player){
        updateKeepScreenOn();
        // Keep actionable failure/retry messages until recovery actually proceeds.
        if(player.getPlayerError()!=null||terminalFailure[slot]||recoveryPending(slot))return;
        int state=player.getPlaybackState();
        states[slot]=state==Player.STATE_ENDED?"Ended":!player.getPlayWhenReady()?"Paused":
            state==Player.STATE_READY?"Playing":state==Player.STATE_BUFFERING?"Buffering…":"Connecting…";
        updateLabel(slot);
    }
    void cancelRecovery(int slot){if(retries[slot]!=null)ui.removeCallbacks(retries[slot]);retries[slot]=null;}
    void handlePlaybackError(int slot,ExoPlayer player,PlaybackException error){
        if(!started||players[slot]!=player)return;
        cancelRecovery(slot);
        PlaybackRecovery.Kind kind=PlaybackRecovery.classify(error);
        if(kind==PlaybackRecovery.Kind.LIVE_WINDOW)knownLive[slot]=true;
        if(kind==PlaybackRecovery.Kind.AUTHORIZATION){endAutomaticRecovery(slot);states[slot]="Error · feed authorization expired or denied; replace source";updateLabel(slot);return;}
        if(kind==PlaybackRecovery.Kind.INVALID_SOURCE){endAutomaticRecovery(slot);states[slot]="Source returned a web page or API response · hold OK to change source";updateLabel(slot);return;}
        if(kind==PlaybackRecovery.Kind.PERMANENT){endAutomaticRecovery(slot);states[slot]="Error · "+error.getErrorCodeName()+" · hold OK for options";updateLabel(slot);return;}
        if(!player.getPlayWhenReady()){endAutomaticRecovery(slot);states[slot]="Error · playback paused; use Retry or Go live";updateLabel(slot);return;}
        long serverDelay=StreamLoadErrorPolicy.serverDelay(error,System.currentTimeMillis());
        if(serverDelay>RetryAfter.MAX_AUTOMATIC_WAIT_MS){endAutomaticRecovery(slot);states[slot]="Error · source requests a long wait; try again later";updateLabel(slot);return;}
        long delay=retryBudgets[slot].nextDelay(slot,Math.random());
        if(delay<0){endAutomaticRecovery(slot);states[slot]="Error · automatic recovery stopped after 4 attempts; hold OK to retry";updateLabel(slot);return;}
        delay=Math.max(delay,serverDelay);Diagnostics.log("retry_scheduled","slot="+slot+" attempt="+retryBudgets[slot].attempts+" delayMs="+delay);
        states[slot]="Recovering · attempt "+retryBudgets[slot].attempts+" / 4 · retry in "+((delay+999)/1000)+"s";updateLabel(slot);
        Runnable task=new Runnable(){public void run(){
            if(retries[slot]!=this)return;retries[slot]=null;
            if(!started||players[slot]!=player||!eligible(slot)||!player.getPlayWhenReady())return;
            Diagnostics.log("retry_started","slot="+slot+" attempt="+retryBudgets[slot].attempts);
            if(knownLive[slot]||player.isCurrentMediaItemLive())player.seekToDefaultPosition();
            player.prepare();
        }};
        retries[slot]=task;ui.postDelayed(task,delay);
    }
    boolean recoveryPending(int slot){return retries[slot]!=null;}
    private int activeGameMask(){
        int mask=0;for(int i=0;i<count;i++)if(eligible(i)&&!terminalFailure[i])mask|=1<<i;
        return mask;
    }
    void updateBandwidthParticipants(){bandwidthBudget.setActiveMask(activeGameMask());}
    void endAutomaticRecovery(int slot){
        Diagnostics.log("recovery_stopped","slot="+slot+" attempts="+retryBudgets[slot].attempts);
        sampleMetrics(slot);terminalFailure[slot]=true;sampleMetrics(slot);bufferingSince[slot]=0;updateBandwidthParticipants();refreshQuality();updateKeepScreenOn();
    }
    void retryPlayback(int slot){
        if(!started||games[slot]==null)return;Diagnostics.log("manual_retry","slot="+slot);
        cancelRecovery(slot);retryBudgets[slot].reset();
        terminalFailure[slot]=false;updateBandwidthParticipants();refreshQuality();
        if(players[slot]==null){suspended[slot]=null;resumePlayback[slot]=true;startPlayers();save();return;}
        ExoPlayer player=players[slot];
        boolean live=knownLive[slot]||player.isCurrentMediaItemLive();
        player.stop();if(live)player.seekToDefaultPosition();player.prepare();player.play();
    }
    void goLive(int slot){
        ExoPlayer player=players[slot];
        if(player==null||(!knownLive[slot]&&!player.isCurrentMediaItemLive())){message("Go live is available once a live feed is identified.");return;}
        cancelRecovery(slot);retryBudgets[slot].reset();terminalFailure[slot]=false;updateBandwidthParticipants();refreshQuality();player.seekToDefaultPosition();player.prepare();player.play();
    }
    boolean eligible(int i){return i<count&&games[i]!=null&&(full<0||full==i)&&suspended[i]==null;}
    String decoderErrorReason(PlaybackException error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof GuardedRenderersFactory.AdmissionException)return cause.getMessage();
        return "Decoder could not start or continue ("+error.getErrorCodeName()+")";
    }
    void pauseForDecoder(int slot,String reason){
        Diagnostics.log("decoder_suspended","slot="+slot);
        suspended[slot]="Paused · "+reason+" · hold OK to retry";
        release(slot);
        // No automatic retry: a failed source cannot create an allocation/retry loop.
        startPlayers();setControlsVisible(true);
    }
    void shedDecoderLoad(String reason,long now){
        if(activeGameCount()<2||!decoderAdmission.mayShed(now))return;
        int victim=-1;
        for(int i=3;i>=0;i--)if(players[i]!=null&&i!=audible){victim=i;break;}
        if(victim<0)return;
        decoderAdmission.restrict(activeGameCount()-1);
        pauseForDecoder(victim,reason+"; reduced games to keep playback smooth");
    }
    int admittedDecoderCount(){return decoderAdmission.videoCount();}
    String suspensionReason(int slot){return suspended[slot];}
    int activeGameCount(){return Integer.bitCount(activeGameMask());}
    VideoBudget.Profile videoProfile() {
        android.view.Display display=getWindowManager().getDefaultDisplay();
        android.view.Display.Mode mode=display.getMode();
        long budget=Long.MAX_VALUE;int instances=Integer.MAX_VALUE;float sourceRate=0;
        int active=activeGameMask();
        for(int i=0;i<count;i++)if((active&(1<<i))!=0){
            sourceRate=Math.max(sourceRate,sourceFrameRates[i]>0?sourceFrameRates[i]:60);
            DecoderCapacity.Capacity capacity=decoderCapacity.get(decoderNames[i],videoMimes[i]);
            budget=Math.min(budget,capacity.pixelsPerSecond);instances=Math.min(instances,capacity.instances);
        }
        if(budget==Long.MAX_VALUE)budget=1280L*720*60;
        return VideoBudget.choose(mode.getPhysicalWidth(),mode.getPhysicalHeight(),count,full>=0,
            Integer.bitCount(active),Math.min(mode.getRefreshRate(),sourceRate>0?sourceRate:60),budget,instances,videoHealth.penalty);
    }
    static float sourceFrameRate(Tracks tracks){
        boolean unknown=false;float rate=0;
        for(Tracks.Group group:tracks.getGroups())if(group.getType()==C.TRACK_TYPE_VIDEO)
            for(int track=0;track<group.length;track++)if(group.isTrackSupported(track)){
                float fps=group.getTrackFormat(track).frameRate;
                if(fps>0)rate=Math.max(rate,fps);else unknown=true;
            }
        return unknown||rate==0?60:rate;
    }
    void refreshQuality(){VideoBudget.Profile profile=videoProfile();for(ExoPlayer player:players)if(player!=null)applyVideoQuality(player,profile);}
    void applyVideoQuality(ExoPlayer player) {
        applyVideoQuality(player,videoProfile());
    }
    void applyVideoQuality(ExoPlayer player,VideoBudget.Profile profile) {
        int slot=0;while(slot<4&&players[slot]!=player)slot++;
        if(slot==4)return;
        TrackSelectionParameters current=player.getTrackSelectionParameters();
        int bitrate=(int)Math.min(Integer.MAX_VALUE,Math.max(0,bandwidthBudget.share(slot)-audioReservation(player)));
        boolean muted=slot!=audioOwner;
        if(current.maxVideoWidth==profile.width&&current.maxVideoHeight==profile.height&&current.maxVideoFrameRate==profile.fps
            &&current.viewportWidth==profile.width&&current.viewportHeight==profile.height&&!current.viewportOrientationMayChange
            &&current.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)==muted&&current.maxVideoBitrate==bitrate)return;
        TrackSelectionParameters policy=current.buildUpon()
            .setMaxVideoSize(profile.width,profile.height).setMaxVideoFrameRate(profile.fps)
            .setViewportSize(profile.width,profile.height,false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO,muted).setMaxVideoBitrate(bitrate).build();
        player.setTrackSelectionParameters(policy);
    }
    long audioReservation(ExoPlayer player){
        if(audioOwner<0||players[audioOwner]!=player)return 0;
        Tracks tracks=player.getCurrentTracks();
        if(tracks.isEmpty())return 256000;
        long reserve=0;
        for(Tracks.Group group:tracks.getGroups())if(group.getType()==C.TRACK_TYPE_AUDIO&&group.isSelected())
            for(int t=0;t<group.length;t++)if(group.isTrackSupported(t)){
                int bitrate=group.getTrackFormat(t).bitrate;reserve=Math.max(reserve,bitrate>0?bitrate:256000);
            }
        return reserve;
    }
    long bandwidthShare(int slot){return bandwidthBudget.share(slot);}
    String bandwidthDetails(int slot){
        long declared=0;boolean unknown=false;
        for(ExoPlayer p:players)if(p!=null){
            Format video=p.getVideoFormat(),audio=p.getAudioFormat();
            if(video==null||video.bitrate<=0)unknown=true;else declared+=video.bitrate;
            if(audio!=null){if(audio.bitrate<=0)unknown=true;else declared+=audio.bitrate;}
        }
        return String.format(Locale.US,"\nNetwork estimate: %.2f Mbps\nTotal media budget: %.2f Mbps\nThis game's share (includes audio): %.2f Mbps\nSelected declared media: %.2f Mbps%s",
            bandwidthBudget.estimate()/1000000.0,bandwidthBudget.total()/1000000.0,bandwidthBudget.share(slot)/1000000.0,
            declared/1000000.0,unknown?" + unknown rates":"")
            +(declared>bandwidthBudget.total()?"\nSelected feeds exceed budget; lower renditions may be unavailable or a switch may be pending.":"");
    }
    String decoderDetails(int slot){
        DecoderAdmission.Request request=decoderAdmission.video(slot);
        Format format=players[slot]==null?null:players[slot].getVideoFormat();
        return "\nReserved video decoders: "+decoderAdmission.videoCount()+" / session limit "+decoderAdmission.limit()
            +(request==null?"":"\n"+(request.hardware?"Hardware":"Software")+" decoder; advertised instances: "+request.instances)
            +(format==null||format.codecs==null?"":"\nCodec profile: "+format.codecs)
            +"\nAudio decoders reserved: "+decoderAdmission.audioCount()+" (maximum 1)"+"\nMemory: "+memoryState+(suspended[slot]==null?"":"\n"+suspended[slot]);
    }
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);Diagnostics.log("memory_pressure","level="+level);
        if(!started)return;
        if(level==TRIM_MEMORY_RUNNING_LOW||level==TRIM_MEMORY_RUNNING_CRITICAL){
            memoryState=level==TRIM_MEMORY_RUNNING_CRITICAL?"Critical pressure":"Low memory";
            long now=android.os.SystemClock.elapsedRealtime();videoHealth.bad(now);refreshQuality();
            if(level==TRIM_MEMORY_RUNNING_CRITICAL)shedDecoderLoad("Critical TV memory pressure",now);
        }
    }
    @Override public void onLowMemory(){super.onLowMemory();if(started){memoryState="Critical pressure";shedDecoderLoad("Critical TV memory pressure",android.os.SystemClock.elapsedRealtime());}}
    String actualVideo(int i){
        Format f=players[i]==null?null:players[i].getVideoFormat();
        if(f==null)return "";
        return " · "+(f.height>0?f.height+"p":"Video")+(f.frameRate>0?String.format(Locale.US," %.2f fps",f.frameRate):" fps unknown")
            +(f.bitrate>0?String.format(Locale.US," %.1f Mbps",f.bitrate/1000000.0):"");
    }
    void release(int i) {
        terminalFailure[i]=false;
        if(audioOwner==i){audioOwner=-1;audioEpoch++;}
        if(audioDraining==i){audioDraining=-1;audioEpoch++;}
        cancelRecovery(i);retryBudgets[i].reset();knownLive[i]=false;sourceFrameRates[i]=0;
        if(players[i]!=null){
            recordMetrics(i,true);
            surfaces[i].setPlayer(null);
            ExoPlayer old=players[i];players[i]=null;metrics[i]=null;
            old.release();
        }
        decoderAdmission.release(i);
        states[i]=suspended[i];decoderNames[i]=null;videoMimes[i]=null;
        hasPlayed[i]=false;bufferingSince[i]=0;updateLabel(i);
    }
    void assign(int i,FeedParser.Feed feed) {release(i);games[i]=feed;resumePlayback[i]=true;suspended[i]=null;if(feed==null&&full==i)full=-1;startPlayers();save();updateLayout();View tile=slots[i];if(tile!=null)tile.requestFocus();}
    void pick(int i) {
        if(catalog.isEmpty()){sources();return;}
        String[] titles=new String[catalog.size()];for(int n=0;n<titles.length;n++)titles[n]=catalog.get(n).title;
        new Ui.DialogBuilder(this).setTitle("Choose stream · slot "+(i+1)).setItems(titles,(d,n)->assign(i,catalog.get(n))).setNegativeButton("Cancel",null).show();
    }
    void options(int i) {
        if(games[i]==null){pick(i);return;}
        ArrayList<CharSequence> actions=new ArrayList<>(Arrays.asList(Ui.iconLabel(this,full<0?"Full screen":"Return to multiview",full<0?R.drawable.ic_expand:R.drawable.ic_collapse),Ui.iconLabel(this,"Listen to this game",R.drawable.ic_volume),"Replace game","Retry playback","Remove from view","Playback details","Go live"));
        if(!games[i].eventId.isEmpty())actions.add("Choose source");
        new Ui.DialogBuilder(this).setTitle(games[i].title).setItems(actions.toArray(new CharSequence[0]),(d,n)->{
            if(n==0)expandGame(full<0?i:-1);
            if(n==1)selectAudio(i);
            if(n==2)pick(i);
            if(n==3)retryPlayback(i);
            if(n==6)goLive(i);
            if(n==7){
                FeedParser.Feed game=games[i];
                GameSources.show(this,game.eventId,game.title,game.url,source->{if(games[i]==game)switchSource(i,source);});
            }
            if(n==4){if(full==i)full=-1;assign(i,null);}
            if(n==5){VideoBudget.Profile profile=videoProfile();
                message(games[i].title+actualVideo(i)+"\nQuality allowance: "+profile.width+" × "+profile.height+" at up to "+profile.fps+" fps"
                    +"\nActive games: "+activeGameCount()+"\nDecoder: "+(decoderNames[i]==null?"Not initialized":decoderNames[i])
                    +"\nQuality fallback level: "+videoHealth.penalty+bandwidthDetails(i)+decoderDetails(i)+bufferDetails(i));
            }
        }).show();
    }
    String bufferDetails(int slot){
        ExoPlayer player=players[slot];if(player==null)return "";
        long offset=player.getCurrentLiveOffset();
        return String.format(Locale.US,"\nBuffered ahead: %.1f s",Math.max(0,player.getTotalBufferedDuration())/1000.0)
            +(offset==C.TIME_UNSET?"\nLive offset: unavailable":String.format(Locale.US,"\nLive offset: %.1f s",offset/1000.0));
    }
    void sources() {
        new Ui.DialogBuilder(this).setTitle("Stream sources").setItems(new String[]{"Add stream","Import NFL playlist","Manage saved streams ("+catalog.size()+")"},(d,n)->{
            if(n==0)addStream();if(n==1)importCatalog();if(n==2)manage();
        }).setNegativeButton("Close",null).show();
    }
    LinearLayout form() {LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),dp(8));return box;}
    EditText input(LinearLayout box,String hint) {EditText e=Ui.input(this,hint);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(56));p.bottomMargin=dp(12);box.addView(e,p);return e;}
    void addStream() {
        LinearLayout box=form();EditText name=input(box,"Game name, e.g. Chiefs at Bills");EditText url=input(box,"https://… stream.m3u8 or manifest.mpd");url.setInputType(17);
        AlertDialog dialog=new Ui.DialogBuilder(this).setTitle("Add stream").setView(box).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->{Ui.styleDialog(dialog);dialog.getButton(-1).setOnClickListener(v->{String t=name.getText().toString().trim(),u=url.getText().toString().trim();if(t.isEmpty()){name.setError("Enter a game name");return;}if(!FeedParser.validUrl(u)){url.setError("Enter a valid HTTP or HTTPS URL");return;}addCatalogFeed(new FeedParser.Feed(t,u));dialog.dismiss();message("Stream saved. Choose an empty slot to watch.");});});dialog.show();
    }
    void importCatalog() {
        LinearLayout box=form();EditText url=input(box,"https://… channels.m3u");url.setInputType(17);box.addView(text("Imports entries mentioning NFL or an NFL team.\nA channel catalog is different from a single HLS stream.",14,MUTED));
        AlertDialog dialog=new Ui.DialogBuilder(this).setTitle("Import NFL playlist").setView(box).setPositiveButton("Import",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->{Ui.styleDialog(dialog);dialog.getButton(-1).setOnClickListener(v->{String u=url.getText().toString().trim();if(!FeedParser.validUrl(u)){url.setError("Enter a valid HTTP or HTTPS URL");return;}dialog.dismiss();Toast.makeText(this,"Loading catalog…",Toast.LENGTH_SHORT).show();
            startCatalogImport(u);
        });});dialog.show();
    }
    void cancelCatalogImport(){catalogGeneration++;if(catalogRequest!=null)catalogRequest.cancel(true);catalogRequest=null;}
    void startCatalogImport(String url){
        if(!started)return;
        cancelCatalogImport();final int generation=catalogGeneration;
        catalogRequest=network.submit(()->{
            try{
                CatalogHttp.Result data=CatalogHttp.fetch(url,2*1024*1024,getString(R.string.app_name)+"/"+BuildConfig.VERSION_NAME);
                List<FeedParser.Feed> found=FeedParser.parseM3u(data.body,data.url);
                ui.post(()->{
                    if(!started||generation!=catalogGeneration||isDestroyed())return;catalogRequest=null;
                    Set<String> urls=new HashSet<>();for(var feed:catalog)urls.add(feed.url);
                    int added=0;for(var feed:found)if(catalog.size()<200&&urls.add(feed.url)){catalog.add(feed);added++;}
                    save();refreshCatalog();message(added+" NFL feeds added. "+(found.isEmpty()?"Check NFL labels in the playlist.":"Choose an empty slot to watch."));
                });
            }catch(Exception error){ui.post(()->{if(started&&generation==catalogGeneration&&!isDestroyed()){catalogRequest=null;message("Import failed: "+error.getMessage());}});}
        });
    }
    void manage() {
        if(catalog.isEmpty()){message("No saved streams yet.");return;}
        String[] titles=new String[catalog.size()];for(int i=0;i<titles.length;i++)titles[i]=catalog.get(i).title;
        new Ui.DialogBuilder(this).setTitle("Remove a saved stream").setItems(titles,(d,n)->{removeCatalogFeed(n);}).setNegativeButton("Close",null).show();
    }
    void message(String s) {new Ui.DialogBuilder(this).setMessage(s).setPositiveButton("OK",null).show();}
    void switchSource(int slot,FeedParser.Feed source){
        FeedParser.Feed game=games[slot];if(game==null||game.eventId.isEmpty()||!game.eventId.equals(source.eventId))return;
        // GameSources persists the choice before delivering it to playback.
        if(!game.url.equals(source.url))assign(slot,new FeedParser.Feed(game.title,source.url,game.eventId));
    }
    JSONObject json(FeedParser.Feed f)throws JSONException {return new JSONObject().put("title",f.title).put("url",f.url).put("eventId",f.eventId);}
    FeedParser.Feed feed(JSONObject o)throws JSONException {String u=o.getString("url");if(!FeedParser.validUrl(u))throw new JSONException("Bad URL");return new FeedParser.Feed(o.getString("title"),u,o.optString("eventId",""));}
    void save() {
        try{
            int paused=0;for(int i=0;i<4;i++)if(!resumePlayback[i])paused|=1<<i;
            JSONArray feeds=new JSONArray(),slots=new JSONArray();
            for(FeedParser.Feed feed:catalog)feeds.put(json(feed));
            for(FeedParser.Feed game:games)slots.put(game==null?JSONObject.NULL:json(game));
            getPreferences(0).edit()
                .putString("feeds",feeds.toString()).putString("slots",slots.toString())
                .putInt("count",count).putInt("audio",audible)
                .putInt("paused",paused).putInt("full",full).apply();
        }catch(JSONException ignored){}
    }
    void load() {
        var p=getPreferences(0);count=p.getInt("count",4);if(count!=1&&count!=2&&count!=4)count=4;
        audible=Math.max(0,Math.min(count-1,p.getInt("audio",0)));
        int paused=p.getInt("paused",0);for(int i=0;i<4;i++)resumePlayback[i]=(paused&(1<<i))==0;
        // One damaged entry must not hide valid saved feeds or prevent slot restoration.
        try{JSONArray feeds=new JSONArray(p.getString("feeds","[]"));
            for(int i=0;i<feeds.length()&&catalog.size()<200;i++)try{catalog.add(feed(feeds.getJSONObject(i)));}catch(JSONException ignored){}
        }catch(JSONException ignored){}
        try{JSONArray slots=new JSONArray(p.getString("slots","[]"));
            for(int i=0;i<Math.min(4,slots.length());i++)try{if(!slots.isNull(i))games[i]=feed(slots.getJSONObject(i));}catch(JSONException ignored){}
        }catch(JSONException ignored){}
        full=p.getInt("full",-1);if(full < -1||full>=count||(full>=0&&games[full]==null))full=-1;
    }
    private void recordMetrics(int slot,boolean ended){
        sampleMetrics(slot);Diagnostics.log("player_summary","player="+metricIds[slot]+" sourceId="+metricSources[slot]+" build="+BuildConfig.VERSION_CODE+" final="+ended+" "+metrics[slot].summary(android.os.SystemClock.elapsedRealtime()));
    }
    private void sampleMetrics(int slot){
        ExoPlayer player=players[slot];PlaybackMetrics totals=metrics[slot];if(player==null||totals==null)return;
        totals.state(android.os.SystemClock.elapsedRealtime(),player.isPlaying(),player.getPlayWhenReady()&&!terminalFailure[slot]&&player.getPlaybackState()!=Player.STATE_ENDED,player.getPlaybackState()==Player.STATE_BUFFERING&&player.getPlayerError()==null);
    }
    private void recordPlayback(){
        android.net.ConnectivityManager network=getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities caps=network.getNetworkCapabilities(network.getActiveNetwork());
        VideoBudget.Profile allowance=videoProfile();
        Diagnostics.log("sample","active="+activeGameCount()+" layout="+count+" expanded="+full+" audio="+audioOwner+" budgetBps="+bandwidthBudget.total()+" maxWidth="+allowance.width+" maxHeight="+allowance.height+" maxFps="+allowance.fps+" qualityPenalty="+videoHealth.penalty+" estimateBps="+sharedMeter.getBitrateEstimate()+" connected="+(caps!=null)+" validated="+(caps!=null&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))+" wifi="+(caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI))+" freeHeap="+Runtime.getRuntime().freeMemory()+" memory="+memoryState);
        for(int i=0;i<4;i++)if(players[i]!=null){
            ExoPlayer player=players[i];recordMetrics(i,false);var counters=player.getVideoDecoderCounters();
            Diagnostics.log("slot_sample","slot="+i+" decoder="+decoderNames[i]+" shareBps="+bandwidthBudget.share(i)+" state="+player.getPlaybackState()+" playing="+player.isPlaying()+" bufferMs="+player.getTotalBufferedDuration()+" liveOffsetMs="+player.getCurrentLiveOffset()+" rendered="+(counters==null?0:counters.renderedOutputBufferCount)+" dropped="+(counters==null?0:counters.droppedBufferCount));
        }
    }
    private void startPlayback(){
        if(started)return;Diagnostics.log("playback_start","layout="+count+" expanded="+full);started=true;bandwidthBudget.reset(sharedMeter.getBitrateEstimate());sharedMeter.addEventListener(ui,bandwidthListener);
        startPlayers();updateLayout();ui.removeCallbacks(qualityTick);ui.post(qualityTick);
    }
    private void stopPlayback(){
        cancelCatalogImport();
        if(!started)return;recordPlayback();Diagnostics.log("playback_stop","background=true");started=false;sharedMeter.removeEventListener(bandwidthListener);bandwidthBudget.setActiveMask(0);
        ui.removeCallbacks(qualityTick);ui.removeCallbacks(hideControls);for(int i=0;i<4;i++)release(i);updateKeepScreenOn();
    }
    @Override protected void onStart(){super.onStart();if(android.os.Build.VERSION.SDK_INT>23)startPlayback();}
    @Override protected void onResume(){super.onResume();if(android.os.Build.VERSION.SDK_INT<=23)startPlayback();}
    @Override protected void onPause(){if(android.os.Build.VERSION.SDK_INT<=23)stopPlayback();super.onPause();}
    @Override protected void onStop(){stopPlayback();super.onStop();}
    @Override protected void onDestroy(){cancelCatalogImport();network.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(full>=0)expandGame(-1);else if(controlsVisible)finish();else showMenu();}
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int key=e.getKeyCode();
        if(root!=null&&e.getAction()==KeyEvent.ACTION_DOWN&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT))setControlsVisible(true);
        return super.dispatchKeyEvent(e);
    }
    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_MENU){showMenu();return true;}
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE){
            if(e.getRepeatCount()>0)return true;
            boolean play=key==KeyEvent.KEYCODE_MEDIA_PLAY;
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)for(ExoPlayer player:players)if(player!=null&&!player.getPlayWhenReady())play=true;
            for(ExoPlayer player:players)if(player!=null)player.setPlayWhenReady(play);
            return true;
        }
        return super.onKeyDown(key,e);
    }
}
