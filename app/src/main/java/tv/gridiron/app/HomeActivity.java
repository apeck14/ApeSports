package tv.gridiron.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Event-first TV home. Playback stays in MainActivity's existing native wall. */
@androidx.media3.common.util.UnstableApi
public class HomeActivity extends Activity {
    private static final int BG=Ui.BACKGROUND,PANEL=Ui.SURFACE,WHITE=Ui.TEXT,MUTED=Ui.MUTED;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final LinkedHashSet<String> selected=new LinkedHashSet<>();
    private GameSources sources;
    private List<NflScoreboard.Game> games=Collections.emptyList();
    private LinearLayout cards;private ScrollView scroll;private TextView status,selection;private Button watch;
    private Future<?> updateCheck;
    private int updateGeneration;
    private Future<?> request;private boolean foreground,loading;private int generation;private long fetched;
    private String failure="";
    private final Runnable refreshTick=()->refresh();
    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    TextView text(String value,int size,int color){return Ui.text(this,value,size,color);}
    GradientDrawable background(int fill,int stroke){return Ui.surface(this,fill,stroke);}
    Button button(String label,Runnable action){return Ui.button(this,label,action);}
    void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(Ui.LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);sources=new GameSources(this);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(40),dp(24),dp(40),dp(24));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(0,0,0,dp(20));
        LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(dp(48),dp(48));markParams.rightMargin=dp(12);header.addView(Ui.brandMark(this),markParams);
        TextView brand=text("ApeSports",22,WHITE);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));header.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));brand.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(button("Refresh",this::refresh),new LinearLayout.LayoutParams(dp(100),dp(44)));
        LinearLayout.LayoutParams updatesParams=new LinearLayout.LayoutParams(dp(110),dp(44));updatesParams.leftMargin=dp(12);
        header.addView(button("Settings",()->new Ui.DialogBuilder(this).setTitle("ApeSports").setItems(new String[]{"Check for updates","Diagnostics"},(dialog,index)->startActivity(new Intent(this,index==0?UpdateActivity.class:DiagnosticsActivity.class))).show()),updatesParams);
        LinearLayout.LayoutParams libraryParams=new LinearLayout.LayoutParams(dp(148),dp(44));libraryParams.leftMargin=dp(12);
        header.addView(button("Saved streams",()->startActivity(new Intent(this,MainActivity.class))),libraryParams);root.addView(header);divider(root);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(0,dp(20),0,dp(4));
        TextView title=text("NFL games",32,WHITE);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(text(new java.text.SimpleDateFormat("EEEE, MMMM d",Locale.getDefault()).format(new Date()),14,MUTED));root.addView(heading);
        status=text("Loading games…",13,MUTED);status.setPadding(0,0,0,dp(12));root.addView(status);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);scroll.addView(cards);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        divider(root);LinearLayout bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER_VERTICAL);bottom.setPadding(0,dp(16),0,0);
        LinearLayout instructions=new LinearLayout(this);instructions.setOrientation(LinearLayout.VERTICAL);selection=text("Choose up to 4 games",17,WHITE);instructions.addView(selection);instructions.addView(text("OK  Select     /     Hold OK  Watch one",12,MUTED));bottom.addView(instructions,new LinearLayout.LayoutParams(0,-2,1));
        bottom.addView(button("Sources",this::chooseGameSources),new LinearLayout.LayoutParams(dp(110),dp(48)));
        bottom.addView(button("Clear",()->{selected.clear();render();}),new LinearLayout.LayoutParams(dp(88),dp(48)));
        watch=button("Watch",this::watchSelected);Ui.icon(watch,R.drawable.ic_play);LinearLayout.LayoutParams watchParams=new LinearLayout.LayoutParams(dp(148),dp(48));watchParams.leftMargin=dp(12);bottom.addView(watch,watchParams);root.addView(bottom);setContentView(root);render();
        if(state!=null){ArrayList<String> ids=state.getStringArrayList("selected");if(ids!=null)for(String id:ids)if(selected.size()<4)selected.add(id);}
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putStringArrayList("selected",new ArrayList<>(selected));}
    @Override protected void onStart(){super.onStart();foreground=true;refresh();checkForUpdate();}
    @Override protected void onStop(){foreground=false;cancelRefresh();updateGeneration++;if(updateCheck!=null)updateCheck.cancel(true);updateCheck=null;super.onStop();}
    private void checkForUpdate(){
        long now=System.currentTimeMillis(),last=getPreferences(0).getLong("updateChecked",0);
        if(now>=last&&now-last<86400000)return;
        getPreferences(0).edit().putLong("updateChecked",now).apply();final int token=++updateGeneration;
        updateCheck=network.submit(()->{try{AppUpdate update=AppUpdate.latest();ui.post(()->{
            if(!foreground||token!=updateGeneration||isFinishing())return;updateCheck=null;
            if(update!=null&&update.newer())new Ui.DialogBuilder(this).setTitle("ApeSports update")
                .setMessage("Version "+update.versionName+" is available.").setPositiveButton("View update",(d,n)->startActivity(new Intent(this,UpdateActivity.class))).setNegativeButton("Later",null).show();
        });}catch(Exception ignored){/* Automatic checks stay quiet offline; Updates offers manual retry. */}});
    }
    void cancelRefresh(){generation++;loading=false;ui.removeCallbacks(refreshTick);if(request!=null)request.cancel(true);request=null;}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
    void refresh(){
        if(!foreground||loading)return;loading=true;ui.removeCallbacks(refreshTick);final int token=++generation;
        status.setText(games.isEmpty()?"Loading NFL games…":"Refreshing NFL games…");
        request=network.submit(()->{
            if(fetched==0)try{
                File file=new File(getCacheDir(),"nfl-scoreboard.json");if(file.length()>0&&file.length()<2*1024*1024){
                    String json=read(file);JSONObject cache=new JSONObject(json);List<NflScoreboard.Game> cached=NflScoreboard.parse(cache.getString("data"));long time=cache.getLong("fetched");
                    ui.post(()->{if(foreground&&token==generation){games=cached;fetched=time;failure="Checking saved data…";render();}});
                }
            }catch(Exception ignored){}
            try{
                String data=CatalogHttp.fetch(NflScoreboard.url(System.currentTimeMillis()),2*1024*1024,"ApeSports/"+BuildConfig.VERSION_NAME).body;
                List<NflScoreboard.Game> result=NflScoreboard.parse(data);long time=System.currentTimeMillis();
                if(!Thread.currentThread().isInterrupted())try{android.util.AtomicFile cache=new android.util.AtomicFile(new File(getCacheDir(),"nfl-scoreboard.json"));FileOutputStream out=null;try{out=cache.startWrite();out.write(new JSONObject().put("fetched",time).put("data",data).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));cache.finishWrite(out);}catch(Exception e){cache.failWrite(out);}}catch(Exception ignored){}
                ui.post(()->{if(foreground&&token==generation){applySnapshot(result,time,"");finishRefresh(60000);}});
            }catch(Exception e){if(!Thread.currentThread().isInterrupted())Diagnostics.log("home_fetch_error",Diagnostics.error(e));ui.post(()->{if(foreground&&token==generation){failure="Live data unavailable · Retry or use Saved streams";render();finishRefresh(120000);}});}
        });
    }
    private void finishRefresh(long delay){loading=false;request=null;ui.postDelayed(refreshTick,delay);}
    private String read(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Cache too large");out.write(b,0,n);}return out.toString("UTF-8");}}
    void applySnapshot(List<NflScoreboard.Game> result,long time,String error){games=result;fetched=time;failure=error;Set<String> live=new HashSet<>();for(var g:games)if(g.live())live.add(g.id);selected.retainAll(live);render();}
    private boolean fresh(){return failure.isEmpty()&&NflScoreboard.fresh(fetched,System.currentTimeMillis());}
    void render(){
        View focus=getCurrentFocus();Object focused=focus==null?null:focus.getTag();int y=scroll.getScrollY();cards.removeAllViews();
        boolean fresh=fresh();int live=0;boolean hasGames=false;for(var g:games){if(g.live())live++;if(g.order()<2)hasGames=true;}
        String updated=fetched>0?" · Updated "+new java.text.SimpleDateFormat("h:mm a",Locale.getDefault()).format(new Date(fetched)):"";
        status.setText(!failure.isEmpty()?failure+updated:fetched==0?"Loading NFL games…":"Game status · "+live+" live"+updated+(fresh?"":" · Stale data"));
        if(!hasGames){
            TextView empty=text(!failure.isEmpty()?"Games are unavailable":fetched==0?"Finding games":"No live or upcoming games",24,WHITE);empty.setPadding(0,dp(48),0,dp(12));cards.addView(empty);
            cards.addView(text(failure.isEmpty()?"Live matchups appear first. Choose one game or build your multiview.":"Try refreshing, or open Saved streams to watch a linked feed.",16,MUTED));
        }
        for(int section=0;section<2;section++){
            List<NflScoreboard.Game> group=new ArrayList<>();for(var g:games)if(g.order()==section)group.add(g);if(group.isEmpty())continue;
            TextView label=text(section==0?(fresh?"Live now":"Last known live · Unverified"):"Coming up",16,WHITE);label.setPadding(0,dp(12),0,dp(12));cards.addView(label);
            LinearLayout row=null;for(int i=0;i<group.size();i++){
                if(i%3==0){row=new LinearLayout(this);cards.addView(row,new LinearLayout.LayoutParams(-1,-2));}
                View card=card(group.get(i));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(172),1);p.setMargins(0,0,i%3==2?0:dp(12),dp(12));row.addView(card,p);
            }
            int remainder=group.size()%3;if(remainder!=0)for(int i=remainder;i<3;i++){LinearLayout.LayoutParams blank=new LinearLayout.LayoutParams(0,1,1);blank.rightMargin=i==2?0:dp(12);row.addView(new View(this),blank);}
        }
        selection.setText(selected.isEmpty()?"Choose up to 4 games":selected.size()+" selected"+(selected.size()==3?" · Four-view layout":""));watch.setText(selected.isEmpty()?"Watch":"Watch "+selected.size());watch.setEnabled(!selected.isEmpty());watch.setAlpha(watch.isEnabled()?1f:0.45f);
        if(focused!=null){View restore=cards.findViewWithTag(focused);if(restore!=null)restore.requestFocus();}scroll.post(()->scroll.scrollTo(0,y));
    }
    private View card(NflScoreboard.Game game){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(14),dp(18),dp(14));card.setTag(game.id);card.setFocusable(true);card.setClickable(true);
        boolean chosen=selected.contains(game.id);card.setBackground(background(chosen?Ui.RAISED:PANEL,Ui.LINE));
        card.setOnFocusChangeListener((v,f)->v.setBackground(background(selected.contains(game.id)?Ui.RAISED:PANEL,f?WHITE:Ui.LINE)));
        String detail=gameStatus(game);
        LinearLayout meta=new LinearLayout(this);meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView gameStatus=text(detail,12,game.live()&&fresh()?Ui.LIVE:MUTED);gameStatus.setSingleLine(true);gameStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.addView(gameStatus,new LinearLayout.LayoutParams(0,dp(26),1));
        TextView check=text(chosen?"✓":"",14,WHITE);check.setGravity(Gravity.CENTER);check.setBackground(background(chosen?Ui.LINE:PANEL,Ui.LINE));meta.addView(check,new LinearLayout.LayoutParams(dp(22),dp(22)));card.addView(meta);
        card.addView(team(game.away));card.addView(team(game.home));
        int alternatives=sources.list(game.id).size();
        TextView source=text(alternatives>0?alternatives+" saved source"+(alternatives==1?"":"s"):"Stream not connected",11,MUTED);source.setPadding(0,dp(4),0,0);card.addView(source);
        card.setContentDescription(game.name+", "+detail+(chosen?", selected":"")+(alternatives>0?", saved source available":", stream not connected"));
        card.setOnClickListener(v->toggle(game));card.setOnLongClickListener(v->{if(canSelect(game)){selected.clear();selected.add(game.id);render();watchSelected();}return true;});return card;
    }
    private View team(NflScoreboard.Team team){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);ImageView icon=new ImageView(this);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));icon.setImageResource(TeamLogos.resource(team.abbreviation));
        TextView name=text(team.name,18,WHITE);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(dp(10),0,0,0);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));row.addView(name,new LinearLayout.LayoutParams(0,dp(40),1));
        return row;
    }
    private String gameStatus(NflScoreboard.Game game){
        if(game.live())return fresh()?"Live":"Last known live";
        if(game.state.equals("pre"))return new java.text.SimpleDateFormat("EEE h:mm a z",Locale.getDefault()).format(new Date(game.start));
        return game.state.equals("post")?"Finished":"Status unavailable";
    }
    private boolean canSelect(NflScoreboard.Game game){if(!game.live()){message(game.name+" · "+gameStatus(game));return false;}return true;}
    void toggle(NflScoreboard.Game game){if(!canSelect(game))return;if(!selected.remove(game.id)){if(selected.size()==4){message("Choose up to four games. Deselect one to add another.");return;}selected.add(game.id);}render();}
    int selectedCount(){return selected.size();}
    boolean hasSource(String id){return FeedParser.validUrl(sources.selected(id));}
    void watchSelected(){
        if(selected.isEmpty()){message("Select games to watch.");return;}
        for(var g:games)if(selected.contains(g.id)&&!hasSource(g.id)){linkSource(g);return;}
        JSONArray feeds=new JSONArray();try{for(String id:selected)for(var g:games)if(g.id.equals(id)&&g.live())feeds.put(new JSONObject().put("title",g.name).put("eventId",id).put("url",sources.selected(id)));}catch(JSONException e){return;}
        if(feeds.length()!=selected.size())return;
        startActivity(new Intent(this,MainActivity.class).putExtra("selectedFeeds",feeds.toString()));
    }
    private void linkSource(NflScoreboard.Game game){
        GameSources.show(this,game.id,game.name,sources.selected(game.id),source->{render();watchSelected();});
    }
    void chooseGameSources(){
        List<NflScoreboard.Game> choices=new ArrayList<>();for(var game:games)if(game.live()&&(selected.isEmpty()||selected.contains(game.id)))choices.add(game);
        if(choices.isEmpty()){message("Choose a live game to manage its sources.");return;}
        String[] labels=new String[choices.size()];for(int i=0;i<labels.length;i++)labels[i]=choices.get(i).name;
        if(choices.size()==1)showSources(choices.get(0));else new Ui.DialogBuilder(this).setTitle("Sources for game").setItems(labels,(d,n)->showSources(choices.get(n))).setNegativeButton("Cancel",null).show();
    }
    private void showSources(NflScoreboard.Game game){GameSources.show(this,game.id,game.name,sources.selected(game.id),source->render());}
    private void message(String text){new Ui.DialogBuilder(this).setMessage(text).setPositiveButton("OK",null).show();}
}
