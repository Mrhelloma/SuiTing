package cn.musicsquare.app;
import org.json.*;
/** Public search metadata only; forces the fallback path without changing production endpoints. */
public class LiveQQSearchProbe {
 public static void main(String[] args)throws Exception{
  JSONArray normal=new QQSearch().search("周杰伦",40);if(normal.length()==0)throw new AssertionError("Normal QQ search was empty");
  System.out.println("PASS normal QQ search 周杰伦: "+normal.length()+" songs");
  JSONArray backup=new QQSearch(url->url.contains("tang.api")?"[]":MusicApi.get(url,3500,5000)).search("周杰伦",40);
  if(backup.length()!=40||!backup.getJSONObject(0).optBoolean("qqCatalogFallback"))throw new AssertionError("Fallback catalog incomplete");
  for(int i=0;i<backup.length();i++)if(backup.getJSONObject(i).optString("songMid").isEmpty())throw new AssertionError("Missing stable song mid");
  System.out.println("PASS forced-empty primary -> QQ public catalog: "+backup.length()+" songs, pagination and stable mids verified");
  System.out.println("Scope: production Java search adapter only; not an Android-device network guarantee.");
 }
}
