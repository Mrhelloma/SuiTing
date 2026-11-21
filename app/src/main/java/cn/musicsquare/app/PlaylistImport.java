package cn.musicsquare.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

final class PlaylistImport {
    private static final int MAX_TRACKS = 1000;
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>「」《》【】。，；]+", Pattern.CASE_INSENSITIVE);
    private final CatalogApi.Fetcher fetch;

    PlaylistImport() { this(url -> MusicApi.get(url, 4000, 6000)); }
    PlaylistImport(CatalogApi.Fetcher fetch) { this.fetch = fetch; }

    JSONObject read(String text) throws Exception {
        return read(parseLink(text));
    }

    static String platform(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getPort() != -1
                || !"https".equalsIgnoreCase(uri.getScheme())) throw new IOException("分享链接无效");
        host = host.toLowerCase(Locale.ROOT);
        if (domain(host, "music.163.com") || host.equals("163cn.tv")) return "netease";
        if (domain(host, "y.qq.com")) return "qq";
        if (domain(host, "kuwo.cn")) return "kuwo";
        throw new IOException("请粘贴网易云、QQ 音乐或酷我的歌单分享链接");
    }

    private static boolean domain(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    private static URI parseLink(String text) throws Exception {
        if (text.length() > 4096) throw new IOException("分享文案过长");
        Matcher match = LINK.matcher(text);
        if (!match.find()) throw new IOException("没有找到歌单链接");
        try {
            return URI.create(match.group().replaceAll("[),.!?]+$", "").replaceFirst("(?i)^http:", "https:"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("分享链接格式无效");
        }
    }

    static String playlistId(URI uri, String source) {
        String path = uri.getPath();
        String fragment = uri.getFragment();
        if (fragment != null && fragment.startsWith("/playlist")) path = fragment.split("\\?", 2)[0];
        Matcher direct = Pattern.compile("/(?:playlist(?:_detail)?|playsheet|playlist_detail_new)/(\\d{1,20})(?:\\.html)?/?$",
                Pattern.CASE_INSENSITIVE).matcher(path);
        if (direct.find()) return direct.group(1);
        boolean playlist = path.toLowerCase(Locale.ROOT).matches(".*(?:playlist|playsheet|taoge|gedan).*");
        String query = uri.getQuery();
        if (fragment != null && fragment.contains("?")) query = fragment.split("\\?", 2)[1];
        if (query == null) return "";
        String key = source.equals("netease") ? "id" : source.equals("qq") ? "id|disstid|tid" : "pid|id|playlistId";
        if (!playlist && !(source.equals("qq") && query.matches(".*(?:^|&)ADTAG=[^&]*(?:taoge|gedan).*"))) return "";
        Matcher param = Pattern.compile("(?:^|&)(?:" + key + ")=(\\d{1,20})(?:&|$)", Pattern.CASE_INSENSITIVE).matcher(query);
        return param.find() ? param.group(1) : "";
    }

    private JSONObject read(URI uri) throws Exception {
        String source = platform(uri);
        for (int step = 0; step < 5; step++) {
            String id = playlistId(uri, source);
            if (!id.isEmpty()) return fetchTracks(source, id);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            try {
                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) break;
                uri = uri.resolve(location);
                if (!source.equals(platform(uri))) throw new IOException("分享链接跳转到了其他平台");
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("无法识别歌单。请试试平台分享的完整歌单链接");
    }

    JSONObject fetchTracks(String source, String id) throws Exception {
        if (!id.matches("[0-9]{1,20}")) throw new IOException("歌单 ID 无效");
        JSONArray tracks = new JSONArray();
        Set<String> seen = new HashSet<>();
        String name = "";
        int total = -1;
        int offset = 0;
        String notice = "";
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(85);
        JSONObject netease = source.equals("netease") ? neteasePlaylist(id) : null;
        while (offset < MAX_TRACKS && System.nanoTime() < deadline) {
            try {
                JSONObject page;
                if (source.equals("netease")) {
                    page = neteasePage(netease, offset);
                } else {
                    page = otherPage(source, id, offset);
                }
                JSONObject entity = page.getJSONObject("entity");
                name = entity.optString("name", name);
                total = Math.max(total, Math.max(page.optInt("total", -1), entity.optInt("count", -1)));
                JSONArray rows = page.getJSONArray("tracks");
                int before = tracks.length();
                for (int i = 0; i < rows.length() && tracks.length() < MAX_TRACKS; i++) {
                    JSONObject song = rows.getJSONObject(i);
                    if (seen.add(song.getString("uid"))) tracks.put(song);
                }
                int next = page.optInt("offset", offset);
                if (!page.optBoolean("hasMore", false) || next <= offset || before == tracks.length()) break;
                offset = next;
            } catch (Exception failure) {
                if (tracks.length() == 0) throw failure;
                notice = "后续曲目获取失败，可重新导入";
                break;
            }
        }
        if (tracks.length() == 0) throw new IOException("没有读取到可导入曲目。请检查歌单是否公开分享");
        if (total < 0 || tracks.length() < total) {
            notice = notice.isEmpty() ? "未能确认已读取全部曲目，可重新导入" : notice;
        }
        return new JSONObject().put("id","pl-link-"+source+"-"+id).put("name",name)
                .put("tracks",tracks).put("total",total).put("notice",notice);
    }

    private JSONObject neteasePlaylist(String id) throws Exception {
        JSONObject root = CatalogApi.parseObject(fetch.get(
                "https://music.163.com/api/v6/playlist/detail?id=" + id + "&n=20&s=0"));
        JSONObject playlist = root.optJSONObject("playlist");
        if (playlist == null || !id.equals(MusicApi.text(playlist,"id"))) {
            throw new IOException("网易云未返回该歌单，请检查是否公开分享");
        }
        return playlist;
    }

    private JSONObject neteasePage(JSONObject playlist, int offset) throws Exception {
        JSONArray ids = playlist.optJSONArray("trackIds");
        JSONArray items = playlist.optJSONArray("tracks");
        if (items == null) items = new JSONArray();
        int count = ids == null ? items.length() : ids.length();
        int end = Math.min(Math.min(count, MAX_TRACKS), offset + 50);
        if (ids == null || items.length() >= end) {
            return CatalogApi.detail(CatalogApi.entity(playlist,"playlist"),
                    CatalogApi.slice(items, offset, end - offset), count, offset);
        }
        JSONArray chunk = new JSONArray();
        for (int i = offset; i < end; i++) chunk.put(ids.getJSONObject(i).get("id"));
        JSONArray response = CatalogApi.parseObject(fetch.get(
                "https://music.163.com/api/song/detail?ids=" + MusicApi.enc(chunk.toString()))).optJSONArray("songs");
        if (response == null) throw new IOException("网易云未返回曲目详情");
        java.util.Map<String,JSONObject> byId = new java.util.HashMap<>();
        for (int i = 0; i < response.length(); i++) {
            JSONObject row = response.getJSONObject(i);
            byId.put(MusicApi.text(row,"id"), row);
        }
        JSONArray ordered = new JSONArray();
        for (int i = 0; i < chunk.length(); i++) {
            JSONObject row = byId.get(String.valueOf(chunk.get(i)));
            if (row != null) ordered.put(row);
        }
        return CatalogApi.detail(CatalogApi.entity(playlist,"playlist"), ordered, count, offset)
                .put("offset",end).put("hasMore", end < count);
    }

    private JSONObject otherPage(String source, String id, int offset) throws Exception {
        if (!source.equals("qq") && !source.equals("kuwo")) throw new IOException("不支持此歌单平台");
        boolean qq = source.equals("qq");
        int size = 50;
        String url = qq
                ? "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&format=json&disstid="+id+"&song_begin="+offset+"&song_num="+size
                : "https://m.kuwo.cn/newh5app/wapi/api/www/playlist/playListInfo?pid="+id+"&pn="+(offset/size+1)+"&rn="+size;
        JSONObject root = CatalogApi.parseObject(fetch.get(url));
        int status = root.optInt("code", -1);
        if (status != (qq ? 0 : 200)) throw new IOException("平台暂未开放此歌单读取（"+status+"）");
        JSONArray lists = root.optJSONArray("cdlist");
        JSONObject data = qq ? (lists == null ? root.optJSONObject("data") : lists.optJSONObject(0)) : root.optJSONObject("data");
        String returnedId = data == null ? "" : MusicApi.first(data, qq ? "disstid" : "id", qq ? "tid" : "pid");
        if (data == null || (!returnedId.isEmpty() && !id.equals(returnedId))) {
            throw new IOException("无法核对平台返回的歌单，请检查链接是否可公开访问");
        }
        JSONArray rows = data.optJSONArray(qq ? "songlist" : "musicList");
        if (rows == null) throw new IOException("平台没有返回曲目列表");
        JSONArray tracks = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String mid = qq ? MusicApi.first(row,"mid","songmid","song_mid") : MusicApi.first(row,"rid","musicrid").replaceFirst("^MUSIC_", "");
            String title = MusicApi.first(row,"title","songname","name");
            if (!mid.matches(qq ? "[A-Za-z0-9]{1,40}" : "[0-9]{1,20}") || title.isEmpty()) continue;
            String artist = qq ? MusicApi.artistNames(row.optJSONArray("singer")) : MusicApi.text(row,"artist");
            tracks.put(MusicApi.base(source,mid,title,artist).put("keyword",(title+" "+artist).trim())
                    .put("kuwoImported",!qq).put("cover",MusicApi.imageUrl(MusicApi.first(row,"pic","albumpic"))));
        }
        int total = qq ? data.optInt("total_song_num", data.optInt("songnum", -1)) : data.optInt("total", -1);
        int next = offset + rows.length();
        return new JSONObject().put("entity",new JSONObject().put("name",MusicApi.first(data,"dissname","name")))
                .put("tracks",tracks).put("total",total).put("offset",next)
                .put("hasMore",rows.length() > 0 && (total >= 0 ? next < total : rows.length() >= size));
    }
}
