package cn.musicsquare.app;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class MusicApiTest {
    @Test public void qqHttpCdnLinkIsRetained()throws Exception{
        JSONObject song=MusicApi.base("qq","mid-1","示例歌曲","示例歌手");
        JSONObject details=new JSONObject().put("song_mid","mid-1").put("song_play_url_standard","http://isure6.stream.qqmusic.qq.com/C400example.m4a?vkey=test");
        assertEquals(details.getString("song_play_url_standard"),MusicApi.parseQQDetails(song,details).getString("audioUrl"));
    }
    @Test public void missingAndJsonNullUrlsDoNotMaskWorkingAlternatives()throws Exception{
        JSONObject song=MusicApi.base("qq","mid-1","示例歌曲","示例歌手");
        JSONObject details=new JSONObject().put("song_mid","mid-1").put("song_play_url_standard",JSONObject.NULL).put("song_play_url_hq","  https://isure6.stream.qqmusic.qq.com/file.mp3  ");
        assertEquals("https://isure6.stream.qqmusic.qq.com/file.mp3",MusicApi.parseQQDetails(song,details).getString("audioUrl"));
    }
    @Test public void fallbackCandidatesExcludeAccompanimentAndDuplicates()throws Exception{
        JSONObject song=MusicApi.base("qq","mid-1","示例歌曲","示例歌手");
        JSONObject details=new JSONObject().put("song_play_url_standard","http://isure6.stream.qqmusic.qq.com/standard.m4a").put("song_play_url","http://isure6.stream.qqmusic.qq.com/standard.m4a").put("song_play_url_hq","http://isure6.stream.qqmusic.qq.com/hq.mp3").put("song_play_url_accom","http://isure6.stream.qqmusic.qq.com/accompaniment.mp3");
        JSONArray alternatives=MusicApi.parseQQDetails(song,details).getJSONArray("audioCandidates");
        assertEquals(2,alternatives.length());assertFalse(alternatives.toString().contains("accompaniment"));
    }
    @Test public void wrongSongCannotReplaceSelectedTrack()throws Exception{
        JSONObject song=MusicApi.base("qq","mid-1","示例歌曲","示例歌手");
        try{MusicApi.parseQQDetails(song,new JSONObject().put("song_mid","mid-2"));fail("must reject mismatched song");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("不一致"));}
    }
    @Test public void neteaseCloudAndLegacySchemasAreBothSupported()throws Exception{
        JSONArray modern=MusicApi.parseNeteaseSearch("{\"code\":200,\"result\":{\"songs\":[{\"id\":123,\"name\":\"示例歌曲\",\"ar\":[{\"name\":\"甲\"},{\"name\":\"乙\"}],\"al\":{\"name\":\"专辑\",\"picUrl\":\"http://p1.music.126.net/cover.jpg\"}}]}}","甲",20);
        assertEquals("123",modern.getJSONObject(0).getString("songid"));assertEquals("甲 / 乙",modern.getJSONObject(0).getString("artist"));assertEquals("https://p1.music.126.net/cover.jpg",modern.getJSONObject(0).getString("cover"));
        JSONArray old=MusicApi.parseNeteaseSearch("{\"result\":{\"songs\":[{\"id\":456,\"name\":\"旧结构\",\"artists\":[{\"name\":\"甲\"}],\"album\":{\"name\":\"专辑\"}}]}}","甲",20);
        assertEquals("甲",old.getJSONObject(0).getString("artist"));assertEquals("netease-456",old.getJSONObject(0).getString("uid"));
    }
    @Test public void unavailablePlatformPlaybackIsReportedHonestly()throws Exception{
        JSONObject song=MusicApi.base("netease","123","示例歌曲","示例歌手");
        try{MusicApi.parseNeteasePlayback(song,"{\"code\":200,\"data\":[{\"id\":123,\"url\":null,\"code\":404}]}");fail("must report unavailable resource");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("授权"));}
    }
    @Test public void trialPlaybackIsMarked()throws Exception{
        JSONObject song=MusicApi.base("netease","123","示例歌曲","示例歌手");
        JSONObject resolved=MusicApi.parseNeteasePlayback(song,"{\"data\":[{\"id\":123,\"url\":\"http://m801.music.126.net/example.mp3\",\"freeTrialInfo\":{\"start\":0,\"end\":30}}]}");
        assertTrue(resolved.getBoolean("trial"));assertTrue(resolved.getString("lrcUrl").contains("id=123"));
    }
    @Test public void httpAllowlistCannotBeSpoofed()throws Exception{
        for(String u:new String[]{"http://qqmusic.qq.com.evil.example/audio.mp3","http://evilqqmusic.qq.com/audio.mp3","http://127.0.0.1/music","file:///etc/passwd","javascript:alert(1)","http://user:pass@isure6.stream.qqmusic.qq.com/audio"}){
            try{MediaUrlPolicy.normalize(u);fail("must reject "+u);}catch(java.io.IOException expected){}
        }
        assertEquals("https://isure6.stream.qqmusic.qq.com/audio",MediaUrlPolicy.normalize("//isure6.stream.qqmusic.qq.com/audio"));
        assertEquals("http://kw-er.kuwo.cn/file.flac",MediaUrlPolicy.normalize("http://kw-er.kuwo.cn/file.flac"));
    }

    @Test public void shortPreviewDetectionDoesNotMislabelShortSongs(){
        assertTrue(PreviewPolicy.isShortPreview(240000,30000));
        assertTrue(PreviewPolicy.isShortPreview(240000,60000));
        assertFalse(PreviewPolicy.isShortPreview(60000,59000));
        assertFalse(PreviewPolicy.isShortPreview(240000,239000));
        assertFalse(PreviewPolicy.isShortPreview(240000,0));
        assertFalse(PreviewPolicy.isShortPreview(0,30000));
    }
    @Test public void neteaseSearchKeepsFullSongDuration()throws Exception{
        JSONArray modern=MusicApi.parseNeteaseSearch("{\"code\":200,\"result\":{\"songs\":[{\"id\":123,\"name\":\"示例歌曲\",\"dt\":240000}]}}","示例",20);
        assertEquals(240000,modern.getJSONObject(0).getLong("durationMs"));
        JSONArray legacy=MusicApi.parseNeteaseSearch("{\"result\":{\"songs\":[{\"id\":123,\"name\":\"示例歌曲\",\"duration\":240000}]}}","示例",20);
        assertEquals(240000,legacy.getJSONObject(0).getLong("durationMs"));
    }
}
