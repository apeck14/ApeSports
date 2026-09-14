package tv.gridiron.app;

/** One aggregate media budget. Synchronized because selection runs on playback threads. */
final class BandwidthBudget {
    private int mask;
    private long estimate,budget,lastSample=-1,healthySince=-1,lastIncrease=-1,lastCongestion=-10000;
    BandwidthBudget(long initialEstimate){reset(initialEstimate);}
    private static long safe(long value){return Math.max(0,Math.min(value,1_000_000_000_000L));}
    private static long usable(long value){return safe(value)*7/10;}
    synchronized void reset(long initialEstimate){
        estimate=safe(initialEstimate);budget=usable(Math.min(estimate,5_000_000));
        lastSample=-1;healthySince=-1;lastIncrease=-1;lastCongestion=-10000;
    }
    synchronized void setActiveMask(int value){
        value&=15;
        if(mask!=value){mask=value;healthySince=-1;}
    }
    synchronized void observe(long rawEstimate,long now){
        estimate=safe(rawEstimate);lastSample=now;
        if(usable(estimate)<budget){budget=usable(estimate);healthySince=-1;}
    }
    synchronized void sample(long now,boolean healthy,boolean congested){
        if(congested){
            healthySince=-1;
            if(now-lastCongestion>=10000){budget=budget*3/4;lastCongestion=now;}
        }
        if(!healthy||mask==0){healthySince=-1;return;}
        if(healthySince<0)healthySince=now;
        if(lastSample<0||now-lastSample>15000||now-healthySince<20000)return;
        if(lastIncrease>=0&&now-lastIncrease<5000)return;
        long target=usable(estimate);
        if(target>budget){budget=Math.min(target,budget+Math.max(100000,budget/10));lastIncrease=now;}
    }
    synchronized long share(int slot){
        return (mask&(1<<slot))==0?0:budget/Integer.bitCount(mask);
    }
    synchronized long total(){return mask==0?0:budget;}
    synchronized long estimate(){return estimate;}
}
