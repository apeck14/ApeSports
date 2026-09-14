package tv.gridiron.app;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackMetricsTest {
    @Test public void prerenderedFrameDoesNotTurnInitialBufferingIntoAStall(){
        PlaybackMetrics m=new PlaybackMetrics(0);m.state(0,false,true,true);m.firstFrame(100);
        m.state(200,false,true,true);m.state(1000,true,true,false);
        assertTrue(m.summary(2000).contains("rebufferMs=0"));assertTrue(m.summary(2000).contains("stalls=0"));
    }
    @Test public void startupExcludesPauseAndIsNotARebuffer(){
        PlaybackMetrics metrics=new PlaybackMetrics(0);
        metrics.state(0,false,true,true);metrics.state(1000,false,false,true);
        metrics.state(6000,false,true,true);assertTrue(metrics.firstFrame(6500));assertFalse(metrics.firstFrame(6600));
        assertEquals(1500,metrics.startupActiveMs);assertTrue(metrics.summary(6600).contains("stalls=0"));
    }
    @Test public void rebufferDurationsExcludePauseAndSnapshotsDoNotDoubleCount(){
        PlaybackMetrics m=new PlaybackMetrics(0);m.state(0,true,true,false);m.firstFrame(0);
        m.state(1000,false,true,true);m.summary(1500);m.summary(1500);m.state(2000,false,false,true);
        m.state(7000,false,true,true);m.state(7500,true,true,false);
        String totals=m.summary(8500);assertTrue(totals.contains("playingMs=2000"));assertTrue(totals.contains("rebufferMs=1500"));assertTrue(totals.contains("stalls=2"));
    }
    @Test public void repeatedFailuresDoNotResetRecoveryAndUnfinishedRecoveryIsVisible(){
        PlaybackMetrics m=new PlaybackMetrics(0);m.error(1000);m.error(3000);
        assertTrue(m.summary(4000).contains("recoveryPending=true"));m.state(5000,true,true,false);
        String totals=m.summary(6000);assertTrue(totals.contains("errors=2"));assertTrue(totals.contains("recoveries=1"));assertTrue(totals.contains("recoveredWallMs=4000"));
    }
    @Test public void initialAndDuplicateFormatsAreNotQualitySwitches(){
        PlaybackMetrics m=new PlaybackMetrics(0);m.format("720p");m.format("720p");m.format("1080p");
        assertTrue(m.summary(1000).contains("formatChanges=1"));
    }
}
