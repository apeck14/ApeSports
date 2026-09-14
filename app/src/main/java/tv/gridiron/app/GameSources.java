package tv.gridiron.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Per-event alternatives. A saved URL is not a claim of verified video availability. */
final class GameSources {
    interface Choice {void accept(FeedParser.Feed source);}
    private final SharedPreferences prefs;
    GameSources(Context context){prefs=context.getSharedPreferences("HomeActivity",0);}
    List<FeedParser.Feed> list(String event){
        LinkedHashMap<String,FeedParser.Feed> unique=new LinkedHashMap<>();
        try{JSONArray a=new JSONArray(prefs.getString("sources."+event,"[]"));
            for(int i=0;i<Math.min(a.length(),64);i++){JSONObject f=a.optJSONObject(i);if(f!=null&&FeedParser.validUrl(f.optString("url")))unique.put(f.getString("url"),new FeedParser.Feed(f.optString("title","Source"),f.getString("url"),event));}
        }catch(JSONException ignored){}
        String legacy=selected(event);if(FeedParser.validUrl(legacy)&&!unique.containsKey(legacy))unique.put(legacy,new FeedParser.Feed("Linked stream",legacy,event));
        return new ArrayList<>(unique.values());
    }
    String selected(String event){return prefs.getString("source."+event,"");}
    void select(String event,FeedParser.Feed source){
        if(event.isEmpty()||!FeedParser.validUrl(source.url))throw new IllegalArgumentException("Enter a valid HTTP or HTTPS media URL");
        List<FeedParser.Feed> all=list(event);for(int i=all.size()-1;i>=0;i--)if(all.get(i).url.equals(source.url))all.remove(i);
        if(all.size()>=64)throw new IllegalArgumentException("This game already has 64 sources");
        all.add(new FeedParser.Feed(source.title,source.url,event));JSONArray data=new JSONArray();
        try{for(var f:all)data.put(new JSONObject().put("title",f.title).put("url",f.url));}catch(JSONException e){throw new IllegalArgumentException(e);}
        prefs.edit().putString("sources."+event,data.toString()).putString("source."+event,source.url).apply();
    }
    static AlertDialog show(Context context,String event,String title,String current,Choice chosen){
        GameSources store=new GameSources(context);List<FeedParser.Feed> sources=store.list(event);
        String[] labels=new String[sources.size()];for(int i=0;i<labels.length;i++)labels[i]=(sources.get(i).url.equals(current)?"Current · ":"")+sources.get(i).title;
        var builder=new Ui.DialogBuilder(context).setTitle("Sources · "+title)
            .setPositiveButton("Add source",(d,n)->add(context,event,title,chosen))
            .setNeutralButton("Saved streams",(d,n)->saved(context,event,title,chosen)).setNegativeButton("Cancel",null);
        if(sources.isEmpty())builder.setMessage("No sources linked yet. Add a direct media URL or choose a saved stream.");
        else builder.setItems(labels,(d,n)->{store.select(event,sources.get(n));chosen.accept(sources.get(n));});
        return builder.show();
    }
    private static void add(Context context,String event,String title,Choice chosen){
        LinearLayout form=new LinearLayout(context);form.setOrientation(LinearLayout.VERTICAL);int pad=Ui.dp(context,24);form.setPadding(pad,0,pad,pad);
        EditText name=Ui.input(context,"Source name"),url=Ui.input(context,"https://… stream.m3u8 or manifest.mpd");url.setInputType(17);form.addView(name);form.addView(url);
        form.addView(Ui.text(context,"Playback availability is checked when you watch.",13,Ui.MUTED));
        AlertDialog dialog=new Ui.DialogBuilder(context).setTitle("Add source · "+title).setView(form).setPositiveButton("Save and select",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->{Ui.styleDialog(dialog);dialog.getButton(-1).setOnClickListener(v->{
            String label=name.getText().toString().trim();if(label.isEmpty()){name.setError("Name this source");return;}
            var source=new FeedParser.Feed(label,url.getText().toString().trim(),event);
            try{new GameSources(context).select(event,source);}catch(IllegalArgumentException e){url.setError(e.getMessage());return;}
            dialog.dismiss();chosen.accept(source);
        });});dialog.show();
    }
    private static void saved(Context context,String event,String title,Choice chosen){
        List<FeedParser.Feed> sources=new ArrayList<>();
        try{JSONArray a=new JSONArray(context.getSharedPreferences("MainActivity",0).getString("feeds","[]"));for(int i=0;i<a.length();i++){JSONObject f=a.optJSONObject(i);if(f!=null&&FeedParser.validUrl(f.optString("url")))sources.add(new FeedParser.Feed(f.optString("title","Source"),f.getString("url"),event));}}catch(JSONException ignored){}
        if(sources.isEmpty()){new Ui.DialogBuilder(context).setMessage("No saved streams. Add a URL or import an NFL playlist from Saved streams.").setPositiveButton("OK",null).show();return;}
        String[] labels=new String[sources.size()];for(int i=0;i<labels.length;i++)labels[i]=sources.get(i).title;
        new Ui.DialogBuilder(context).setTitle("Link saved stream · "+title).setItems(labels,(d,n)->{
            try{new GameSources(context).select(event,sources.get(n));chosen.accept(sources.get(n));}catch(IllegalArgumentException e){new Ui.DialogBuilder(context).setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
        }).setNegativeButton("Cancel",null).show();
    }
}
