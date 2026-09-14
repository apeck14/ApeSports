package tv.gridiron.app;

import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class SportsTrackSelectionTest {
    private Format video(int height,float fps,int bitrate){return new Format.Builder().setSampleMimeType("video/avc")
        .setWidth(height*16/9).setHeight(height).setFrameRate(fps).setAverageBitrate(bitrate).build();}
    private SportsTrackSelection selection(Format... formats){
        int[] indices=new int[formats.length];for(int i=0;i<indices.length;i++)indices[i]=i;
        return new SportsTrackSelection(new TrackGroup(formats),indices,0,
            DefaultBandwidthMeter.getSingletonInstance(InstrumentationRegistry.getInstrumentation().getTargetContext()),ImmutableList.of());
    }
    @Test public void affordable60FpsBeatsHigherResolution30Fps(){
        Format hd=video(720,59.94f,5000000),full=video(1080,30,7000000);
        SportsTrackSelection s=selection(hd,full);
        assertFalse(s.canSelectFormat(full,7000000,8000000));assertTrue(s.canSelectFormat(hd,5000000,8000000));
    }
    @Test public void bandwidthFallbackRemainsAvailable(){
        Format hd=video(720,60,5000000),low=video(360,30,1000000);
        SportsTrackSelection s=selection(hd,low);
        assertTrue(s.canSelectFormat(low,1000000,2000000));assertFalse(s.canSelectFormat(hd,5000000,2000000));
    }
    @Test public void missingFrameRatesDoNotRejectPlayableLadder(){
        Format unknown=video(720,-1,3000000),low=video(360,30,1000000);
        assertTrue(selection(unknown,low).canSelectFormat(unknown,3000000,4000000));
    }
    @Test public void fiftyFpsGetsMotionPreference(){
        Format smooth=video(720,50,5000000),full=video(1080,25,7000000);
        assertFalse(selection(smooth,full).canSelectFormat(full,7000000,8000000));
    }
    @Test public void absentVendorMetadataUsesStableFallback(){
        DecoderCapacity capacity=new DecoderCapacity();
        assertEquals(1280L*720*60,capacity.get(null,null).pixelsPerSecond);
        DecoderCapacity.Capacity missing=capacity.get("missing.decoder","video/avc");
        assertEquals(1280L*720*60,missing.pixelsPerSecond);
        assertSame(missing,capacity.get("missing.decoder","video/avc"));
    }
    @Test public void frameRateMetadataUsesOnlySupportedVideoAndRetainsUnknownFallback(){
        var group=new TrackGroup(video(720,30,3000000),video(720,60,5000000));
        var tracks=new androidx.media3.common.Tracks(ImmutableList.of(new androidx.media3.common.Tracks.Group(group,true,
            new int[]{androidx.media3.common.C.FORMAT_HANDLED,androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE},new boolean[]{true,false})));
        assertEquals(30f,MainActivity.sourceFrameRate(tracks),0f);
        var unknown=new TrackGroup(video(720,30,3000000),video(720,-1,5000000));
        tracks=new androidx.media3.common.Tracks(ImmutableList.of(new androidx.media3.common.Tracks.Group(unknown,true,
            new int[]{androidx.media3.common.C.FORMAT_HANDLED,androidx.media3.common.C.FORMAT_HANDLED},new boolean[]{true,false})));
        assertEquals(60f,MainActivity.sourceFrameRate(tracks),0f);assertEquals(60f,MainActivity.sourceFrameRate(androidx.media3.common.Tracks.EMPTY),0f);
    }
}
