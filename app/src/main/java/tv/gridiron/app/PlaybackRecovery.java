package tv.gridiron.app;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;

@androidx.media3.common.util.UnstableApi
final class PlaybackRecovery {
    enum Kind { LIVE_WINDOW, TRANSIENT, AUTHORIZATION, INVALID_SOURCE, PERMANENT }
    static boolean acceptsContentType(String contentType){
        // Some working HLS/video servers use text/plain, octet-stream, or no MIME type.
        if(contentType==null)return true;
        String type=contentType.split(";",2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return !type.equals("text/html")&&!type.equals("application/xhtml+xml")
            &&!type.equals("application/json")&&!type.equals("text/json")&&!type.endsWith("+json");
    }
    static Kind classify(PlaybackException error){
        if(error.errorCode==PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE)return Kind.INVALID_SOURCE;
        boolean transportFailure=false;
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof HttpDataSource.InvalidContentTypeException)return Kind.INVALID_SOURCE;
            if(cause instanceof HttpDataSource.InvalidResponseCodeException){
                int status=((HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                if(status==401||status==403)return Kind.AUTHORIZATION;
                return status==408||status==429||status==500||status==502||status==503||status==504?Kind.TRANSIENT:Kind.PERMANENT;
            }
            if(cause instanceof javax.net.ssl.SSLException)return Kind.PERMANENT;
            transportFailure|=cause instanceof HttpDataSource.HttpDataSourceException||cause instanceof java.net.SocketException
                ||cause instanceof java.net.SocketTimeoutException||cause instanceof java.net.UnknownHostException;
        }
        if(error.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)return Kind.LIVE_WINDOW;
        if(error.errorCode==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            ||error.errorCode==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)return Kind.TRANSIENT;
        if(error.errorCode==PlaybackException.ERROR_CODE_IO_UNSPECIFIED&&transportFailure)return Kind.TRANSIENT;
        return Kind.PERMANENT;
    }
}
