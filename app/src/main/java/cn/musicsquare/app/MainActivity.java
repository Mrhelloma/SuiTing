package cn.musicsquare.app;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import java.util.ArrayDeque;
import androidx.media3.session.*;
import androidx.webkit.WebViewAssetLoader;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private WebView web;
    private final ArrayDeque<String> pendingScripts=new ArrayDeque<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newFixedThreadPool(4);
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private boolean ready=false,active=false,destroyed=false,keyboardVisible=false;
    private String exportData="";
    private static final String LOCAL="https://appassets.androidplatform.net/assets/index.html";
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(saved!=null)exportData=saved.getString("exportData", "");
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        FrameLayout container=new FrameLayout(this);container.setBackgroundColor(0xff101915);
        web=new WebView(this);web.setBackgroundColor(0xff101915);
        container.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(container);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container,(v,insets)->{
            androidx.core.graphics.Insets bar=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            int keyboard=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            boolean visible=insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())&&keyboard>bar.bottom;
            if(visible!=keyboardVisible){keyboardVisible=visible;sendScript("window.setKeyboardVisible&&window.setKeyboardVisible("+visible+")");}
            container.setPadding(bar.left,bar.top,bar.right,Math.max(bar.bottom,keyboard));return insets;
        });
        container.post(container::requestApplyInsets);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){
                WebResourceResponse r=loader.shouldInterceptRequest(req.getUrl());if(r!=null)return r;
                if("https".equals(req.getUrl().getScheme())&&!req.isForMainFrame())return null;
                return new WebResourceResponse("text/plain","utf-8",new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return !LOCAL.equals(req.getUrl().toString());}
            @Override public void onPageFinished(WebView v,String url){ready=LOCAL.equals(url);if(ready){while(!pendingScripts.isEmpty())web.evaluateJavascript(pendingScripts.removeFirst(),null);web.evaluateJavascript("window.setKeyboardVisible&&window.setKeyboardVisible("+keyboardVisible+")",null);}}
        });
        web.addJavascriptInterface(new Bridge(),"Native");
        SessionToken token=new SessionToken(this,new ComponentName(this,PlaybackService.class));
        controllerFuture=new MediaController.Builder(this,token).buildAsync();
        controllerFuture.addListener(()->{try{controller=controllerFuture.get();}catch(Exception e){toast("播放器连接失败，请重新启动");}},main::post);
        web.loadUrl(LOCAL);
    }
    private class Bridge {
        @JavascriptInterface public void request(String action,String payload,String id){
            if(payload.length()>3*1024*1024){reply(id,null,"数据过大");return;}
            io.execute(()->{
                try {
                    JSONObject p=new JSONObject(payload);
                    switch(action){
                        case "library":reply(id,new JSONObject(getPreferences(MODE_PRIVATE).getString("library","{\"version\":1,\"favorites\":[],\"playlists\":[],\"local\":[]}")),null);break;
                        case "save":
                            if(!p.has("favorites")||!p.has("playlists"))throw new IOException("歌单格式不正确");
                            if(!getPreferences(MODE_PRIVATE).edit().putString("library",p.toString()).commit())throw new IOException("保存失败，请检查手机空间");
                            reply(id,new JSONObject().put("saved",true),null);break;
                        case "catalog":reply(id,new CatalogApi().request(p),null);break;
                        case "search":
                            String q=p.optString("keyword").trim();if(q.isEmpty()||q.length()>100)throw new IOException("请输入 1–100 字的关键词");
                            reply(id,MusicApi.searchPage(p.optString("source"),q,Math.max(0,p.optInt("offset",0))),null);break;
                        case "command":main.post(()->{
                            if(controller==null){reply(id,null,"播放器正在连接，请稍后重试");return;}
                            Bundle args=new Bundle();args.putString("json",p.toString());
                            ListenableFuture<SessionResult> result=controller.sendCustomCommand(PlaybackService.UI_COMMAND,args);
                            result.addListener(()->{try{if(result.get().resultCode!=SessionResult.RESULT_SUCCESS)throw new IOException();reply(id,new JSONObject(),null);}catch(Exception e){reply(id,null,"播放指令执行失败");}},main::post);
                        });break;
                        case "export":main.post(()->{exportData=p.toString();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"随听歌单.json");startActivityForResult(intent,41);});reply(id,new JSONObject(),null);break;
                        case "import":main.post(()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,42);});reply(id,new JSONObject(),null);break;
                        case "audio":main.post(()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,43);});reply(id,new JSONObject(),null);break;
                        default:throw new IOException("未知操作");
                    }
                }catch(Exception e){reply(id,null,PlaybackService.safeMessage(e));}
            });
        }
    }
    private void reply(String id,Object value,String error){try{
        JSONObject response=new JSONObject().put("id",id).put("data",value==null?JSONObject.NULL:value).put("error",error==null?JSONObject.NULL:error);
        sendScript("window.nativeReply("+response+")");
    }catch(Exception ignored){}}
    private void sendScript(String script){main.post(()->{if(destroyed)return;if(ready)web.evaluateJavascript(script,null);else if(pendingScripts.size()<128)pendingScripts.addLast(script);});}
    private void event(String name,Object value){sendScript("window.nativeEvent("+JSONObject.quote(name)+","+String.valueOf(value)+")");}
    private final Runnable poll=new Runnable(){public void run(){if(active&&!destroyed){if(ready)web.evaluateJavascript("window.playerState("+PlaybackService.snapshot+")",null);main.postDelayed(this,500);}}};
    @Override public void onResume(){super.onResume();active=true;main.removeCallbacks(poll);main.post(poll);}
    @Override public void onPause(){active=false;main.removeCallbacks(poll);super.onPause();}
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null)return;
        Uri uri=data.getData();
        io.execute(()->{try{
            if(code==41&&uri!=null){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException();out.write(exportData.getBytes(java.nio.charset.StandardCharsets.UTF_8));}toast("歌单已导出");}
            if(code==42&&uri!=null){
                try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    if(in==null)throw new IOException();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("文件超过 2MB");out.write(b,0,n);}
                    JSONObject imported=new JSONObject(out.toString("UTF-8"));if(!imported.has("favorites")&&!imported.has("playlists"))throw new IOException("不是有效的歌单文件");event("import",imported);
                }
            }
            if(code==43){JSONArray tracks=new JSONArray();if(data.getClipData()!=null){for(int i=0;i<Math.min(200,data.getClipData().getItemCount());i++)tracks.put(localTrack(data.getClipData().getItemAt(i).getUri()));}else if(uri!=null)tracks.put(localTrack(uri));event("audio",tracks);}
        }catch(Exception e){toast("操作失败："+PlaybackService.safeMessage(e));}});
    }
    private JSONObject localTrack(Uri uri)throws Exception{
        getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        String name="本地音乐";
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
        return new JSONObject().put("uid","local-"+uri).put("source","local").put("songid",uri.toString()).put("audioUrl",uri.toString()).put("title",name).put("artist","本地音频");
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putString("exportData",exportData);super.onSaveInstanceState(out);}
    private void toast(String text){main.post(()->android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show());}
    @Override public void onBackPressed(){if(ready)web.evaluateJavascript("window.appBack()",value->{if("false".equals(value))moveTaskToBack(true);});else super.onBackPressed();}
    @Override public void onDestroy(){destroyed=true;active=false;pendingScripts.clear();main.removeCallbacksAndMessages(null);if(controllerFuture!=null)MediaController.releaseFuture(controllerFuture);io.shutdownNow();if(web!=null){web.removeJavascriptInterface("Native");web.destroy();}super.onDestroy();}
}
