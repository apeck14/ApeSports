package tv.gridiron.app;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RetryAfterTest {
    @Test public void secondsAndZero(){assertEquals(120000,RetryAfter.parse(" 120 ",0));assertEquals(0,RetryAfter.parse("0",0));}
    @Test public void caseInsensitiveAndConservativeDuplicateHeaders(){
        Map<String,List<String>> h=new HashMap<>();h.put("rEtRy-AfTeR",Arrays.asList("bad","10","30"));assertEquals(30000,RetryAfter.delay(h,0));
    }
    @Test public void datesAndPastDate(){
        long date=1445412480000L;
        assertEquals(60000,RetryAfter.parse("Wed, 21 Oct 2015 07:28:00 GMT",date-60000));
        assertEquals(0,RetryAfter.parse("Wed, 21 Oct 2015 07:28:00 GMT",date+60000));
        assertEquals(60000,RetryAfter.parse("Wednesday, 21-Oct-15 07:28:00 GMT",date-60000));
        assertEquals(60000,RetryAfter.parse("Wed Oct 21 07:28:00 2015",date-60000));
    }
    @Test public void invalidValuesUseNormalPolicy(){for(String s:new String[]{"","-1","1.5","later","Wed, 21 Oct 2015 07:28:00 GMT junk"})assertEquals(-1,RetryAfter.parse(s,0));}
    @Test public void largeValuesCannotWrapIntoAnEarlyRetry(){assertEquals(Long.MAX_VALUE,RetryAfter.parse("99999999999999999999999999",0));assertTrue(RetryAfter.parse("301",0)>RetryAfter.MAX_AUTOMATIC_WAIT_MS);}
}
