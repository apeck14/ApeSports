package tv.gridiron.app;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

@androidx.media3.common.util.UnstableApi
final class GuardedRenderersFactory extends DefaultRenderersFactory {
    static final class AdmissionException extends IOException {AdmissionException(String reason){super(reason);}}
    private final MediaCodecAdapter.Factory guarded;
    GuardedRenderersFactory(Context context,int slot,DecoderAdmission admission,DecoderCapacity capacity){
        super(context);
        setEnableDecoderFallback(false); // Do not silently retry a failed hardware codec in software.
        setMediaCodecSelector((mime,secure,tunneling)->{
            var candidates=new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunneling));
            if(mime.startsWith("video/"))Collections.sort(candidates,(a,b)->Boolean.compare(b.hardwareAccelerated,a.hardwareAccelerated));
            return Collections.unmodifiableList(candidates);
        });
        MediaCodecAdapter.Factory delegate=MediaCodecAdapter.Factory.getDefault(context);
        guarded=configuration->{
            var codec=configuration.codecInfo;Format format=configuration.format;
            boolean video=codec.mimeType.startsWith("video/");
            try {
                if(!codec.isFormatSupported(format))throw new AdmissionException("Codec does not support this profile, size, or frame rate");
            }catch(androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException error){
                throw new IOException("Could not query decoder support",error);
            }
            DecoderCapacity.Capacity hint=capacity.get(codec.name,codec.mimeType);
            DecoderAdmission.Request request=new DecoderAdmission.Request(codec.name,video,codec.hardwareAccelerated,
                codec.getMaxSupportedInstances()>0?codec.getMaxSupportedInstances():1,
                video?workload(format):0,hint.pixelsPerSecond);
            String denied=admission.reserve(slot,request);
            if(denied!=null)throw new AdmissionException(denied);
            boolean success=false;
            try {MediaCodecAdapter adapter=delegate.createAdapter(configuration);success=true;return adapter;}
            finally {if(!success)admission.cancel(slot,request);}
        };
    }
    static long workload(Format f){return (long)(Math.max(1,f.width>0?f.width:1920)*(double)(f.height>0?f.height:1080)*(f.frameRate>0?f.frameRate:60));}
    @Override protected MediaCodecAdapter.Factory getCodecAdapterFactory(){return guarded;}
}
