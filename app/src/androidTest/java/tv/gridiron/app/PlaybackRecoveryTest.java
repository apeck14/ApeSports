package tv.gridiron.app;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class PlaybackRecoveryTest {
    @Test public void nonMediaResponsesDoNotRetryOrSwitchRenditions(){
        for(String type:new String[]{"text/html; charset=utf-8","Application/JSON", "application/xhtml+xml","application/problem+json","text/json"})assertFalse(type,PlaybackRecovery.acceptsContentType(type));
        for(String type:new String[]{null,"","text/plain","application/octet-stream","application/vnd.apple.mpegurl","application/dash+xml","video/mp2t","audio/aac"})assertTrue(String.valueOf(type),PlaybackRecovery.acceptsContentType(type));
        var spec=new DataSpec.Builder().setUri("https://example.com/live.m3u8").build();
        var error=new HttpDataSource.InvalidContentTypeException("text/html",spec);
        var info=new androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            new androidx.media3.exoplayer.source.LoadEventInfo(0,spec,0),
            new androidx.media3.exoplayer.source.MediaLoadData(androidx.media3.common.C.DATA_TYPE_MEDIA),error,1);
        var policy=new StreamLoadErrorPolicy();
        assertEquals(androidx.media3.common.C.TIME_UNSET,policy.getRetryDelayMsFor(info));
        assertNull(policy.getFallbackSelectionFor(new androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions(2,0,2,0),info));
        assertEquals(PlaybackRecovery.Kind.INVALID_SOURCE,PlaybackRecovery.classify(new PlaybackException("test",error,PlaybackException.ERROR_CODE_IO_UNSPECIFIED)));
    }
    private androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo loadError(int status,String retryAfter){
        var spec=new DataSpec.Builder().setUri("https://example.com/test.m3u8").build();
        var error=new HttpDataSource.InvalidResponseCodeException(status,"test",null,
            retryAfter==null?Collections.emptyMap():Collections.singletonMap("Retry-After",Collections.singletonList(retryAfter)),spec,new byte[0]);
        return new androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo(
            new androidx.media3.exoplayer.source.LoadEventInfo(0,spec,0),
            new androidx.media3.exoplayer.source.MediaLoadData(androidx.media3.common.C.DATA_TYPE_MEDIA),error,1);
    }
    @Test public void serverWaitAppliesToLoaderAndFallback(){
        StreamLoadErrorPolicy policy=new StreamLoadErrorPolicy();
        for(int status:new int[]{429,503}){
            var info=loadError(status,"30");assertEquals(30000,policy.getRetryDelayMsFor(info));
            assertNull(policy.getFallbackSelectionFor(new androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions(2,0,2,0),info));
        }
    }
    @Test public void loaderStopsForDeniedAccessOrExcessiveWait(){
        StreamLoadErrorPolicy policy=new StreamLoadErrorPolicy();
        for(int status:new int[]{401,403})assertEquals(androidx.media3.common.C.TIME_UNSET,policy.getRetryDelayMsFor(loadError(status,null)));
        assertEquals(androidx.media3.common.C.TIME_UNSET,policy.getRetryDelayMsFor(loadError(503,"301")));
        assertTrue(StreamLoadErrorPolicy.permanentAccessFailure(new java.io.IOException(new javax.net.ssl.SSLException("test"))));
    }
    @Test public void missingOrMalformedWaitPreservesMedia3Retry(){
        StreamLoadErrorPolicy policy=new StreamLoadErrorPolicy();var defaults=new androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy();
        for(int status:new int[]{404,408,429,500,502,503,504}){
            var info=loadError(status,"invalid");assertEquals(defaults.getRetryDelayMsFor(info),policy.getRetryDelayMsFor(info));
        }
    }
    private PlaybackException http(int status){return new PlaybackException("test",
        new HttpDataSource.InvalidResponseCodeException(status,"test",null,Collections.emptyMap(),
            new DataSpec.Builder().setUri("https://example.com/test.m3u8").build(),new byte[0]),PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);}
    @Test public void statusClassificationIsSelective(){
        for(int status:new int[]{408,429,500,502,503,504})assertEquals(PlaybackRecovery.Kind.TRANSIENT,PlaybackRecovery.classify(http(status)));
        for(int status:new int[]{401,403})assertEquals(PlaybackRecovery.Kind.AUTHORIZATION,PlaybackRecovery.classify(http(status)));
        for(int status:new int[]{400,404,410,501})assertEquals(PlaybackRecovery.Kind.PERMANENT,PlaybackRecovery.classify(http(status)));
    }
    @Test public void liveWindowAndTimeoutRecoverButTlsDoesNot(){
        assertEquals(PlaybackRecovery.Kind.TRANSIENT,PlaybackRecovery.classify(new PlaybackException("test",new java.net.SocketException("reset"),PlaybackException.ERROR_CODE_IO_UNSPECIFIED)));
        assertEquals(PlaybackRecovery.Kind.LIVE_WINDOW,PlaybackRecovery.classify(new PlaybackException("test",null,PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)));
        assertEquals(PlaybackRecovery.Kind.TRANSIENT,PlaybackRecovery.classify(new PlaybackException("test",null,PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)));
        assertEquals(PlaybackRecovery.Kind.PERMANENT,PlaybackRecovery.classify(new PlaybackException("test",new javax.net.ssl.SSLException("test"),PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)));
    }
}
