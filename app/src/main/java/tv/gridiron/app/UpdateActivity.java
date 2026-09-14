package tv.gridiron.app;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.concurrent.*;

/** TV-friendly update flow; Android owns permission and installation confirmation. */
public final class UpdateActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView status;private Button action;private ProgressBar progress;
    private Future<?> task;private AppUpdate.Download download;private AppUpdate update;private File apk;
    private boolean foreground,checked;private int generation;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setBackgroundColor(Ui.BACKGROUND);root.setPadding(Ui.dp(this,64),0,Ui.dp(this,64),0);
        root.addView(Ui.brandMark(this),new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
        TextView title=Ui.text(this,"ApeSports updates",28,Ui.TEXT);title.setGravity(Gravity.CENTER);root.addView(title);
        status=Ui.text(this,"Installed version "+BuildConfig.VERSION_NAME,17,Ui.MUTED);status.setGravity(Gravity.CENTER);status.setPadding(0,Ui.dp(this,24),0,Ui.dp(this,24));root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(Ui.dp(this,400),Ui.dp(this,12)));
        action=Ui.button(this,"Check for updates",this::check);root.addView(action,new LinearLayout.LayoutParams(Ui.dp(this,260),Ui.dp(this,52)));
        LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(Ui.dp(this,260),Ui.dp(this,52));backParams.topMargin=Ui.dp(this,8);
        root.addView(Ui.button(this,"Back",this::finish),backParams);setContentView(root);action.requestFocus();
    }
    @Override protected void onStart(){super.onStart();foreground=true;if(!checked){checked=true;check();}}
    void check(){
        cancel();apk=null;update=null;busy("Checking for updates…",true);final int token=generation;
        task=worker.submit(()->{try{AppUpdate latest=AppUpdate.latest();main.post(()->{
            if(!active(token))return;task=null;update=latest;
            if(latest==null)ready("No published update is available yet.","Check again",this::check);
            else if(!latest.newer())ready("You're up to date · "+BuildConfig.VERSION_NAME,"Check again",this::check);
            else ready("Version "+latest.versionName+" is available. Your saved streams will be kept.","Download update",this::download);
        });}catch(Exception error){if(!Thread.currentThread().isInterrupted())Diagnostics.log("update_error",Diagnostics.error(error));main.post(()->{if(active(token)){task=null;ready("Couldn't check for updates. Check your connection and try again.","Try again",this::check);}});}});
    }
    private boolean active(int token){return foreground&&!isDestroyed()&&token==generation;}
    private void busy(String message,boolean indeterminate){status.setText(message);action.setEnabled(false);progress.setVisibility(View.VISIBLE);progress.setIndeterminate(indeterminate);progress.setProgress(0);}
    private void ready(String message,String label,Runnable next){progress.setVisibility(View.GONE);status.setText(message);action.setEnabled(true);action.setText(label);action.setOnClickListener(v->next.run());}
    private void download(){
        if(update==null)return;cancel();busy("Downloading update…",false);final int token=generation;
        AppUpdate candidate=update;AppUpdate.Download request=new AppUpdate.Download();download=request;
        task=worker.submit(()->{try{File result=request.run(this,candidate,percent->main.post(()->{if(active(token))progress.setProgress(percent);}));
            main.post(()->{if(!active(token))return;task=null;download=null;apk=result;ready("Update verified. Android will confirm installation.","Install update",this::install);install();});
        }catch(Exception error){if(!Thread.currentThread().isInterrupted())Diagnostics.log("update_error",Diagnostics.error(error));main.post(()->{if(active(token)){task=null;download=null;ready("Couldn't download or verify the update. "+error.getMessage(),"Try again",this::check);}});}});
    }
    void install(){
        if(apk==null||!apk.isFile()){ready("Check for an update to continue.","Check for updates",this::check);return;}
        try{
            // Let Android own the unknown-sources permission flow: changing it can kill this process.
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",apk);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }catch(ActivityNotFoundException|SecurityException error){ready("This TV couldn't open its update installer. You can install the APK manually.","Check again",this::check);}
    }
    private void cancel(){generation++;if(download!=null)download.cancel();download=null;if(task!=null)task.cancel(true);task=null;}
    @Override protected void onStop(){foreground=false;boolean interrupted=task!=null;cancel();if(interrupted)ready("Update check or download paused. Try again when ready.","Check for updates",this::check);super.onStop();}
    @Override protected void onDestroy(){cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
