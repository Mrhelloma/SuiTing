package cn.musicsquare.app;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
public class LiveNeteaseProbe {
 public static void main(String[] args)throws Exception{
  for(String[] s:new String[][]{{"108485","Always Online","林俊杰","225013"},{"165340","有点甜","汪苏泷 / By2","235200"},{"29814898","可惜没如果","林俊杰","298293"}}){
   JSONObject t=MusicApi.base("netease",s[0],s[1],s[2]).put("durationMs",Long.parseLong(s[3])).put("trial",true).put("trialDetectedByDuration",true);
   JSONObject r=MusicApi.resolve(t);
   if(!r.getString("uid").equals(t.getString("uid"))||!r.optString("playbackProvider").equals("wzapi")||r.optBoolean("trial"))throw new AssertionError("unexpected resolver result");
   Process p=new ProcessBuilder("ffprobe","-v","quiet","-rw_timeout","10000000","-user_agent","Mozilla/5.0","-headers","Referer: https://music.163.com/\r\n","-show_entries","format=duration","-of","json",r.getString("audioUrl")).redirectError(ProcessBuilder.Redirect.DISCARD).start();
   if(!p.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("media check timeout");}
   String raw=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);double actual=new JSONObject(raw).getJSONObject("format").getDouble("duration");
   if(Math.abs(actual*1000-Long.parseLong(s[3]))>3000)throw new AssertionError("duration mismatch");
   System.out.println("PASS production resolver: "+s[1]+" / "+s[2]+"; id="+s[0]+"; audio="+actual+"s; uid retained; old preview flags cleared; provider=wzapi");
  }
  System.out.println("Scope: real Java resolver and remote media duration; no new Android-device playback run.");
 }
}
