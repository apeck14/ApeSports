package tv.gridiron.app;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Bounded local evidence. Callers supply metrics, never URLs, headers, or exception messages. */
final class Diagnostics {
    static final int LIMIT=256*1024;
    private static Diagnostics instance;
    private final File directory;
    private final String session=java.util.UUID.randomUUID().toString().substring(0,8);
    private final java.util.concurrent.atomic.AtomicLong dropped=new java.util.concurrent.atomic.AtomicLong(),failedWrites=new java.util.concurrent.atomic.AtomicLong();
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"diagnostics");t.setPriority(Thread.MIN_PRIORITY);return t;},(task,executor)->dropped.incrementAndGet());
    Diagnostics(File directory){this.directory=directory;}
    static void init(Context context){instance=new Diagnostics(new File(context.getFilesDir(),"diagnostics"));}
    static void log(String event,String detail){Diagnostics current=instance;if(current!=null){String line=System.currentTimeMillis()+" "+android.os.SystemClock.elapsedRealtime()+" session="+current.session+" "+event+" "+detail;current.writer.execute(()->current.append(line));}}
    synchronized void append(String line){
        try{
            if(!directory.isDirectory()&&!directory.mkdirs()){failedWrites.incrementAndGet();return;}
            byte[] bytes=(line.substring(0,Math.min(line.length(),6000)).replace('\n',' ').replace('\r',' ')+"\n").getBytes(StandardCharsets.UTF_8);
            File file=new File(directory,"current.log"),previous=new File(directory,"previous.log");
            if(file.length()+bytes.length>LIMIT){if(previous.exists()&&!previous.delete()){failedWrites.incrementAndGet();return;}if(file.exists()&&!file.renameTo(previous)){failedWrites.incrementAndGet();return;}}
            try(OutputStream out=new FileOutputStream(file,true)){out.write(bytes);}
        }catch(IOException|SecurityException ignored){failedWrites.incrementAndGet();/* Diagnostics must not interrupt playback, even on a full disk. */}
    }
    static String error(Throwable error){
        StringBuilder result=new StringBuilder();
        for(int cause=0;error!=null&&cause<4;cause++,error=error.getCause()){
            result.append(error.getClass().getName());
            if(error instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)result.append(" http=").append(((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)error).responseCode);
            if(error instanceof CatalogHttp.HttpException)result.append(" http=").append(((CatalogHttp.HttpException)error).status);
            StackTraceElement[] frames=error.getStackTrace();
            for(int i=0;i<Math.min(8,frames.length);i++){StackTraceElement frame=frames[i];result.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());}
            result.append("; ");
        }
        return result.toString();
    }
    static void crash(Throwable error){Diagnostics current=instance;if(current!=null)current.append(System.currentTimeMillis()+" session="+current.session+" CRASH "+error(error));}
    static String sourceId(String url){
        try{return AppUpdate.hex(java.security.MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(java.security.NoSuchAlgorithmException impossible){return "unknown";}
    }
    synchronized void copyTo(OutputStream output)throws IOException {
        for(String name:new String[]{"previous.log","current.log"}){File file=new File(directory,name);if(!file.exists())continue;try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)output.write(buffer,0,count);}}
    }
    static void export(Context context,OutputStream output)throws IOException {
        String header="ApeSports "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+" Android "+Build.VERSION.RELEASE+" API "+Build.VERSION.SDK_INT+"\nTimes: epoch milliseconds, elapsed milliseconds. Local logs; no source URLs or exception messages.\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));
        output.write(("Telemetry schema=2. Per-player summaries are cumulative: use the latest per session/player, not their sum. startupActiveMs=-1 means no frame rendered. Recovery duration is wall time.\n"+(instance==null?"":"Current logger: session="+instance.session+" droppedEvents="+instance.dropped.get()+" failedWrites="+instance.failedWrites.get()+"\n")).getBytes(StandardCharsets.UTF_8));
        // Never hold the log lock while a USB/document provider may block.
        ByteArrayOutputStream snapshot=new ByteArrayOutputStream();if(instance!=null)instance.copyTo(snapshot);snapshot.writeTo(output);
        if(Build.VERSION.SDK_INT>=30)try{
            android.app.ActivityManager manager=context.getSystemService(android.app.ActivityManager.class);
            for(android.app.ApplicationExitInfo exit:manager.getHistoricalProcessExitReasons(null,0,5)){
                String line="Process exit: time="+exit.getTimestamp()+" reason="+exit.getReason()+" status="+exit.getStatus()+" pssKB="+exit.getPss()+"\n";
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }catch(RuntimeException ignored){}
    }
}
