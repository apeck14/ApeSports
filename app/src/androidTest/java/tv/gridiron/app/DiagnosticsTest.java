package tv.gridiron.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DiagnosticsTest {
    @Test public void reportFinishingInBackgroundDoesNotOpenAShareScreen()throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var gate=new java.util.concurrent.CountDownLatch(1);
        var opened=new java.util.concurrent.atomic.AtomicBoolean();
        var worker=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ExecutorService>();
        var monitor=new android.app.Instrumentation.ActivityMonitor(){
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(android.content.Intent intent){
                if(!android.content.Intent.ACTION_CHOOSER.equals(intent.getAction()))return null;
                opened.set(true);return new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null);
            }
        };
        try(var scenario=androidx.test.core.app.ActivityScenario.launch(DiagnosticsActivity.class)){
            instrumentation.addMonitor(monitor);
            scenario.onActivity(a->{try{var field=DiagnosticsActivity.class.getDeclaredField("worker");field.setAccessible(true);worker.set((java.util.concurrent.ExecutorService)field.get(a));}catch(Exception error){throw new AssertionError(error);}});
            worker.get().submit(()->{try{gate.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            scenario.onActivity(a->{try{var method=DiagnosticsActivity.class.getDeclaredMethod("share");method.setAccessible(true);method.invoke(a);}catch(Exception error){throw new AssertionError(error);}});
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);gate.countDown();
            worker.get().submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);instrumentation.waitForIdleSync();
            assertFalse("Leaving the app must prevent a late share screen",opened.get());
        }finally{gate.countDown();instrumentation.removeMonitor(monitor);}
    }
    @Test public void rotationKeepsNewestEventsWithinBoundAndSurvivesNewInstance()throws Exception {
        File directory=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"diagnostics-test-"+System.nanoTime());
        try{
            Diagnostics log=new Diagnostics(directory);String payload=new String(new char[4000]).replace('\0','x');
            for(int i=0;i<200;i++)log.append("event="+i+" "+payload);
            long bytes=0;for(File file:directory.listFiles())bytes+=file.length();assertTrue(bytes<=2L*Diagnostics.LIMIT);
            ByteArrayOutputStream output=new ByteArrayOutputStream();new Diagnostics(directory).copyTo(output);String report=output.toString("UTF-8");
            assertTrue(report.contains("event=199 "));assertFalse(report.contains("event=0 "));assertTrue(report.endsWith("\n"));
        }finally{File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();}
    }
    @Test public void exceptionEvidenceRetainsFramesButNeverMessagesOrNestedTokens(){
        IOException cause=new IOException("Authorization: Bearer private-secret https://host/game.m3u8?token=secret");
        String result=Diagnostics.error(new IllegalStateException("password=secret",cause));
        assertTrue(result.contains("java.io.IOException"));assertTrue(result.contains("exceptionEvidenceRetainsFrames"));
        for(String secret:new String[]{"private-secret","https://","password=","token=","Authorization:"})assertFalse(secret,result.contains(secret));
    }
    @Test public void unusableStorageDoesNotThrowOrBreakPlayback()throws Exception {
        File file=File.createTempFile("diagnostics-blocked",".tmp",InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try{new Diagnostics(file).append("event");}finally{file.delete();}
    }
    @Test public void crashEvidenceIsImmediatelyExportableWithVersionAndDevice()throws Exception {
        Diagnostics.crash(new IllegalArgumentException("do-not-export-this-message"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();Diagnostics.export(InstrumentationRegistry.getInstrumentation().getTargetContext(),out);
        String report=out.toString(StandardCharsets.UTF_8.name());
        assertTrue(report.contains("ApeSports "+BuildConfig.VERSION_NAME));assertTrue(report.contains("Device:"));
        assertTrue(report.contains("CRASH java.lang.IllegalArgumentException"));assertFalse(report.contains("do-not-export-this-message"));
    }
    @Test public void localExportFallbackWritesAReadableReportWithoutAnotherApp()throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();File report=new File(context.getExternalFilesDir(null),"ApeSports-diagnostics.txt");report.delete();
        try(var scenario=androidx.test.core.app.ActivityScenario.launch(DiagnosticsActivity.class)){
            scenario.onActivity(activity->{try{var method=DiagnosticsActivity.class.getDeclaredMethod("saveOnTv");method.setAccessible(true);method.invoke(activity);}catch(Exception error){throw new AssertionError(error);}});
            long deadline=android.os.SystemClock.elapsedRealtime()+5000;
            String text="";
            do{
                if(report.isFile())try(InputStream input=new FileInputStream(report);ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);text=output.toString("UTF-8");
                }
                if(text.contains("Device:"))break;android.os.SystemClock.sleep(20);
            }while(android.os.SystemClock.elapsedRealtime()<deadline);
            assertTrue(text.contains("ApeSports "+BuildConfig.VERSION_NAME));assertTrue(text.contains("Device:"));
        }finally{report.delete();}
    }
}
