package tv.gridiron.app;
import java.net.ServerSocket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.*;
import static org.junit.Assert.*;
public class CatalogHttpTest {
    ServerSocket server;String base;Thread worker;
    final java.util.Map<String,byte[]> responses=new java.util.concurrent.ConcurrentHashMap<>();
    @Before public void start()throws Exception{
        server=new ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort();
        worker=new Thread(()->{while(!server.isClosed())try(var socket=server.accept()){
            socket.setSoTimeout(2000);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String request=in.readLine(),line;while((line=in.readLine())!=null&&!line.isEmpty()){}
            byte[] response=responses.get(request.split(" ")[1]);if(response!=null)socket.getOutputStream().write(response);
        }catch(IOException ignored){}});worker.setDaemon(true);worker.start();
    }
    @After public void stop()throws Exception{server.close();worker.join(3000);}
    void serve(String path,byte[] body)throws IOException{
        var out=new ByteArrayOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);responses.put(path,out.toByteArray());
    }
    void redirect(String path,String to){responses.put(path,("HTTP/1.1 302 Found\r\nLocation: "+to+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));}
    @Test public void resolvesPlaylistAgainstRedirectDestination()throws Exception{
        redirect("/old","/new/list.m3u");
        serve("/new/list.m3u","#EXTM3U\n#EXTINF:-1,NFL\nstream".getBytes(StandardCharsets.UTF_8));
        var result=CatalogHttp.fetch(base+"/old",4096,"ApeSports-test");assertEquals(base+"/new/stream",FeedParser.parseM3u(result.body,result.url).get(0).url);
    }
    @Test public void readsGzipFileWithoutEncodingHeader()throws Exception{
        var bytes=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(bytes)){gzip.write("<tv/>".getBytes(StandardCharsets.UTF_8));}
        serve("/guide.xml.gz",bytes.toByteArray());assertEquals("<tv/>",CatalogHttp.fetch(base+"/guide.xml.gz",4096,"test").body);
    }
    @Test(expected=IOException.class) public void boundsUncompressedSize()throws Exception{
        var bytes=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(bytes)){gzip.write(new byte[8192]);}
        serve("/large",bytes.toByteArray());CatalogHttp.fetch(base+"/large",4096,"test");
    }
    @Test(expected=IOException.class) public void stopsRedirectLoop()throws Exception{
        redirect("/loop","/loop");CatalogHttp.fetch(base+"/loop",4096,"test");
    }
}
