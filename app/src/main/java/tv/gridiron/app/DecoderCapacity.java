package tv.gridiron.app;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.HashMap;
import java.util.Locale;

/** Cached vendor hints, refined using the decoder actually initialized by Media3. */
final class DecoderCapacity {
    static final class Capacity {
        final long pixelsPerSecond; final int instances;
        Capacity(long pixels,int instances){pixelsPerSecond=pixels;this.instances=instances;}
    }
    private final HashMap<String,Capacity> cache=new HashMap<>();
    private static final Capacity FALLBACK=new Capacity(1280L*720*60,4);
    private MediaCodecInfo[] codecs;
    synchronized Capacity get(String name,String mime) {
        if(name==null||mime==null)return FALLBACK;
        String key=name+"/"+mime;
        Capacity cached=cache.get(key);if(cached!=null)return cached;
        Capacity result=FALLBACK;
        try {
            if(codecs==null)codecs=new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            for(MediaCodecInfo codec:codecs)if(codec.getName().equals(name)){
                MediaCodecInfo.CodecCapabilities caps=codec.getCapabilitiesForType(mime);
                String lower=name.toLowerCase(Locale.US);
                boolean hardware=Build.VERSION.SDK_INT>=29?codec.isHardwareAccelerated():
                    !(lower.startsWith("omx.google.")||lower.startsWith("c2.android.")||lower.contains("sw.decoder"));
                long budget=hardware?1920L*1080*60:1280L*720*60;
                if(hardware&&Build.VERSION.SDK_INT>=29&&caps.getVideoCapabilities()!=null){
                    var points=caps.getVideoCapabilities().getSupportedPerformancePoints();
                    long reported=0;
                    if(points!=null)for(var point:points)for(int height:new int[]{720,1080,2160})for(int fps:new int[]{30,60,120}){
                        int width=height*16/9;
                        if(point.covers(new MediaCodecInfo.VideoCapabilities.PerformancePoint(width,height,fps)))
                            reported=Math.max(reported,(long)width*height*fps);
                    }
                    if(reported>0)budget=reported*3/4; // Reserve headroom for composition and concurrent sessions.
                }
                result=new Capacity(budget,Math.max(1,caps.getMaxSupportedInstances()));break;
            }
        }catch(RuntimeException ignored){/* Missing or faulty vendor metadata: retain conservative fallback. */}
        cache.put(key,result);return result;
    }
}
