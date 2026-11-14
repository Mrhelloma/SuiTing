package cn.musicsquare.app;
import org.json.*;
import java.net.URI;
import java.util.*;

/** Small persistent URL cache. Audio bytes are never stored here. */
final class PlaybackCache {
 interface Store{String read();void write(String value);}
 interface Clock{long now();}
 interface Resolver{JSONObject resolve(JSONObject track)throws Exception;}
 private final Store store;private final Clock clock;private final Resolver other;
 private JSONObject entries=new JSONObject(),previews=new JSONObject();
 PlaybackCache(Store store){this(store,System::currentTimeMillis,MusicApi::resolve);}
 PlaybackCache(Store store,Clock clock,Resolver other){this.store=store;this.clock=clock;this.other=other;try{JSONObject all=new JSONObject(store.read());entries=all.optJSONObject("urls");previews=all.optJSONObject("previews");}catch(Exception ignored){}if(entries==null)entries=new JSONObject();if(previews==null)previews=new JSONObject();}
 static String key(JSONObject t){return t.optString("source")+":"+t.optString("songid",t.optString("uid"));}
 synchronized JSONObject get(JSONObject t){try{JSONObject e=entries.optJSONObject(key(t));if(e==null)return null;if(e.optLong("until")<=clock.now()){entries.remove(key(t));persist();return null;}JSONObject result=new JSONObject(t.toString()),cached=e.getJSONObject("track");for(String k:new String[]{"audioUrl","audioCandidates","playbackProvider","resolvedQuality","lrcUrl","lrc","durationMs"})if(cached.has(k))result.put(k,cached.get(k));result.remove("trialDetectedByDuration");result.remove("neteaseFallbackAttempted");result.put("trial",false).put("audioFromCache",true);return result;}catch(Exception e){return null;}}
 JSONObject resolve(JSONObject t,boolean force)throws Exception{
  if(t.optString("source").equals("local"))return t;
  if(force)invalidate(t);else{JSONObject hit=get(t);if(hit!=null)return hit;}
  JSONObject r=t.optString("source").equals("netease")?new NeteaseResolver(MusicApi::get,this::recentPreview,this::markPreview).resolve(t):other.resolve(t);
  r.put("audioFromCache",false);put(r);return r;
 }
 synchronized void put(JSONObject t){try{if(t.optBoolean("trial")||t.optString("audioUrl").isEmpty()||t.optString("source").equals("local"))return;long now=clock.now(),ttl=t.optString("source").equals("netease")?10*60000L:5*60000L;long until=now+ttl;
  URI uri=new URI(t.getString("audioUrl"));String path=uri.getPath();if(path!=null){java.util.regex.Matcher m=java.util.regex.Pattern.compile("/(20[0-9]{12})/").matcher(path);if(m.find()){java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));until=Math.min(until,f.parse(m.group(1)).getTime()-30000);}}
  if(until<=now)return;
  JSONObject compact=new JSONObject();for(String k:new String[]{"audioUrl","audioCandidates","playbackProvider","resolvedQuality","lrcUrl","durationMs"})if(t.has(k))compact.put(k,t.get(k));entries.put(key(t),new JSONObject().put("until",until).put("track",compact));trim(entries,80);persist();
 }catch(Exception ignored){}}
 synchronized void invalidate(JSONObject t){entries.remove(key(t));persist();}
 synchronized boolean recentPreview(JSONObject t){return previews.optLong(key(t),0)>clock.now();}
 synchronized void markPreview(JSONObject t){try{previews.put(key(t),clock.now()+30*60000L);entries.remove(key(t));trim(previews,120);persist();}catch(Exception ignored){}}
 private void trim(JSONObject obj,int max){while(obj.length()>max)obj.remove(obj.keys().next());}
 private void persist(){try{store.write(new JSONObject().put("urls",entries).put("previews",previews).toString());}catch(Exception ignored){}}
}
