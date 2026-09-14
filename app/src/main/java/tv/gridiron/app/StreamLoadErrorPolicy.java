package tv.gridiron.app;

import androidx.media3.common.C;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/** Preserve Media3's bounded load retries and rendition fallback, with server backpressure. */
@androidx.media3.common.util.UnstableApi
final class StreamLoadErrorPolicy extends DefaultLoadErrorHandlingPolicy {
    static boolean permanentAccessFailure(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof HttpDataSource.InvalidContentTypeException)return true;
            if(cause instanceof javax.net.ssl.SSLException)return true;
            if(cause instanceof HttpDataSource.InvalidResponseCodeException){
                int status=((HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                if(status==401||status==403)return true;
            }
        }
        return false;
    }
    static long serverDelay(Throwable error,long now){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof HttpDataSource.InvalidResponseCodeException){
            var http=(HttpDataSource.InvalidResponseCodeException)cause;
            return http.responseCode==429||http.responseCode==503?RetryAfter.delay(http.headerFields,now):-1;
        }
        return -1;
    }
    @Override public long getRetryDelayMsFor(LoadErrorInfo info){
        if(permanentAccessFailure(info.exception))return C.TIME_UNSET;
        long fallback=super.getRetryDelayMsFor(info);
        if(fallback==C.TIME_UNSET)return fallback;
        long server=serverDelay(info.exception,System.currentTimeMillis());
        return server>RetryAfter.MAX_AUTOMATIC_WAIT_MS?C.TIME_UNSET:Math.max(fallback,server);
    }
    @Override public FallbackSelection getFallbackSelectionFor(FallbackOptions options,LoadErrorInfo info){
        // An immediate rendition/location switch must not bypass an access denial or server delay.
        if(permanentAccessFailure(info.exception)||serverDelay(info.exception,System.currentTimeMillis())>0)return null;
        return super.getFallbackSelectionFor(options,info);
    }
}
