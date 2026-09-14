package tv.gridiron.app;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/** Server delay parsing; oversized waits stop automatic retries rather than retrying early. */
final class RetryAfter {
    static final long MAX_AUTOMATIC_WAIT_MS=5*60*1000;
    static long delay(Map<String,List<String>> headers,long now){
        long delay=-1;
        for(var entry:headers.entrySet())if("Retry-After".equalsIgnoreCase(entry.getKey())&&entry.getValue()!=null)
            for(String value:entry.getValue())delay=Math.max(delay,parse(value,now));
        return delay;
    }
    static long parse(String value,long now){
        if(value==null)return -1;value=value.trim();
        if(value.matches("[0-9]+")){
            try{long seconds=Long.parseLong(value);return seconds>Long.MAX_VALUE/1000?Long.MAX_VALUE:seconds*1000;}
            catch(NumberFormatException e){return Long.MAX_VALUE;}
        }
        for(String pattern:new String[]{"EEE, dd MMM yyyy HH:mm:ss zzz","EEEE, dd-MMM-yy HH:mm:ss zzz","EEE MMM d HH:mm:ss yyyy"}){
            SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("GMT"));
            ParsePosition p=new ParsePosition(0);Date date=f.parse(value,p);
            if(date!=null&&p.getIndex()==value.length())return Math.max(0,date.getTime()-now);
        }
        return -1;
    }
}
