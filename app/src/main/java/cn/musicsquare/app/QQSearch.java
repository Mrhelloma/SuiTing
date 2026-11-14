package cn.musicsquare.app;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Distinguishes real empty results from a failing provider and falls back to QQ's public catalog. */
public final class QQSearch {
    interface Fetcher {String get(String url)throws Exception;}
    private final Fetcher fetch;
    public QQSearch(){this(url->MusicApi.get(url,3500,5000));}
    QQSearch(Fetcher fetch){this.fetch=fetch;}
    static JSONArray parse(String raw,String keyword,int limit)throws Exception{
        String json=raw.trim();if(!json.startsWith("{")&&!json.startsWith("[")){
            int left=json.indexOf('('),right=json.lastIndexOf(')');
            if(left<1||right<=left||!json.substring(0,left).trim().matches("[A-Za-z_$][A-Za-z0-9_$]*"))throw new IOException("QQ 搜索返回格式异常");
            json=json.substring(left+1,right);
        }
        Object value=new JSONTokener(json).nextValue();JSONArray rows=null;
        if(value instanceof JSONArray)rows=(JSONArray)value;
        else if(value instanceof JSONObject){
            JSONObject root=(JSONObject)value;int code=root.optInt("code",200);
            if(code!=0&&code!=200)throw new IOException("QQ 搜索服务返回状态 "+code);
            Object data=root.opt("data");if(data instanceof JSONArray)rows=(JSONArray)data;
            else if(data instanceof JSONObject){JSONObject song=((JSONObject)data).optJSONObject("song");if(song!=null)rows=song.optJSONArray("list");}
        }
        if(rows==null)throw new IOException("QQ 搜索返回格式异常");
        JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length()&&out.length()<limit;i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String mid=MusicApi.first(row,"song_mid","songmid","mid");if(mid.isEmpty()||!seen.add(mid))continue;
            String title=MusicApi.first(row,"song_title","songname","song_name","name"),artist=MusicApi.text(row,"singer_name");
            if(artist.isEmpty())artist=MusicApi.artistNames(row.optJSONArray("singer"));if(title.isEmpty())continue;
            JSONObject track=MusicApi.base("qq",mid,title,artist).put("songMid",mid).put("keyword",keyword).put("qqSearchKey",keyword);
            String pay=row.opt("pay") instanceof String?MusicApi.text(row,"pay"):"";track.put("pay",pay);
            long seconds=row.optLong("interval",0);if(seconds>0)track.put("durationMs",seconds*1000);
            track.put("album",MusicApi.first(row,"albumname","album_name"));String cover=MusicApi.first(row,"album_pic","pic");if(!cover.isEmpty())track.put("cover",MusicApi.imageUrl(cover));
            out.put(track);
        }
        if(rows.length()>0&&out.length()==0)throw new IOException("QQ 搜索结果缺少有效歌曲标识");
        return out;
    }
    public JSONArray search(String keyword,int requested)throws Exception{
        int limit=Math.min(40,Math.max(1,requested));String primary="https://tang.api.s01s.cn/music_open_api.php?msg="+MusicApi.enc(keyword)+"&type=json";
        boolean primaryEmpty=false,officialEmpty=false;
        JSONArray rows=new JSONArray();Set<String> ids=new HashSet<>();
        try{JSONArray primaryRows=parse(fetch.get(primary),keyword,limit);for(int i=0;i<primaryRows.length();i++){JSONObject t=primaryRows.getJSONObject(i);if(ids.add(t.getString("uid")))rows.put(t);}if(rows.length()>=limit)return rows;primaryEmpty=rows.length()==0;}catch(Exception ignored){}
        try{
            for(int page=1;page<=2&&rows.length()<limit;page++){
                String url="https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp?w="+MusicApi.enc(keyword)+"&format=json&p="+page+"&n="+Math.min(limit,30);
                JSONArray batch;try{batch=parse(fetch.get(url),keyword,limit);}catch(Exception e){if(rows.length()>0)return rows;throw e;}int added=0;
                for(int i=0;i<batch.length()&&rows.length()<limit;i++){JSONObject t=batch.getJSONObject(i);if(ids.add(t.getString("uid"))){t.put("qqCatalogFallback",true);rows.put(t);added++;}}
                if(batch.length()<30)break;
            }
            if(rows.length()>0)return rows;officialEmpty=true;
        }catch(Exception ignored){if(rows.length()>0)return rows;}
        if(primaryEmpty&&officialEmpty)return new JSONArray();
        // One bounded retry for transient empty/error responses; no endless spinner or false 'no matches'.
        try{JSONArray retry=parse(fetch.get(primary),keyword,limit);if(retry.length()>0)return retry;if(officialEmpty)return retry;}catch(Exception ignored){}
        throw new IOException("QQ 搜索服务暂时不可用，请稍后重试，或同时选择其他来源");
    }
}
