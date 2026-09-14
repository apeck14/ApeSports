package tv.gridiron.app;

final class RetryBudget {
    int attempts;
    private long healthySince=-1;
    long nextDelay(int slot,double jitter){
        healthySince=-1;
        if(attempts>=4)return -1;
        return (1000L<<attempts++)+slot*150L+(long)(Math.max(0,Math.min(1,jitter))*500);
    }
    void sample(long now,boolean playing){
        if(!playing){healthySince=-1;return;}
        if(healthySince<0)healthySince=now;
        if(now-healthySince>=60000)reset();
    }
    void reset(){attempts=0;healthySince=-1;}
}
