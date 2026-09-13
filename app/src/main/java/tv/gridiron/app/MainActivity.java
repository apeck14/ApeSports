package tv.gridiron.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
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
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public class MainActivity extends Activity {
    private static final int BG=0xff0b1114, PANEL=0xff151f24, WHITE=0xffeef5f2, MUTED=0xff96aaa5, GREEN=0xffa4f77b;
    private final ArrayList<FeedParser.Feed> catalog=new ArrayList<>();
    private final FeedParser.Feed[] games=new FeedParser.Feed[4];
    private final ExoPlayer[] players=new ExoPlayer[4];
    private final ArrayList<PlayerView> surfaces=new ArrayList<>();
    private final TextView[] labels=new TextView[4];
    private final String[] states=new String[4];
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private LinearLayout root, wall;
    private int count=4, audible=0, full=-1;
    private boolean started=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        load(); render();
    }
    int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    TextView text(String s,int size,int color) { TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t; }
    GradientDrawable shape(int fill,int stroke) { GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(12));d.setStroke(dp(2),stroke);return d; }
    void focusStyle(View v) {
        v.setFocusable(true);v.setBackground(shape(PANEL,Color.TRANSPARENT));
        v.setOnFocusChangeListener((w,f)-> {w.setBackground(shape(f?0xff263b35:PANEL,f?GREEN:Color.TRANSPARENT));});
    }
    Button button(String title,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(WHITE);b.setPadding(dp(14),0,dp(14),0);b.setMinWidth(0);b.setMinimumWidth(0);
        focusStyle(b);b.setOnClickListener(v->action.run());return b;
    }
    void render() {
        for(PlayerView view:surfaces)view.setPlayer(null);
        surfaces.clear();
        Arrays.fill(labels,null);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(28),dp(18),dp(28),dp(14));root.setBackgroundColor(BG);
        if(full<0) {
            LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
            TextView brand=text("▦  GRIDIRON",24,WHITE);brand.setTypeface(null,Typeface.BOLD);top.addView(brand,new LinearLayout.LayoutParams(0,dp(50),1));
            TextView tag=text("NFL  /  GAME ROOM",12,GREEN);tag.setGravity(Gravity.CENTER);top.addView(tag,new LinearLayout.LayoutParams(dp(170),dp(50)));
            Button sources=button("Sources",this::sources);top.addView(sources,new LinearLayout.LayoutParams(dp(112),dp(42)));root.addView(top);
            LinearLayout sub=new LinearLayout(this);sub.setGravity(Gravity.CENTER_VERTICAL);sub.setPadding(0,dp(8),0,dp(14));
            TextView heading=text("Your Sunday. Every angle.",21,WHITE);sub.addView(heading,new LinearLayout.LayoutParams(0,dp(44),1));
            for(int n:new int[]{1,2,4}) { Button b=button(n+(n==1?" view":" views")+(count==n?" ✓":""),()->setCount(n));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(100),dp(40));p.leftMargin=dp(8);sub.addView(b,p); }
            root.addView(sub);
        }
        wall=new LinearLayout(this);wall.setOrientation(LinearLayout.VERTICAL);
        root.addView(wall,new LinearLayout.LayoutParams(-1,0,1));
        if(full>=0) { LinearLayout row=new LinearLayout(this);row.addView(tile(full),new LinearLayout.LayoutParams(0,-1,1));wall.addView(row,new LinearLayout.LayoutParams(-1,0,1)); }
        else {
            int rows=count==4?2:1,cols=count==1?1:2;
            for(int r=0;r<rows;r++) { LinearLayout row=new LinearLayout(this);for(int c=0;c<cols;c++) {LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(4),dp(4),dp(4),dp(4));row.addView(tile(r*cols+c),p);}wall.addView(row,new LinearLayout.LayoutParams(-1,0,1)); }
        }
        TextView help=text(full>=0?"BACK  Return to multiview     •     OK  Audio     •     HOLD OK  Game options":"D-PAD  Move     •     OK  Choose game / audio     •     HOLD OK  Full screen, replace or retry",12,MUTED);
        help.setGravity(Gravity.CENTER_VERTICAL);root.addView(help,new LinearLayout.LayoutParams(-1,dp(32)));
        setContentView(root);
        if(full>=0)root.findViewWithTag("slot"+full).requestFocus();
    }
    View tile(int i) {
        FrameLayout frame=new FrameLayout(this);frame.setTag("slot"+i);frame.setPadding(dp(3),dp(3),dp(3),dp(3));focusStyle(frame);frame.setClipToOutline(true);
        if(games[i]!=null) {
            PlayerView pv=new PlayerView(this);surfaces.add(pv);pv.setUseController(false);pv.setFocusable(false);pv.setFocusableInTouchMode(false);pv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);pv.setPlayer(players[i]);
            pv.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);frame.addView(pv,new FrameLayout.LayoutParams(-1,-1));
            TextView label=text("",14,WHITE);label.setPadding(dp(12),dp(8),dp(12),dp(8));label.setBackgroundColor(0xe60b1114);labels[i]=label;updateLabel(i);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(44),Gravity.BOTTOM);frame.addView(label,lp);
        } else {
            LinearLayout empty=new LinearLayout(this);empty.setGravity(Gravity.CENTER);empty.setOrientation(LinearLayout.VERTICAL);empty.setPadding(dp(18),0,dp(18),0);
            TextView number=text(String.format(Locale.US,"%02d",i+1),34,GREEN);number.setTypeface(null,Typeface.BOLD);empty.addView(number);
            TextView title=text("Add a game",21,WHITE);title.setPadding(0,dp(8),0,dp(6));empty.addView(title);
            empty.addView(text(catalog.isEmpty()?"Connect your NFL feeds in Sources":"Press OK to choose an NFL feed",13,MUTED));
            for(int n=0;n<empty.getChildCount();n++)((TextView)empty.getChildAt(n)).setGravity(Gravity.CENTER);
            frame.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        }
        frame.setContentDescription("Game slot "+(i+1)+(games[i]==null?", empty":", "+games[i].title));
        frame.setOnClickListener(v->{if(games[i]==null)pick(i);else{audible=i;audio();save();}});
        frame.setOnLongClickListener(v->{options(i);return true;});return frame;
    }
    void setCount(int n) {
        full=-1; count=n;if(audible>=count)audible=0;
        for(int i=n;i<4;i++)release(i);
        startPlayers();save();render();
    }
    void updateLabel(int i) {
        if(labels[i]!=null&&games[i]!=null)labels[i].setText((i==audible?"● AUDIO   ":"○   ")+games[i].title+"   ·   "+(states[i]==null?"Connecting…":states[i]));
    }
    void audio() {
        for(int i=0;i<4;i++)if(players[i]!=null) {
            players[i].setVolume(i==audible?1:0);
            players[i].setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),i==audible);
            updateLabel(i);
        }
    }
    void startPlayers() {
        if(!started)return;
        for(int i=0;i<count;i++) {
            if(games[i]==null)continue;
            if(players[i]==null) {
                final int slot=i;ExoPlayer player=new ExoPlayer.Builder(this).build();players[i]=player;states[i]="Connecting…";
                player.addListener(new Player.Listener(){
                    @Override public void onPlaybackStateChanged(int s) {states[slot]=s==Player.STATE_READY?"Playing":s==Player.STATE_BUFFERING?"Buffering…":s==Player.STATE_ENDED?"Ended":"Connecting…";updateLabel(slot);}
                    @Override public void onPlayerError(PlaybackException e) {states[slot]="Error · hold OK to retry ("+e.getErrorCodeName()+")";updateLabel(slot);}
                });
                player.setMediaItem(MediaItem.fromUri(games[i].url));player.prepare();player.play();
            }
            players[i].setTrackSelectionParameters(players[i].getTrackSelectionParameters().buildUpon().setMaxVideoSize(count>1?1280:3840,count>1?720:2160).setMaxVideoBitrate(count>1?2500000:20000000).build());
        }
        if(audible>=count||games[audible]==null)for(int i=0;i<count;i++)if(games[i]!=null){audible=i;break;}
        audio();
    }
    void release(int i) {if(players[i]!=null){players[i].release();players[i]=null;}states[i]=null;}
    void assign(int i,FeedParser.Feed feed) {release(i);games[i]=feed;startPlayers();save();render();View tile=root.findViewWithTag("slot"+i);if(tile!=null)tile.requestFocus();}
    void pick(int i) {
        if(catalog.isEmpty()){sources();return;}
        String[] titles=new String[catalog.size()];for(int n=0;n<titles.length;n++)titles[n]=catalog.get(n).title;
        new AlertDialog.Builder(this).setTitle("Choose feed · slot "+(i+1)).setItems(titles,(d,n)->assign(i,catalog.get(n))).setNegativeButton("Cancel",null).show();
    }
    void options(int i) {
        if(games[i]==null){pick(i);return;}
        new AlertDialog.Builder(this).setTitle(games[i].title).setItems(new String[]{full<0?"Full screen":"Return to multiview","Listen to this game","Replace game","Retry playback","Remove from view"},(d,n)->{
            if(n==0){full=full<0?i:-1;audible=i;audio();render();}
            if(n==1){audible=i;audio();save();}
            if(n==2)pick(i);
            if(n==3)assign(i,games[i]);
            if(n==4){if(full==i)full=-1;assign(i,null);}
        }).show();
    }
    void sources() {
        new AlertDialog.Builder(this).setTitle("NFL feed sources").setItems(new String[]{"Add a game stream URL","Import NFL M3U catalog URL","Manage saved feeds ("+catalog.size()+")","Load 4 test videos · not NFL broadcasts"},(d,n)->{
            if(n==0)addStream();if(n==1)importCatalog();if(n==2)manage();if(n==3)demo();
        }).setNegativeButton("Close",null).show();
    }
    LinearLayout form() {LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),dp(8));return box;}
    EditText input(LinearLayout box,String hint) {EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setTextColor(WHITE);e.setHintTextColor(MUTED);box.addView(e,new LinearLayout.LayoutParams(-1,dp(56)));return e;}
    void addStream() {
        LinearLayout box=form();EditText name=input(box,"Game name, e.g. Chiefs at Bills");EditText url=input(box,"https://… stream.m3u8 or manifest.mpd");url.setInputType(17);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Add an NFL game feed").setView(box).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String t=name.getText().toString().trim(),u=url.getText().toString().trim();if(t.isEmpty()){name.setError("Enter a game name");return;}if(!FeedParser.validUrl(u)){url.setError("Enter a valid HTTP or HTTPS URL");return;}catalog.add(new FeedParser.Feed(t,u));save();dialog.dismiss();render();message("Feed saved. Choose an empty slot to watch.");}));dialog.show();
    }
    void importCatalog() {
        LinearLayout box=form();EditText url=input(box,"https://… channels.m3u");url.setInputType(17);box.addView(text("Imports entries mentioning NFL or an NFL team.\nA channel catalog is different from a single HLS stream.",14,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Import NFL playlist").setView(box).setPositiveButton("Import",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String u=url.getText().toString().trim();if(!FeedParser.validUrl(u)){url.setError("Enter a valid HTTP or HTTPS URL");return;}dialog.dismiss();message("Loading catalog…");
            network.execute(()->{try {String data=download(u);List<FeedParser.Feed> found=FeedParser.parseM3u(data,u);runOnUiThread(()->{if(isDestroyed())return;int added=0;for(FeedParser.Feed f:found){boolean exists=false;for(FeedParser.Feed old:catalog)if(old.url.equals(f.url))exists=true;if(!exists&&catalog.size()<200){catalog.add(f);added++;}}save();render();message(added+" NFL feeds added. "+(found.isEmpty()?"Check NFL labels in the playlist.":"Choose an empty slot to watch."));});}
                catch(Exception e){runOnUiThread(()->{if(!isDestroyed())message("Import failed: "+e.getMessage());});}});
        }));dialog.show();
    }
    String download(String source)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(source).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","GridironTV/0.1");
        try {int status=c.getResponseCode();if(status!=200)throw new IOException("HTTP "+status);try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Catalog exceeds 2 MB");out.write(buf,0,n);}return out.toString("UTF-8");}}
        finally {c.disconnect();}
    }
    void manage() {
        if(catalog.isEmpty()){message("No saved feeds yet.");return;}
        String[] titles=new String[catalog.size()];for(int i=0;i<titles.length;i++)titles[i]=catalog.get(i).title;
        new AlertDialog.Builder(this).setTitle("Select a feed to remove").setItems(titles,(d,n)->{catalog.remove(n);save();render();}).setNegativeButton("Close",null).show();
    }
    void demo() {
        new AlertDialog.Builder(this).setTitle("Playback test").setMessage("Replaces the current view with four copies of Big Buck Bunny. These are test videos, not NFL games.").setPositiveButton("Load test videos",(d,n)->{
            count=4;full=-1;for(int i=0;i<4;i++){release(i);games[i]=new FeedParser.Feed("TEST "+(i+1)+" · Big Buck Bunny","https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8");}audible=0;startPlayers();save();render();
        }).setNegativeButton("Cancel",null).show();
    }
    void message(String s) {new AlertDialog.Builder(this).setMessage(s).setPositiveButton("OK",null).show();}
    JSONObject json(FeedParser.Feed f)throws JSONException {return new JSONObject().put("title",f.title).put("url",f.url);}
    FeedParser.Feed feed(JSONObject o)throws JSONException {String u=o.getString("url");if(!FeedParser.validUrl(u))throw new JSONException("Bad URL");return new FeedParser.Feed(o.getString("title"),u);}
    void save() {try {JSONArray feeds=new JSONArray(),slots=new JSONArray();for(FeedParser.Feed f:catalog)feeds.put(json(f));for(FeedParser.Feed f:games)slots.put(f==null?JSONObject.NULL:json(f));getPreferences(0).edit().putString("feeds",feeds.toString()).putString("slots",slots.toString()).putInt("count",count).putInt("audio",audible).apply();}catch(JSONException ignored){}}
    void load() {try {var p=getPreferences(0);count=p.getInt("count",4);if(count!=1&&count!=2&&count!=4)count=4;audible=Math.max(0,Math.min(count-1,p.getInt("audio",0)));JSONArray feeds=new JSONArray(p.getString("feeds","[]"));for(int i=0;i<feeds.length();i++)catalog.add(feed(feeds.getJSONObject(i)));JSONArray slots=new JSONArray(p.getString("slots","[]"));for(int i=0;i<Math.min(4,slots.length());i++)if(!slots.isNull(i))games[i]=feed(slots.getJSONObject(i));}catch(Exception ignored){}}
    @Override protected void onStart(){super.onStart();started=true;startPlayers();render();}
    @Override protected void onStop(){started=false;for(int i=0;i<4;i++)release(i);super.onStop();}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(full>=0){full=-1;render();}else super.onBackPressed();}
    @Override public boolean onKeyDown(int key,KeyEvent e){if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){boolean play=false;for(ExoPlayer p:players)if(p!=null&&!p.getPlayWhenReady())play=true;for(ExoPlayer p:players)if(p!=null)p.setPlayWhenReady(play);return true;}return super.onKeyDown(key,e);}
}

