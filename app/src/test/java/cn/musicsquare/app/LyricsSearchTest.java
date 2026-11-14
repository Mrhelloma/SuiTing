package cn.musicsquare.app;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class LyricsSearchTest {
    private static final String LRC="[00:00.00]测试开始\n[00:10.00]下一句测试\n[00:20.00]测试结束";
    private JSONObject target()throws Exception{return new JSONObject().put("title","三国杀").put("artist","汪苏泷").put("durationMs",227000);}
    private String metadata(String title,String artist,long duration)throws Exception{
        return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",new JSONArray().put(new JSONObject().put("id",123).put("name",title).put("ar",new JSONArray().put(new JSONObject().put("name",artist))).put("dt",duration)))).toString();
    }
    @Test public void exactTitleArtistDurationAutoMatchesSyncedLyrics()throws Exception{
        LyricsSearch s=new LyricsSearch(url->url.contains("cloudsearch")?metadata("三国杀","汪苏泷",227690):new JSONObject().put("lrc",new JSONObject().put("lyric",LRC)).toString());
        JSONObject result=s.find(target());assertNotNull(result.optJSONObject("automatic"));assertEquals(LRC,result.getJSONObject("automatic").getString("lrc"));assertEquals("synced",result.getJSONObject("automatic").getString("kind"));
    }
    @Test public void sameTitleDifferentSingerRequiresConfirmation()throws Exception{
        LyricsSearch s=new LyricsSearch(url->{if(url.contains("cloudsearch"))return metadata("三国杀","其他歌手",227690);if(url.contains("api/song/lyric"))return new JSONObject().put("lrc",new JSONObject().put("lyric",LRC)).toString();return "[]";});
        JSONObject r=s.find(target());assertNull(r.optJSONObject("automatic"));assertEquals(1,r.getJSONArray("candidates").length());
    }
    @Test public void mismatchedRecordingDurationIsNotAutoApplied()throws Exception{
        LyricsSearch s=new LyricsSearch(url->{if(url.contains("cloudsearch"))return metadata("三国杀","汪苏泷",171000);if(url.contains("api/song/lyric"))return new JSONObject().put("lrc",new JSONObject().put("lyric",LRC)).toString();return "[]";});
        assertNull(s.find(target()).optJSONObject("automatic"));
    }
    @Test public void liveVersionIsNotTreatedAsStudioVersion()throws Exception{
        JSONObject c=new JSONObject().put("title","三国杀 (Live)").put("artist","汪苏泷").put("durationMs",227000);assertFalse(LyricsSearch.highConfidence(target(),c));
    }
    @Test public void collaborationNamesAndEnglishAliasesMatch()throws Exception{
        assertTrue(LyricsSearch.artistMatches("汪苏泷 (Silence Wang)/BY2","汪苏泷 / By2"));assertFalse(LyricsSearch.artistMatches("汪苏泷 / BY2","汪苏泷"));
    }
    @Test public void unknownDurationOrArtistRequiresConfirmation()throws Exception{
        JSONObject c=new JSONObject(target().toString());c.put("durationMs",0);assertFalse(LyricsSearch.highConfidence(target(),c));c=new JSONObject(target().toString()).put("artist","本地音频");assertFalse(LyricsSearch.highConfidence(c,target()));
    }
    @Test public void independentLyricsProviderIsUsedAsFallback()throws Exception{
        LyricsSearch s=new LyricsSearch(url->{if(url.contains("music.163.com"))throw new java.io.IOException("offline provider");return new JSONArray().put(new JSONObject().put("id",456).put("trackName","三国杀").put("artistName","汪苏泷").put("duration",227).put("syncedLyrics",LRC)).toString();});
        JSONObject r=s.find(target());assertEquals("LRCLIB",r.getJSONObject("automatic").getString("provider"));
    }
    @Test public void plainLyricsAreNotGivenFakeTimestamps()throws Exception{
        LyricsSearch s=new LyricsSearch(url->{if(url.contains("music.163.com"))throw new java.io.IOException();return new JSONArray().put(new JSONObject().put("id",456).put("trackName","三国杀").put("artistName","汪苏泷").put("duration",227).put("plainLyrics","只有文本歌词")).toString();});
        JSONObject r=s.find(target());assertNull(r.optJSONObject("automatic"));assertEquals("plain",r.getJSONArray("candidates").getJSONObject(0).getString("kind"));assertEquals("",r.getJSONArray("candidates").getJSONObject(0).getString("lrc"));
    }
    @Test public void noResultsAreReportedWithoutAnIncorrectMatch()throws Exception{
        LyricsSearch s=new LyricsSearch(url->url.contains("music.163.com")?"{\"code\":200,\"result\":{\"songs\":[]}}":"[]");JSONObject r=s.find(target());assertNull(r.optJSONObject("automatic"));assertEquals(0,r.getJSONArray("candidates").length());
    }
    @Test public void lrcNormalizationKeepsARealTimeAxis(){
        assertTrue(LyricsSearch.hasTimedLyrics("\uFEFF[00:10.00]测试&#10;[00:20.00]测试二"));assertFalse(LyricsSearch.hasTimedLyrics("普通歌词，没有时间戳"));
    }
}
