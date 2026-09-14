package tv.gridiron.app;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real pinned Media3 selectors with controlled aggregate-throughput traces, no network timing noise. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class CoordinatedSelectionTest {
    private static final int[] RATES={150000,300000,600000,1200000,2400000};
    private ExoTrackSelection[] create(BandwidthBudget budget,int slot,boolean adaptiveAudio){
        Format[] ladder=new Format[RATES.length];int[] indices=new int[RATES.length];
        for(int i=0;i<ladder.length;i++){
            indices[i]=i;ladder[i]=new Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180)
                .setFrameRate(60).setAverageBitrate(RATES[i]).build();
        }
        Format low=new Format.Builder().setSampleMimeType("audio/mp4a-latm").setAverageBitrate(64000).build();
        Format high=low.buildUpon().setAverageBitrate(128000).build();
        ExoTrackSelection.Definition[] definitions={new ExoTrackSelection.Definition(new TrackGroup("video",ladder),indices),
            adaptiveAudio?new ExoTrackSelection.Definition(new TrackGroup("audio",low,high),0,1):
                new ExoTrackSelection.Definition(new TrackGroup("audio",high),0)};
        return new SportsTrackSelection.Factory(budget,slot).createTrackSelections(definitions,
            DefaultBandwidthMeter.getSingletonInstance(InstrumentationRegistry.getInstrumentation().getTargetContext()),
            new MediaPeriodId(new Object()),Timeline.EMPTY);
    }
    private long select(ExoTrackSelection[] selections){
        long sum=0;
        for(ExoTrackSelection selection:selections){
            MediaChunkIterator[] iterators=new MediaChunkIterator[selection.length()];
            java.util.Arrays.fill(iterators,MediaChunkIterator.EMPTY);
            selection.updateSelectedTrack(0,60000000,C.TIME_UNSET,Collections.emptyList(),iterators);
            sum+=selection.getSelectedFormat().bitrate;
        }
        return sum;
    }
    @Test public void allFourSelectorsFitOneBudgetIncludingMutedAudio(){
        BandwidthBudget b=new BandwidthBudget(5000000);b.setActiveMask(15);long sum=0;
        for(int i=0;i<4;i++){long demand=select(create(b,i,false));assertTrue(demand<=b.share(i));sum+=demand;}
        assertTrue(sum<=b.total());assertEquals(2912000,sum);
    }
    @Test public void reductionAffectsNextSelectionDespiteSixtySecondBuffer(){
        BandwidthBudget b=new BandwidthBudget(5000000);b.setActiveMask(15);
        ExoTrackSelection[][] players=new ExoTrackSelection[4][];
        for(int i=0;i<4;i++){players[i]=create(b,i,false);select(players[i]);}
        b.observe(2000000,1000);long sum=0;
        for(int i=0;i<4;i++){long demand=select(players[i]);assertTrue(demand<=b.share(i));sum+=demand;}
        assertEquals(1112000,sum);assertTrue(sum<=b.total());
    }
    @Test public void marginIsNotAppliedAgainInsideSelector(){
        BandwidthBudget b=new BandwidthBudget(4000000);b.setActiveMask(1);
        ExoTrackSelection[] selections=create(b,0,false);select(selections);
        assertEquals(2400000,selections[0].getSelectedFormat().bitrate);
    }
    @Test public void adaptiveAudioAndVideoUseSamePerGameCheckpoints(){
        BandwidthBudget b=new BandwidthBudget(5000000);b.setActiveMask(15);long sum=0;
        for(int i=0;i<4;i++){long demand=select(create(b,i,true));assertTrue(demand<=b.share(i));sum+=demand;}
        assertTrue(sum<=b.total());
    }
    @Test public void expansionAndReturnReallocateBeforeNextLoads(){
        BandwidthBudget b=new BandwidthBudget(5000000);b.setActiveMask(15);
        ExoTrackSelection[] selected=create(b,2,false);select(selected);
        b.setActiveMask(4);assertEquals(0,b.share(0));assertTrue(select(selected)<=b.total());
        b.setActiveMask(15);assertTrue(select(selected)<=b.share(2));
    }
    @Test public void oscillatingCapacityCannotCauseUnboundedJointUpshifts(){
        BandwidthBudget b=new BandwidthBudget(5000000);b.setActiveMask(15);
        ExoTrackSelection[][] players=new ExoTrackSelection[4][];for(int i=0;i<4;i++)players[i]=create(b,i,false);
        for(int second=0;second<120;second++){
            long raw=second%30<10?2000000:20000000;
            b.observe(raw,second*1000L);b.sample(second*1000L,true,false);
            long sum=0;for(int i=0;i<4;i++)sum+=select(players[i]);
            assertTrue("Joint rendition demand at second "+second,sum<=b.total());
        }
    }
    @Test public void impossibleBudgetKeepsLowestPlayableRendition(){
        BandwidthBudget b=new BandwidthBudget(100000);b.setActiveMask(1);
        ExoTrackSelection[] selections=create(b,0,false);
        assertTrue(select(selections)>b.total());assertEquals(150000,selections[0].getSelectedFormat().bitrate);
    }
}
