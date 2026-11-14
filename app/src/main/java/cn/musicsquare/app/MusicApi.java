package cn.musicsquare.app;

import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Public metadata and playback endpoints; a missing/unauthorized URL is not bypassed. */
public final class MusicApi {
    static String enc(String s) throws UnsupportedEncodingException {return URLEncoder.encode(s,"UTF-8");}
    static String get(String url)throws Exception {return get(url,8000,10000);}
    private static volatile long lyricsRetryAfter=0;
    static String get(String url,int connectTimeout,int readTimeout)throws Exception {
        URL next=new URL(url);
        for(int redirect=0;redirect<6;redirect++){
            if(!"https".equals(next.getProtocol()))throw new IOException("数据接口未提供 HTTPS 连接");
            HttpURLConnection c=(HttpURLConnection)next.openConnection();
            c.setConnectTimeout(connectTimeout);c.setReadTimeout(readTimeout);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36 MusicSquare/0.1.1");
            if(next.getHost().endsWith("music.163.com"))c.setRequestProperty("Referer","https://music.163.com/");
            if(next.getHost().endsWith(".y.qq.com"))c.setRequestProperty("Referer","https://y.qq.com/");
            boolean lrcLib=next.getHost().equals("lrclib.net");
            if(lrcLib){if(System.currentTimeMillis()<lyricsRetryAfter)throw new IOException("歌词服务请求过多，请稍后重试");c.setRequestProperty("User-Agent","SuiTing/0.1.4 (personal Android player based on https://github.com/CharlesPikachu/musicsquare)");}
            try{
                int status=c.getResponseCode();
                if(lrcLib&&status==429){long wait=60;try{wait=Math.max(1,Long.parseLong(c.getHeaderField("Retry-After")));}catch(Exception ignored){}lyricsRetryAfter=System.currentTimeMillis()+Math.min(86400,wait)*1000L;throw new IOException("歌词服务请求过多，请稍后重试");}
                if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("音乐源重定向缺少目标地址");next=new URL(next,location);continue;}
                if(status!=200)throw new IOException("音乐源返回 HTTP "+status);
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("接口响应过大");out.write(buffer,0,n);}
                    return out.toString("UTF-8");
                }
            }finally{c.disconnect();}
        }
        throw new IOException("接口重定向过多");
    }
    static JSONObject base(String source,String id,String title,String artist)throws JSONException{
        return new JSONObject().put("uid",source+"-"+id).put("source",source).put("songid",id).put("title",title).put("artist",artist);
    }
    static String text(JSONObject o,String key){Object v=o.opt(key);return v==null||v==JSONObject.NULL?"":String.valueOf(v).trim();}
    static String first(JSONObject o,String... keys){for(String k:keys){String v=text(o,k);if(!v.isEmpty())return v;}return "";}
    static String imageUrl(String u){return u.startsWith("http://")?"https://"+u.substring(7):u;}
    static String artistNames(JSONArray rows){StringBuilder names=new StringBuilder();if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject a=rows.optJSONObject(i);if(a!=null){if(names.length()>0)names.append(" / ");names.append(text(a,"name"));}}return names.toString();}
    static JSONArray parseNeteaseSearch(String raw,String keyword,int limit)throws Exception{
        JSONObject root=new JSONObject(raw);JSONObject result=root.optJSONObject("result");
        if(root.optInt("code",200)!=200)throw new IOException("网易云搜索返回状态 "+root.optInt("code"));
        if(result==null)throw new IOException("网易云搜索响应格式变化，请稍后重试");
        JSONArray songs=result.optJSONArray("songs"),out=new JSONArray();if(songs==null)return out;
        for(int i=0;i<Math.min(limit,songs.length());i++){
            JSONObject s=songs.getJSONObject(i);String id=text(s,"id");if(id.isEmpty())continue;
            JSONArray artists=s.optJSONArray("ar");if(artists==null)artists=s.optJSONArray("artists");
            JSONObject album=s.optJSONObject("al");if(album==null)album=s.optJSONObject("album");
            JSONObject t=base("netease",id,text(s,"name"),artistNames(artists)).put("keyword",keyword).put("neteaseProvider","public").put("fee",s.optInt("fee",0)).put("durationMs",s.optLong("dt",s.optLong("duration",0)));
            if(album!=null)t.put("cover",imageUrl(text(album,"picUrl"))).put("album",text(album,"name"));
            out.put(t);
        }
        return out;
    }
    public static JSONArray search(String source,String keyword,int limit)throws Exception{
        if(source.equals("netease")){
            String query="?s="+enc(keyword)+"&type=1&limit="+limit+"&offset=0";
            try{return parseNeteaseSearch(get("https://music.163.com/api/cloudsearch/pc"+query),keyword,limit);}
            catch(Exception first){try{return parseNeteaseSearch(get("https://music.163.com/api/search/get"+query),keyword,limit);}catch(Exception second){throw new IOException("网易云搜索暂不可用："+second.getMessage(),second);}}
        }
        JSONArray out=new JSONArray();
        if(source.equals("qq")){
            return new QQSearch().search(keyword,limit);
        }else if(source.equals("kuwo")){
            JSONObject raw=new JSONObject(get("https://oiapi.net/api/Kuwo?msg="+enc(keyword)+"&page=1&limit="+limit));
            JSONArray songs=raw.optJSONArray("data");if(songs==null)throw new IOException("酷我搜索响应格式变化");
            for(int i=0;i<Math.min(limit,songs.length());i++){
                JSONObject s=songs.getJSONObject(i);String id=text(s,"rid");if(id.isEmpty())continue;
                out.put(base(source,id,first(s,"song","name"),first(s,"singer","artist")).put("keyword",keyword).put("cover",imageUrl(text(s,"picture"))).put("displayIndex",i+1));
            }
        }else throw new IOException("暂不支持此音乐源");
        return out;
    }
    public static JSONArray searchPage(String source,String keyword,int offset)throws Exception{
        if(offset<=0)return search(source,keyword,20);
        offset=Math.min(2000,offset);
        if(source.equals("netease"))return parseNeteaseSearch(get("https://music.163.com/api/cloudsearch/pc?s="+enc(keyword)+"&type=1&limit=20&offset="+offset),keyword,20);
        if(source.equals("qq"))return QQSearch.parse(get("https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w="+enc(keyword)+"&format=json&n=20&p="+(offset/20+1)),keyword,20);
        if(source.equals("kuwo")){
            JSONObject raw=new JSONObject(get("https://oiapi.net/api/Kuwo?msg="+enc(keyword)+"&page="+(offset/20+1)+"&limit=20"));JSONArray songs=raw.optJSONArray("data"),out=new JSONArray();if(songs==null)throw new IOException("酷我分页搜索暂不可用");
            for(int i=0;i<Math.min(20,songs.length());i++){JSONObject row=songs.getJSONObject(i);String id=text(row,"rid");if(!id.isEmpty())out.put(base(source,id,first(row,"song","name"),first(row,"singer","artist")).put("keyword",keyword).put("cover",imageUrl(text(row,"picture"))).put("displayIndex",offset+i+1));}return out;
        }
        throw new IOException("不支持的音乐源");
    }
    static JSONObject parseQQDetails(JSONObject original,JSONObject response)throws Exception{
        JSONObject d=response.optJSONObject("data");if(d==null)d=response;
        String expected=first(original,"songid","songMid","qqId"),actual=text(d,"song_mid");
        if(!actual.isEmpty()&&!expected.equals(actual))throw new IOException("QQ 返回的歌曲与选择不一致，请重新搜索");
        JSONObject t=new JSONObject(original.toString());JSONArray candidates=new JSONArray();Set<String> seen=new HashSet<>();
        String[] keys={"song_play_url_standard","song_play_url_hq","song_play_url","song_play_url_fq","song_play_url_sq","song_play_url_pq"};
        for(String key:keys){String value=text(d,key);if(value.isEmpty())continue;try{String u=MediaUrlPolicy.normalize(value);if(seen.add(u))candidates.put(u);}catch(IOException ignored){}}
        if(candidates.length()==0)throw new IOException("QQ 暂未提供可用播放地址，可能是权限、地区或接口限制");
        t.put("audioUrl",candidates.getString(0)).put("audioCandidates",candidates).put("lrc",first(d,"song_lyric","lyric")).put("cover",imageUrl(first(d,"album_pic","singer_pic")));
        return t;
    }
    static JSONObject parseNeteasePlayback(JSONObject original,String raw)throws Exception{
        JSONArray rows=new JSONObject(raw).optJSONArray("data");JSONObject d=rows==null?null:rows.optJSONObject(0);
        if(d==null||text(d,"url").isEmpty())throw new IOException("网易云未提供播放地址：可能需要平台登录、会员或地区授权，可换其他来源");
        if(!text(d,"id").equals(text(original,"songid")))throw new IOException("网易云返回的歌曲标识不一致");
        JSONObject t=new JSONObject(original.toString());t.remove("trialDetectedByDuration");t.remove("neteaseFallbackAttempted");t.remove("audioCandidates");t.remove("resolvedQuality");t.remove("playbackProvider");t.put("audioUrl",MediaUrlPolicy.normalize(text(d,"url")));
        t.put("lrcUrl","https://music.163.com/api/song/lyric?id="+enc(text(t,"songid"))+"&lv=-1&kv=-1&tv=-1");
        t.put("trial",d.optJSONObject("freeTrialInfo")!=null||PreviewPolicy.isShortPreview(t.optLong("durationMs",0),d.optLong("time",0)));return t;
    }
    public static JSONObject resolve(JSONObject original)throws Exception{
        JSONObject t=new JSONObject(original.toString());String source=text(t,"source"),id=first(t,"songid","songMid","qqId");
        if(source.equals("local"))return t;
        if(source.equals("netease")){
            if(!id.matches("[0-9]+"))throw new IOException("旧歌单中的网易云歌曲缺少有效标识，请重新搜索");
            return new NeteaseResolver().resolve(t);
        }
        if(source.equals("qq")){
            String keyword=first(t,"qqSearchKey","keyword");if(keyword.isEmpty())keyword=text(t,"title")+" "+text(t,"artist");
            return parseQQDetails(t,new JSONObject(get("https://tang.api.s01s.cn/music_open_api.php?msg="+enc(keyword)+"&type=json&mid="+enc(id))));
        }
        if(source.equals("kuwo")){
            JSONObject raw=new JSONObject(get("https://oiapi.net/api/Kuwo?msg="+enc(text(t,"keyword"))+"&n="+t.optInt("displayIndex",1)+"&br=1"));
            JSONObject d=raw.optJSONObject("data");if(d==null)d=raw;
            String actual=text(d,"rid");if(!actual.isEmpty()&&!actual.equals(id))throw new IOException("酷我搜索排序已变化，请重新搜索后播放");
            t.put("audioUrl",first(d,"url","music")).put("lrc",first(d,"lyric","lrc","lyrics")).put("cover",imageUrl(first(d,"picture","pic")));
        }else throw new IOException("暂不支持此音乐源");
        String url=text(t,"audioUrl");if(url.isEmpty())throw new IOException("来源未提供可播放地址，请换一个来源");
        t.put("audioUrl",MediaUrlPolicy.normalize(url));return t;
    }
    public static String lyrics(JSONObject t){
        String lyric=text(t,"lrc");if(!lyric.isEmpty())return lyric;
        try{String url=text(t,"lrcUrl");if(url.isEmpty())return "";String raw=get(url);if(!raw.trim().startsWith("{"))return raw;
            JSONObject o=new JSONObject(raw);JSONObject lrc=o.optJSONObject("lrc");if(lrc!=null)return text(lrc,"lyric");
            String direct=first(o,"lyric","lrc");if(!direct.isEmpty())return direct;
            JSONObject data=o.optJSONObject("data");return data==null?"":first(data,"lyric","lrc");
        }catch(Exception e){return "";}
    }
}
