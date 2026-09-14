package tv.gridiron.app;

import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;

/** Bounded catalog fetches only. Never probes or opens a video stream. */
final class CatalogHttp {
    static final class HttpException extends IOException {
        final int status;HttpException(int status){super("Source returned HTTP "+status);this.status=status;}
    }
    static final class Result {final String body,url;Result(String body,String url){this.body=body;this.url=url;}}
    static Result fetch(String source,int limit,String userAgent)throws IOException{
        long deadline=System.nanoTime()+30_000_000_000L;
        for(int redirects=0;redirects<=5;redirects++){
            if(!FeedParser.validUrl(source))throw new IOException("Expected HTTP or HTTPS source URL");
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            HttpURLConnection c=(HttpURLConnection)new URL(source).openConnection();
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept-Encoding","gzip");
            try{
                int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){
                    String location=c.getHeaderField("Location");if(location==null)throw new IOException("Redirect has no destination");
                    String next=new URL(new URL(source),location).toString();
                    if(source.startsWith("https:")&&next.startsWith("http:"))throw new IOException("Source redirects to insecure HTTP");
                    source=next;continue;
                }
                if(status!=200)throw new HttpException(status);
                try(PushbackInputStream raw=new PushbackInputStream(c.getInputStream(),2)){
                    int a=raw.read(),b=raw.read();if(b>=0)raw.unread(b);if(a>=0)raw.unread(a);
                    InputStream in=a==0x1f&&b==0x8b?new GZIPInputStream(raw):raw;
                    try(ByteArrayOutputStream out=new ByteArrayOutputStream()){
                        byte[] bytes=new byte[8192];int n;
                        while((n=in.read(bytes))!=-1){
                            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                            if(System.nanoTime()>deadline)throw new IOException("Source download timed out");
                            if(out.size()+n>limit)throw new IOException("Source exceeds "+limit/1024/1024+" MB uncompressed");
                            out.write(bytes,0,n);
                        }
                        return new Result(out.toString("UTF-8"),source);
                    }
                }
            }finally{c.disconnect();}
        }
        throw new IOException("Too many source redirects");
    }
}
