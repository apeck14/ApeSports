package tv.gridiron.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoBudgetTest {
    private VideoBudget.Profile profile(int w,int h,int count,boolean expanded,int active,long budget){
        return VideoBudget.choose(w,h,count,expanded,active,60,budget,4,0);
    }
    @Test public void countsAssignedGamesInsteadOfEmptySlots(){
        long capacity=1920L*1080*60;
        assertEquals(1080,profile(3840,2160,4,false,1,capacity).height);
        assertEquals(540,profile(3840,2160,4,false,4,capacity).height);
    }
    @Test public void physical4kOutputAllows1080Tiles(){
        assertEquals(1080,profile(3840,2160,4,false,4,3840L*2160*60).height);
        assertEquals(540,profile(1920,1080,4,false,4,3840L*2160*60).height);
    }
    @Test public void stackedAndQuadrantVideoAreasMatch(){
        assertEquals(1080,profile(3840,2160,2,false,2,Long.MAX_VALUE).height);
        assertEquals(1080,profile(3840,2160,4,false,4,Long.MAX_VALUE).height);
    }
    @Test public void expansionUsesFullViewportAndOneDecoder(){
        assertEquals(2160,profile(3840,2160,4,true,1,3840L*2160*60).height);
    }
    @Test public void thirtyFpsLadderCanUseMorePixels(){
        long capacity=3840L*2160*30;
        assertEquals(1080,profile(3840,2160,1,false,1,capacity).height);
        assertEquals(2160,VideoBudget.choose(3840,2160,1,false,1,30,capacity,4,0).height);
    }
    @Test public void instanceShortfallAndUnknownCapacityStayConservative(){
        assertEquals(540,VideoBudget.choose(3840,2160,4,false,4,60,Long.MAX_VALUE,2,0).height);
        assertEquals(360,profile(3840,2160,4,false,4,0).height);
    }
    @Test public void reductionsArePromptButRateLimited(){
        VideoBudget.Health health=new VideoBudget.Health();
        health.bad(100);assertEquals(1,health.penalty);
        health.bad(500);assertEquals(1,health.penalty);
        health.bad(10100);assertEquals(2,health.penalty);
        assertEquals(360,VideoBudget.choose(3840,2160,4,false,4,60,Long.MAX_VALUE,4,3).height);
    }
    @Test public void recoveryRequiresContinuousHealthyMinute(){
        VideoBudget.Health health=new VideoBudget.Health();health.bad(0);
        health.sample(1000,true);health.sample(60000,true);assertEquals(1,health.penalty);
        health.sample(61000,false);health.sample(62000,true);health.sample(121999,true);assertEquals(1,health.penalty);
        health.sample(122000,true);assertEquals(0,health.penalty);
    }
}
