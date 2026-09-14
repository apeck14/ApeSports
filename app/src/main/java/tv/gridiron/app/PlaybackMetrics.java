package tv.gridiron.app;

/** Monotonic per-player totals. Pauses are excluded from active time and rebuffering. */
final class PlaybackMetrics {
    private long last,playingMs,waitingMs,rebufferMs,recoveryStart=-1,recoveryWallMs;
    private boolean playing,waiting,buffering,firstFrame,hasPlayed;
    private int stalls,errors,recoveries,formatChanges;
    private String format;
    long startupActiveMs=-1;
    PlaybackMetrics(long now){last=now;}
    private void advance(long now){
        long elapsed=Math.max(0,now-last);last=Math.max(last,now);
        if(playing)playingMs+=elapsed;else if(waiting)waitingMs+=elapsed;
        if(buffering)rebufferMs+=elapsed;
    }
    void state(long now,boolean isPlaying,boolean wantsPlayback,boolean isBuffering){
        advance(now);hasPlayed|=isPlaying;boolean next=firstFrame&&hasPlayed&&wantsPlayback&&isBuffering;
        if(next&&!buffering)stalls++;
        playing=isPlaying;waiting=wantsPlayback&&!isPlaying;buffering=next;
        if(isPlaying&&recoveryStart>=0){recoveries++;recoveryWallMs+=Math.max(0,now-recoveryStart);recoveryStart=-1;}
    }
    boolean firstFrame(long now){advance(now);if(firstFrame)return false;firstFrame=true;startupActiveMs=playingMs+waitingMs;return true;}
    void error(long now){advance(now);errors++;if(recoveryStart<0)recoveryStart=now;}
    void format(String value){if(format!=null&&!format.equals(value))formatChanges++;format=value;}
    String summary(long now){
        advance(now);
        return "startupActiveMs="+startupActiveMs+" playingMs="+playingMs+" waitingMs="+waitingMs+" rebufferMs="+rebufferMs+" stalls="+stalls
            +" errors="+errors+" recoveries="+recoveries+" recoveredWallMs="+recoveryWallMs+" recoveryPending="+(recoveryStart>=0)+" formatChanges="+formatChanges+" format="+(format==null?"unknown":format);
    }
}
