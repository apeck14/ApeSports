package tv.gridiron.app;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/** A release descriptor plus a bounded, verified APK download. No background installer. */
final class AppUpdate {
    static final String MANIFEST="https://github.com/apeck14/ApeSports/releases/latest/download/update.json";
    static final long MAX_BYTES=50L*1024*1024;
    final int versionCode;final String versionName,url,sha256;final long bytes;
    AppUpdate(String json)throws JSONException {
        JSONObject data=new JSONObject(json);
        versionCode=data.getInt("versionCode");versionName=data.getString("versionName");
        url=data.getString("apkUrl");sha256=data.getString("sha256").toLowerCase(Locale.US);bytes=data.getLong("bytes");
        if(versionCode<=0||versionName.isEmpty()||versionName.length()>64||bytes<=0||bytes>MAX_BYTES||!sha256.matches("[0-9a-f]{64}")||!releaseUrl(url))
            throw new JSONException("Invalid update descriptor");
    }
    static boolean releaseUrl(String url){
        try{URI u=new URI(url);return "https".equals(u.getScheme())&&"github.com".equals(u.getHost())&&u.getPort()==-1&&u.getUserInfo()==null&&u.getQuery()==null&&u.getFragment()==null
            &&u.getPath().matches("/apeck14/ApeSports/releases/download/[^/]+/ApeSports\\.apk")&&!u.getPath().contains("..");
        }catch(Exception error){return false;}
    }
    static AppUpdate latest()throws IOException,JSONException {
        try{return new AppUpdate(CatalogHttp.fetch(MANIFEST,16384,"ApeSports/"+BuildConfig.VERSION_NAME).body);}
        catch(CatalogHttp.HttpException error){if(error.status==404)return null;throw error;}
    }
    boolean newer(){return versionCode>BuildConfig.VERSION_CODE;}
    static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format(Locale.US,"%02x",value&255));return text.toString();}
    static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    static Set<String> signers(PackageInfo info){
        Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo==null?null:info.signingInfo.getApkContentsSigners()):info.signatures;
        Set<String> result=new HashSet<>();if(signatures!=null)for(Signature signature:signatures)result.add(signature.toCharsString());return result;
    }
    void verifyPackage(Context context,File apk)throws IOException {
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags);
        try{
            PackageInfo installed=pm.getPackageInfo(context.getPackageName(),flags);
            if(candidate==null||!context.getPackageName().equals(candidate.packageName)||code(candidate)!=versionCode||code(candidate)<=code(installed)
                    ||signers(candidate).isEmpty()||!signers(candidate).equals(signers(installed)))throw new IOException("This update does not match the installed app or its signing key.");
        }catch(PackageManager.NameNotFoundException error){throw new IOException("Could not verify the installed app",error);}
    }
    interface Progress {void percent(int value);}
    static final class Download {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;
        void cancel(){cancelled=true;HttpURLConnection current=connection;if(current!=null)current.disconnect();}
        void copyVerified(InputStream input,File apk,AppUpdate update,Progress progress,long deadline)throws Exception {
            boolean verified=false;
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long read=0;int last=-1;
                try(OutputStream output=new FileOutputStream(apk)){
                    byte[] buffer=new byte[65536];int count;
                    while((count=input.read(buffer))!=-1){
                        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                        if(System.nanoTime()>deadline)throw new IOException("Update download timed out");
                        read+=count;if(read>update.bytes)throw new IOException("Update size mismatch");
                        output.write(buffer,0,count);digest.update(buffer,0,count);
                        int percent=(int)(read*100/update.bytes);if(percent!=last){last=percent;progress.percent(percent);}
                    }
                }
                if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(read!=update.bytes||!hex(digest.digest()).equals(update.sha256))throw new IOException("Update verification failed. Try downloading again.");
                verified=true;
            }finally{if(!verified)apk.delete();}
        }
        File run(Context context,AppUpdate update,Progress progress)throws Exception {
            File directory=new File(context.getCacheDir(),"updates");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Not enough storage for the update.");
            File apk=new File(directory,"ApeSports.apk");boolean complete=false;
            try{
                String url=update.url;long deadline=System.nanoTime()+300_000_000_000L;
                for(int redirect=0;redirect<=5;redirect++){
                    if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null)throw new IOException("Update requires HTTPS");
                    HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();connection=c;
                    c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Accept-Encoding","identity");
                    try{
                        if(cancelled)throw new InterruptedIOException();
                        int status=c.getResponseCode();
                        if(status==301||status==302||status==303||status==307||status==308){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Invalid update redirect");url=uri.resolve(location).toString();continue;}
                        if(status!=200)throw new IOException("Update download returned HTTP "+status);
                        try(InputStream input=c.getInputStream()){
                            copyVerified(input,apk,update,progress,deadline);
                        }
                        update.verifyPackage(context,apk);if(cancelled)throw new InterruptedIOException();complete=true;return apk;
                    }finally{c.disconnect();connection=null;}
                }
                throw new IOException("Too many update redirects");
            }finally{if(!complete&&apk.exists())apk.delete();}
        }
    }
}
