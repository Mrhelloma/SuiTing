package cn.musicsquare.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlaylistImportTest {
    @Test public void recognizesShareLinkFormats() throws Exception {
        assertEquals("123", PlaylistImport.playlistId(URI.create("https://music.163.com/#/playlist?id=123"), "netease"));
        assertEquals("123", PlaylistImport.playlistId(URI.create("https://i.y.qq.com/n2/m/share/details/taoge.html?id=123"), "qq"));
        assertEquals("123", PlaylistImport.playlistId(URI.create("https://m.kuwo.cn/newh5app/playlist_detail/123"), "kuwo"));
        assertEquals("", PlaylistImport.playlistId(URI.create("https://music.163.com/song?id=123"), "netease"));
        try {
            PlaylistImport.platform(URI.create("https://music.163.com.example.org/playlist/123"));
            fail("lookalike domain was accepted");
        } catch (IOException expected) { }
    }

    @Test public void collectsTwoPagesInOrderForQqAndKuwo() throws Exception {
        for (String source : new String[]{"qq","kuwo"}) {
            boolean qq = source.equals("qq");
            PlaylistImport importer = new PlaylistImport(url -> {
                int begin = url.contains("song_begin=50") || url.contains("pn=2") ? 50 : 0;
                return page(qq, begin, 51);
            });
            JSONObject result = importer.fetchTracks(source,"123");
            JSONArray tracks = result.getJSONArray("tracks");
            assertEquals(51, tracks.length());
            assertEquals(qq ? "mid51" : "51", tracks.getJSONObject(50).getString("songid"));
            assertEquals("", result.getString("notice"));
        }
    }

    @Test public void reportsPartialImportWhenLaterPageFails() throws Exception {
        PlaylistImport importer = new PlaylistImport(url -> {
            if (url.contains("song_begin=50")) throw new IOException("timeout");
            return page(true,0,51);
        });
        JSONObject result = importer.fetchTracks("qq","123");
        assertEquals(50, result.getJSONArray("tracks").length());
        assertEquals(51, result.getInt("total"));
        assertFalse(result.getString("notice").isEmpty());
    }

    private static String page(boolean qq, int begin, int total) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = begin + 1; i <= Math.min(begin + 50, total); i++) {
            rows.put(new JSONObject().put(qq ? "songmid" : "musicrid", qq ? "mid"+i : "MUSIC_"+i)
                    .put(qq ? "songname" : "name","Track "+i));
        }
        JSONObject data = new JSONObject().put(qq ? "disstid" : "id","123")
                .put(qq ? "songnum" : "total",total).put(qq ? "songlist" : "musicList",rows);
        JSONObject root = new JSONObject().put("code",qq ? 0 : 200);
        return (qq ? root.put("cdlist",new JSONArray().put(data)) : root.put("data",data)).toString();
    }
}
