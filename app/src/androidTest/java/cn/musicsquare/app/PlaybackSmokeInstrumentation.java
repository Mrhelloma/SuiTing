package cn.musicsquare.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import androidx.media3.session.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Instrumentation-only: generated sine wave, real MediaStore, real Media3 service. */
public class PlaybackSmokeInstrumentation extends Instrumentation {
    private MediaController controller;
    private Uri audio;
    private Activity activity;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private JSONObject state()throws Exception{return new JSONObject(PlaybackService.snapshot);}
    private void waitFor(java.util.concurrent.Callable<Boolean> condition,String label)throws Exception{long deadline=System.currentTimeMillis()+18000;while(System.currentTimeMillis()<deadline){if(condition.call())return;Thread.sleep(150);}throw new AssertionError("timeout: "+label+" state="+PlaybackService.snapshot);}
    private void command(JSONObject json)throws Exception{
        AtomicReference<com.google.common.util.concurrent.ListenableFuture<SessionResult>> f=new AtomicReference<>();
        runOnMainSync(()->{Bundle args=new Bundle();args.putString("json",json.toString());f.set(controller.sendCustomCommand(PlaybackService.UI_COMMAND,args));});
        check(f.get().get(10,TimeUnit.SECONDS).resultCode==SessionResult.RESULT_SUCCESS,"command rejected");
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context ctx=getTargetContext();
        activity=startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(1500);
        SessionToken token=new SessionToken(ctx,new ComponentName(ctx,PlaybackService.class));
        AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>> future=new AtomicReference<>();
        runOnMainSync(()->future.set(new MediaController.Builder(ctx,token).buildAsync()));
        controller=future.get().get(15,TimeUnit.SECONDS);
        ContentValues v=new ContentValues();v.put(MediaStore.Audio.Media.DISPLAY_NAME,"MusicSquare-instrumentation.wav");v.put(MediaStore.Audio.Media.MIME_TYPE,"audio/wav");v.put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/MusicSquareTest");
        audio=ctx.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,v);check(audio!=null,"media insert failed");
        try(OutputStream out=ctx.getContentResolver().openOutputStream(audio)){out.write(wave());}
        JSONArray tracks=new JSONArray();for(int i=0;i<2;i++)tracks.put(new JSONObject().put("uid","local-test-"+i).put("source","local").put("songid",audio.toString()).put("audioUrl",audio.toString()).put("title","Native test "+i).put("artist","Generated tone"));
        command(new JSONObject().put("action","queue").put("tracks",tracks).put("index",0));
        waitFor(()->state().optBoolean("playing")&&state().optLong("position")>200,"local playback");
        long before=state().optLong("position");
        runOnMainSync(()->activity.moveTaskToBack(true));Thread.sleep(1700);
        check(state().optBoolean("playing")&&state().optLong("position")>before+700,"background playback did not advance");
        runOnMainSync(()->controller.seekToNext());waitFor(()->state().optInt("index")==1&&state().optBoolean("playing"),"session next");
        runOnMainSync(()->controller.seekToPrevious());waitFor(()->state().optInt("index")==0&&state().optBoolean("playing"),"session previous");
        runOnMainSync(()->controller.pause());waitFor(()->!state().optBoolean("playing"),"session pause");
        runOnMainSync(()->controller.seekTo(7000));waitFor(()->state().optLong("position")>=6900,"seek");
        runOnMainSync(()->controller.play());waitFor(()->state().optBoolean("playing"),"resume");
        command(new JSONObject().put("action","mode").put("value","single"));
        runOnMainSync(()->controller.seekTo(29900));waitFor(()->state().optLong("position")<2500&&state().optInt("index")==0&&state().optBoolean("playing"),"single repeat");
        command(new JSONObject().put("action","mode").put("value","list"));
        runOnMainSync(()->controller.seekTo(29900));waitFor(()->state().optInt("index")==1&&state().optBoolean("playing"),"queue auto advance");
        result.putString("stream","PASS: real local playback, progress, background service, MediaSession next/previous/pause/seek/resume, single repeat, automatic queue advance.\n");
        cleanup();finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e+"\n");cleanup();finish(Activity.RESULT_CANCELED,result);}}
    private void cleanup(){if(controller!=null)runOnMainSync(()->{controller.stop();controller.release();});if(audio!=null)getTargetContext().getContentResolver().delete(audio,null,null);if(activity!=null)runOnMainSync(()->activity.finish());}
    private byte[] wave()throws Exception{int rate=8000,n=rate*30;ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeBytes("RIFF");le(out,36+n*2,4);out.writeBytes("WAVEfmt ");le(out,16,4);le(out,1,2);le(out,1,2);le(out,rate,4);le(out,rate*2,4);le(out,2,2);le(out,16,2);out.writeBytes("data");le(out,n*2,4);for(int i=0;i<n;i++)le(out,(int)(Math.sin(i*2*Math.PI*220/rate)*600),2);return bytes.toByteArray();}
    private void le(DataOutputStream out,int value,int count)throws Exception{for(int i=0;i<count;i++)out.writeByte(value>>(8*i));}
}
