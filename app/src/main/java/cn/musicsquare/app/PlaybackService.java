package cn.musicsquare.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import java.util.Collections;
import android.content.SharedPreferences;
import java.util.Map;
import androidx.media3.session.*;
import com.google.common.util.concurrent.*;
import org.json.*;
import java.util.concurrent.*;

/** The queue and player live outside the Activity, including asynchronous next-track resolution. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlaybackService extends MediaSessionService {
    public static final SessionCommand UI_COMMAND=new SessionCommand("cn.musicsquare.UI",Bundle.EMPTY);
    private MediaSession session;
    private ExoPlayer player;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(3);
    private JSONArray queue=new JSONArray();
    private JSONObject current=null;
    private int index=-1, generation=0, candidateIndex=0;
    private String mode="list", error="", lyric="";
    private String lyricKind="synced",lyricProvider="",lyricSearchStatus="idle",lyricSearchMessage="";
    private JSONArray lyricCandidates=new JSONArray();
    private int lyricSearchSerial=0;
    private boolean lyricOverride=false;
    private boolean resolving=false;
    private PlaybackCache playbackCache;private boolean refreshedCachedUrl=false;
    public static volatile String snapshot="{}";

    @Override public void onCreate() {
        super.onCreate();
        final SharedPreferences links=getSharedPreferences("playback_links",MODE_PRIVATE);
        playbackCache=new PlaybackCache(new PlaybackCache.Store(){public String read(){return links.getString("data","{}");}public void write(String value){links.edit().putString("data",value).apply();}});
        player=new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        Player controls=new ForwardingPlayer(player) {
            @Override public Player.Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon().add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build();
            }
            @Override public boolean isCommandAvailable(int command) {return getAvailableCommands().contains(command);}
            @Override public void seekToNext(){PlaybackService.this.next(1,false);}
            @Override public void seekToNextMediaItem(){PlaybackService.this.next(1,false);}
            @Override public void seekToPrevious(){PlaybackService.this.next(-1,false);}
            @Override public void seekToPreviousMediaItem(){PlaybackService.this.next(-1,false);}
        };
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        session=new MediaSession.Builder(this,controls).setSessionActivity(open).setCallback(new MediaSession.Callback(){
            @Override public MediaSession.ConnectionResult onConnect(MediaSession s,MediaSession.ControllerInfo controller) {
                if(!getPackageName().equals(controller.getPackageName())&&!controller.isTrusted()) return MediaSession.ConnectionResult.reject();
                return new MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(UI_COMMAND).build()).build();
            }
            @Override public ListenableFuture<SessionResult> onCustomCommand(MediaSession s,MediaSession.ControllerInfo controller,SessionCommand command,Bundle args) {
                if(!getPackageName().equals(controller.getPackageName())) return Futures.immediateFuture(new SessionResult(SessionError.ERROR_PERMISSION_DENIED));
                try {dispatch(new JSONObject(args.getString("json","{}")));return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));}
                catch(Exception e){return Futures.immediateFuture(new SessionResult(SessionError.ERROR_BAD_VALUE));}
            }
        }).build();
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state) {if(state==Player.STATE_ENDED)next(1,true);publish();}
            @Override public void onIsPlayingChanged(boolean value){publish();}
            @Override public void onPlayerError(PlaybackException e){
                if(current!=null&&current.optBoolean("audioFromCache",false)&&!refreshedCachedUrl){
                    refreshedCachedUrl=true;final int ticket=generation;final JSONObject stale=current;final long position=player.getCurrentPosition();final boolean resume=player.getPlayWhenReady();resolving=true;publish();
                    io.execute(()->{try{JSONObject fresh=playbackCache.resolve(stale,true);main.post(()->{if(ticket!=generation)return;try{current=fresh;candidateIndex=0;startAudio(fresh);player.seekTo(position);if(!resume)player.pause();}catch(Exception fail){resolving=false;error="重新获取音频失败，请重试";publish();}});}catch(Exception fail){main.post(()->{if(ticket==generation){resolving=false;error="链接已失效，重新获取失败，请重试或换源";publish();}});}});return;
                }
                JSONArray alternatives=current==null?null:current.optJSONArray("audioCandidates");
                if(alternatives!=null&&candidateIndex+1<Math.min(alternatives.length(),4)){
                    final int ticket=generation;candidateIndex++;resolving=true;publish();
                    main.post(()->{if(ticket!=generation)return;try{current.put("audioUrl",alternatives.getString(candidateIndex));startAudio(current);}catch(Exception failure){resolving=false;error="备用音频地址不可用，请换源";publish();}});
                }else{if(current!=null)playbackCache.invalidate(current);error=playbackError(e);resolving=false;publish();}
            }
        });
        main.post(ticker);
    }
    private final Runnable ticker=new Runnable(){public void run(){publish();main.postDelayed(this,500);}};
    private void dispatch(JSONObject c) throws Exception {
        switch(c.optString("action")) {
            case "queue":
                JSONArray incoming=c.getJSONArray("tracks");
                if(incoming.length()==0||incoming.length()>1000)throw new IllegalArgumentException();
                queue=new JSONArray(incoming.toString()); select(c.optInt("index",0));break;
            case "toggle":
                if(resolving)break;
                if(player.isPlaying())player.pause();else if(player.getPlayerError()!=null&&index>=0)select(index);else if(current!=null)player.play();break;
            case "next":next(1,false);break;
            case "prev":next(-1,false);break;
            case "seek":if(!resolving){long duration=player.getDuration(),position=Math.max(0,c.optLong("position"));if(duration>0)position=Math.min(position,Math.max(0,duration-100));player.seekTo(position);if(c.optBoolean("resume",false))player.play();}break;
            case "mode":mode=c.optString("value","list");publish();break;
            case "retry":if(index>=0)select(index);break;
            case "find-lyrics":findLyrics(c);break;
            case "use-lyrics":
                if(current==null||!current.optString("uid").equals(c.optString("uid")))throw new IllegalArgumentException("歌曲已切换");
                useLyrics(lyricCandidates.getJSONObject(c.getInt("candidate")),true);break;
        }
    }
    private void next(int direction,boolean ended) {
        if(queue.length()==0)return;
        if(ended&&mode.equals("single")){player.seekTo(0);player.play();return;}
        int n=mode.equals("shuffle")&&queue.length()>1?(index+1+ThreadLocalRandom.current().nextInt(queue.length()-1))%queue.length():Math.floorMod(index+direction,queue.length());
        select(n);
    }
    private void select(int n) {
        if(n<0||n>=queue.length())return;
        int ticket=++generation; refreshedCachedUrl=false;index=n;current=queue.optJSONObject(n);error="";lyric="";resolving=true;candidateIndex=0;
        lyricKind="synced";lyricProvider="";lyricSearchStatus="idle";lyricSearchMessage="";lyricCandidates=new JSONArray();lyricOverride=false;lyricSearchSerial++;
        player.stop();player.clearMediaItems();publish();
        final JSONObject track=current;
        io.execute(()->{
            try {
                JSONObject resolved=playbackCache.resolve(track,false);
                main.post(()->{
                    if(ticket!=generation)return;
                    current=resolved;
                    JSONObject cached=cachedLyrics(resolved);if(cached!=null)try{useLyrics(cached,false);}catch(Exception ignored){}
                    try{startAudio(resolved);}catch(Exception e){resolving=false;error="音频地址不可用："+safeMessage(e);publish();}
                });
                String words=MusicApi.lyrics(resolved);
                main.post(()->{if(ticket==generation&&!lyricOverride){lyric=words;lyricKind=LyricsSearch.hasTimedLyrics(words)?"synced":"plain";publish();}});
            }catch(Exception e){main.post(()->{if(ticket==generation){resolving=false;error=e instanceof java.net.SocketTimeoutException?"音乐源响应超时，请重试或换源":"资源获取失败："+safeMessage(e);publish();}});}
        });
    }
    private void startAudio(JSONObject track)throws Exception{
        String url=track.optString("audioUrl");
        if(!track.optString("source").equals("local"))url=MediaUrlPolicy.normalize(url);
        else if(!url.startsWith("content://"))throw new java.io.IOException("本地文件授权无效，请重新导入");
        String referer=track.optString("source").equals("qq")?"https://y.qq.com/":track.optString("source").equals("netease")?"https://music.163.com/":"https://www.kuwo.cn/";
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("Mozilla/5.0 MusicSquare/0.1.1").setConnectTimeoutMs(10000).setReadTimeoutMs(15000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(Collections.singletonMap("Referer",referer));
        DefaultDataSource.Factory data=new DefaultDataSource.Factory(this,http);
        MediaMetadata.Builder metadata=new MediaMetadata.Builder().setTitle(track.optString("title")).setArtist(track.optString("artist")).setIsPlayable(true);
        String cover=track.optString("cover");if(cover.startsWith("https://"))metadata.setArtworkUri(Uri.parse(cover));
        MediaItem item=new MediaItem.Builder().setMediaId(track.optString("uid")).setUri(url).setMediaMetadata(metadata.build()).build();
        player.setMediaSource(new DefaultMediaSourceFactory(data).createMediaSource(item));
        error="";resolving=false;player.prepare();player.play();publish();
    }
    private String playbackError(PlaybackException e){
        for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof HttpDataSource.InvalidResponseCodeException){int code=((HttpDataSource.InvalidResponseCodeException)cause).responseCode;return "音频服务器返回 HTTP "+code+"，链接可能已过期或受地区限制，请重试或换源";}
        if(e.errorCode==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED||e.errorCode==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)return "音频网络连接失败，请检查网络后重试，或换一个来源";
        if(e.errorCode==PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED)return "该来源的 HTTP 音频域名暂未适配，请换源";
        return "播放失败：音频可能已失效或格式不支持，请重试或换源（"+e.getErrorCodeName()+"）";
    }
    private void findLyrics(JSONObject request)throws Exception{
        if(current==null)throw new IllegalArgumentException("先选择一首歌曲");
        if(request.has("uid")&&!current.optString("uid").equals(request.optString("uid")))throw new IllegalArgumentException("歌曲已切换");
        if(lyricSearchStatus.equals("searching"))return;
        JSONObject target=new JSONObject(current.toString());
        if(request.has("title")){String title=request.optString("title").trim();if(title.isEmpty()||title.length()>150)throw new IllegalArgumentException("请填写歌名");target.put("title",title);}
        if(request.has("artist")){String artist=request.optString("artist").trim();if(artist.length()>150)throw new IllegalArgumentException("歌手名称过长");target.put("artist",artist);}
        if(target.optLong("durationMs",0)<=0&&!target.optBoolean("trial",false))target.put("durationMs",Math.max(0,player.getDuration()));
        final int songTicket=generation,searchTicket=++lyricSearchSerial;
        lyricSearchStatus="searching";lyricSearchMessage="正在按歌名、歌手和时长匹配…";lyricCandidates=new JSONArray();publish();
        io.execute(()->{
            try{
                JSONObject result=new LyricsSearch().find(target);
                main.post(()->{
                    if(songTicket!=generation||searchTicket!=lyricSearchSerial)return;
                    try{
                        lyricCandidates=result.optJSONArray("candidates");if(lyricCandidates==null)lyricCandidates=new JSONArray();
                        JSONObject automatic=result.optJSONObject("automatic");
                        if(automatic!=null)useLyrics(automatic,true);
                        else{lyricSearchStatus=lyricCandidates.length()>0?"choices":"notfound";lyricSearchMessage=result.optString("message");publish();}
                    }catch(Exception e){lyricSearchStatus="error";lyricSearchMessage="歌词处理失败，请稍后重试";publish();}
                });
            }catch(Exception e){main.post(()->{if(songTicket==generation&&searchTicket==lyricSearchSerial){lyricSearchStatus="error";lyricSearchMessage="歌词搜索失败："+safeMessage(e);publish();}});}
        });
    }
    private void useLyrics(JSONObject candidate,boolean save)throws Exception{
        String timed=candidate.optString("lrc"),plain=candidate.optString("plain");
        if(!LyricsSearch.hasTimedLyrics(timed)&&plain.trim().isEmpty())throw new IllegalArgumentException("歌词内容为空");
        lyricOverride=true;lyricKind=LyricsSearch.hasTimedLyrics(timed)?"synced":"plain";lyric=lyricKind.equals("synced")?timed:plain;
        lyricProvider=candidate.optString("provider");lyricSearchStatus="found";lyricSearchMessage="已匹配："+candidate.optString("title")+" · "+candidate.optString("artist");
        if(save&&current!=null){
            JSONObject saved=new JSONObject(candidate.toString()).put("trackTitle",current.optString("title")).put("trackArtist",current.optString("artist")).put("savedAt",System.currentTimeMillis());
            SharedPreferences prefs=getSharedPreferences("lyrics-cache-v1",MODE_PRIVATE);SharedPreferences.Editor editor=prefs.edit();
            if(prefs.getAll().size()>=250&&!prefs.contains(current.optString("uid"))){String oldest=null;long time=Long.MAX_VALUE;for(Map.Entry<String,?> entry:prefs.getAll().entrySet())try{long when=new JSONObject(String.valueOf(entry.getValue())).optLong("savedAt");if(when<time){time=when;oldest=entry.getKey();}}catch(Exception ignored){}if(oldest!=null)editor.remove(oldest);}
            editor.putString(current.optString("uid"),saved.toString()).apply();
        }
        publish();
    }
    private JSONObject cachedLyrics(JSONObject track){
        try{String raw=getSharedPreferences("lyrics-cache-v1",MODE_PRIVATE).getString(track.optString("uid"),null);if(raw==null)return null;JSONObject saved=new JSONObject(raw);
            if(!LyricsSearch.normalize(saved.optString("trackTitle")).equals(LyricsSearch.normalize(track.optString("title")))||!LyricsSearch.normalize(saved.optString("trackArtist")).equals(LyricsSearch.normalize(track.optString("artist"))))return null;
            return saved;
        }catch(Exception ignored){return null;}
    }
    private JSONArray lyricCandidateSummaries(){
        JSONArray summaries=new JSONArray();for(int i=0;i<lyricCandidates.length();i++){JSONObject c=lyricCandidates.optJSONObject(i);if(c==null)continue;try{summaries.put(new JSONObject().put("index",i).put("title",c.optString("title")).put("artist",c.optString("artist")).put("durationMs",c.optLong("durationMs")).put("provider",c.optString("provider")).put("kind",c.optString("kind")).put("autoEligible",c.optBoolean("autoEligible")));}catch(Exception ignored){}}
        return summaries;
    }
    static String safeMessage(Exception e){String s=e.getMessage();return s==null?"网络或接口异常":s.substring(0,Math.min(150,s.length()));}
    private void publish(){
        if(player==null)return;
        try {
            MediaItem item=player.getCurrentMediaItem();
            if(current!=null&&!resolving&&item!=null&&item.mediaId.equals(current.optString("uid"))&&current.optString("source").equals("netease")&&PreviewPolicy.isShortPreview(current.optLong("durationMs",0),player.getDuration())){
                if(!current.optBoolean("trialDetectedByDuration",false))playbackCache.markPreview(current);
                current.put("trial",true).put("trialDetectedByDuration",true);
                if(current.optString("playbackProvider").equals("netease-public")&&!current.optBoolean("neteaseFallbackAttempted",false)){
                    current.put("neteaseFallbackAttempted",true);
                    final JSONObject target=new JSONObject(current.toString());final int ticket=generation;
                    io.execute(()->{
                        try{
                            JSONObject full=new NeteaseResolver().resolveAfterPreview(target);playbackCache.put(full);
                            main.post(()->{if(ticket!=generation)return;try{long position=player.getCurrentPosition();boolean playing=player.getPlayWhenReady();current=full;candidateIndex=0;startAudio(full);player.seekTo(position);if(!playing)player.pause();}catch(Exception e){error="完整音频切换失败，请重试或换源";resolving=false;publish();}});
                        }catch(Exception ignored){}
                    });
                }
            }
            snapshot=new JSONObject().put("track",current==null?JSONObject.NULL:current).put("queue",queue).put("index",index).put("playing",player.isPlaying()).put("position",Math.max(0,player.getCurrentPosition())).put("duration",Math.max(0,player.getDuration())).put("loading",resolving||player.getPlaybackState()==Player.STATE_BUFFERING).put("error",error).put("mode",mode).put("lyric",lyric).put("lyricKind",lyricKind).put("lyricProvider",lyricProvider).put("lyricSearchStatus",lyricSearchStatus).put("lyricSearchMessage",lyricSearchMessage).put("lyricSearchSerial",lyricSearchSerial).put("lyricCandidates",lyricCandidateSummaries()).toString();}catch(Exception ignored){}
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onDestroy(){generation++;main.removeCallbacksAndMessages(null);io.shutdownNow();if(session!=null){session.release();session=null;}if(player!=null){player.release();player=null;}snapshot="{}";super.onDestroy();}
}
