package tv.gridiron.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class RetryBudgetTest {
    @Test public void retriesAreBoundedAndExponential(){
        RetryBudget b=new RetryBudget();assertEquals(1000,b.nextDelay(0,0));assertEquals(2000,b.nextDelay(0,0));
        assertEquals(4000,b.nextDelay(0,0));assertEquals(8000,b.nextDelay(0,0));assertEquals(-1,b.nextDelay(0,0));
    }
    @Test public void jitterAndSlotOffsetSeparateRetries(){assertEquals(1950,new RetryBudget().nextDelay(3,1));}
    @Test public void readyFlappingDoesNotResetAttempts(){
        RetryBudget b=new RetryBudget();b.nextDelay(0,0);b.sample(0,true);b.sample(59000,true);assertEquals(1,b.attempts);
        b.sample(59500,false);b.sample(60000,true);b.sample(119999,true);assertEquals(1,b.attempts);
        b.sample(120000,true);assertEquals(0,b.attempts);
    }
}
