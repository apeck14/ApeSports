package tv.gridiron.app;

import android.content.Context;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppUpdateTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static JSONObject descriptor(byte[] data)throws Exception {
        return new JSONObject().put("versionCode",BuildConfig.VERSION_CODE+1).put("versionName","next")
            .put("apkUrl","https://github.com/apeck14/ApeSports/releases/download/vNext/ApeSports.apk")
            .put("sha256",AppUpdate.hex(MessageDigest.getInstance("SHA-256").digest(data))).put("bytes",data.length);
    }
    private File output()throws Exception {return File.createTempFile("update-test", ".apk",context.getCacheDir());}
    private void rejects(byte[] source,JSONObject descriptor,AppUpdate.Download download,long deadline)throws Exception {
        File file=output();try {
            try{download.copyVerified(new ByteArrayInputStream(source),file,new AppUpdate(descriptor.toString()),p->{},deadline);fail("Must reject invalid or interrupted download");}
            catch(IOException expected){assertFalse("Partial APK must be deleted",file.exists());}
        }finally{file.delete();}
    }
    @Test public void descriptorAllowsOnlyBoundedApksFromOurReleaseRepository()throws Exception {
        JSONObject valid=descriptor(new byte[]{1});assertTrue(new AppUpdate(valid.toString()).newer());
        String[] urls={"http://github.com/apeck14/ApeSports/releases/download/v1/ApeSports.apk",
            "https://github.com/other/ApeSports/releases/download/v1/ApeSports.apk",
            "https://github.com.evil.test/apeck14/ApeSports/releases/download/v1/ApeSports.apk",
            "https://github.com/apeck14/ApeSports/releases/download/../ApeSports.apk",
            "https://github.com/apeck14/ApeSports/releases/download/v1/ApeSports.apk?other=1"};
        for(String url:urls)assertFalse(url,AppUpdate.releaseUrl(url));
        for(Object[] field:new Object[][]{{"bytes",0},{"bytes",AppUpdate.MAX_BYTES+1},{"sha256","bad"},{"versionCode",0},{"versionName",""}}){
            JSONObject invalid=new JSONObject(valid.toString()).put((String)field[0],field[1]);
            try{new AppUpdate(invalid.toString());fail("Invalid "+field[0]);}catch(org.json.JSONException expected){}
        }
        assertFalse(new AppUpdate(valid.put("versionCode",BuildConfig.VERSION_CODE).toString()).newer());
    }
    @Test public void verifiedCopyPreservesBytesAndReportsMonotonicProgress()throws Exception {
        byte[] data=new byte[200_000];new Random(17).nextBytes(data);List<Integer> progress=new ArrayList<>();File file=output();
        try {
            new AppUpdate.Download().copyVerified(new ByteArrayInputStream(data),file,new AppUpdate(descriptor(data).toString()),progress::add,Long.MAX_VALUE);
            ByteArrayOutputStream actual=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)actual.write(buffer,0,n);}
            assertArrayEquals(data,actual.toByteArray());assertEquals(Integer.valueOf(100),progress.get(progress.size()-1));
            for(int i=1;i<progress.size();i++)assertTrue(progress.get(i)>progress.get(i-1));
        }finally{file.delete();}
    }
    @Test public void corruptTruncatedAndOversizedDownloadsAreRemoved()throws Exception {
        byte[] expected={1,2,3};JSONObject description=descriptor(expected);
        rejects(new byte[]{1,2,4},description,new AppUpdate.Download(),Long.MAX_VALUE);
        rejects(new byte[]{1,2},description,new AppUpdate.Download(),Long.MAX_VALUE);
        rejects(new byte[]{1,2,3,4},description,new AppUpdate.Download(),Long.MAX_VALUE);
    }
    @Test public void cancellationAndDeadlineRemoveIncompleteApks()throws Exception {
        byte[] data={1,2,3};AppUpdate.Download cancelled=new AppUpdate.Download();cancelled.cancel();
        rejects(data,descriptor(data),cancelled,Long.MAX_VALUE);
        rejects(data,descriptor(data),new AppUpdate.Download(),0);
        File file=output();AppUpdate.Download during=new AppUpdate.Download();
        try {
            byte[] large=new byte[200_000];
            try{during.copyVerified(new ByteArrayInputStream(large),file,new AppUpdate(descriptor(large).toString()),p->during.cancel(),Long.MAX_VALUE);fail();}
            catch(InterruptedIOException expected){assertFalse(file.exists());}
        }finally{file.delete();}
    }
    @Test public void archiveValidationRejectsSameVersionAndNonApks()throws Exception {
        File installed=new File(context.getApplicationInfo().sourceDir);
        AppUpdate same=new AppUpdate(descriptor(new byte[]{1}).put("versionCode",BuildConfig.VERSION_CODE).toString());
        try{same.verifyPackage(context,installed);fail("Must not reinstall same version");}catch(IOException expected){}
        AppUpdate next=new AppUpdate(descriptor(new byte[]{1}).toString());
        try{next.verifyPackage(context,installed);fail("Manifest must match actual APK version");}catch(IOException expected){}
        File junk=output();try{next.verifyPackage(context,junk);fail("Must reject a non-APK");}catch(IOException expected){}finally{junk.delete();}
    }
    @Test public void fileProviderExposesOnlyUpdateCacheAndCanReadApk()throws Exception {
        File directory=new File(context.getCacheDir(),"updates");directory.mkdirs();File apk=new File(directory,"provider-test.apk");
        try {
            try(OutputStream out=new FileOutputStream(apk)){out.write(new byte[]{9,8,7});}
            var uri=FileProvider.getUriForFile(context,context.getPackageName()+".updates",apk);
            assertEquals("content",uri.getScheme());
            try(InputStream in=context.getContentResolver().openInputStream(uri)){assertEquals(9,in.read());assertEquals(8,in.read());assertEquals(7,in.read());assertEquals(-1,in.read());}
            File outside=new File(context.getFilesDir(),"private.json");
            try{FileProvider.getUriForFile(context,context.getPackageName()+".updates",outside);fail("Private files must not be shareable");}catch(IllegalArgumentException expected){}
        }finally{apk.delete();}
    }
    @Test public void installerHandoffUsesContentUriAndReadPermission()throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        File directory=new File(context.getCacheDir(),"updates");directory.mkdirs();File apk=new File(directory,"handoff-test.apk");
        try(OutputStream out=new FileOutputStream(apk)){out.write(42);}
        var captured=new java.util.concurrent.atomic.AtomicReference<android.content.Intent>();
        var monitor=new android.app.Instrumentation.ActivityMonitor(){
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(android.content.Intent intent){
                captured.set(intent);return new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null);
            }
        };
        try(var scenario=androidx.test.core.app.ActivityScenario.launch(UpdateActivity.class)){
            instrumentation.addMonitor(monitor);
            scenario.onActivity(activity->{try{
                var cancel=UpdateActivity.class.getDeclaredMethod("cancel");cancel.setAccessible(true);cancel.invoke(activity);
                var field=UpdateActivity.class.getDeclaredField("apk");field.setAccessible(true);field.set(activity,apk);activity.install();
            }catch(Exception error){throw new AssertionError(error);}});
            var intent=captured.get();assertNotNull("Must hand off to Android",intent);
            assertEquals(android.content.Intent.ACTION_VIEW,intent.getAction());
            assertEquals("application/vnd.android.package-archive",intent.getType());
            assertEquals("content",intent.getData().getScheme());
            assertTrue((intent.getFlags()&android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            try(InputStream input=context.getContentResolver().openInputStream(intent.getData())){assertEquals(42,input.read());}
        }finally{
            instrumentation.removeMonitor(monitor);apk.delete();
        }
    }
}
