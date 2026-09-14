package tv.gridiron.app;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

public final class DiagnosticsActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private boolean busy,foreground;
    @Override protected void onStart(){super.onStart();foreground=true;}
    @Override protected void onStop(){foreground=false;super.onStop();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setBackgroundColor(Ui.BACKGROUND);root.setPadding(Ui.dp(this,48),Ui.dp(this,24),Ui.dp(this,48),Ui.dp(this,24));
        TextView title=Ui.text(this,"Diagnostics",28,Ui.TEXT);title.setGravity(Gravity.CENTER);root.addView(title);
        status=Ui.text(this,"Recent playback events and crashes stay on this TV.\nLogs rotate automatically (512 KB maximum).\nNo stream URLs, passwords, or automatic uploads.\nExport after a problem and send the report with what happened.",17,Ui.MUTED);status.setGravity(Gravity.CENTER);status.setPadding(0,Ui.dp(this,24),0,Ui.dp(this,24));root.addView(status);
        root.addView(Ui.button(this,"Export report",()->{
            if(busy)return;
            try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"ApeSports-diagnostics-"+System.currentTimeMillis()+".txt"),1);}
            catch(ActivityNotFoundException error){saveOnTv();}
        }),new LinearLayout.LayoutParams(Ui.dp(this,300),Ui.dp(this,52)));
        root.addView(Ui.button(this,"Share report",this::share),new LinearLayout.LayoutParams(Ui.dp(this,300),Ui.dp(this,52)));
        root.addView(Ui.button(this,"Back",this::finish),new LinearLayout.LayoutParams(Ui.dp(this,300),Ui.dp(this,52)));setContentView(root);
    }
    private void saveOnTv(){busy=true;worker.execute(()->{
        try{
            File directory=getExternalFilesDir(null);if(directory==null)throw new IOException();File file=new File(directory,"ApeSports-diagnostics.txt");
            try(OutputStream out=new FileOutputStream(file)){Diagnostics.export(this,out);}
            result("No document picker is installed. Report saved on TV:\n"+file.getAbsolutePath()+"\nCopy this file with ADB, or use Share report with a transfer app.");
        }catch(IOException|SecurityException error){result("Couldn't save on this TV. Try Share report.");}
    });}
    private void share(){if(busy)return;busy=true;worker.execute(()->{
        try{
            File directory=new File(getCacheDir(),"reports");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException();File file=new File(directory,"ApeSports-diagnostics.txt");
            try(OutputStream out=new FileOutputStream(file)){Diagnostics.export(this,out);}
            runOnUiThread(()->{busy=false;if(!foreground||isDestroyed()||isFinishing())return;try{
                var uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".updates",file);
                Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("Report",uri));
                startActivity(Intent.createChooser(intent,"Share diagnostic report"));
            }catch(ActivityNotFoundException|SecurityException error){status.setText("No sharing app is available. Use Export report to save a local copy.");}});
        }catch(IOException|SecurityException error){result("Couldn't prepare the report. Check available storage and try again.");}
    });}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=1||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(busy)return;busy=true;worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IOException();Diagnostics.export(this,out);result("Report saved. Send it with the approximate time and description of the problem.");}catch(IOException|SecurityException error){result("Couldn't save the report. Try another destination.");}});
    }
    private void result(String message){runOnUiThread(()->{busy=false;if(!isDestroyed()&&!isFinishing())status.setText(message);});}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
