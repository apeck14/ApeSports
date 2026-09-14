package tv.gridiron.app;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class BandwidthBudgetTest {
    @Test public void safetyMarginAppliedOnceAcrossAllGames(){
        BandwidthBudget b=new BandwidthBudget(4000000);b.setActiveMask(15);
        assertEquals(2800000,b.total());assertEquals(700000,b.share(0));
        assertEquals(b.total(),b.share(0)+b.share(1)+b.share(2)+b.share(3));
    }
    @Test public void reserveConnectingGamesBeforeCreatingPlayers(){
        BandwidthBudget b=new BandwidthBudget(4000000);b.setActiveMask(1);assertEquals(2800000,b.share(0));
        b.setActiveMask(15);assertEquals(700000,b.share(0));assertEquals(700000,b.share(3));
    }
    @Test public void hiddenAndEmptySlotsGetNoShare(){
        BandwidthBudget b=new BandwidthBudget(4000000);b.setActiveMask(5);
        assertEquals(1400000,b.share(0));assertEquals(1400000,b.share(2));assertEquals(0,b.share(1));
        b.setActiveMask(4);assertEquals(2800000,b.share(2));assertEquals(0,b.share(0));
        b.setActiveMask(0);assertEquals(0,b.total());assertEquals(0,b.share(2));
    }
    @Test public void estimateDropReducesImmediately(){
        BandwidthBudget b=new BandwidthBudget(4000000);b.setActiveMask(15);b.observe(1000000,100);
        assertEquals(700000,b.total());assertEquals(175000,b.share(0));
    }
    @Test public void startupAndNetworkResetDoNotTrustHugeInitialEstimate(){
        BandwidthBudget b=new BandwidthBudget(Long.MAX_VALUE);b.setActiveMask(1);assertEquals(3500000,b.total());
        b.observe(1000000,1);b.reset(200000000);assertEquals(3500000,b.total());
    }
    @Test public void healthyRiseRequiresTimeAndFreshSamples(){
        BandwidthBudget b=new BandwidthBudget(2000000);b.setActiveMask(15);
        b.observe(10000000,0);b.sample(0,true,false);b.sample(19999,true,false);assertEquals(1400000,b.total());
        b.sample(20000,true,false);assertEquals(1400000,b.total()); // stale sample
        b.observe(10000000,20001);b.sample(20001,true,false);assertEquals(1540000,b.total());
        b.sample(24999,true,false);assertEquals(1540000,b.total());
        b.sample(25001,true,false);assertEquals(1694000,b.total());
    }
    @Test public void pauseAndMembershipChangesResetHealthyInterval(){
        BandwidthBudget b=new BandwidthBudget(2000000);b.setActiveMask(15);b.observe(10000000,0);b.sample(0,true,false);
        b.sample(19999,false,false);b.observe(10000000,20000);b.sample(20000,true,false);assertEquals(1400000,b.total());
        b.setActiveMask(3);b.sample(40000,true,false);b.observe(10000000,40000);b.sample(40001,true,false);assertEquals(1400000,b.total());
    }
    @Test public void stallsReduceAtMostOncePerTenSeconds(){
        BandwidthBudget b=new BandwidthBudget(4000000);b.setActiveMask(15);
        b.sample(0,false,true);assertEquals(2100000,b.total());b.sample(9999,false,true);assertEquals(2100000,b.total());
        b.sample(10000,false,true);assertEquals(1575000,b.total());
    }
    @Test public void tinyOrInvalidEstimatesNeverCreateBandwidth(){
        BandwidthBudget b=new BandwidthBudget(-1);b.setActiveMask(15);assertEquals(0,b.total());
        b.observe(3,0);assertEquals(0,b.total());
        b.reset(3);assertEquals(2,b.total());assertEquals(0,b.share(0));
    }
    @Test public void replayAlwaysBoundsCombinedAllocatedMedia(){
        Random random=new Random(42);BandwidthBudget b=new BandwidthBudget(2000000);
        for(int n=0;n<10000;n++){
            b.setActiveMask(random.nextInt(16));long estimate=random.nextInt(50000000);
            b.observe(estimate,n*1000L);b.sample(n*1000L,random.nextBoolean(),false);
            long sum=0;for(int i=0;i<4;i++){assertTrue(b.share(i)>=0);sum+=b.share(i);}
            assertTrue(sum<=b.total());assertTrue(b.total()<=estimate*7/10);
        }
    }
}
