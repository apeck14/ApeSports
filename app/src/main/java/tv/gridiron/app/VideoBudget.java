package tv.gridiron.app;

/** Deterministic policy, independent of Android and network estimates. */
final class VideoBudget {
    static final int[] HEIGHTS={360,540,720,1080,2160};
    static final class Profile {
        final int width,height,fps;
        Profile(int height,int fps){this.height=height;this.width=(height*16+8)/9;this.fps=fps;}
    }
    static Profile choose(int outputWidth,int outputHeight,int layout,boolean expanded,
                          int active,float refreshRate,long pixelsPerSecond,int maxInstances,int penalty) {
        int columns=!expanded&&layout==4?2:1;
        int rows=!expanded&&layout>1?2:1;
        int fittedHeight=Math.min(outputHeight/rows,outputWidth/columns*9/16);
        int fps=refreshRate>0&&refreshRate<49?30:60;
        // Round up to a normal rendition: e.g. a 400-line tile can use a 540p ladder.
        int viewportTier=0;
        while(viewportTier<HEIGHTS.length-1&&HEIGHTS[viewportTier]<fittedHeight)viewportTier++;
        int tier=viewportTier;
        long share=Math.max(1,pixelsPerSecond)/Math.max(1,active);
        while(tier>0&&(long)HEIGHTS[tier]*HEIGHTS[tier]*16/9*fps>share)tier--;
        // Instance counts are hints, not proof that lowering resolution permits more sessions.
        if(active>maxInstances)tier=Math.min(tier,1);
        tier=Math.max(0,tier-penalty);
        return new Profile(HEIGHTS[tier],fps);
    }
    static final class Health {
        int penalty;
        private long lastBad=-10000,healthySince=-1;
        void bad(long now){healthySince=-1;if(now-lastBad>=10000){penalty=Math.min(3,penalty+1);lastBad=now;}}
        void sample(long now,boolean healthy){
            if(!healthy){healthySince=-1;return;}
            if(healthySince<0)healthySince=now;
            if(penalty>0&&now-healthySince>=60000){penalty--;healthySince=now;}
        }
    }
}
