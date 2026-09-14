package tv.gridiron.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class DecoderAdmissionTest {
    private DecoderAdmission.Request video(String name,boolean hardware,int instances,long pixels,long budget){
        return new DecoderAdmission.Request(name,true,hardware,instances,pixels,budget);
    }
    @Test public void reservationsCountBeforeInitializationCompletes(){
        DecoderAdmission d=new DecoderAdmission();
        assertNull(d.reserve(0,video("avc",true,1,100,10000)));
        assertNotNull(d.reserve(1,video("avc",true,1,100,10000)));
        assertEquals(1,d.videoCount());
    }
    @Test public void concurrentRequestsCannotOverbookAnInstance() throws Exception {
        DecoderAdmission d=new DecoderAdmission();AtomicInteger admitted=new AtomicInteger();CountDownLatch go=new CountDownLatch(1);
        Thread[] threads=new Thread[4];
        for(int i=0;i<4;i++){final int slot=i;threads[i]=new Thread(()->{try{go.await();if(d.reserve(slot,video("avc",true,2,100,10000))==null)admitted.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}});threads[i].start();}
        go.countDown();for(Thread t:threads)t.join();assertEquals(2,admitted.get());assertEquals(2,d.videoCount());
    }
    @Test public void releaseAndFailedAllocationReturnPermits(){
        DecoderAdmission d=new DecoderAdmission();var r=video("avc",true,1,100,10000);d.reserve(0,r);d.cancel(0,r);
        assertEquals(0,d.videoCount());assertNull(d.reserve(1,r));d.release(1);assertEquals(0,d.videoCount());
    }
    @Test public void codecReplacementDoesNotConsumeAnotherSlot(){
        DecoderAdmission d=new DecoderAdmission();DecoderAdmission.Request old=video("avc",true,1,100,10000),replacement=video("avc",true,1,200,10000);
        d.reserve(0,old);assertNull(d.reserve(0,replacement));d.cancel(0,old);assertSame(replacement,d.video(0));
    }
    @Test public void mixedCodecsStillShareTotalWorkload(){
        DecoderAdmission d=new DecoderAdmission();d.reserve(0,video("avc",true,4,600,1000));
        assertNotNull(d.reserve(1,video("hevc",true,4,600,1000)));
        assertNull(d.reserve(1,video("hevc",true,4,400,1000)));
    }
    @Test public void heavySoftwareMultiviewIsDeniedButSmallFixturesFit(){
        DecoderAdmission d=new DecoderAdmission();
        assertNull(d.reserve(0,video("software",false,4,1280L*720*30,Long.MAX_VALUE)));
        assertNotNull(d.reserve(1,video("software",false,4,1280L*720*30,Long.MAX_VALUE)));
        d.release(0);for(int i=0;i<4;i++)assertNull(d.reserve(i,video("software",false,4,320L*180*30,Long.MAX_VALUE)));
    }
    @Test public void audioInstancesAlsoHaveAdmissionLimits(){
        DecoderAdmission d=new DecoderAdmission();var audio=new DecoderAdmission.Request("aac",false,false,1,0,0);
        assertNull(d.reserve(0,audio));assertNotNull(d.reserve(1,audio));assertEquals(0,d.videoCount());d.release(0);assertNull(d.reserve(1,audio));
    }
    @Test public void sessionFallbackPreventsAutomaticLoadRegrowth(){
        DecoderAdmission d=new DecoderAdmission();d.restrict(2);
        for(int i=0;i<2;i++)assertNull(d.reserve(i,video("avc",true,4,100,10000)));
        assertNotNull(d.reserve(2,video("avc",true,4,100,10000)));d.restrict(4);assertEquals(2,d.limit());
    }
    @Test public void isolatedDropEventsDoNotShedGames(){
        DecoderAdmission d=new DecoderAdmission();assertFalse(d.dropped(0,0,1000,10,60));
        assertFalse(d.dropped(0,5000,1000,10,60));assertTrue(d.dropped(0,10000,1000,10,60));
        assertFalse(d.dropped(0,11000,1000,0,60));assertFalse(d.dropped(0,12000,1000,10,60));
    }
    @Test public void pressureFallbackHasCooldown(){
        DecoderAdmission d=new DecoderAdmission();assertTrue(d.mayShed(0));assertFalse(d.mayShed(19999));assertTrue(d.mayShed(20000));
    }
    @Test public void onlyOneAudioDecoderAcrossDifferentCodecNames(){
        DecoderAdmission d=new DecoderAdmission();
        assertNull(d.reserve(0,new DecoderAdmission.Request("aac",false,false,32,0,0)));
        assertNotNull(d.reserve(1,new DecoderAdmission.Request("opus",false,false,32,0,0)));
        assertEquals(1,d.audioCount());d.releaseAudio(0);
        assertNull(d.reserve(1,new DecoderAdmission.Request("opus",false,false,32,0,0)));assertEquals(1,d.audioCount());
    }
    @Test public void batchedDropsWithLongIntervalsStillDetectSustainedTrouble(){
        DecoderAdmission d=new DecoderAdmission();
        assertFalse(d.dropped(0,16000,16000,50,60));
        assertFalse(d.dropped(0,32000,16000,50,60));
        assertTrue(d.dropped(0,48000,16000,50,60));
    }
}
