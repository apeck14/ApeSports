package tv.gridiron.app;

import android.os.Handler;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;

/** Read-only selection input; network transfers still report to the original shared meter once. */
@androidx.media3.common.util.UnstableApi
final class AllocatedBandwidthMeter implements BandwidthMeter {
    private final BandwidthBudget budget;
    private final int slot;
    private final BandwidthMeter source;
    AllocatedBandwidthMeter(BandwidthBudget budget,int slot,BandwidthMeter source){
        this.budget=budget;this.slot=slot;this.source=source;
    }
    @Override public long getBitrateEstimate(){return budget.share(slot);}
    @Override public long getTimeToFirstByteEstimateUs(){return source.getTimeToFirstByteEstimateUs();}
    @Override public TransferListener getTransferListener(){return null;}
    // AdaptiveTrackSelection polls this view for each decision; it does not subscribe to events.
    @Override public void addEventListener(Handler handler,EventListener listener){}
    @Override public void removeEventListener(EventListener listener){}
}
