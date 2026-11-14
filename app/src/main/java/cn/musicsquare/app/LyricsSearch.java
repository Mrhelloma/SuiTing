package cn.musicsquare.app;

import org.json.*;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Metadata-based lyric lookup. Audio links and playback never change during this operation. */
public final class LyricsSearch {
    interface Fetcher {String get(String url)throws Exception;}
    private final Fetcher fetch;
    private static final Pattern TIMED=Pattern.compile("\\[(\\d{1,3}):([0-5]\\d)(?:\\.\\d{1,3})?\\]\\s*[^\\[\\r\\n]+");
    public LyricsSearch(){this(url->MusicApi.get(url,4000,6000));}
    LyricsSearch(Fetcher fetch){this.fetch=fetch;}
    static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    static Set<String> artists(String value){
        String cleaned=Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).replaceAll("\\([^)]*\\)","");
        Set<String> out=new HashSet<>();for(String item:cleaned.split("[/、&,，;；]+")){String n=normalize(item);if(!n.isEmpty()&&!Arrays.asList("未知歌手","本地音频","unknown","unknownartist").contains(n))out.add(n);}return out;
    }
    static boolean artistMatches(String target,String candidate){Set<String> wanted=artists(target);return !wanted.isEmpty()&&artists(candidate).containsAll(wanted);}
    static boolean titleMatches(String target,String candidate){return !normalize(target).isEmpty()&&normalize(target).equals(normalize(candidate));}
    static boolean durationMatches(long target,long candidate){return target>0&&candidate>0&&Math.abs(target-candidate)<=Math.min(8000,Math.max(4000,target*.025));}
    static boolean highConfidence(JSONObject target,JSONObject candidate){return titleMatches(target.optString("title"),candidate.optString("title"))&&artistMatches(target.optString("artist"),candidate.optString("artist"))&&durationMatches(target.optLong("durationMs"),candidate.optLong("durationMs"));}
    static int score(JSONObject target,JSONObject candidate){
        String wanted=normalize(target.optString("title")),name=normalize(candidate.optString("title"));
        boolean exact=!wanted.isEmpty()&&wanted.equals(name);
        if(!exact&&(wanted.length()<2||!name.contains(wanted)&&!wanted.contains(name)))return -1;
        int score=exact?100:55;if(artistMatches(target.optString("artist"),candidate.optString("artist")))score+=40;
        if(durationMatches(target.optLong("durationMs"),candidate.optLong("durationMs")))score+=25;
        else if(target.optLong("durationMs")>0&&candidate.optLong("durationMs")>0)score-=25;
        return score;
    }
    static String clean(String raw){return (raw==null?"":raw).replace("\uFEFF","").replace("\\n","\n").replace("&#10;","\n").replace("&#13;","").replace("&amp;","&").trim();}
    public static boolean hasTimedLyrics(String raw){return TIMED.matcher(clean(raw)).find();}
    static JSONObject candidate(String title,String artist,long duration,String provider,String id,String lrc,String plain)throws Exception{
        lrc=clean(lrc);plain=clean(plain);boolean timed=hasTimedLyrics(lrc);
        if(!timed&&plain.isEmpty())plain=lrc;
        return new JSONObject().put("title",title).put("artist",artist).put("durationMs",duration).put("provider",provider).put("candidateId",id).put("lrc",timed?lrc:"").put("plain",plain).put("kind",timed?"synced":"plain");
    }
    private String read(String url,long deadline)throws Exception{if(System.currentTimeMillis()>deadline)throw new IOException("歌词搜索超时，请稍后重试");return fetch.get(url);}
    public JSONObject find(JSONObject target)throws Exception{
        String title=target.optString("title").trim(),artist=target.optString("artist").trim();
        if(title.isEmpty())throw new IOException("请先填写歌名");
        long deadline=System.currentTimeMillis()+30000;List<JSONObject> found=new ArrayList<>();List<String> errors=new ArrayList<>();
        String query=title+" "+artist.replaceAll("[（(][^）)]*[）)]","");
        try{
            String suffix="?s="+MusicApi.enc(query.trim())+"&type=1&limit=12&offset=0";JSONArray rows;
            try{rows=MusicApi.parseNeteaseSearch(read("https://music.163.com/api/cloudsearch/pc"+suffix,deadline),query,12);}
            catch(Exception first){rows=MusicApi.parseNeteaseSearch(read("https://music.163.com/api/search/get"+suffix,deadline),query,12);}
            List<JSONObject> ranked=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(score(target,row)>=0)ranked.add(row);}
            ranked.sort((a,b)->Integer.compare(score(target,b),score(target,a)));
            for(int i=0;i<Math.min(3,ranked.size());i++){
                JSONObject meta=ranked.get(i);try{
                    String raw=read("https://music.163.com/api/song/lyric?id="+MusicApi.enc(meta.optString("songid"))+"&lv=-1&kv=-1&tv=-1",deadline);
                    JSONObject response=new JSONObject(raw),lrc=response.optJSONObject("lrc");String words=lrc==null?"":MusicApi.text(lrc,"lyric");if(words.isEmpty())continue;
                    JSONObject c=candidate(meta.optString("title"),meta.optString("artist"),meta.optLong("durationMs"),"网易云歌词",meta.optString("songid"),words,"");
                    c.put("score",score(target,c)).put("autoEligible",highConfidence(target,c));found.add(c);
                    if(c.optString("kind").equals("synced")&&highConfidence(target,c))return result(c,found,"");
                }catch(Exception e){errors.add("网易云歌词暂不可用");}
            }
        }catch(Exception e){errors.add("网易云歌词搜索暂不可用");}
        // LRCLIB is a second independent lyrics-only source; one sequential request, no batch scans.
        if(System.currentTimeMillis()<deadline)try{
            String url="https://lrclib.net/api/search?track_name="+MusicApi.enc(title);if(!artists(artist).isEmpty())url+="&artist_name="+MusicApi.enc(artist);
            JSONArray rows=new JSONArray(read(url,deadline));
            for(int i=0;i<Math.min(30,rows.length());i++){
                JSONObject row=rows.getJSONObject(i);JSONObject c=candidate(MusicApi.text(row,"trackName"),MusicApi.text(row,"artistName"),Math.round(row.optDouble("duration",0)*1000),"LRCLIB",MusicApi.text(row,"id"),MusicApi.text(row,"syncedLyrics"),MusicApi.text(row,"plainLyrics"));
                if(score(target,c)<0||c.optString("lrc").isEmpty()&&c.optString("plain").isEmpty())continue;
                c.put("score",score(target,c)).put("autoEligible",highConfidence(target,c));found.add(c);
                if(c.optString("kind").equals("synced")&&highConfidence(target,c))return result(c,found,"");
            }
        }catch(Exception e){errors.add("备用歌词服务暂不可用");}
        found.sort((a,b)->{int timing=(b.optString("kind").equals("synced")?1:0)-(a.optString("kind").equals("synced")?1:0);return timing!=0?timing:Integer.compare(b.optInt("score"),a.optInt("score"));});
        return result(null,found,found.isEmpty()?(errors.isEmpty()?"未找到匹配歌词，可修改歌名和歌手重试":"歌词服务暂不可用或未找到匹配结果，请稍后重试"):"请确认歌曲版本后使用歌词");
    }
    private static JSONObject result(JSONObject automatic,List<JSONObject> candidates,String message)throws Exception{
        JSONArray rows=new JSONArray();Set<String> used=new HashSet<>();for(JSONObject c:candidates){String id=c.optString("provider")+":"+c.optString("candidateId");if(used.add(id))rows.put(c);if(rows.length()>=5)break;}
        return new JSONObject().put("automatic",automatic==null?JSONObject.NULL:automatic).put("candidates",rows).put("message",message);
    }
}
