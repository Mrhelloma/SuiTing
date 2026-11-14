package cn.musicsquare.app;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Anonymous NetEase catalog. Recommendations are editorial, not personal predictions. */
final class CatalogApi {
 interface Fetcher{String get(String url)throws Exception;}
 private final Fetcher fetcher;
 private static final Map<String,JSONObject> cache=new LinkedHashMap<>();
 private static final Map<String,Long> times=new HashMap<>();
 CatalogApi(){this(MusicApi::get);}CatalogApi(Fetcher fetcher){this.fetcher=fetcher;}
 JSONObject request(JSONObject p)throws Exception{
  String op=p.optString("operation"),kind=p.optString("kind","song"),id=p.optString("id");int offset=Math.max(0,Math.min(2000,p.optInt("offset"))),limit=20;
  if(!op.equals("search")&&!op.equals("recommend")&&!op.equals("hot")&&!id.matches("[0-9]+"))throw new IOException("无效的在线内容 ID");
  String key=p.toString();synchronized(cache){if(!p.optBoolean("refresh",false)&&times.getOrDefault(key,0L)>System.currentTimeMillis())return new JSONObject(cache.get(key).toString());}
  JSONObject out;
  if(op.equals("search")){
   String kw=p.optString("keyword").trim();if(kw.isEmpty()||kw.length()>100)throw new IOException("请输入 1–100 字关键词");
   if(!kind.equals("artist")&&!kind.equals("playlist"))throw new IOException("不支持的搜索类型");
   JSONObject root=json("https://music.163.com/api/cloudsearch/pc?s="+MusicApi.enc(kw)+"&type="+(kind.equals("artist")?100:1000)+"&limit=20&offset="+offset);
   JSONObject result=root.optJSONObject("result");if(result==null)throw new IOException("搜索响应格式异常");String plural=kind.equals("artist")?"artists":"playlists";JSONArray rows=result.optJSONArray(plural),items=new JSONArray();
   if(rows!=null)for(int i=0;i<rows.length();i++)items.put(entity(rows.getJSONObject(i),kind));
   int total=result.optInt(kind.equals("artist")?"artistCount":"playlistCount",offset+items.length());out=new JSONObject().put("items",items).put("hasMore",items.length()==20&&offset+20<total).put("offset",offset+items.length());
  }else if(op.equals("recommend")){
   JSONArray rows=json("https://music.163.com/api/personalized/playlist?limit=8").optJSONArray("result");if(rows==null)throw new IOException("推荐暂不可用");JSONArray items=new JSONArray();for(int i=0;i<Math.min(8,rows.length());i++)items.put(entity(rows.getJSONObject(i),"playlist"));out=new JSONObject().put("items",items).put("label","网易云精选 · 非个性化推荐");
  }else if(op.equals("artist")){
   JSONObject root=json("https://music.163.com/api/v1/artist/"+id);JSONObject artist=root.optJSONObject("artist");if(artist==null||!id.equals(MusicApi.text(artist,"id")))throw new IOException("歌手信息不一致");JSONArray all=root.optJSONArray("hotSongs");if(all==null)all=new JSONArray();out=detail(entity(artist,"artist"),slice(all,offset,limit),all.length(),offset);out.put("description","热门歌曲 · 平台当前提供的曲目");
  }else if(op.equals("playlist")||op.equals("hot")){
   if(op.equals("hot"))id="3778678";
   JSONObject root=json("https://music.163.com/api/v6/playlist/detail?id="+id+"&n=20&s=0"),pl=root.optJSONObject("playlist");if(pl==null||!id.equals(MusicApi.text(pl,"id")))throw new IOException("歌单信息不一致");
   JSONArray ids=pl.optJSONArray("trackIds"),all=pl.optJSONArray("tracks");if(all==null)all=new JSONArray();int total=ids!=null?ids.length():all.length();JSONArray rows;
   if(offset==0&&all.length()>=Math.min(limit,total))rows=slice(all,offset,limit);
   else if(ids!=null&&offset<ids.length()){
    JSONArray selected=new JSONArray();for(int i=offset;i<Math.min(ids.length(),offset+limit);i++)selected.put(ids.getJSONObject(i).get("id"));
    JSONArray found=json("https://music.163.com/api/song/detail?ids="+MusicApi.enc(selected.toString())).optJSONArray("songs");if(found==null)throw new IOException("歌单曲目暂不可用");
    Map<String,JSONObject> byId=new HashMap<>();for(int i=0;i<found.length();i++)byId.put(MusicApi.text(found.getJSONObject(i),"id"),found.getJSONObject(i));rows=new JSONArray();for(int i=0;i<selected.length();i++){JSONObject t=byId.get(String.valueOf(selected.get(i)));if(t!=null)rows.put(t);}
   }else rows=slice(all,offset,limit);
   out=detail(entity(pl,"playlist"),rows,total,offset);out.put("description","在线歌单 · 可加载更多曲目");
   if(pl.optInt("trackCount",total)>total)out.put("notice","平台匿名接口仅提供部分曲目");
  }else throw new IOException("不支持的目录操作");
  synchronized(cache){while(cache.size()>=40){String first=cache.keySet().iterator().next();cache.remove(first);times.remove(first);}cache.put(key,new JSONObject(out.toString()));times.put(key,System.currentTimeMillis()+(op.equals("search")?60000:15*60000));}
  return out;
 }
 private JSONObject json(String url)throws Exception{JSONObject r=parseObject(fetcher.get(url));if(r.optInt("code",200)!=200)throw new IOException("网易云目录暂不可用（"+r.optInt("code")+"）");return r;}
 // Some anonymous catalog responses repeat metadata keys; keep the last value,
 // matching Android's JSON parser rather than failing JVM validation.
 static JSONObject parseObject(String raw)throws Exception{JSONTokener tok=new JSONTokener(raw);Object value=readJson(tok,0);if(!(value instanceof JSONObject)||tok.nextClean()!=0)throw new IOException("目录 JSON 格式异常");return (JSONObject)value;}
 private static Object readJson(JSONTokener tok,int depth)throws Exception{
  if(depth>64)throw new IOException("目录数据层级过深");char c=tok.nextClean();
  if(c=='{'){JSONObject out=new JSONObject();if(tok.nextClean()=='}')return out;tok.back();while(true){Object k=tok.nextValue();if(!(k instanceof String)||tok.nextClean()!=':')throw new IOException("目录对象格式异常");out.put((String)k,readJson(tok,depth+1));c=tok.nextClean();if(c=='}')return out;if(c!=',')throw new IOException("目录对象分隔异常");}}
  if(c=='['){JSONArray out=new JSONArray();if(tok.nextClean()==']')return out;tok.back();while(true){out.put(readJson(tok,depth+1));c=tok.nextClean();if(c==']')return out;if(c!=',')throw new IOException("目录数组分隔异常");}}
  if(c==0)throw new IOException("目录响应为空");tok.back();return tok.nextValue();
 }
 static JSONArray slice(JSONArray all,int offset,int limit)throws Exception{JSONArray rows=new JSONArray();for(int i=offset;i<Math.min(all.length(),offset+limit);i++)rows.put(all.getJSONObject(i));return rows;}
 static JSONObject entity(JSONObject row,String kind)throws Exception{
  String id=MusicApi.text(row,"id");if(!id.matches("[0-9]+"))throw new IOException("目录缺少稳定 ID");JSONObject creator=row.optJSONObject("creator");
  return new JSONObject().put("id",id).put("source","netease").put("kind",kind).put("name",MusicApi.text(row,"name")).put("cover",MusicApi.imageUrl(MusicApi.first(row,"coverImgUrl","picUrl","img1v1Url"))).put("subtitle",kind.equals("artist")?row.optInt("musicSize",0)+" 首歌曲":(creator==null?"网易云精选":creator.optString("nickname","网易云用户"))).put("count",row.optInt("trackCount",row.optInt("musicSize",0)));
 }
 static JSONObject detail(JSONObject entity,JSONArray rows,int total,int offset)throws Exception{
  JSONArray tracks=MusicApi.parseNeteaseSearch(new JSONObject().put("result",new JSONObject().put("songs",rows)).toString(),"",rows.length());
  return new JSONObject().put("entity",entity).put("tracks",tracks).put("total",total).put("offset",offset+rows.length()).put("hasMore",rows.length()>0&&offset+rows.length()<total);
 }
}
