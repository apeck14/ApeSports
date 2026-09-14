package tv.gridiron.app;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

/** Opt-in screenshot review. Synthetic scores stay in the test APK only. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class UiReviewTest {
    private void capture(String name)throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync();SystemClock.sleep(300);
        Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();
        var resolver=instrumentation.getTargetContext().getContentResolver();ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,name+".png");values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/ApeSportsReview");
        var uri=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        try(var out=resolver.openOutputStream(uri)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private void back(){InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    @Test public void renderReviewScreens()throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("renderReview")));
        String[][] teams={{"BUF","Buffalo Bills","BAL","Baltimore Ravens"},{"KC","Kansas City Chiefs","DAL","Dallas Cowboys"},{"GB","Green Bay Packers","PHI","Philadelphia Eagles"},{"SF","San Francisco 49ers","SEA","Seattle Seahawks"},{"NO","New Orleans Saints","DET","Detroit Lions"},{"PIT","Pittsburgh Steelers","CIN","Cincinnati Bengals"}};
        JSONObject[] events=new JSONObject[6];
        for(int i=0;i<6;i++){
            JSONObject e=NflHomeTest.event("review-"+i,i<4?"in":"pre");e.put("name",teams[i][1]+" at "+teams[i][3]);
            e.getJSONObject("status").getJSONObject("type").put("shortDetail","Preview · 7:32 / 3rd");
            JSONArray competitors=e.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors");
            for(int j=0;j<2;j++){int index=j==0?2:0;JSONObject team=competitors.getJSONObject(j).getJSONObject("team");team.put("abbreviation",teams[i][index]).put("displayName",teams[i][index+1]);}
            events[i]=e;
        }
        var games=NflScoreboard.parse(NflHomeTest.board(events));
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{a.cancelRefresh();a.applySnapshot(games,System.currentTimeMillis(),"");a.toggle(games.get(0));a.toggle(games.get(2));a.getWindow().getDecorView().findViewWithTag("review-1").requestFocus();});
            capture("home-fixture");
            scenario.onActivity(a->a.watchSelected());capture("link-stream");back();
            String sourceEvent="review-sources-"+java.util.UUID.randomUUID();
            try{
                scenario.onActivity(a->{GameSources store=new GameSources(a);
                    store.select(sourceEvent,new FeedParser.Feed("Source 1 · Preview","https://example.invalid/first.m3u8",sourceEvent));
                    store.select(sourceEvent,new FeedParser.Feed("Source 2 · Preview","https://example.invalid/second.mpd",sourceEvent));
                    GameSources.show(a,sourceEvent,games.get(0).name,store.selected(sourceEvent),source->{});
                });capture("game-source-picker");back();
            }finally{scenario.onActivity(a->a.getPreferences(0).edit().remove("source."+sourceEvent).remove("sources."+sourceEvent).commit());}
        }
        try(var scenario=ActivityScenario.launch(UpdateActivity.class)){capture("updates");}
        try(var scenario=ActivityScenario.launch(DiagnosticsActivity.class)){capture("diagnostics");}
        try(var scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->a.setControlsVisible(true));capture("playback-controls");
            scenario.onActivity(a->a.sources());capture("sources");back();
            scenario.onActivity(a->a.addStream());capture("add-stream");back();
            scenario.onActivity(a->{
                android.widget.FrameLayout frame=new android.widget.FrameLayout(a);frame.setBackgroundColor(Ui.BACKGROUND);
                android.widget.ImageView banner=new android.widget.ImageView(a);banner.setImageResource(R.drawable.ape_banner);banner.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                frame.addView(banner,new android.widget.FrameLayout.LayoutParams(a.dp(320),a.dp(180),android.view.Gravity.CENTER));a.setContentView(frame);
            });capture("tv-banner");
            scenario.onActivity(a->{
                android.widget.LinearLayout row=new android.widget.LinearLayout(a);row.setGravity(android.view.Gravity.CENTER);row.setBackgroundColor(Ui.BACKGROUND);
                android.widget.ImageView icon=new android.widget.ImageView(a);icon.setImageDrawable(a.getApplicationInfo().loadIcon(a.getPackageManager()));icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                android.widget.LinearLayout.LayoutParams iconParams=new android.widget.LinearLayout.LayoutParams(a.dp(220),a.dp(220));iconParams.rightMargin=a.dp(64);row.addView(icon,iconParams);
                android.widget.ImageView banner=new android.widget.ImageView(a);banner.setImageDrawable(a.getApplicationInfo().loadBanner(a.getPackageManager()));banner.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                row.addView(banner,new android.widget.LinearLayout.LayoutParams(a.dp(480),a.dp(270)));a.setContentView(row);
            });capture("launcher-branding");
        }
    }
}
