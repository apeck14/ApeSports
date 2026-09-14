package tv.gridiron.app;

import org.json.*;
import java.util.*;
import java.text.*;

/** Public NFL schedule/status adapter. Event availability does not imply a video source. */
final class NflScoreboard {
    static final long FRESH_MS=120000;
    static final class Team {
        final String name,abbreviation;
        Team(JSONObject competitor)throws JSONException{
            JSONObject team=competitor.getJSONObject("team");name=team.getString("displayName");
            abbreviation=team.optString("abbreviation",name);
        }
    }
    static final class Game {
        final String id,name,state;final long start;final Team away,home;
        Game(JSONObject event)throws JSONException{
            id=event.getString("id");name=event.getString("name");start=parseDate(event.getString("date"));
            JSONObject competition=event.getJSONArray("competitions").getJSONObject(0);
            JSONObject type=competition.optJSONObject("status");if(type==null)type=event.getJSONObject("status");type=type.getJSONObject("type");
            state=type.optBoolean("completed",false)?"post":type.optString("state","unknown");
            Team a=null,h=null;JSONArray competitors=competition.getJSONArray("competitors");
            for(int i=0;i<competitors.length();i++){JSONObject c=competitors.getJSONObject(i);if("home".equals(c.optString("homeAway")))h=new Team(c);if("away".equals(c.optString("homeAway")))a=new Team(c);}
            if(a==null||h==null||id.isEmpty()||start<0)throw new JSONException("Incomplete NFL event");away=a;home=h;
        }
        boolean live(){return state.equals("in");}
        int order(){return live()?0:state.equals("pre")?1:2;}
    }
    static List<Game> parse(String json)throws JSONException{
        JSONObject root=new JSONObject(json);JSONArray leagues=root.getJSONArray("leagues");boolean nfl=false;
        for(int i=0;i<leagues.length();i++)if("nfl".equals(leagues.getJSONObject(i).optString("slug")))nfl=true;
        if(!nfl)throw new JSONException("Expected NFL scoreboard");
        JSONArray events=root.getJSONArray("events");if(events.length()>100)throw new JSONException("Scoreboard exceeds event limit");
        ArrayList<Game> games=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<events.length();i++)try{Game game=new Game(events.getJSONObject(i));if(ids.add(game.id))games.add(game);}catch(JSONException ignored){}
        if(events.length()>0&&games.isEmpty())throw new JSONException("Scoreboard contains no valid events");
        Collections.sort(games,(a,b)->{int c=Integer.compare(a.order(),b.order());return c!=0?c:Long.compare(a.start,b.start);});
        return Collections.unmodifiableList(games);
    }
    static long parseDate(String value){
        for(String pattern:new String[]{"yyyy-MM-dd'T'HH:mm'Z'","yyyy-MM-dd'T'HH:mm:ss'Z'"}){
            SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));f.setLenient(false);
            ParsePosition p=new ParsePosition(0);Date d=f.parse(value,p);if(d!=null&&p.getIndex()==value.length())return d.getTime();
        }return -1;
    }
    static String url(long now){
        SimpleDateFormat f=new SimpleDateFormat("yyyyMMdd",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));
        // Include the prior UTC date for games running past midnight and the next date for upcoming games.
        return "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?dates="+f.format(new Date(now-86400000))+"-"+f.format(new Date(now+86400000))+"&limit=1000";
    }
    static boolean fresh(long fetched,long now){return fetched>0&&now>=fetched&&now-fetched<=FRESH_MS;}
}
