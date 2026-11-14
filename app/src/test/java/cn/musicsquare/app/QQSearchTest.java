package cn.musicsquare.app;
import org.junit.Test;
import org.json.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class QQSearchTest {
    private String primary()throws Exception{return new JSONArray().put(new JSONObject().put("song_mid","mid-1").put("song_title","测试歌曲").put("singer_name","测试歌手")).toString();}
    private String official(int start,int count)throws Exception{JSONArray rows=new JSONArray();for(int i=start;i<start+count;i++)rows.put(new JSONObject().put("songmid","mid-"+i).put("songname","测试歌曲 "+i).put("interval",240).put("singer",new JSONArray().put(new JSONObject().put("name","测试歌手"))));return new JSONObject().put("code",0).put("data",new JSONObject().put("song",new JSONObject().put("list",rows))).toString();}
    @Test public void positivePrimaryAvoidsExtraRequests()throws Exception{AtomicInteger calls=new AtomicInteger();JSONArray rows=new QQSearch(url->{calls.incrementAndGet();return primary();}).search("测试",1);assertEquals(1,rows.length());assertEquals(1,calls.get());}
    @Test public void emptyPrimaryFallsBackToPublicCatalog()throws Exception{JSONArray rows=new QQSearch(url->url.contains("tang.api")?"[]":official(0,30)).search("测试",20);assertEquals(20,rows.length());assertTrue(rows.getJSONObject(0).getBoolean("qqCatalogFallback"));assertEquals("mid-0",rows.getJSONObject(0).getString("songid"));assertEquals(240000,rows.getJSONObject(0).getLong("durationMs"));}
    @Test public void officialCatalogPaginatesUpToForty()throws Exception{JSONArray rows=new QQSearch(url->url.contains("tang.api")?"[]":official(url.contains("p=2")?30:0,30)).search("测试",40);assertEquals(40,rows.length());assertEquals("qq-mid-39",rows.getJSONObject(39).getString("uid"));}
    @Test public void secondPageFailureKeepsValidFirstPage()throws Exception{JSONArray rows=new QQSearch(url->{if(url.contains("tang.api"))return "[]";if(url.contains("p=2"))throw new IOException();return official(0,30);}).search("测试",40);assertEquals(30,rows.length());}
    @Test public void bothFailuresDoNotPretendThereAreNoSongs()throws Exception{AtomicInteger calls=new AtomicInteger();try{new QQSearch(url->{calls.incrementAndGet();throw new IOException();}).search("周杰伦",20);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("暂时不可用"));}assertEquals(3,calls.get());}
    @Test public void transientPrimaryCanRecoverOnBoundedRetry()throws Exception{AtomicInteger primaryCalls=new AtomicInteger();JSONArray rows=new QQSearch(url->{if(url.contains("tang.api")){if(primaryCalls.incrementAndGet()==1)throw new IOException();return primary();}throw new IOException();}).search("测试",20);assertEquals(1,rows.length());assertEquals(2,primaryCalls.get());}
    @Test public void genuineEmptyAndJsonpAreHandled()throws Exception{assertEquals(0,new QQSearch(url->url.contains("tang.api")?"[]":official(0,0)).search("不存在的测试",20).length());assertEquals(1,QQSearch.parse("callback("+official(0,1)+");","测试",20).length());}
    @Test public void malformedRowsAreNotSilentlyCalledEmpty()throws Exception{try{QQSearch.parse("[{\"name\":\"没有歌曲标识\"}]","测试",20);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("标识"));}}
    @Test public void shortPrimaryListCanBeToppedUpWithoutDuplicateSongs()throws Exception{JSONArray rows=new QQSearch(url->url.contains("tang.api")?primary():official(0,30)).search("测试",20);assertEquals(20,rows.length());java.util.Set<String> ids=new java.util.HashSet<>();for(int i=0;i<rows.length();i++)assertTrue(ids.add(rows.getJSONObject(i).getString("uid")));}
    @Test public void catalogFirstPageDuplicatesStillAllowSecondPageTopUp()throws Exception{JSONArray rows=new QQSearch(url->{if(url.contains("tang.api")){JSONArray a=new JSONArray();for(int i=0;i<30;i++)a.put(new JSONObject().put("song_mid","mid-"+i).put("song_title","歌曲 "+i).put("singer_name","测试歌手"));return a.toString();}return official(url.contains("p=2")?30:0,30);}).search("测试",40);assertEquals(40,rows.length());}
}
