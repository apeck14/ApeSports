package tv.gridiron.app;

/** Atomic reservations include codecs still initializing, not just completed callbacks. */
final class DecoderAdmission {
    static final class Request {
        final String name; final boolean video,hardware; final int instances;
        final long pixels,budget;
        Request(String name,boolean video,boolean hardware,int instances,long pixels,long budget){
            this.name=name;this.video=video;this.hardware=hardware;this.instances=Math.max(1,instances);
            this.pixels=pixels;this.budget=budget;
        }
    }
    private final Request[] reservations=new Request[8];
    private final long[] firstDrop={-1,-1,-1,-1};
    private final long[] lastDrop={-1,-1,-1,-1};
    private final int[] badWindows=new int[4];
    private int sessionLimit=4;
    private long lastShed=-20000;
    synchronized String reserve(int slot,Request request){
        int index=slot*2+(request.video?0:1),same=1,videos=request.video?1:0;
        long pixels=request.video?request.pixels:0,software=request.video&&!request.hardware?request.pixels:0;
        long budget=request.video?request.budget:Long.MAX_VALUE;
        int instances=request.instances;
        for(int i=0;i<8;i++)if(i!=index&&reservations[i]!=null){
            Request other=reservations[i];
            if(!request.video&&!other.video)return "Only one game's audio decoder may be active";
            if(other.name.equals(request.name)){same++;instances=Math.min(instances,other.instances);}
            if(other.video){videos++;pixels+=other.pixels;budget=Math.min(budget,other.budget);if(!other.hardware)software+=other.pixels;}
        }
        if(same>instances)return "Reported decoder instance limit reached";
        if(request.video&&videos>sessionLimit)return "This session is limited to "+sessionLimit+" simultaneous games";
        if(request.video&&videos>1&&software>1280L*720*30)return "Software decoding is too heavy for multiview";
        if(request.video&&videos>1&&pixels>budget)return "Combined video workload exceeds the decoder allowance";
        reservations[index]=request;return null;
    }
    synchronized void cancel(int slot,Request request){int index=slot*2+(request.video?0:1);if(reservations[index]==request)reservations[index]=null;}
    synchronized void release(int slot){reservations[slot*2]=null;reservations[slot*2+1]=null;firstDrop[slot]=-1;lastDrop[slot]=-1;badWindows[slot]=0;}
    synchronized int videoCount(){int n=0;for(Request r:reservations)if(r!=null&&r.video)n++;return n;}
    synchronized int audioCount(){int n=0;for(Request r:reservations)if(r!=null&&!r.video)n++;return n;}
    synchronized void releaseAudio(int slot){reservations[slot*2+1]=null;}
    synchronized int limit(){return sessionLimit;}
    synchronized void restrict(int running){sessionLimit=Math.min(sessionLimit,Math.max(1,running));}
    synchronized Request video(int slot){return reservations[slot*2];}
    synchronized boolean dropped(int slot,long now,long elapsed,int frames,float fps){
        boolean bad=elapsed>=500&&frames>=10&&frames>=Math.max(1,fps)*elapsed/1000*0.05;
        if(!bad){firstDrop[slot]=-1;lastDrop[slot]=-1;badWindows[slot]=0;return false;}
        // Media3 batches drops, so a mild sustained problem can produce long sample intervals.
        if(firstDrop[slot]<0||now-lastDrop[slot]>Math.max(5000,elapsed+2000)){
            firstDrop[slot]=Math.max(0,now-elapsed);badWindows[slot]=0;
        }
        lastDrop[slot]=now;
        return ++badWindows[slot]>=3&&now-firstDrop[slot]>=10000;
    }
    synchronized boolean mayShed(long now){if(now-lastShed<20000)return false;lastShed=now;return true;}
}
