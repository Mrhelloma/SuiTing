package cn.musicsquare.app;

import org.json.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Resolves the selected NetEase ID without changing the song or its library identity. */
final class NeteaseResolver {
    interface Fetcher { String get(String url)throws Exception; }
    interface PreviewCheck {boolean test(JSONObject track);}
    interface PreviewMark {void accept(JSONObject track);}
    private final Fetcher fetcher;private final PreviewCheck recent;private final PreviewMark mark;
    NeteaseResolver(){this(MusicApi::get);}
    NeteaseResolver(Fetcher fetcher){this(fetcher,t->false,t->{});}
    NeteaseResolver(Fetcher fetcher,PreviewCheck recent,PreviewMark mark){this.fetcher=fetcher;this.recent=recent;this.mark=mark;}

    JSONObject resolve(JSONObject original)throws Exception {
        String id=MusicApi.text(original,"songid");
        if(!id.matches("[0-9]+"))throw new IOException("网易云歌曲缺少有效标识，请重新搜索");
        JSONObject direct=null;boolean alreadyTried=false;
        if(recent.test(original)){alreadyTried=true;try{return resolveAfterPreview(original);}catch(Exception ignored){}}
        try{
            direct=MusicApi.parseNeteasePlayback(original,fetcher.get("https://music.163.com/api/song/enhance/player/url?ids="+MusicApi.enc("["+id+"]")+"&br=128000"));
            direct.put("playbackProvider","netease-public");
            if(!direct.optBoolean("trial",false))return direct;
            mark.accept(original);
        }catch(Exception unavailable){}
        try{if(alreadyTried)throw new IOException("第三方解析暂不可用");return resolveAfterPreview(original);}
        catch(Exception unavailable){
            if(direct!=null)return direct.put("neteaseFallbackAttempted",true);
            throw new IOException("网易云原接口和第三方解析均未提供可用音频，请重试或换源",unavailable);
        }
    }

    JSONObject resolveAfterPreview(JSONObject original)throws Exception {
        String id=MusicApi.text(original,"songid");
        if(!id.matches("[0-9]+"))throw new IOException("网易云歌曲缺少有效标识");
        return parse(original,fetcher.get("https://wzapi.com/api/wyy?id="+id));
    }

    static JSONObject parse(JSONObject original,String raw)throws Exception {
        JSONObject root=new JSONObject(raw),song=root.optJSONObject("song");
        String id=MusicApi.text(original,"songid");
        if(!root.optBoolean("success",false)||song==null)throw new IOException("网易云解析服务未返回歌曲");
        if(!id.equals(MusicApi.text(root,"id"))||!id.equals(MusicApi.text(song,"id")))throw new IOException("网易云解析返回的歌曲标识不一致");
        long expected=original.optLong("durationMs",0),duration=song.optLong("duration_ms",0);
        if(expected>0&&duration>0&&Math.abs(expected-duration)>Math.max(5000,expected*.03))throw new IOException("网易云解析返回的歌曲时长不一致");
        if(root.optJSONObject("freeTrialInfo")!=null||song.optJSONObject("freeTrialInfo")!=null||root.optBoolean("trial",false))throw new IOException("网易云解析仅返回试听");
        JSONArray levels=root.optJSONArray("levels"),urls=new JSONArray();Set<String> seen=new HashSet<>();String quality="";
        // Prefer smaller standard files, with bounded high-quality CDN alternatives.
        for(String level:new String[]{"standard","exhigh","lossless"}){
            if(levels==null)break;
            for(int i=0;i<levels.length();i++){
                JSONObject item=levels.optJSONObject(i);
                if(item==null||!level.equals(item.optString("level"))||item.optJSONObject("freeTrialInfo")!=null||item.optBoolean("trial",false))continue;
                try{
                    String url=MediaUrlPolicy.normalize(MusicApi.text(item,"url"));
                    String host=new URI(url).getHost().toLowerCase(Locale.ROOT);
                    if(!MediaUrlPolicy.domainMatches(host,"music.126.net"))continue;
                    if(seen.add(url)){urls.put(url);if(quality.isEmpty())quality=level;}
                }catch(IOException|java.net.URISyntaxException ignored){}
            }
        }
        if(urls.length()==0)throw new IOException("网易云解析未提供可用音频地址");
        JSONObject result=new JSONObject(original.toString());
        result.remove("trialDetectedByDuration");result.remove("neteaseFallbackAttempted");
        result.put("trial",false).put("audioUrl",urls.getString(0)).put("audioCandidates",urls).put("playbackProvider","wzapi").put("resolvedQuality",quality);
        if(expected<=0&&duration>0)result.put("durationMs",duration);
        result.put("lrcUrl","https://music.163.com/api/song/lyric?id="+id+"&lv=-1&kv=-1&tv=-1");
        return result;
    }
}
