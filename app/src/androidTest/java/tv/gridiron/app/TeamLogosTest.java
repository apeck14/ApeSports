package tv.gridiron.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.HashSet;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class TeamLogosTest {
    @Test public void all32TeamsHaveDistinctDrawableResourcesThatRenderVisiblePixels(){
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var ids=new HashSet<Integer>();
        for(String team:"ARI ATL BAL BUF CAR CHI CIN CLE DAL DEN DET GB HOU IND JAX KC LV LAC LAR MIA MIN NE NO NYG NYJ PHI PIT SF SEA TB TEN WSH".split(" ")){
            int id=TeamLogos.resource(team);assertNotEquals(team,0,id);assertTrue("Duplicate logo for "+team,ids.add(id));
            var drawable=context.getDrawable(id);assertNotNull(team,drawable);
            Bitmap bitmap=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);
            drawable.setBounds(0,0,80,80);drawable.draw(new Canvas(bitmap));
            int visible=0;for(int y=0;y<80;y++)for(int x=0;x<80;x++)if(Color.alpha(bitmap.getPixel(x,y))>0)visible++;
            assertTrue("Logo must contain visible artwork for "+team,visible>100);bitmap.recycle();
        }
        assertEquals(32,ids.size());
    }
    @Test public void aliasesNormalizeAndUnknownTeamsHaveNoIncorrectLogo(){
        assertEquals(TeamLogos.resource("LAR"),TeamLogos.resource("la"));
        assertEquals(TeamLogos.resource("JAX"),TeamLogos.resource("JAC"));
        assertEquals(TeamLogos.resource("WSH"),TeamLogos.resource(" WAS "));
        assertEquals(0,TeamLogos.resource(null));assertEquals(0,TeamLogos.resource("UNKNOWN"));
    }
    @Test public void homeDisplaysTeamLogosWithNoImageUrlsInTheScoreboard()throws Exception {
        var games=NflScoreboard.parse(NflHomeTest.board(NflHomeTest.event("logo-test","in")));
        try(var scenario=ActivityScenario.launch(HomeActivity.class)){
            scenario.onActivity(a->{a.cancelRefresh();a.applySnapshot(games,System.currentTimeMillis(),"");
                LinearLayout card=a.findViewById(android.R.id.content).findViewWithTag("logo-test");
                for(int row=1;row<=2;row++){
                    ImageView logo=(ImageView)((LinearLayout)card.getChildAt(row)).getChildAt(0);
                    assertNotNull("Home must display local artwork immediately",logo.getDrawable());
                    assertEquals(ImageView.ScaleType.FIT_CENTER,logo.getScaleType());
                }
            });
        }
    }
}
