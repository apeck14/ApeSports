package tv.gridiron.app;

import android.os.SystemClock;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import com.google.common.collect.ImmutableList;

/** Prefer affordable motion detail within the device-eligible adaptive group. */
@androidx.media3.common.util.UnstableApi
final class SportsTrackSelection extends AdaptiveTrackSelection {
    static final class Factory extends AdaptiveTrackSelection.Factory {
        private final BandwidthBudget budget;
        private final int slot;
        Factory(BandwidthBudget budget,int slot){this.budget=budget;this.slot=slot;}
        @Override protected AdaptiveTrackSelection createAdaptiveTrackSelection(TrackGroup group,int[] tracks,int type,
                BandwidthMeter meter,ImmutableList<AdaptationCheckpoint> checkpoints){
            return new SportsTrackSelection(group,tracks,type,new AllocatedBandwidthMeter(budget,slot,meter),checkpoints);
        }
    }
    SportsTrackSelection(TrackGroup group,int[] tracks,int type,BandwidthMeter meter,
                         ImmutableList<AdaptationCheckpoint> checkpoints){
        super(group,tracks,type,meter,DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
            // Media3 defers a reduction when buffer >= this threshold; make it unreachable.
            Long.MAX_VALUE/1000,DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
            DEFAULT_MAX_WIDTH_TO_DISCARD,DEFAULT_MAX_HEIGHT_TO_DISCARD,1f,
            DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,checkpoints,Clock.DEFAULT);
    }
    @Override protected boolean canSelectFormat(Format format,int bitrate,long available){
        if(!super.canSelectFormat(format,bitrate,available))return false;
        if(format.frameRate>=49||format.sampleMimeType==null||!format.sampleMimeType.startsWith("video/"))return true;
        long now=SystemClock.elapsedRealtime();
        for(int i=0;i<length;i++){
            Format candidate=getFormat(i);
            if(candidate.frameRate>=49&&candidate.bitrate>0&&candidate.bitrate<=available&&!isTrackExcluded(i,now))return false;
        }
        return true; // Includes missing frame-rate metadata and constrained-bandwidth fallbacks.
    }
}
