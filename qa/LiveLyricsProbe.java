package cn.musicsquare.app;
import org.json.*;
import java.util.regex.*;
/** Runs the production lyric lookup and only prints metadata/counts, never lyric text. */
public class LiveLyricsProbe {
    public static void main(String[] args)throws Exception{
        String[][] tracks={{"三国杀","汪苏泷","227000"},{"有点甜","汪苏泷","235200"}};
        for(String[] song:tracks){
            JSONObject target=new JSONObject().put("title",song[0]).put("artist",song[1]).put("durationMs",Long.parseLong(song[2]));
            JSONObject result=new LyricsSearch().find(target),matched=result.optJSONObject("automatic");
            if(matched==null)throw new AssertionError("No high-confidence synced result for "+song[0]);
            if(!LyricsSearch.hasTimedLyrics(matched.optString("lrc")))throw new AssertionError("No timestamps");
            int timestamps=0;Matcher m=Pattern.compile("\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?\\]").matcher(matched.getString("lrc"));while(m.find())timestamps++;
            if(timestamps<10)throw new AssertionError("Too few timed lines");
            System.out.println("PASS: "+song[0]+" / "+song[1]+" -> "+matched.getString("provider")+", matched duration="+matched.optLong("durationMs")+"ms, timed lines="+timestamps+", highConfidence="+LyricsSearch.highConfidence(target,matched));
        }
        System.out.println("Scope: production Java network lookup only; Android cache and device rendering are not exercised here.");
    }
}
